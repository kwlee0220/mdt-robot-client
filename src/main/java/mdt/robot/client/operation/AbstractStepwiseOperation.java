package mdt.robot.client.operation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;
import mdt.robot.client.OperationResult;

import utils.Preconditions;
import utils.async.AbstractThreadedExecution;
import utils.async.AsyncState;

/**
 * 이동·그리퍼·물체 정착 관측 판정을 순서대로 조합하는 한 번의 실행.
 * 단계 실행·후속 단계 중단·취소 전달·예외와 원격 결과 보존은 이 클래스가 맡는다.
 * 하위 클래스는 실행 순서와 최종 결과 해석만 제공한다. 생성자는 통신하지 않는다.
 * <p>
 * 실행 순서는 두 가지로 줄 수 있다. 단계 목록을 생성자에 주면 그 순서대로 실행한다(Grasp·Place).
 * 목록 없이 생성하면 하위 클래스가 {@link #nextStep(StepwiseProgress)}에서 직전 결과를 보고 다음 단계를
 * 정한다. 같은 단계 이름을 여러 번 쓸 수 있으므로 보정을 반복하는 연산에 쓴다.
 * <p>
 * 각 단계는 독립된 미실행 하위 객체를 사용해야 하며 실행 중 외부에서 직접 제어하지 않는다.
 * 한 단계가 목표를 확인하지 못하면 이후 단계를 수행하지 않고 결과를 반환한다.
 * 지원하는 하위 판정은 MoveCheck, GripperCheck, WaitUntilObjectStill.Result, MeasureObjectOffset.Result다.
 * <p>
 * cancel(true)는 후속 단계 중단을 먼저 접수하고 현재 하위 취소를 잠금 밖에서 시도한다.
 * 하위 취소 불가·거부·오류인 경우에도 후속 단계는 실행하지 않는다. 동기 HTTP는 종료를 기다린다.
 * 원격 정지를 보장하지 않으며 자동 복구·재시도는 하지 않는다. STARTING 중 취소는 거부한다.
 * 시도 기록은 단계 진입과 함께 같은 잠금으로 갱신하며 전송 여부를 단정하지 않는다.
 */
