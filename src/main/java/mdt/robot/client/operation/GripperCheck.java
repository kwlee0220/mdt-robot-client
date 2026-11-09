package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;

/**
 * 그리퍼 판정 결과. 원격 실패나 정보 누락·형식 오류도 goalReached=false이며 failureReason으로 구분한다.
 * stalled는 JSON Boolean true만 인정하는 관측값이다. false에는 누락·형식 오류도 포함되며,
 * 원래 값과 다른 출력은 result에서 조회한다. stalled만으로 성공을 판단하지 않는다.
 */
public record GripperCheck(boolean goalReached, boolean stalled, @Nullable String failureReason, OperationResult result)
		implements StepVerdict {
	@Override
	public @Nullable String failureReason() {
		return goalReached ? null : failureReason;
	}
}
