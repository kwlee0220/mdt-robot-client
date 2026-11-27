package mdt.robot.client.operation;

import java.math.BigDecimal;
import java.time.Duration;

import org.jetbrains.annotations.Nullable;

import utils.Preconditions;

/**
 * 이동 연산({@link MoveToJoints}·{@link MoveToNamedTarget})의 속도·시간 제한.
 * <p>
 * {@code maxDuration}은 <b>서버</b>의 실행 제한({@code max_duration_sec})이고, {@code timeout}은 <b>클라이언트</b>가
 * 원격 결과를 기다리는 제한이다. {@code timeout}을 주지 않고 {@code maxDuration}만 주면, 대기 제한은 클라이언트
 * 기본값과 {@code maxDuration + }{@link #CLIENT_TIMEOUT_MARGIN} 중 큰 값이다. 서버가 아직 팔을 움직이는 동안
 * 클라이언트가 먼저 포기하지 않게 하기 위해서다({@link MoveLinear}도 같은 규칙을 쓴다).
 * {@code velocityScaling}이 {@code null}이면 요청에서 생략하여 서버 기본 속도를 쓴다.
 * <p>
 * {@code settleTimeout}이 양수이고 트윈이 {@code settle_timeout_sec} 입력을 받으면, 트윈이 실행 뒤 팔이 멈출 때까지
 * 기다린 다음 목표 관절값과의 최대 차({@code goal_error.joint_max_rad})를 돌려준다. 그러면 개루프 스택에서도
 * 그 값이 {@code jointToleranceRad} 이하인지로 도달을 판정한다({@link ClosedLoopCheck}). 정착 대기는 서버의
 * {@code max_duration_sec} 안에 포함된다. 입력을 받지 않는 옛 트윈이면 보내지 않고 {@code closed_loop}로 판정한다.
 *
 * @param velocityScaling	속도 비율 (0, 1]. {@code null}이면 서버 기본값.
 * @param maxDuration		서버 실행 제한. {@code null}이면 서버 기본값.
 * @param timeout			클라이언트 대기 제한. {@code null}이면 위 규칙으로 정한다.
 * @param settleTimeout		트윈의 정착 대기 상한. 0이면 기다리지 않는다.
 * @param jointToleranceRad	정착 후 관절 최대 오차의 허용값(rad). 유한한 0 이상 값.
 */
public record MotionLimits(@Nullable Double velocityScaling, @Nullable Duration maxDuration, @Nullable Duration timeout,
							Duration settleTimeout, double jointToleranceRad) {
	/** 기본 속도 비율. */
	public static final double DEFAULT_VELOCITY_SCALING = 0.3;
	/** {@code maxDuration}으로 대기 제한을 정할 때 더하는 여유. */
	public static final Duration CLIENT_TIMEOUT_MARGIN = Duration.ofSeconds(30);
	/** 속도 0.3, 서버·클라이언트 기본 시간 제한. */
	/** 정착 후 관절 최대 오차의 기본 허용값(rad). */
	public static final double DEFAULT_JOINT_TOLERANCE_RAD = 0.01;
	public static final MotionLimits DEFAULT = new MotionLimits(DEFAULT_VELOCITY_SCALING, null, null);

	public MotionLimits {
		Preconditions.checkArgument(velocityScaling == null
				|| (Double.isFinite(velocityScaling) && velocityScaling > 0 && velocityScaling <= 1),
				"velocityScaling must be finite and in (0, 1]");
		if ( maxDuration != null ) { checkPositive(maxDuration, "maxDuration"); }
		if ( timeout != null ) { checkPositive(timeout, "timeout"); }
		Preconditions.checkNotNullArgument(settleTimeout, "settleTimeout is null");
		Preconditions.checkArgument(!settleTimeout.isNegative(), "settleTimeout must be nonnegative");
		try { settleTimeout.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException("settleTimeout is too large", e); }
		Preconditions.checkArgument(Double.isFinite(jointToleranceRad) && jointToleranceRad >= 0,
				"jointToleranceRad must be finite and nonnegative");
	}

	/** 정착 대기 없이(기본 관절 허용값으로) 만든다. */
	public MotionLimits(@Nullable Double velocityScaling, @Nullable Duration maxDuration, @Nullable Duration timeout) {
		this(velocityScaling, maxDuration, timeout, Duration.ZERO, DEFAULT_JOINT_TOLERANCE_RAD);
	}

	public MotionLimits withVelocityScaling(@Nullable Double value) {
		return new MotionLimits(value, maxDuration, timeout, settleTimeout, jointToleranceRad);
	}
	public MotionLimits withMaxDuration(@Nullable Duration value) {
		return new MotionLimits(velocityScaling, value, timeout, settleTimeout, jointToleranceRad);
	}
	public MotionLimits withTimeout(@Nullable Duration value) {
		return new MotionLimits(velocityScaling, maxDuration, value, settleTimeout, jointToleranceRad);
	}
	/** 트윈의 정착 대기 상한. 0이면 기다리지 않는다. */
	public MotionLimits withSettleTimeout(Duration value) {
		return new MotionLimits(velocityScaling, maxDuration, timeout, value, jointToleranceRad);
	}
	/** 정착 후 관절 최대 오차의 허용값(rad). */
	public MotionLimits withJointToleranceRad(double value) {
		return new MotionLimits(velocityScaling, maxDuration, timeout, settleTimeout, value);
	}

	/** 대기 제한을 정한다(클래스 설명의 규칙). */
	Duration resolveTimeout(Duration clientDefault) {
		return resolveWaitTimeout(timeout, maxDuration, clientDefault);
	}

	static Duration resolveWaitTimeout(@Nullable Duration timeout, @Nullable Duration maxDuration, Duration clientDefault) {
		if ( timeout != null ) { return timeout; }
		if ( maxDuration == null ) { return clientDefault; }
		try {
			Duration withMargin = maxDuration.plus(CLIENT_TIMEOUT_MARGIN);
			return clientDefault.compareTo(withMargin) >= 0 ? clientDefault : withMargin;
		}
		catch ( ArithmeticException e ) {
			throw new IllegalArgumentException("maxDuration + timeout margin is too large", e);
		}
	}

	/** 서버 입력 {@code max_duration_sec}(초, 소수)로 바꾼다. */
	static BigDecimal toSeconds(Duration duration) {
		return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
	}

	static Duration checkPositive(Duration duration, String name) {
		Preconditions.checkNotNullArgument(duration, name + " is null");
		Preconditions.checkArgument(!duration.isNegative() && !duration.isZero(), name + " must be positive");
		return duration;
	}
}
