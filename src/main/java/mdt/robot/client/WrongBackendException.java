package mdt.robot.client;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * 연결된 트윈이 작업이 요구하는 환경(백엔드)이 아니다.
 * <p>
 * 트윈 id는 환경마다 다르지만(Isaac {@code panda01}, 펑션베이 {@code rdfp01}) 포트는 같고, 어느 id가 어느
 * 백엔드로 뜨는지는 서버 설정이 정한다. 그래서 <b>접속 주소만으로는 환경을 단정할 수 없다.</b> 확인하지 않으면
 * Isaac 물리가 필요한 작업이 다른 백엔드의 트윈에 붙어 오류 없이 엉뚱하게 돈다.
 * {@link RobotTwinClient#requireBackend} 가 던진다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class WrongBackendException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String m_purpose;
	private final List<String> m_expected;
	private final @Nullable String m_actual;

	public WrongBackendException(String purpose, List<String> expected, @Nullable String actual) {
		super(String.format("%s requires a twin of %s, but the connected twin is %s; "
							+ "start a twin of that backend with ./scripts/run_robot_twin.sh --twin <id> and connect "
							+ "to its id (an unknown id lists the available twins)",
							purpose, String.join(" | ", expected),
							actual != null ? actual : "unknown (the twin reports no backend — update rdfp_ws)"));
		m_purpose = purpose;
		m_expected = List.copyOf(expected);
		m_actual = actual;
	}

	public String getPurpose() {
		return m_purpose;
	}

	public List<String> getExpected() {
		return m_expected;
	}

	/**
	 * 연결된 트윈의 백엔드를 반환한다.
	 *
	 * @return	백엔드 이름. 트윈이 알려 주지 않으면 {@code null}.
	 */
	public @Nullable String getActual() {
		return m_actual;
	}
}
