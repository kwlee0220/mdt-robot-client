package mdt.robot.client;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

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
	private final transient BooleanSupplier m_canceller;
	private final @Nullable String m_sessionUrl;

	OperationTimeoutException(String operation, Duration timeout, BooleanSupplier canceller,
								@Nullable String sessionUrl) {
		super(String.format("'%s' did not finish in %s; the remote operation keeps running (session=%s)",
							operation, timeout, sessionUrl));
		m_operation = operation;
		m_timeout = timeout;
		m_canceller = canceller;
		m_sessionUrl = sessionUrl;
	}

	public String getOperationName() {
		return m_operation;
	}

	public Duration getTimeout() {
		return m_timeout;
	}

	/**
	 * 계속 실행 중인 원격 연산 세션의 URL을 반환한다. 다른 프로세스에서 취소하거나 상태를 확인할 때 쓴다.
	 *
	 * @return	세션 URL. 시간 초과 시점까지 세션을 받지 못했으면 {@code null}.
	 */
	public @Nullable String getSessionUrl() {
		return m_sessionUrl;
	}

	/**
	 * 원격 연산 세션에 취소를 요청한다. 접수가 정지 완료를 뜻하지는 않는다.
	 *
	 * @return	취소가 접수되었으면 {@code true}, 이미 끝나 취소할 대상이 없으면 {@code false}.
	 */
	public boolean cancel() {
		return m_canceller.getAsBoolean();
	}
}
