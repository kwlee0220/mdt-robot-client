package mdt.robot.client.operation;

import static mdt.robot.client.operation.OperationInputs.defaultTimeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;
import mdt.robot.client.Ops;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * SRDF에 정의된 이름 자세로 이동하고 서버의 closed_loop 결과로 도달을 판정한다.
 * <p>
 * run()은 동기 실행, start()/get()은 비동기 실행이다. 생성자는 통신하지 않으며 한 번만 실행한다.
 * 원격 결과 대기 상한과 세션 취소는 {@link AbstractRobotTwinOperation}의 계약을 따른다.
 * <p>
 * 서버 연산이 COMPLETED이고 closed_loop가 JSON Boolean true인 경우에만 goalReached=true다.
 * 개루프·정보 누락·형식 오류의 goalReached=false는 도달 확인 불가이며 미도달이 확정됐다는 뜻이 아니다.
 * 별도의 관측이나 정착 검증은 수행하지 않는다.
 * 서버의 FAILED/CANCELLED는 goalReached=false 결과로 정상 완료한다.
 * 없는 이름 등 접수 거부는 실행 실패이며 run()/get()의 ExecutionException에 원인이 담긴다.
 * 기존 {@link Ops#moveToNamedTargetVerified}는 이 클래스를 호출하는 호환 API다.
 */
public final class MoveToNamedTarget extends AbstractRobotTwinOperation<ClosedLoopCheck> {
	private final String m_target;
	private final @Nullable Double m_velocityScaling;
	private final @Nullable Duration m_maxDuration;

	/** 기본 속도 배율 0.3과 클라이언트의 원격 대기 상한을 사용한다. */
	public MoveToNamedTarget(RobotTwinClient twin, String target) {
		this(twin, target, MotionLimits.DEFAULT_VELOCITY_SCALING);
	}

	public MoveToNamedTarget(RobotTwinClient twin, String target, double velocityScaling) {
		this(twin, target, velocityScaling, defaultTimeout(twin));
	}

	/**
	 * timeout은 원격 결과 대기 상한이며 원격 동작을 강제로 중단하는 시각이 아니다.
	 * 배포별 속도 하한과 목표 이름의 존재 여부는 실행 시 서버가 검증한다.
	 *
	 * @throws IllegalArgumentException 목표 이름이 null/공백 문자열이거나 앞뒤 공백을 포함한 경우,
	 *         속도가 유한한 0 초과 1 이하 값이 아니거나, timeout이 null/0 이하인 경우.
	 */
	public MoveToNamedTarget(RobotTwinClient twin, String target, double velocityScaling, Duration timeout) {
		this(twin, target, new MotionLimits(velocityScaling, null, MotionLimits.checkPositive(timeout, "timeout")));
	}

	/**
	 * 서버 실행 상한과 클라이언트 대기 상한을 따로 지정한다.
	 * 속도가 null이면 서버 기본값을 사용한다. 시간 초과 시 원격 연산을 자동 취소하지 않는다.
	 */
	public MoveToNamedTarget(RobotTwinClient twin, String target, MotionLimits options) {
		super(twin, "move_to_named_target", resolveTimeout(twin, options));
		Preconditions.checkNotNullArgument(target, "target is null");
		Preconditions.checkArgument(!target.isBlank(), "target must not be blank");
		Preconditions.checkArgument(target.equals(target.strip()), "target must not have surrounding whitespace");
		m_target = target;
		m_velocityScaling = options.velocityScaling();
		m_maxDuration = options.maxDuration();
	}

	@Override
	protected Map<String,?> buildInputs() {
		Map<String,Object> inputs = new LinkedHashMap<>();
		inputs.put("target", m_target);
		if ( m_velocityScaling != null ) { inputs.put("velocity_scaling", m_velocityScaling); }
		if ( m_maxDuration != null ) {
			inputs.put("max_duration_sec", MotionLimits.toSeconds(m_maxDuration));
		}
		return inputs;
	}

	@Override
	protected ClosedLoopCheck judgeResult(OperationResult result) {
		ClosedLoopCheck check = OperationChecks.judgeClosedLoop(result);
		return check;
	}

	private static Duration resolveTimeout(RobotTwinClient twin, MotionLimits options) {
		Preconditions.checkNotNullArgument(options, "options is null");
		return options.resolveTimeout(defaultTimeout(twin));
	}

}
