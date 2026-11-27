package mdt.robot.client;

import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.MoveCheck;

/**
 * 종료된 연산의 결과.
 * <p>
 * <b>{@code COMPLETED} 는 "연산이 끝났다"일 뿐 "도달했다"나 "쥐었다"가 아니다.</b> 위치 도달은
 * {@link mdt.robot.client.operation.MoveLinear}의 {@link MoveCheck#goalReached()}, 그리퍼 성공은 출력의 {@code at_goal} 로 확인한다.
 *
 * @param operation		연산 이름.
 * @param status		종료 상태 — {@code COMPLETED} · {@code FAILED} · {@code CANCELLED}.
 * @param outputs		연산 출력. 없으면 빈 맵.
 * @param errorCode		실패 코드. (예: {@code PLANNING_FAILED}, {@code TIMEOUT}) 실패가 아니면 {@code null}.
 * @param errorMessage	실패 사유. 실패가 아니면 {@code null}.
 * @param errorDetails	트윈이 {@code error.details}에 실은 구조화된 원인(예: MoveIt 원본 오류 코드). 없으면 {@code null}.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record OperationResult(String operation, Status status, Map<String,JsonNode> outputs,
								@Nullable String errorCode, @Nullable String errorMessage,
								@Nullable JsonNode errorDetails) {
	/**
	 * 원격 연산의 종료 상태.
	 */
	public enum Status {
		/** 정상 종료. 도메인 판정(도달 여부 등)은 연산 결과에서 따로 확인한다. */
		COMPLETED,
		/** 접수된 뒤 실패로 끝남. {@code errorCode}·{@code errorMessage}에 이유가 있다. */
		FAILED,
		/** 취소로 끝남. 로봇의 정지 완료를 뜻하지 않는다. */
		CANCELLED
	}

	public OperationResult {
		outputs = (outputs != null) ? Map.copyOf(outputs) : Map.of();
		if ( errorDetails != null && (errorDetails.isNull() || errorDetails.isMissingNode()) ) {
			errorDetails = null;
		}
	}

	/** 구조화된 원인({@code error.details}) 없이 만든다. */
	public OperationResult(String operation, Status status, Map<String,JsonNode> outputs,
							@Nullable String errorCode, @Nullable String errorMessage) {
		this(operation, status, outputs, errorCode, errorMessage, null);
	}

	/**
	 * MoveIt 원본 오류 이름을 반환한다 (트윈 설계서 6.8 — {@code error.details.moveit_error_name}).
	 * <p>
	 * 트윈은 MoveIt 이 코드를 준 실패에만 이 값을 싣는다. {@code PLANNING_FAILED} 하나로는 충돌 · IK 실패 · 시작 자세
	 * 무효가 같아 보이는데 대응은 다르므로, 원인별로 분기할 때 쓴다.
	 *
	 * @return	이름(예: {@code GOAL_IN_COLLISION}). 이름 없이 코드만 있으면 {@code error_code=<값>}.
	 *			원인이 없으면 {@link Optional#empty()}.
	 */
	public Optional<String> moveitErrorName() {
		if ( errorDetails == null || !errorDetails.isObject() ) {
			return Optional.empty();
		}
		JsonNode name = errorDetails.get("moveit_error_name");
		if ( name != null && name.isTextual() && !name.asText().isEmpty() ) {
			return Optional.of(name.asText());
		}
		JsonNode code = errorDetails.get("moveit_error_code");
		if ( code != null && code.isIntegralNumber() ) {
			return Optional.of("error_code=" + code.asLong());
		}
		return Optional.empty();
	}

	/**
	 * 정상 종료했는지 반환한다.
	 *
	 * @return	{@code status == COMPLETED}.
	 */
	public boolean isCompleted() {
		return status == Status.COMPLETED;
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
