package mdt.robot.client;

import org.jetbrains.annotations.Nullable;

/**
 * 비동기 연산이 <b>접수된 뒤</b> 세션 조회가 HTTP 오류를 받아 <b>결과를 알 수 없게 되었다.</b>
 * <p>
 * 세션이 만료·축출되었거나 트윈이 재시작되면 세션 조회가 404 등을 받는다. 이때 원격 연산은 이미 시작되었으므로
 * <b>로봇이 움직였을 수 있다.</b> {@link TwinException}(접수 거부 — 아무 일도 일어나지 않음)과 구분한다.
 * 같은 명령을 바로 다시 보내지 말고, 상태 변수({@code ee_pose} 등)와 {@link RobotTwinClient#resources()}로
 * 현재 상태를 확인한다. 트윈이 준 오류(상태 코드·코드)는 원인인 {@link TwinException}에 있다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SessionLostException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String m_operation;
	private final String m_sessionUrl;

	SessionLostException(String operation, String sessionUrl, TwinException cause) {
		super(String.format("'%s' was accepted but its session is no longer available (session=%s): %s; "
							+ "the outcome is unknown and the robot may have moved — check the state before retrying",
							operation, sessionUrl, cause.getMessage()), cause);
		m_operation = operation;
		m_sessionUrl = sessionUrl;
	}

	/**
	 * 연산 이름을 반환한다.
	 *
	 * @return	연산 이름.
	 */
	public String getOperationName() {
		return m_operation;
	}

	/**
	 * 잃어버린 세션의 URL을 반환한다.
	 *
	 * @return	세션 URL.
	 */
	public String getSessionUrl() {
		return m_sessionUrl;
	}

	/**
	 * 세션 조회가 받은 HTTP 상태 코드를 반환한다. (대개 404)
	 *
	 * @return	HTTP 상태 코드.
	 */
	public int getHttpStatus() {
		return getCause().getHttpStatus();
	}

	/**
	 * 트윈의 오류 코드를 반환한다. (예: {@code SESSION_NOT_FOUND})
	 *
	 * @return	오류 코드. 본문에 없으면 {@code null}.
	 */
	public @Nullable String getCode() {
		return getCause().getCode();
	}

	@Override
	public synchronized TwinException getCause() {
		return (TwinException)super.getCause();
	}
}
