package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;

/**
 * 서버가 폐루프로 도달을 확인하는 이동({@link MoveToJoints}·{@link MoveToNamedTarget})의 판정.
 * <p>
 * 원격 {@code COMPLETED}이고 출력 {@code closed_loop}이 JSON Boolean {@code true}일 때만 목표 도달이다.
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
}
