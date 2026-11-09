package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

/**
 * 순차 연산의 한 단계가 목표를 이뤘는지에 대한 판정. 단계로 쓰는 연산의 결과 타입이 구현한다.
 * <p>
 * {@link AbstractStepwiseOperation.Step}은 이 판정을 돌려주는 연산만 받으므로, 판정할 수 없는 연산을 단계로
 * 넣으면 컴파일되지 않는다.
 */
public interface StepVerdict {
	/**
	 * 이 단계의 목표를 이뤘는지 반환한다.
	 *
	 * @return	목표를 이뤘으면 {@code true}.
	 */
	boolean goalReached();

	/**
	 * 목표를 이루지 못한 이유를 사람이 읽을 문장으로 반환한다.
	 *
	 * @return	이유. 목표를 이뤘으면 {@code null}.
	 */
	@Nullable String failureReason();
}
