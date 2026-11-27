package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.Ops;
import mdt.robot.client.RobotTwinClient;

/** 실행 상태 관리와 분리한 연산 결과 판정. 공개 결과 타입은 각 연산이 유지한다. */
final class OperationChecks {
	/** 트윈이 이동 뒤 정착을 기다려 주는 입력 (트윈 설계서 6.7 "정착 대기"). */
	static final String SETTLE_INPUT = "settle_timeout_sec";

	private OperationChecks() { }

	/**
	 * 정착 대기를 트윈에 맡길지 정한다. 대기 상한이 양수이고 트윈이 그 입력을 받을 때만 {@code true}다.
	 * 옛 트윈은 모르는 입력을 400 으로 거부하므로 카탈로그로 먼저 확인한다(통신이 일어날 수 있다).
	 */
	static boolean twinSettles(RobotTwinClient twin, String operation, Duration settleTimeout) {
		return !settleTimeout.isZero() && twin.acceptsInput(operation, SETTLE_INPUT);
	}

	/** 트윈이 정착을 판정했는가 — 출력 {@code settled}가 JSON Boolean {@code true}일 때만. */
	static boolean settled(OperationResult result) {
		JsonNode node = result.output("settled");
		return node != null && node.isBoolean() && node.booleanValue();
	}

	/**
	 * 트윈이 정착을 기다린 관절 공간 이동의 판정. 정착 후 {@code goal_error.joint_max_rad}가 허용값 이하이면 도달이다.
	 * 스택(JTC·JGPC)과 무관하게 같은 기준이다.
	 */
	static ClosedLoopCheck judgeSettledJoints(OperationResult result, double toleranceRad) {
		if ( !result.isCompleted() ) {
			return new ClosedLoopCheck(false, failureWhy(result), result);
		}
		if ( !settled(result) ) {
			return new ClosedLoopCheck(false, "정착 확인 실패(" + MoveCheck.SETTLE_TIMEOUT + ") — 트윈이 제한 시간 안에 "
					+ "팔이 멈춘 것을 확인하지 못했다. 현재 상태를 확인하고 다음 동작을 멈춘다", result);
		}
		JsonNode error = result.output("goal_error");
		JsonNode joint = (error != null) ? error.get("joint_max_rad") : null;
		if ( joint == null || !joint.isNumber() ) {
			return new ClosedLoopCheck(false, "정착은 확인했지만 트윈이 비교할 관절 오차(goal_error)를 주지 않았다 "
					+ "— joint_states 로 현재 관절을 직접 확인한다", result);
		}
		double value = joint.doubleValue();
		if ( value <= toleranceRad ) {
			return new ClosedLoopCheck(true, null, result);
		}
		return new ClosedLoopCheck(false, String.format("관절 오차 %.4f rad 가 허용값 %.4f rad 를 넘는다", value, toleranceRad),
				result);
	}


	static ClosedLoopCheck judgeClosedLoop(OperationResult result) {
		if ( !result.isCompleted() ) {
			return new ClosedLoopCheck(false, failureWhy(result), result);
		}
		JsonNode closedLoop = result.output("closed_loop");
		if ( closedLoop == null || closedLoop.isNull() ) {
			return new ClosedLoopCheck(false, "closed_loop 정보 없음 — 도달을 확인할 수 없다", result);
		}
		if ( !closedLoop.isBoolean() ) {
			return new ClosedLoopCheck(false,
					"closed_loop 형식 오류 — Boolean이 필요하다. 받은 값: " + closedLoop, result);
		}
		if ( !closedLoop.booleanValue() ) {
			return new ClosedLoopCheck(false, "open loop — COMPLETED 가 도달을 보장하지 않아 확인할 수 없다", result);
		}
		return new ClosedLoopCheck(true, null, result);
	}

	/** 알려진 코드 안내 → 서버 메시지 → 원격 상태 순으로 실패 안내를 만든다. */
	static String failureWhy(OperationResult result) {
		return Ops.failureHint(result).orElseGet(() -> result.errorMessage() != null && !result.errorMessage().isBlank()
				? result.errorMessage() : "원격 연산 상태: " + result.status());
	}
}
