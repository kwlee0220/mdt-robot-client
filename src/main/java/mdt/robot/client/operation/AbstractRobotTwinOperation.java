package mdt.robot.client.operation;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;
import utils.RuntimeInterruptedException;
import utils.async.AbstractThreadedExecution;
import utils.async.AsyncState;

/**
 * 원격 연산 한 번의 호출·결과 변환을 수행하는 로봇 연산의 공통 실행부.
 * <p>
 * 생성자는 통신하지 않으며 run()/start()로 한 번 실행한다. 하위 클래스는 입력과 결과 판정을 구현한다.
 * 원격 비동기 세션 확보 후 cancel(true)는 DELETE를 보내고 최종 응답을 기다린다.
 * 세션 확보 전·동기 HTTP 호출 중·실행 후 cancel(false)는 취소하지 않는다.
 * 원격 결과 대기 상한은 timeout이며 후속 관측 대기나 전체 실행 시간의 상한은 아니다.
 * <p>
 * 인터럽트 인식 시 로컬 실행을 취소한다. 동기 HTTP의 블로킹 읽기는 즉시 중단을 보장하지 않으며,
 * 인터럽트 신호 없는 HTTP 시간 초과는 실패다. 외부 인터럽트와 시간 초과는 원격 취소를 자동 요청하지 않는다. CANCELLED는 로컬 실행 중단으로,
 * 실제 로봇의 정지 완료를 보장하지 않는다. 완료 결과의 성공 여부는 도메인 결과에서 확인한다.
 *
 * @param <T> 연산별 판정 결과.
 */
public abstract class AbstractRobotTwinOperation<T> extends AbstractThreadedExecution<T>
                                                    implements RobotTwinOperation<T> {
	protected final RobotTwinClient m_twin;
	private final String m_operation;
	private final Duration m_timeout;
	private final Object m_control = new Object();
	// 아래 필드는 m_control로 보호한다.
	private RobotTwinClient.AsyncOperation m_remote;
	private OperationResult m_remoteResult;
	private Thread m_observationThread;
	private boolean m_cancelRequested;

	protected AbstractRobotTwinOperation(RobotTwinClient twin, String operation, Duration timeout) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		Preconditions.checkNotNullArgument(operation, "operation is null");
		Preconditions.checkNotNullArgument(timeout, "timeout is null");
		Preconditions.checkArgument(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");
		m_twin = twin;
		m_operation = operation;
		m_timeout = timeout;
	}

	protected static Duration defaultTimeout(RobotTwinClient twin) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		return twin.getOperationTimeout();
	}

	/**
	 * 이 실행이 실제로 받은 원격 결과를 반환한다. 로컬 CANCELLED와 별개로 결과를 보존한다.
	 * 결과 수신 전·실행 전 취소·결과를 받지 못한 시간 초과/통신 오류에서는 비어 있다.
	 * 시간 초과 뒤 백그라운드 폴러가 완료되어도 자동 갱신하지 않는다.
	 * 원격 COMPLETED는 실제 도달이나 정지 완료를 보장하지 않는다.
	 */
	public final Optional<OperationResult> getRemoteResult() {
		synchronized ( m_control ) {
			return Optional.ofNullable(m_remoteResult);
		}
	}

	/** 실행 시 원격 연산 입력을 만든다. */
	protected abstract Map<String,?> buildInputs();

	/**
	 * 원격 결과를 도메인 결과로 바꾼다. 필요한 경우 후속 관측도 이 메서드에서 수행한다.
	 * 관측 실행의 checked 예외는 그대로 전달할 수 있다. 접수된 관측 취소는 실패보다 우선한다.
	 */
	protected abstract T convertResult(OperationResult result) throws InterruptedException, ExecutionException;

	/**
	 * 결과 변환 중 인터럽트 가능한 관측 대기가 필요한지 판정한다. 통신 없이 즉시 반환해야 한다.
	 * true이면 해당 단계에서 cancel(true)가 작업 스레드를 인터럽트한다.
	 */
	protected boolean requiresObservation(OperationResult result) {
		return false;
	}

	/** true는 취소 요청 접수이며, 종료는 get()/상태로 별도 확인한다. */
	@Override
	public final boolean cancel(boolean mayInterruptIfRunning) {
		m_aopGuard.lock();
		try {
			if ( isNotStarted() || isCancelled() ) {
				return super.cancel(mayInterruptIfRunning);
			}
			if ( !mayInterruptIfRunning || isDone() || getState() == AsyncState.STARTING ) {
				return false;
			}
		}
		finally {
			m_aopGuard.unlock();
		}
		synchronized ( m_control ) {
			if ( m_cancelRequested ) {
				return true;
			}
			if ( m_observationThread != null ) {
				m_cancelRequested = true;
				m_observationThread.interrupt();
			}
			else if ( m_remote != null && m_remote.cancel() ) {
				m_cancelRequested = true;
			}
			else {
				return false;
			}
		}
		// HTTP 요청 동안 실행 상태 lock은 잡지 않는다.
		notifyCancelling();
		return true;
	}

	@Override
	protected final T executeWork() throws InterruptedException, ExecutionException {
		try {
			OperationResult result = m_twin.runCancellable(m_operation, buildInputs(), m_timeout, remote -> {
				synchronized ( m_control ) {
					m_remote = remote;
				}
			});
			synchronized ( m_control ) {
				// 취소가 접수되었더라도 실제로 받은 최종 결과는 먼저 보존한다.
				m_remoteResult = result;
				m_remote = null;
				checkCancellation();
				if ( requiresObservation(result) ) {
					m_observationThread = Thread.currentThread();
				}
			}
			T converted = convertResult(result);
			synchronized ( m_control ) {
				checkCancellation();
				m_observationThread = null;
			}
			return converted;
		}
		catch ( CancellationException e ) {
			throw e;
		}
		catch ( RuntimeInterruptedException e ) {
			// 런타임 래퍼가 복원한 플래그를 해제하고 checked exception으로 전달한다.
			Thread.interrupted();
			InterruptedException cause = e.getCause();
			if ( cause == null ) {
				cause = new InterruptedException(e.getMessage());
				cause.initCause(e);
			}
			throw cause;
		}
		catch ( RuntimeException | ExecutionException e ) {
			synchronized ( m_control ) {
				if ( m_cancelRequested && m_observationThread != null ) {
					Thread.interrupted();
					CancellationException cancelled = new CancellationException("observation cancelled");
					cancelled.initCause(e);
					throw cancelled;
				}
				// 실패가 확정된 관측 스레드에 뒤늦은 취소를 접수하지 않는다.
				m_observationThread = null;
			}
			throw e;
		}
		finally {
			synchronized ( m_control ) {
				m_observationThread = null;
				m_remote = null;
			}
		}
	}

	// m_control를 잡은 상태에서 호출한다.
	private void checkCancellation() {
		if ( m_cancelRequested ) {
			if ( m_observationThread != null ) {
				Thread.interrupted();
			}
			throw new CancellationException("operation cancelled");
		}
	}
}
