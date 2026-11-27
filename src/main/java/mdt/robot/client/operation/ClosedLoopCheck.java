package mdt.robot.client.operation;

import java.util.Optional;
import java.util.OptionalDouble;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;

/**
 * 관절 공간 이동({@link MoveToJoints}·{@link MoveToNamedTarget})의 판정.
 * <p>
 * 정착 대기({@link MotionLimits#settleTimeout()})를 트윈에 맡겼으면, 트윈이 정착을 확인하고 돌려준 관절 최대 오차가
 * {@link MotionLimits#jointToleranceRad()} 이하일 때 목표 도달이다({@link #jointErrorRad()}). 개루프 스택도 같다.
 * <p>
 * 그 밖에는 원격 {@code COMPLETED}이고 출력 {@code closed_loop}이 JSON Boolean {@code true}일 때만 목표 도달이다.
 * 개루프·정보 누락·형식 오류·원격 실패는 {@code goalReached=false}이며 {@code failureReason}으로 구분한다. 개루프의
 * {@code false}는 "도달 확인 불가"이지 미도달이 확정됐다는 뜻이 아니다.
 *
 * @param goalReached	목표 도달을 확인했는지.
 * @param failureReason	확인하지 못한 이유. 확인했으면 {@code null}.
 * @param result		원격 결과.
 */
public record ClosedLoopCheck(boolean goalReached, @Nullable String failureReason, OperationResult result) implements StepVerdict {
	@Override
	public @Nullable String failureReason() {
		return goalReached ? null : failureReason;
	}

	/** 트윈이 정착을 판정했는지. 정착 대기를 요청하지 않았거나 옛 트윈이면 비어 있다. */
	public Optional<Boolean> settled() {
		JsonNode node = result.output("settled");
		return (node != null && node.isBoolean()) ? Optional.of(node.booleanValue()) : Optional.empty();
	}

	/** 트윈이 보고한 목표 관절값과의 최대 차(rad, {@code goal_error.joint_max_rad}). 없으면 비어 있다. */
	public OptionalDouble jointErrorRad() {
		JsonNode error = result.output("goal_error");
		JsonNode joint = (error != null) ? error.get("joint_max_rad") : null;
		return (joint != null && joint.isNumber()) ? OptionalDouble.of(joint.doubleValue()) : OptionalDouble.empty();
	}
}
