package mdt.robot.client;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.MoveCheck;

/**
 * 종료된 연산의 결과.
 * <p>
 * <b>{@code COMPLETED} 는 "연산이 끝났다"일 뿐 "도달했다"나 "쥐었다"가 아니다.</b> 위치 도달은
 * {@link mdt.robot.client.operation.MoveLinear}의 {@link MoveCheck#reached()}, 그리퍼 성공은 출력의 {@code at_goal} 로 확인한다.
 *
 * @param operation		연산 이름.
 * @param status		종료 상태 — {@code COMPLETED} · {@code FAILED} · {@code CANCELLED}.
 * @param outputs		연산 출력. 없으면 빈 맵.
 * @param errorCode		실패 코드. (예: {@code PLANNING_FAILED}, {@code TIMEOUT}) 실패가 아니면 {@code null}.
 * @param errorMessage	실패 사유. 실패가 아니면 {@code null}.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record OperationResult(String operation, String status, Map<String,JsonNode> outputs,
								@Nullable String errorCode, @Nullable String errorMessage) {
	public static final String COMPLETED = "COMPLETED";
	public static final String FAILED = "FAILED";
	public static final String CANCELLED = "CANCELLED";

	public OperationResult {
		outputs = (outputs != null) ? Map.copyOf(outputs) : Map.of();
	}

	/**
	 * 정상 종료했는지 반환한다.
	 *
	 * @return	{@code status == COMPLETED}.
	 */
	public boolean isCompleted() {
		return COMPLETED.equals(status);
	}

	/**
	 * 출력 하나를 반환한다.
	 *
	 * @param name	출력 이름.
	 * @return	출력 값. 없으면 {@code null}.
	 */
	public @Nullable JsonNode output(String name) {
		return outputs.get(name);
	}
}
