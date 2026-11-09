package mdt.robot.client.operation;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import utils.Preconditions;

/**
 * 생성할 때 정한 단계 목록을 차례로 실행하는 순차 연산(Grasp·Place).
 * <p>
 * 목록의 단계 이름과 하위 실행 객체는 서로 겹치면 안 되며, 하위 실행은 아직 시작하지 않은 새 객체여야 한다.
 * 다음 단계를 실행 중에 정해야 하면 {@link AbstractStepwiseOperation}을 직접 상속한다.
 */
public abstract class AbstractStepSequenceOperation<S extends Enum<S>, C> extends AbstractStepwiseOperation<S, C> {
	private final List<Step<S>> m_sequence;

	/**
	 * 주어진 단계 목록을 순서대로 실행하는 연산을 만든다.
	 *
	 * @param sequence	단계 목록. 비어 있으면 안 된다.
	 */
	protected AbstractStepSequenceOperation(List<Step<S>> sequence) {
		Preconditions.checkNotNullArgument(sequence, "sequence is null");
		Preconditions.checkArgument(!sequence.isEmpty(), "sequence must not be empty");
		Set<S> stages = new HashSet<>();
		Set<Object> operations = Collections.newSetFromMap(new IdentityHashMap<>());
		for ( Step<S> step : sequence ) {
			Preconditions.checkNotNullArgument(step, "step is null");
			Preconditions.checkArgument(stages.add(step.stage()), "duplicate stage: " + step.stage());
			Preconditions.checkArgument(operations.add(step.operation()), "a child operation cannot be reused");
			Preconditions.checkArgument(step.operation().isNotStarted(), "child operation has already started");
		}
		m_sequence = List.copyOf(sequence);
	}

	@Override
	protected final Optional<Step<S>> nextStep(StepwiseProgress<S> progress) {
		int index = progress.steps().size();
		return (index < m_sequence.size()) ? Optional.of(m_sequence.get(index)) : Optional.empty();
	}
}
