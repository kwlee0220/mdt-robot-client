package mdt.robot.client;

import java.time.Duration;

/**
 * 비동기 연산이 제한 시간 안에 끝나지 않아 <b>기다리기를 그만두었다.</b>
 * <p>
 * <b>원격 연산은 취소하지 않는다</b> — 로봇은 계속 움직일 수 있다. 멈추려면 {@link #cancel()} 을
 * 부르고, 그 뒤 상태와 {@link RobotTwinClient#resources()} 를 확인한다. 같은 명령을 바로 다시 보내지
 * 않는다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationTimeoutException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String m_operation;
	private final Duration m_timeout;
	private final transient Runnable m_canceller;

	OperationTimeoutException(String operation, Duration timeout, Runnable canceller) {
		super(String.format("'%s' did not finish in %s; the remote operation keeps running", operation, timeout));
		m_operation = operation;
		m_timeout = timeout;
		m_canceller = canceller;
	}

	public String getOperation() {
		return m_operation;
	}

	public Duration getTimeout() {
		return m_timeout;
	}

	/**
	 * 원격 연산 세션에 취소를 요청한다. 접수가 정지 완료를 뜻하지는 않는다.
	 */
	public void cancel() {
		m_canceller.run();
	}
}
