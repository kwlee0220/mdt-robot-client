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
 *
 * @param velocityScaling	속도 비율 (0, 1]. {@code null}이면 서버 기본값.
 * @param maxDuration		서버 실행 제한. {@code null}이면 서버 기본값.
 * @param timeout			클라이언트 대기 제한. {@code null}이면 위 규칙으로 정한다.
 */
public record MotionLimits(@Nullable Double velocityScaling, @Nullable Duration maxDuration, @Nullable Duration timeout) {
	/** 기본 속도 비율. */
	public static final double DEFAULT_VELOCITY_SCALING = 0.3;
	/** {@code maxDuration}으로 대기 제한을 정할 때 더하는 여유. */
	public static final Duration CLIENT_TIMEOUT_MARGIN = Duration.ofSeconds(30);
	/** 속도 0.3, 서버·클라이언트 기본 시간 제한. */
	public static final MotionLimits DEFAULT = new MotionLimits(DEFAULT_VELOCITY_SCALING, null, null);

	public MotionLimits {
		Preconditions.checkArgument(velocityScaling == null
				|| (Double.isFinite(velocityScaling) && velocityScaling > 0 && velocityScaling <= 1),
				"velocityScaling must be finite and in (0, 1]");
		if ( maxDuration != null ) { checkPositive(maxDuration, "maxDuration"); }
		if ( timeout != null ) { checkPositive(timeout, "timeout"); }
	}

	public MotionLimits withVelocityScaling(@Nullable Double value) { return new MotionLimits(value, maxDuration, timeout); }
	public MotionLimits withMaxDuration(@Nullable Duration value) { return new MotionLimits(velocityScaling, value, timeout); }
	public MotionLimits withTimeout(@Nullable Duration value) { return new MotionLimits(velocityScaling, maxDuration, value); }

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
