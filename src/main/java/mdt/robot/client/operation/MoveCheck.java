package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;

/**
 * 직선 이동의 위치 도달 판정 결과.
 * <p>
 * 원격 연산의 완료와 위치 도달은 별개다. 도달 여부는 {@link #reached()}로 확인한다.
 *
 * @param reached 도달했는가. 연산 실패·정착 실패·관측 없음·오차 초과면 false.
 * @param errorM 위치 오차(m, 각 축 최댓값). 위치를 비교하지 못했으면 null.
 * @param reason {@link #SETTLE_TIMEOUT} 등 판정 사유 코드. 없으면 null.
 * @param why 다음에 할 일 안내. 없으면 null.
 * @param result 트윈의 연산 결과.
 * @param settledPose 판정에 쓴 정착 후 관측값(PoseStamped). 정착을 확인하지 않았거나 실패하면 null.
 *                    정착 후 위치가 필요하면 ee_pose를 다시 읽지 말고 이 값을 사용한다.
 */
public record MoveCheck(boolean reached, @Nullable Double errorM, @Nullable String reason,
                        @Nullable String why, OperationResult result, @Nullable JsonNode settledPose) {
	/** 정착 대기 상한 내에 정착을 확인하지 못했을 때의 reason 코드. */
	public static final String SETTLE_TIMEOUT = "SETTLE_TIMEOUT";
}