public abstract class AbstractStepwiseOperation<S extends Enum<S>, C>
		extends AbstractThreadedExecution<C> implements RobotTwinOperation<C> {

	/**
	 * 단계 이름과 해당 단계가 소유하는 한 번의 하위 실행. 하위 실행은 로봇 트윈 연산
	 * ({@link AbstractRobotTwinOperation})이거나 관측 연산({@link AbstractObservationOperation})이다.
	 */
	protected record Step<S extends Enum<S>>(S stage, AbstractThreadedExecution<?> operation) {
		public Step {
			Preconditions.checkNotNullArgument(stage, "stage is null");
			Preconditions.checkNotNullArgument(operation, "operation is null");
			Preconditions.checkArgument(operation instanceof RobotTwinOperation,
										"operation is not a RobotTwinOperation: " + operation);
		}
	}

	// 단계 목록을 받지 않고 생성하면 null이며, 하위 클래스가 nextStep()을 구현한다.
	private final @Nullable List<Step<S>> m_sequence;
	private final Object m_control = new Object();
	// 아래 실행 상태는 m_control로 보호한다.
	private final List<StepResult<S>> m_steps = new ArrayList<>();
	private final Set<S> m_enteredStages = new HashSet<>();
	private final Set<AbstractThreadedExecution<?>> m_usedOperations = Collections.newSetFromMap(new IdentityHashMap<>());
	private AbstractThreadedExecution<?> m_active;
	// 목표를 확인한 단계의 기록과 같은 잠금 안에서 정한 다음 단계. 끝이면 null.
	private @Nullable Step<S> m_next;
	private S m_stage;
	private boolean m_cancelRequested;
	private ChildCancellationState m_childCancellationState = ChildCancellationState.NOT_REQUESTED;
	private Throwable m_cancellationFailure;
	private boolean m_finished;

	/** 주어진 단계 목록을 순서대로 실행한다. 단계 이름과 하위 실행은 서로 겹치면 안 된다. */
	protected AbstractStepwiseOperation(List<Step<S>> sequence) {
		Preconditions.checkNotNullArgument(sequence, "sequence is null");
		Preconditions.checkArgument(!sequence.isEmpty(), "sequence must not be empty");
		Set<S> stages = new HashSet<>();
		for ( Step<S> step : sequence ) {
			Preconditions.checkNotNullArgument(step, "step is null");
			Preconditions.checkArgument(stages.add(step.stage()), "duplicate stage: " + step.stage());
			Preconditions.checkArgument(m_usedOperations.add(step.operation()), "a child operation cannot be reused");
			Preconditions.checkArgument(step.operation().isNotStarted(), "child operation has already started");
		}
		m_sequence = List.copyOf(sequence);
	}

	/**
	 * 실행 중에 다음 단계를 정하는 연산을 만든다. 하위 클래스는 {@link #nextStep(StepwiseProgress)}를 구현한다.
	 * 같은 단계 이름은 여러 번 쓸 수 있지만, 하위 실행 객체는 단계마다 새로 만들어야 한다.
	 */
	protected AbstractStepwiseOperation() {
		m_sequence = null;
	}

	/** failedStep은 모든 단계의 목표를 확인하면 null. 예외·취소 경로에서는 호출하지 않는다. */
	protected abstract C createResult(@Nullable StepResult<S> failedStep, StepwiseProgress<S> progress);

	/**
	 * 지금까지의 진행을 보고 다음에 실행할 단계를 정한다. 비어 있으면 모든 단계의 목표를 확인한 것으로 보고
	 * {@link #createResult}로 끝낸다.
	 * <p>
	 * 첫 단계 전과 목표를 확인한 단계마다 한 번씩, 내부 잠금 안에서 호출된다. 목표를 확인하지 못한 단계나
	 * 예외·취소 뒤에는 호출되지 않는다. 잠금 안에서 실행되므로 <b>통신·대기 없이 메모리 안의 계산만</b> 해야 한다.
	 * 반환하는 하위 실행은 아직 시작하지 않은 새 객체여야 하며, 이미 쓴 객체를 다시 주면 실행이 실패한다.
	 * 기본 구현은 생성자에서 받은 단계 목록을 차례로 돌려준다.
	 *
	 * @param progress	지금까지의 진행 스냅샷.
	 * @return	다음 단계. 끝이면 {@link Optional#empty()}.
	 */
	protected Optional<Step<S>> nextStep(StepwiseProgress<S> progress) {
		if ( m_sequence == null ) {
			throw new IllegalStateException(getClass().getSimpleName() + " must override nextStep()");
		}
		int index = progress.steps().size();
		return (index < m_sequence.size()) ? Optional.of(m_sequence.get(index)) : Optional.empty();
	}

	/** 워크플로가 단계 enum의 종류와 무관하게 조회할 수 있는 공통 진행 타입. */
	public StepwiseProgress<S> getProgress() { return snapshot(); }

	/** 하위 클래스도 반드시 이 단일 스냅샷으로 도메인 진행 정보를 계산한다. */
	protected final StepwiseProgress<S> snapshot() {
		synchronized ( m_control ) {
			return new StepwiseProgress<>(Optional.ofNullable(m_stage), m_steps, m_enteredStages,
					m_cancelRequested, m_childCancellationState, m_cancellationFailure);
		}
	}

	@Override public final boolean cancel(boolean interrupt) {
		m_aopGuard.lock();
		try {
			if ( isNotStarted() ) {
				synchronized ( m_control ) {
					m_cancelRequested = true;
					m_childCancellationState = ChildCancellationState.NOT_NEEDED;
					m_finished = true;
				}
				return super.cancel(interrupt);
			}
			if ( isCancelled() ) { return super.cancel(interrupt); }
			if ( !interrupt || isDone() || getState() == AsyncState.STARTING ) { return false; }
		}
		finally { m_aopGuard.unlock(); }
		AbstractThreadedExecution<?> active;
		synchronized ( m_control ) {
			if ( m_finished ) { return false; }
			if ( m_cancelRequested ) { return true; }
			m_cancelRequested = true;
			active = m_active;
			m_childCancellationState = active == null ? ChildCancellationState.NOT_NEEDED : ChildCancellationState.IN_PROGRESS;
		}
		notifyCancelling();
		if ( active != null ) {
			// 하위 잠금/HTTP를 기다리는 동안 부모 잠금은 잡지 않는다. 위 플래그가 후속 단계를 막는다.
			ChildCancellationState state;
			Throwable failure = null;
			try { state = active.cancel(true) ? ChildCancellationState.ACCEPTED : ChildCancellationState.REJECTED; }
			catch ( RuntimeException e ) { state = ChildCancellationState.FAILED; failure = e; }
			synchronized ( m_control ) {
				// 작업이 이미 종료됐어도 전달 결과는 보존한다. 실행 결과/단계 기록은 바꾸지 않는다.
				m_childCancellationState = state;
				m_cancellationFailure = failure;
			}
		}
		return true;
	}

	@Override protected final C executeWork() throws InterruptedException, ExecutionException {
		try {
			Step<S> next;
			synchronized ( m_control ) {
				next = takeNextStep();
				if ( next == null ) { checkStopped(); m_finished = true; }
			}
			while ( next != null ) {
				StepResult<S> result = perform(next.stage(), next.operation());
				if ( !result.goalReached() ) { return createResult(result, snapshot()); }
				synchronized ( m_control ) { next = m_next; m_next = null; }
			}
			return createResult(null, snapshot());
		}
		finally {
			synchronized ( m_control ) { m_active = null; m_stage = null; m_next = null; m_finished = true; }
		}
	}

	// m_control를 잡은 상태에서 호출한다.
	private @Nullable Step<S> takeNextStep() {
		Step<S> next = nextStep(snapshot()).orElse(null);
		// 생성자에서 검증한 고정 목록과 달리, 실행 중에 정한 단계는 여기서 검증한다.
		if ( next != null && m_sequence == null ) {
			if ( !m_usedOperations.add(next.operation()) ) {
				throw new IllegalStateException("a child operation cannot be reused: " + next.stage());
			}
			if ( !next.operation().isNotStarted() ) {
				throw new IllegalStateException("child operation has already started: " + next.stage());
			}
		}
		return next;
	}

	private StepResult<S> perform(S stage, AbstractThreadedExecution<?> operation)
			throws InterruptedException, ExecutionException {
		synchronized ( m_control ) {
			checkStopped();
			m_stage = stage;
			m_active = operation;
			// 전송 여부를 단정하지 않는 보수적 기록. 바로 뒤 취소/접수 거부가 생겨도 되돌리지 않는다.
			m_enteredStages.add(stage);
		}
		Object value;
		try {
			if ( operation.isCancelled() ) { throw cancelledBeforeStart(stage); }
			try { value = operation.run(); }
			catch ( CancellationException e ) { throw e; }
			catch ( IllegalStateException e ) {
				// 사전 확인과 run()의 원자적 시작 사이에도 취소될 수 있다.
				// 실행 본문의 오류는 ExecutionException으로 오므로 여기서는 시작 거부만 다룬다.
				boolean childCancelled = operation.isCancelled();
				synchronized ( m_control ) {
					if ( m_cancelRequested && childCancelled ) { throw cancelledBeforeStart(stage); }
				}
				throw e;
			}
		}
		catch ( InterruptedException | ExecutionException | RuntimeException e ) {
			Optional<OperationResult> remoteResult = remoteResultOf(operation);
			synchronized ( m_control ) {
				Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
				m_steps.add(new StepResult<>(stage, null, null, remoteResult, cause));
				m_active = null;
				m_stage = null;
				m_finished = true;
				throwIfCancelled(e);
			}
			throw e;
		}
		// 이 조회도 하위 취소의 잠금을 기다릴 수 있으므로 부모 잠금 밖에서 수행한다.
		Optional<OperationResult> remoteResult = remoteResultOf(operation);
		synchronized ( m_control ) {
			StepResult<S> step = new StepResult<>(stage, value instanceof MoveCheck move ? move : null,
					value instanceof GripperCheck gripper ? gripper : null, remoteResult, null,
					value instanceof WaitUntilObjectStill.Result still ? still : null,
					value instanceof MeasureObjectOffset.Result measured ? measured : null);
			m_steps.add(step); // 접수된 취소보다 먼저 실제로 받은 결과를 기록한다.
			m_active = null;
			m_stage = null;
			checkStopped();
			// 다음 단계도 같은 잠금 안에서 정해, 마지막 단계의 완료와 취소 접수 사이에 틈이 없게 한다.
			m_next = step.goalReached() ? takeNextStep() : null;
			if ( m_next == null ) { m_finished = true; }
			return step;
		}
	}

	// 관측 연산은 원격 연산이 아니므로 원격 결과가 없다.
	private static Optional<OperationResult> remoteResultOf(AbstractThreadedExecution<?> operation) {
		return (operation instanceof AbstractRobotTwinOperation<?> remote) ? remote.getRemoteResult() : Optional.empty();
	}

	private static CancellationException cancelledBeforeStart(Enum<?> stage) {
		return new CancellationException("하위 연산 실행 전 취소: " + stage);
	}

	// m_control를 잡은 상태에서 호출한다.
	private void checkStopped() throws InterruptedException {
		throwIfCancelled(null);
		if ( Thread.interrupted() ) { throw new InterruptedException("interrupted during " + getClass().getSimpleName()); }
	}

	private void throwIfCancelled(@Nullable Throwable cause) {
		if ( m_cancelRequested ) {
			Thread.interrupted();
			CancellationException error = new CancellationException(getClass().getSimpleName() + " cancelled; subsequent stages will not run");
			if ( cause != null ) { error.initCause(cause); }
			throw error;
		}
	}
}
