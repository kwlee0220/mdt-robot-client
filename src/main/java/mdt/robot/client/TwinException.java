package mdt.robot.client;

import org.jetbrains.annotations.Nullable;

/**
 * 트윈이 요청을 <b>접수하지 않고</b> 거부했다 (HTTP 4xx / 5xx).
 * <p>
 * {@link OperationFailedException} 과 다르다 — 그쪽은 접수되어 실행되다가 실패한 것이고, 이쪽은 세션이
 * 만들어지지 않았다. 트윈의 오류 본문 {@code {"error": {"code", "message"}}} 를 그대로 싣는다.
 *
 * <table border="1">
 *   <caption>대표 코드</caption>
 *   <tr><th>HTTP</th><th>code</th><th>뜻</th></tr>
 *   <tr><td>400</td><td>INVALID_INPUT</td><td>요청 형식 또는 연산 입력이 스키마에 맞지 않음</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td>정의되지 않은 변수·연산</td></tr>
 *   <tr><td>409</td><td>RESOURCE_BUSY</td><td>필요한 자원을 다른 연산이 점유</td></tr>
 *   <tr><td>409</td><td>ESTOP_ENGAGED</td><td>비상정지 잠금</td></tr>
 *   <tr><td>503</td><td>PRECONDITION_FAILED</td><td>MoveGroup·수집 서비스 미준비 (백엔드 부재)</td></tr>
 * </table>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TwinException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final int m_httpStatus;
	private final @Nullable String m_code;

	public TwinException(int httpStatus, @Nullable String code, @Nullable String message, Throwable cause) {
		super(String.format("HTTP %d %s: %s", httpStatus, code, message), cause);
		m_httpStatus = httpStatus;
		m_code = code;
	}

	/**
	 * HTTP 상태 코드를 반환한다.
	 *
	 * @return	HTTP 상태 코드.
	 */
	public int getHttpStatus() {
		return m_httpStatus;
	}

	/**
	 * 트윈의 오류 코드를 반환한다. (예: {@code RESOURCE_BUSY})
	 *
	 * @return	오류 코드. 본문에 없으면 {@code null}.
	 */
	public @Nullable String getCode() {
		return m_code;
	}
}
