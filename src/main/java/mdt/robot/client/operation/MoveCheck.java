package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;

/**
 * 직선 이동의 위치 도달 판정 결과.
 * <p>
 * 원격 연산의 완료와 위치 도달은 별개다. 도달 여부는 {@link #goalReached()}로 확인한다.
 *
 * @param goalReached 도달했는가. 연산 실패·정착 실패·관측 없음·오차 초과면 false.
 * @param errorM 위치 오차(m, 각 축 최댓값). 위치를 비교하지 못했으면 null.
 * @param failureCode {@link #SETTLE_TIMEOUT} 등 판정 실패 사유 코드. 없으면 null. 설명 문장은 failureReason에 있다.
 * @param failureReason 다음에 할 일 안내를 담은 실패 이유. 없으면 null(접근자는 대체 문장을 만든다).
 *                    목표를 이뤘으면 접근자가 null을 돌려준다.
 * @param result 트윈의 연산 결과.
 * @param settledPose 판정에 쓴 정착 후 관측값(PoseStamped). 정착을 확인하지 않았거나 실패하면 null.
 *                    정착 후 위치가 필요하면 ee_pose를 다시 읽지 말고 이 값을 사용한다.
 */
public record MoveCheck(boolean goalReached, @Nullable Double errorM, @Nullable String failureCode,
                        @Nullable String failureReason, OperationResult result, @Nullable JsonNode settledPose) implements StepVerdict {
	/** 정착 대기 상한 내에 정착을 확인하지 못했을 때의 failureCode. */
	public static final String SETTLE_TIMEOUT = "SETTLE_TIMEOUT";

	@Override
	public @Nullable String failureReason() {
		if ( goalReached ) { return null; }
		return (failureReason != null) ? failureReason : "위치 도달 확인 불가: failureCode=" + failureCode + ", errorM=" + errorM;
	}
}
