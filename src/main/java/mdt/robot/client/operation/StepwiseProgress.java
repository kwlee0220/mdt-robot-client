package mdt.robot.client.operation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

/**
 * 순차 연산의 불변 진행 스냅샷. 모든 필드는 같은 실행 잠금 안에서 캡처한다.
 * activeStage와 enteredStages는 단계 진입 기록이며 원격 전송/접수/물리 동작의 증거가 아니다.
 * 단계 결과는 실행 순서대로 저장한다. 내부 원격 JSON은 읽기 전용으로 취급한다.
 * <p>
 * 취소 전달이 IN_PROGRESS이면 실행 종료 후에도 새 스냅샷의 전달 결과가 갱신될 수 있다.
 * 이미 반환한 스냅샷은 바뀌지 않는다. 하위 클래스는 이 값에서 도메인 판정을 계산한다.
 */
public class StepwiseProgress<S extends Enum<S>> {
	private final Optional<S> m_activeStage;
	private final List<StepResult<S>> m_steps;
	private final Set<S> m_enteredStages;
	private final boolean m_cancellationRequested;
	private final ChildCancellationState m_childCancellationState;
	private final Throwable m_cancellationFailure;

	StepwiseProgress(Optional<S> activeStage, List<StepResult<S>> steps, Set<S> enteredStages,
			boolean cancellationRequested, ChildCancellationState childCancellationState,
			@Nullable Throwable cancellationFailure) {
		m_activeStage = activeStage;
		m_steps = List.copyOf(steps);
		m_enteredStages = Set.copyOf(enteredStages);
		m_cancellationRequested = cancellationRequested;
		m_childCancellationState = childCancellationState;
		m_cancellationFailure = cancellationFailure;
	}

	/** 별도로 상태를 읽지 않고 이미 캡처된 스냅샷으로 도메인 뷰를 만든다. */
	protected StepwiseProgress(StepwiseProgress<S> source) {
		this(source.activeStage(), source.steps(), source.enteredStages(), source.cancellationRequested(),
				source.childCancellationState(), source.cancellationFailure());
	}

	public final Optional<S> activeStage() { return m_activeStage; }
	public final List<StepResult<S>> steps() { return m_steps; }
	public final Set<S> enteredStages() { return m_enteredStages; }
	public final boolean cancellationRequested() { return m_cancellationRequested; }
	public final ChildCancellationState childCancellationState() { return m_childCancellationState; }
	public final @Nullable Throwable cancellationFailure() { return m_cancellationFailure; }
	public final boolean childCancellationAccepted() { return m_childCancellationState == ChildCancellationState.ACCEPTED; }

	/** 정상 반환한 단계 판정에서 목표를 확인했는지. false는 미확인도 포함한다. */
	public final boolean confirmed(S stage) {
		return m_steps.stream().anyMatch(step -> step.stage() == stage && step.goalReached());
	}
}
