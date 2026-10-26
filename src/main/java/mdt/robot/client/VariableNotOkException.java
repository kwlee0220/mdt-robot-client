package mdt.robot.client;

import org.jetbrains.annotations.Nullable;

/**
 * 상태 변수의 품질({@code quality})이 {@code OK} 가 아니다.
 * <p>
 * {@link RobotTwinClient#read(String)} 가 던진다. 낡은 값을 확인 없이 쓰지 않도록 강제한다 — 품질 자체가
 * 판단 근거라면 {@link RobotTwinClient#readRaw(String)} 를 쓴다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class VariableNotOkException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String m_variable;
	private final String m_quality;
	private final @Nullable String m_reason;

	public VariableNotOkException(String variable, String quality, @Nullable String reason) {
		super(String.format("variable '%s' is %s%s", variable, quality,
							reason != null ? " (" + reason + ")" : ""));
		m_variable = variable;
		m_quality = quality;
		m_reason = reason;
	}

	public String getVariable() {
		return m_variable;
	}

	public String getQuality() {
		return m_quality;
	}

	public @Nullable String getReason() {
		return m_reason;
	}
}
