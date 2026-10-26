package mdt.robot.client.operation;

import java.util.concurrent.CancellationException;

import org.jetbrains.annotations.Nullable;

import utils.RuntimeInterruptedException;
import utils.async.AbstractThreadedExecution;
import utils.async.AsyncState;

/**
 * 로컬 관측의 실행·취소 수명주기와 스냅샷 게시. 원격 POST/DELETE를 수행하지 않는다.
 * <p>
 * 실행 중 cancel(true)는 작업 스레드를 인터럽트하고 종료까지 CANCELLING을 유지한다.
 * STARTING 중 취소와 실행 중 cancel(false)는 거부한다. 접수된 취소는 결과/실패보다 우선한다.
 * 결과 확정 또는 실패 시 작업 스레드를 분리하여 뒤늦은 취소가 다음 작업을 인터럽트하지 않게 한다.
 * 외부 인터럽트는 run()에서 InterruptedException, start()/get()에서 CancellationException이다.
 * HTTP의 즉시 중단은 보장하지 않는다. HTTP 시간 초과와 진짜 인터럽트의 구분은 RobotTwinClient가 맡는다.
 * <p>
 * 하위 클래스는 생성자에서 초기 스냅샷을 게시한다. 수명주기 훅은 잠금 안에서 실행되므로
 * 메모리 내 스냅샷 처리만 하며 통신/대기를 해서는 안 된다. 표본 판정은 observe()에 둔다.
 *
 * @param <R> 기존 호출자에게 반환하는 연산 결과.
 * @param <P> 진행 스냅샷. 결과와 다른 타입일 수 있다.
 */
public abstract class AbstractObservationOperation<R,P> extends AbstractThreadedExecution<R>
		implements RobotTwinOperation<R> {
	private volatile P m_progress;
	// m_aopGuard로 보호한다.
	private Thread m_worker;
	private boolean m_cancelRequested;

	/** 최신 게시 스냅샷. 실행기 제출 실패 등 관측 시작 전 실패는 초기 스냅샷일 수 있다. */
	public final P getProgress() { return m_progress; }
	public abstract ObservationDiagnostics<?> getDiagnostics();

	protected final void publishProgress(P snapshot) { m_progress = snapshot; }
	protected void onObservationStarted() { }
	protected abstract R observe() throws InterruptedException;
	protected abstract void onObservationCompleted(R result);
	/** 실행 전 취소 또는 실행 중 취소/실패의 최종 스냅샷을 게시한다. */
	protected abstract void onObservationStopped(boolean cancelled);

	@Override public final boolean cancel(boolean interrupt) {
		m_aopGuard.lock();
		try {
			if ( isNotStarted() ) {
				onObservationStopped(true);
				return super.cancel(interrupt);
			}
			if ( isCancelled() ) { return true; }
			if ( !interrupt || isDone() || getState() == AsyncState.STARTING ) { return false; }
			if ( m_cancelRequested ) { return true; }
			if ( m_worker == null ) { return false; }
			m_cancelRequested = true;
			notifyCancelling();
			m_worker.interrupt();
			return true;
		}
		finally { m_aopGuard.unlock(); }
	}

	@Override protected final R executeWork() throws InterruptedException {
		boolean completed = false;
		boolean cancelled = false;
		try {
			m_aopGuard.lock();
			try { m_worker = Thread.currentThread(); onObservationStarted(); }
			finally { m_aopGuard.unlock(); }
			checkStopped();
			R result = observe();
			m_aopGuard.lock();
			try {
				checkStopped();
				onObservationCompleted(result);
				m_worker = null;
				completed = true;
				return result;
			}
			finally { m_aopGuard.unlock(); }
		}
		catch ( RuntimeInterruptedException e ) {
			cancelled = true;
			detach(e);
			Thread.interrupted();
			InterruptedException cause = e.getCause();
			if ( cause == null ) { cause = new InterruptedException(e.getMessage()); cause.initCause(e); }
			throw cause;
		}
		catch ( InterruptedException | CancellationException e ) {
			cancelled = true;
			detach(e);
			throw e;
		}
		catch ( RuntimeException e ) { detach(e); throw e; }
		finally {
			m_aopGuard.lock();
			try {
				m_worker = null;
				if ( m_cancelRequested ) { Thread.interrupted(); cancelled = true; }
				if ( !completed ) { onObservationStopped(cancelled); }
			}
			finally { m_aopGuard.unlock(); }
			getLogger().debug("observation finished: {}", getDiagnostics());
		}
	}

	protected final void checkStopped() throws InterruptedException {
		m_aopGuard.lock();
		try { throwIfCancelled(null); }
		finally { m_aopGuard.unlock(); }
		if ( Thread.interrupted() ) { throw new InterruptedException("observation interrupted"); }
	}

	private void detach(Throwable error) {
		m_aopGuard.lock();
		try { m_worker = null; throwIfCancelled(error); }
		finally { m_aopGuard.unlock(); }
	}

	private void throwIfCancelled(@Nullable Throwable cause) {
		if ( m_cancelRequested ) {
			Thread.interrupted();
			CancellationException error = new CancellationException("observation cancelled");
			if ( cause != null ) { error.initCause(cause); }
			throw error;
		}
	}
}
