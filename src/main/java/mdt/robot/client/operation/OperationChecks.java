package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.Ops;

/** 실행 상태 관리와 분리한 연산 결과 판정. 공개 결과 타입은 각 연산이 유지한다. */
final class OperationChecks {
	private OperationChecks() { }

	record ClosedLoopCheck(boolean reached, @Nullable String why, OperationResult result) { }

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
		return Ops.failureWhy(result).orElseGet(() -> result.errorMessage() != null && !result.errorMessage().isBlank()
				? result.errorMessage() : "원격 연산 상태: " + result.status());
	}
}
