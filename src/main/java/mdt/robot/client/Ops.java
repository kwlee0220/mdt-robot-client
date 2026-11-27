package mdt.robot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToNamedTarget;

import utils.RuntimeExecutionException;
import utils.RuntimeInterruptedException;
import utils.async.AbstractThreadedExecution;

/**
 * 연산 호출 위에 얹는 검증 헬퍼.
 * <p>
 * 트윈은 {@code COMPLETED} 를 "연산이 끝났다"는 뜻으로만 쓴다. <b>도달했는지 · 물체를 쥐었는지는
 * 클라이언트가 판단해야 한다.</b> 공통 헬퍼와 기존 호출 API를 제공하며 이동 연산은 전용 실행 클래스에 위임한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class Ops {
	/**
	 * 실패 코드별 <b>다음에 할 일</b>. 코드만 돌려주면 호출자 — 특히 에이전트 — 는 같은 호출을 그대로
	 * 다시 부른다. 계획 실패는 로봇이 제자리라 목표를 바꿔야 하고, 실행 실패는 로봇이 경로 중간에 있을 수
	 * 있어 위치부터 확인해야 한다.
	 */
	public static final Map<String,String> FAILURE_HINTS = Map.of(
		"PLANNING_FAILED", "계획 실패 — 로봇은 움직이지 않았다. 같은 목표로 다시 불러도 결과가 같으니 "
							+ "목표나 시작 자세를 바꾼다",
		"EXECUTION_ABORTED", "실행 실패 — 로봇이 경로 중간에 있을 수 있다. ee_pose 로 현재 위치를 확인한 뒤 "
							+ "판단한다",
		"TIMEOUT", "시간 초과 — 로봇이 아직 움직이는 중일 수 있다. ee_pose 로 현재 위치를 확인한다",
		"PRECONDITION_FAILED", "현재 상태에서 받을 수 없는 요청이다 — 아무것도 실행되지 않았다. 재시도가 "
							+ "아니라 상태(session_state 등)를 먼저 맞춘다",
		"ESTOP_ENGAGED", "비상정지로 중단됐다 — 사람이 해제하기 전에는 아무것도 움직이지 않는다");

	private static final String START_INVALID = "시작 자세가 충돌(자기충돌 포함)이나 무효로 판정됐다 — 목표를 바꿔도 "
			+ "결과가 같다. joint_states 로 현재 자세를 확인하고 먼저 그 자세에서 벗어난다. 벗어날 수 없으면 사람에게 알린다";
	private static final String GOAL_INVALID = "목표가 관절 한계나 제약을 벗어난다 — 목표 값을 확인한다";
	private static final String PLAN_STALE = "계획한 경로가 실행 전에 무효가 됐다(충돌 등) — 현재 상태를 다시 읽고 다시 계획한다";
	private static final String STATE_STALE = "로봇 상태 수신이 끊겼다 — 로봇 스택(joint_states 발행)부터 확인한다";

	/**
	 * MoveIt 원본 오류({@link OperationResult#moveitErrorName()})별 <b>원인과 다음에 할 일</b> (트윈 설계서 6.8).
	 * {@code PLANNING_FAILED} 하나로는 충돌 · IK 실패 · 시작 자세 무효가 같아 보이는데 대응은 다르다 — 시작 자세가
	 * 문제면 목표를 바꿔도 소용없고, 목표가 문제면 목표를 바꿔야 한다. Python 클라이언트의 {@code ops.MOVEIT_HINTS}와
	 * 같은 문장이다.
	 */
	public static final Map<String,String> MOVEIT_HINTS = Map.ofEntries(
		Map.entry("START_STATE_IN_COLLISION", START_INVALID),
		Map.entry("START_STATE_INVALID", START_INVALID),
		Map.entry("START_STATE_VIOLATES_PATH_CONSTRAINTS", START_INVALID),
		Map.entry("GOAL_IN_COLLISION", "목표 자세가 충돌로 판정됐다 — 목표를 물체 · 탁자에서 떨어뜨리거나 접근 자세를 바꾼다"),
		Map.entry("GOAL_STATE_INVALID", GOAL_INVALID),
		Map.entry("GOAL_CONSTRAINTS_VIOLATED", GOAL_INVALID),
		Map.entry("GOAL_VIOLATES_PATH_CONSTRAINTS", GOAL_INVALID),
		Map.entry("INVALID_GOAL_CONSTRAINTS", GOAL_INVALID),
		Map.entry("NO_IK_SOLUTION", "목표 pose 에 IK 해가 없다 — 작업 범위 밖이거나 그 자세로는 닿을 수 없다. "
									+ "위치를 로봇 쪽으로 당기거나 자세를 바꾼다"),
		Map.entry("PLANNING_FAILED", "플래너가 경로를 찾지 못했다 — 중간 경유점을 두거나 목표를 바꾼다"),
		Map.entry("TIMED_OUT", "계획 시간 안에 경로를 찾지 못했다 — 중간 경유점을 두거나 목표를 바꾼다"),
		Map.entry("INVALID_MOTION_PLAN", PLAN_STALE),
		Map.entry("MOTION_PLAN_INVALIDATED_BY_ENVIRONMENT_CHANGE", PLAN_STALE),
		Map.entry("CONTROL_FAILED", "컨트롤러가 궤적을 따르지 못했다 — 팔이 경로 중간에 있을 수 있다. ee_pose 로 위치부터 확인한다"),
		Map.entry("ROBOT_STATE_STALE", STATE_STALE),
		Map.entry("SENSOR_INFO_STALE", STATE_STALE),
		Map.entry("COMMUNICATION_FAILURE", STATE_STALE));

	private Ops() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * 실패로 끝난 연산 결과에서 다음에 할 일을 찾는다. 실패 이유가 아니라 안내다.
	 * <p>
	 * 오류 코드의 안내 뒤에 MoveIt 원인({@code error.details.moveit_error_name})별 안내를
	 * {@code "MoveIt 원인 <이름>: <안내>"} 꼴로 붙인다. 원인이 코드보다 구체적이라 그쪽이 다음 행동을 더 정확히 가른다.
	 * 모르는 코드라도 MoveIt 원인이 있으면 그것만으로 안내한다.
	 *
	 * @param result	연산 결과.
	 * @return	안내 문장. 아는 것이 없으면 {@link Optional#empty()}.
	 */
	public static Optional<String> failureHint(OperationResult result) {
		List<String> parts = new ArrayList<>();
		Optional.ofNullable(result.errorCode()).map(FAILURE_HINTS::get).ifPresent(parts::add);
		result.moveitErrorName().ifPresent(name -> {
			String cause = MOVEIT_HINTS.get(name);
			parts.add("MoveIt 원인 " + name + ((cause != null) ? ": " + cause : ""));
		});
		return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(". ", parts));
	}

	/**
	 * {@link mdt.robot.client.operation.MotionLimits#DEFAULT_VELOCITY_SCALING}으로 실행한다.
	 * 런타임 예외와 Error는 그대로, 검사 예외는 RuntimeExecutionException으로 전달하고, 인터럽트는 플래그를 복원한
	 * RuntimeInterruptedException으로 전달한다.
	 */
	public static ClosedLoopCheck moveToNamedTargetVerified(RobotTwinClient twin, String target) {
		return runOperation(new MoveToNamedTarget(twin, target));
	}

	// ------------------------------------------------------------------
	// 내부
	// ------------------------------------------------------------------

	/**
	 * 연산 객체의 checked exception을 런타임 예외로 바꾼다. 원인이 RuntimeException이면 그대로, Error이면 다시 던지고
	 * (utils 규칙: Exception은 잡고 Error는 전파), 검사 예외는 RuntimeExecutionException으로 감싼다.
	 */
	static <T> T runOperation(AbstractThreadedExecution<T> operation) {
		try {
			return operation.run();
		}
		catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new RuntimeInterruptedException(e);
		}
		catch ( ExecutionException e ) {
			Throwable cause = e.getCause();
			if ( cause instanceof RuntimeException runtime ) {
				throw runtime;
			}
			if ( cause instanceof Error error ) {
				throw error;
			}
			throw new RuntimeExecutionException(cause);
		}
	}

}
