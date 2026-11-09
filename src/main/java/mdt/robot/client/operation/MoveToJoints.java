package mdt.robot.client.operation;

import static mdt.robot.client.operation.OperationInputs.defaultTimeout;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 관절 이름과 목표값(rad)을 지정해 이동하고 서버의 closed_loop 결과로 도달을 판정한다.
 * <p>
	 * 생성자는 통신하지 않고 입력 Map의 순회 순서를 유지해 복사한다. run() 또는 start()/get()으로 한 번 실행한다.
 * 일부 관절만 지정할 수 있다. 나머지 관절의 현재값 보완과 관절 이름·한계 검증은 서버가 맡는다.
 * 제공 Panda 트윈에는 panda_arm 관절만 전달하며 손가락 관절을 섞지 않는다.
 * <p>
 * 서버 COMPLETED와 JSON Boolean closed_loop=true인 경우만 goalReached=true다.
 * 개루프·정보 누락·형식 오류의 goalReached=false는 도달 확인 불가이며 미도달 확정이 아니다.
 * final_joints는 서버 캐시 스냅샷이므로 별도의 오차 비교·정착 검증에는 사용하지 않는다.
 * 원격 FAILED/CANCELLED도 goalReached=false 결과로 정상 완료한다. 접수 거부·통신 오류는
 * run()/get()의 ExecutionException에 담긴다. 직접 요청한 취소는 공통 실행부 계약을 따른다.
 */
public final class MoveToJoints extends AbstractRobotTwinOperation<ClosedLoopCheck> {
	private final Map<String,Double> m_joints;
	private final @Nullable Double m_velocityScaling;
	private final @Nullable Duration m_maxDuration;

	public MoveToJoints(RobotTwinClient twin, Map<String,Double> joints) {
		this(twin, joints, MotionLimits.DEFAULT_VELOCITY_SCALING);
	}

	public MoveToJoints(RobotTwinClient twin, Map<String,Double> joints, double velocityScaling) {
		this(twin, joints, MotionLimits.DEFAULT.withVelocityScaling(velocityScaling));
	}

	/**
	 * timeout은 원격 비동기 결과 대기 상한이다. 생략 시 클라이언트의 operationTimeout을 사용한다.
	 * 서버 max_duration_sec로 전달하지 않으며, 시간 초과 시 원격 연산을 자동 취소하지 않는다.
	 * 동기 카탈로그 연산에는 HTTP 시간 제한이 적용된다.
	 *
	 * @throws IllegalArgumentException joints가 null/비어 있거나 이름이 null/공백/앞뒤 공백을 포함한 경우,
	 *         목표값이 null/비유한 값이거나 속도가 유한한 (0, 1] 범위 밖인 경우,
	 *         또는 timeout이 null/0 이하인 경우. 배포별 속도 하한·관절 범위는 서버가 검증한다.
	 */
	public MoveToJoints(RobotTwinClient twin, Map<String,Double> joints, double velocityScaling, Duration timeout) {
		this(twin, joints, new MotionLimits(velocityScaling, null, MotionLimits.checkPositive(timeout, "timeout")));
	}

	/**
	 * 서버·클라이언트 시간 제한을 따로 지정한다. 생성자는 통신하지 않으며 서버의 시간 범위는
	 * 고정하지 않는다. maxDuration은 소수 초를 보존해 max_duration_sec로 전송한다.
	 * 서버는 상한 초과 시 정지를 요청하므로 이 값은 물리적인 정지 완료 시각을 보장하지 않는다.
	 */
	public MoveToJoints(RobotTwinClient twin, Map<String,Double> joints, MotionLimits options) {
		super(twin, "move_to_joints", resolveTimeout(twin, options));
		Preconditions.checkNotNullArgument(joints, "joints is null");
		Map<String,Double> snapshot = new LinkedHashMap<>(joints);
		Preconditions.checkArgument(!snapshot.isEmpty(), "joints must not be empty");
		for ( Map.Entry<String,Double> joint : snapshot.entrySet() ) {
			String name = joint.getKey();
			Preconditions.checkNotNullArgument(name, "joint name is null");
			Preconditions.checkArgument(!name.isBlank() && name.equals(name.strip()),
					"joint name must not be blank or have surrounding whitespace");
			Preconditions.checkNotNullArgument(joint.getValue(), "joint value is null: " + name);
			Preconditions.checkArgument(Double.isFinite(joint.getValue()), "joint value must be finite: " + name);
		}
		m_joints = Collections.unmodifiableMap(snapshot);
		m_velocityScaling = options.velocityScaling();
		m_maxDuration = options.maxDuration();
	}

	@Override
	protected Map<String,?> buildInputs() {
		Map<String,Object> inputs = new LinkedHashMap<>();
		inputs.put("joints", m_joints);
		if ( m_velocityScaling != null ) { inputs.put("velocity_scaling", m_velocityScaling); }
		if ( m_maxDuration != null ) {
			inputs.put("max_duration_sec", MotionLimits.toSeconds(m_maxDuration));
		}
		return inputs;
	}

	@Override
	protected ClosedLoopCheck judgeResult(OperationResult result) {
		ClosedLoopCheck check = OperationChecks.judgeClosedLoop(result);
		String why = check.failureReason();
		if ( !result.isCompleted() && "PLANNING_FAILED".equals(result.errorCode()) ) {
			why += " — 지정한 관절이 이동 그룹에 속하는지 확인하세요. Panda에서는 손가락 관절이 섞이지 않았는지도 확인하세요.";
		}
		return new ClosedLoopCheck(check.goalReached(), why, check.result());
	}

	private static Duration resolveTimeout(RobotTwinClient twin, MotionLimits options) {
		Preconditions.checkNotNullArgument(options, "options is null");
		return options.resolveTimeout(defaultTimeout(twin));
	}

}
