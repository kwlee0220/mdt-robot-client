package mdt.robot.client;

import java.time.Duration;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 트윈이 요청을 <b>접수하지 않고</b> 거부했다 (HTTP 4xx / 5xx).
 * <p>
 * {@link OperationFailedException} 과 다르다 — 그쪽은 접수되어 실행되다가 실패한 것이고, 이쪽은 세션이
 * 만들어지지 않았다. 접수된 뒤 세션 조회가 HTTP 오류를 받은 경우는 {@link SessionLostException}이며, 이
 * 예외는 그 원인으로 실린다. (변수 조회·취소 요청 등 다른 요청의 HTTP 오류도 이 예외다.) 트윈의 오류 본문 {@code {"error": {"code", "message"}}} 를 그대로 싣는다.
 *
 * <table border="1">
 *   <caption>대표 코드</caption>
 *   <tr><th>HTTP</th><th>code</th><th>뜻</th></tr>
 *   <tr><td>400</td><td>INVALID_INPUT</td><td>요청 형식 또는 연산 입력이 스키마에 맞지 않음</td></tr>
 *   <tr><td>404</td><td>NOT_FOUND</td><td>정의되지 않은 변수·연산</td></tr>
 *   <tr><td>409</td><td>RESOURCE_BUSY</td><td>필요한 자원을 다른 연산이 점유</td></tr>
 *   <tr><td>409</td><td>ESTOP_ENGAGED</td><td>비상정지 잠금</td></tr>
 *   <tr><td>409</td><td>NOT_CANCELLABLE</td><td>취소 요청: 세션이 이미 끝났음
 *       ({@link RobotTwinClient.AsyncOperation#cancel()}는 예외 대신 {@code false})</td></tr>
 *   <tr><td>501</td><td>CANCEL_UNSUPPORTED</td><td>취소 요청: 제어기에 정지를 전달할 수 없음.
 *       <b>팔은 계속 움직일 수 있다</b> — 물리 비상정지를 쓴다</td></tr>
 *   <tr><td>503</td><td>PRECONDITION_FAILED</td><td>MoveGroup·수집 서비스 미준비 (백엔드 부재)</td></tr>
 * </table>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class TwinException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final int m_httpStatus;
	private final @Nullable String m_code;
	private final transient JsonNode m_details;
	private final @Nullable Duration m_retryAfter;

	public TwinException(int httpStatus, @Nullable String code, @Nullable String message, Throwable cause) {
		this(httpStatus, code, message, null, null, cause);
	}

	/**
	 * @param details		오류 본문의 {@code error.details}. 없으면 {@code null}.
	 * @param retryAfter	{@code Retry-After} 헤더(초)를 옮긴 값. 없으면 {@code null}.
	 */
	public TwinException(int httpStatus, @Nullable String code, @Nullable String message,
							@Nullable JsonNode details, @Nullable Duration retryAfter, Throwable cause) {
		super(String.format("HTTP %d %s: %s", httpStatus, code, message), cause);
		m_httpStatus = httpStatus;
		m_code = code;
		m_details = (details != null) ? details : MissingNode.getInstance();
		m_retryAfter = retryAfter;
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

	/**
	 * 오류 본문의 {@code error.details}를 반환한다. 예를 들어 409 {@code RESOURCE_BUSY}는
	 * {@code resource}·{@code operation}·{@code occupied_by}(점유 세션 경로)·{@code estimated_remaining_ms}를 준다.
	 * 점유 세션을 취소하려면 {@code occupied_by}를 {@link RobotTwinClient#cancel(String)}에 넘긴다.
	 *
	 * @return	상세 정보. 없으면 {@link MissingNode}({@code isMissingNode() == true}).
	 */
	public JsonNode getDetails() {
		return m_details;
	}

	/**
	 * 트윈이 알려 준 재시도 대기 시간({@code Retry-After} 헤더)을 반환한다. 409 {@code RESOURCE_BUSY}에서
	 * 점유 연산의 예상 잔여 시간으로 준다.
	 *
	 * @return	재시도 대기 시간. 헤더가 없으면 빈 값.
	 */
	public Optional<Duration> getRetryAfter() {
		return Optional.ofNullable(m_retryAfter);
	}
}
