package mdt.robot.client.operation;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;

/**
 * 순차 연산의 한 단계 기록: 단계 판정({@link StepVerdict})과 수신한 원격 결과. 내부 JSON은 읽기 전용으로 취급한다.
 * <p>
 * 판정은 하위 연산의 결과다({@link MoveCheck}, {@link GripperCheck}, {@link WaitUntilObjectStill.Result},
 * {@link MeasureObjectOffset.Result} 등). 구체 타입이 필요하면 {@link #verdictAs(Class)}로 꺼낸다.
 * 관측·측정 단계는 원격 연산이 아니므로 {@code remoteResult}가 비어 있다.
 *
 * @param stage			단계.
 * @param verdict		단계 판정. 예외·취소로 끝난 단계는 {@code null}.
 * @param remoteResult	수신한 원격 결과.
 * @param failure		단계를 끝낸 예외. 판정을 받았으면 {@code null}.
 */
public record StepResult<S extends Enum<S>>(S stage, @Nullable StepVerdict verdict,
		Optional<OperationResult> remoteResult, @Nullable Throwable failure) {
	/**
	 * 이 단계의 목표를 이뤘는지 반환한다. 예외로 끝났거나 판정이 없으면 {@code false}다.
	 *
	 * @return	목표를 이뤘으면 {@code true}.
	 */
	public boolean goalReached() {
		return failure == null && verdict != null && verdict.goalReached();
	}

	/**
	 * 목표를 이루지 못한 이유를 반환한다. 판정 타입의 {@link StepVerdict#failureReason()}와 같은 이름이다.
	 *
	 * @return	이유. 목표를 이뤘으면 {@code null}.
	 */
	public @Nullable String failureReason() {
		if ( failure != null ) { return failure.toString(); }
		if ( verdict == null ) { return "단계 판정 없음"; }
		return verdict.failureReason();
	}

	/**
	 * 단계 판정을 주어진 타입으로 꺼낸다.
	 *
	 * @param type	판정 타입. (예: {@code MoveCheck.class})
	 * @return	그 타입의 판정. 판정이 없거나 다른 타입이면 빈 값.
	 */
	public <V extends StepVerdict> Optional<V> verdictAs(Class<V> type) {
		return type.isInstance(verdict) ? Optional.of(type.cast(verdict)) : Optional.empty();
	}
}
