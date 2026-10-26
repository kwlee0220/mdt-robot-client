package mdt.robot.client;

/**
 * 연산이 <b>접수된 뒤</b> 실패로 끝났다 ({@code status} 가 {@code COMPLETED} 가 아니다).
 * <p>
 * {@link RobotTwinClient#outputs} 가 던진다. {@link RobotTwinClient#run} 은 실패해도 상태를 그대로
 * 돌려주므로, 결과를 쓸 것이라면 {@code outputs} 를 써서 실패를 놓치지 않는다. 트윈이 준 사유가
 * {@link #getResult()} 에 그대로 실린다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationFailedException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final transient OperationResult m_result;

	public OperationFailedException(OperationResult result) {
		super(String.format("'%s' %s: %s%s", result.operation(), result.status(),
							result.errorCode() != null ? result.errorCode() + ": " : "",
							result.errorMessage() != null ? result.errorMessage() : "the twin gave no reason"));
		m_result = result;
	}

	/**
	 * 실패로 끝난 연산 결과를 반환한다.
	 *
	 * @return	연산 결과.
	 */
	public OperationResult getResult() {
		return m_result;
	}

	/**
	 * 실패 코드를 반환한다. (예: {@code PLANNING_FAILED})
	 *
	 * @return	실패 코드. 취소된 경우 등 코드가 없으면 {@code null}.
	 */
	public String getCode() {
		return m_result.errorCode();
	}
}
