package mdt.robot.client.operation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.Ops;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 직선 이동을 요청하고 위치 도달을 판정하는 한 번의 실행.
 * <p>
 * 직선 이동·도달 판정을 수행하는 실행 객체다.
 * 결과는 {@link MoveCheck}로 반환하며, 옵션은 {@link Options}, 실패 안내는 {@link Ops}를 사용한다.
 * 정착 관측은 {@link WaitUntilStill}이 담당한다.
 * {@link #run()}은 호출 스레드에서 실행하고, {@link #start()}는 별도 스레드 또는 설정된 executor에서
 * 실행한다. 생성만으로는 통신하지 않으며 객체 하나는 한 번만 실행할 수 있다.
 * <p>
 * 목표는 서버 기본 EE 프레임(미설정 시 tip 프레임) 기준으로 전달한다. frame, max_step,
 * jump_threshold는 보내지 않고 서버 설정을 따른다. 목표와 ee_pose는 같은 좌표계·기준점이어야 한다.
 * {@link Options#settleTimeout()}이 0이거나 closed_loop이 JSON Boolean true이면 final_pose로
 * 위치를 비교한다. 그 외에는 새 관측으로 정착을 확인한 뒤 비교한다. 회전 도달은 검사하지 않는다.
 * <p>
 * 위치 미도달·정착 시간 초과·서버 연산 실패는 {@code reached=false} 결과로 정상 완료한다.
 * 실행의 {@code isCompleted()}만으로 도달을 판단하지 않는다. 통신·접수 예외는 실행을 실패시키며
 * {@code run()/get()}에서는 원인이 {@code ExecutionException}에 담긴다.
 * <p>
 * 실행 전 취소는 요청을 보내지 않는다. 실행 중 {@code cancel(true)}는 확보된 비동기 세션에
 * DELETE를 보내고 종료 응답을 기다리며, 정착 확인 중에는 로컬 대기를 중단한다.
 * 세션 확보 전·동기 HTTP 호출 중에는 false다. cancel(false)는 시작 후 취소하지 않는다.
 * CANCELLED는 로컬 실행 중단이며 실제 로봇 정지를 보장하지 않는다. 비상정지는 자동 요청하지 않는다.
 * <p>
 * 원격 결과 대기 상한은 생성자의 timeout(생략 시 클라이언트 기본값, 기본 120초)이다.
 * 정착 확인 상한은 Builder.settleTimeout() 또는 Options.settleTimeout()로 별도 적용된다. 대기 시간 초과나 외부 인터럽트는
 * 원격 연산을 자동 취소하지 않는다. 정착 확인 중에도 실제 로봇은 움직이고 있을 수 있다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class MoveLinear extends AbstractRobotTwinOperation<MoveCheck> {

	private final Pose m_pose;
	private final Options m_options;
	private final @Nullable Duration m_maxDuration;

	/** 기본 옵션(정착 대기 없음)으로 만든다. 개루프 환경에서는 양수 정착 시간을 지정한다. */
	public MoveLinear(RobotTwinClient twin, Pose pose) {
		this(twin, pose, Options.DEFAULT);
	}

	/**
	 * @param twin 연결 대상.
	 * @param pose 명령 기준점의 목표 pose.
	 * @param options 이동·검증 옵션.
	 */
	public MoveLinear(RobotTwinClient twin, Pose pose, Options options) {
		this(twin, pose, options, defaultTimeout(twin));
	}

	/** 원격 결과 대기 상한을 지정한다. 정착 대기와 HTTP 요청 제한은 별개다. */
	public MoveLinear(RobotTwinClient twin, Pose pose, Options options, Duration timeout) {
		this(builder(twin, pose).moveOptions(options).timeout(timeout));
	}

	private MoveLinear(Builder builder) {
		super(builder.m_twin, "move_linear", builder.m_timeout);
		m_pose = builder.m_pose;
		m_options = builder.m_options;
		m_maxDuration = builder.m_maxDuration;
	}

	/**
	 * 속도·위치 허용 오차·시간 제한을 지정한다. 경로 계획과 프레임은 서버 설정을 따른다.
	 * maxDuration은 서버의 실행 제한이며 timeout은 클라이언트의 대기 제한이다.
	 * 둘은 독립적이므로 서버 종료 응답을 기다리려면 timeout에 통신 여유를 포함한다.
	 */
	public static Builder builder(RobotTwinClient twin, Pose pose) {
		return new Builder(twin, pose);
	}

	/**
	 * 직선 이동의 속도·위치 허용 오차·정착 대기 상한. 생성자와 빌더가 같은 기본값과 검증을 사용한다.
	 * 프레임과 경로 계획은 서버 설정에 맡기며 원격 시간 제한은 실행 객체에 별도로 지정한다.
	 *
	 * @param tolerance 위치 오차 허용값(m, 각 축 최댓값). 유한한 0 이상 값.
	 * @param velocityScaling 유한한 (0, 1] 범위의 속도 배율. null이면 서버 기본값 사용.
	 * @param settleTimeout 정착 확인 상한. 0이면 생략. null·음수·나노초 변환 범위 초과는 허용하지 않는다.
	 */
	public record Options(double tolerance, @Nullable Double velocityScaling, Duration settleTimeout) {
		/** 정착 관측을 생략하는 기본값. 개루프 환경에서는 양수 정착 상한을 지정한다. */
		public static final Options DEFAULT = new Options(0.005, 0.2, Duration.ZERO);

		public Options {
			Preconditions.checkArgument(Double.isFinite(tolerance) && tolerance >= 0,
					"tolerance must be finite and nonnegative");
			Preconditions.checkArgument(velocityScaling == null
					|| (Double.isFinite(velocityScaling) && velocityScaling > 0 && velocityScaling <= 1),
					"velocityScaling must be finite and in (0, 1]");
			Preconditions.checkNotNullArgument(settleTimeout, "settleTimeout is null");
			Preconditions.checkArgument(!settleTimeout.isNegative(), "settleTimeout must be nonnegative");
			try { settleTimeout.toNanos(); }
			catch ( ArithmeticException e ) { throw new IllegalArgumentException("settleTimeout is too large", e); }
		}

		public Options withTolerance(double value) {
			return new Options(value, velocityScaling, settleTimeout);
		}

		public Options withVelocityScaling(@Nullable Double value) {
			return new Options(tolerance, value, settleTimeout);
		}

		public Options withSettleTimeout(Duration value) {
			return new Options(tolerance, velocityScaling, value);
		}
	}

	public static final class Builder {
		private final RobotTwinClient m_twin;
		private final Pose m_pose;
		private Options m_options = Options.DEFAULT;
		private Duration m_timeout;
		private Duration m_maxDuration;

		private Builder(RobotTwinClient twin, Pose pose) {
			Preconditions.checkNotNullArgument(twin, "twin is null");
			Preconditions.checkNotNullArgument(pose, "pose is null");
			m_twin = twin;
			m_pose = pose;
			m_timeout = defaultTimeout(twin);
		}

		private Builder moveOptions(Options options) {
			Preconditions.checkNotNullArgument(options, "options is null");
			m_options = options;
			return this;
		}

		public Builder tolerance(double value) {
			m_options = m_options.withTolerance(value);
			return this;
		}

		/** null이면 velocity_scaling을 보내지 않고 서버 기본값을 사용한다. */
		public Builder velocityScaling(@Nullable Double value) {
			m_options = m_options.withVelocityScaling(value);
			return this;
		}

		/** 정착 확인 대기 상한. 0이면 관측을 생략하며, 폐루프 완료 응답에서도 추가 관측을 하지 않는다. */
		public Builder settleTimeout(Duration value) {
			m_options = m_options.withSettleTimeout(value);
			return this;
		}

		/** 비동기 결과 대기 제한. maxDuration이나 정착 시간을 자동으로 더하지 않는다. */
		public Builder timeout(Duration value) {
			Preconditions.checkNotNullArgument(value, "timeout is null");
			Preconditions.checkArgument(!value.isNegative() && !value.isZero(), "timeout must be positive");
			m_timeout = value;
			return this;
		}

		/** null이면 max_duration_sec를 보내지 않는다. 배포별 허용 범위는 서버가 검증한다. */
		public Builder maxDuration(@Nullable Duration value) {
			Preconditions.checkArgument(value == null || (!value.isNegative() && !value.isZero()),
					"maxDuration must be positive");
			m_maxDuration = value;
			return this;
		}

		/** 설정값을 복사한 실행 객체를 만든다. 빌더를 나중에 수정해도 이 객체는 바뀌지 않는다. */
		public MoveLinear build() { return new MoveLinear(this); }
	}

	@Override
	protected Map<String,?> buildInputs() {
		Map<String,Object> inputs = new LinkedHashMap<>();
		inputs.put("pose", m_pose);
		if ( m_options.velocityScaling() != null ) { inputs.put("velocity_scaling", m_options.velocityScaling()); }
		if ( m_maxDuration != null ) {
			inputs.put("max_duration_sec", BigDecimal.valueOf(m_maxDuration.getSeconds())
					.add(BigDecimal.valueOf(m_maxDuration.getNano(), 9)));
		}
		return inputs;
	}

	@Override
	protected boolean requiresObservation(OperationResult result) {
		JsonNode closedLoop = result.output("closed_loop");
		boolean closed = closedLoop != null && closedLoop.isBoolean() && closedLoop.booleanValue();
		return result.isCompleted() && !m_options.settleTimeout().isZero() && !closed;
	}

	@Override
	protected MoveCheck convertResult(OperationResult result) throws InterruptedException, ExecutionException {
		if ( !result.isCompleted() ) {
			return new MoveCheck(false, null, null, Ops.failureWhy(result).orElse(null), result, null);
		}

		JsonNode settled = null;
		JsonNode observed = result.output("final_pose");
		if ( requiresObservation(result) ) {
			WaitUntilStill observation = new WaitUntilStill(m_twin,
					WaitUntilStill.Options.DEFAULT.withTimeout(m_options.settleTimeout()));
			settled = observation.run().orElse(null);
			if ( settled == null ) {
				return new MoveCheck(false, null, MoveCheck.SETTLE_TIMEOUT,
						"위치 정착 확인 실패: " + observation.getDiagnostics().summary()
						+ ". 관측 진단과 현재 상태를 확인하고 다음 동작을 멈춘다", result, null);
			}
			observed = settled;
		}
		if ( observed == null || observed.isNull() ) {
			return new MoveCheck(false, null, null, "no final_pose in outputs", result, null);
		}

		double error = m_pose.position().maxAxisDistance(Pose.fromJson(observed).position());
		return new MoveCheck(error <= m_options.tolerance(), error, null, null, result, settled);
	}
}
