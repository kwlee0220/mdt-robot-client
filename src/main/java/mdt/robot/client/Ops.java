package mdt.robot.client;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.operation.MoveToNamedTarget;

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

	private Ops() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * 실패로 끝난 연산 결과에서 다음에 할 일을 찾는다.
	 *
	 * @param result	연산 결과.
	 * @return	안내 문장. 모르는 코드거나 실패가 아니면 {@link Optional#empty()}.
	 */
	public static Optional<String> failureWhy(OperationResult result) {
		return Optional.ofNullable(result.errorCode()).map(FAILURE_HINTS::get);
	}

	/**
	 * {@link MoveToNamedTarget#DEFAULT_VELOCITY_SCALING}으로 실행한다.
	 * 런타임 예외는 그대로 전달하고, 인터럽트는 플래그를 복원한 RuntimeInterruptedException으로 전달한다.
	 */
	public static MoveToNamedTarget.Check moveToNamedTargetVerified(RobotTwinClient twin, String target) {
		return runOperation(new MoveToNamedTarget(twin, target));
	}

	// ------------------------------------------------------------------
	// 내부
	// ------------------------------------------------------------------

	/** 연산 객체의 checked exception을 기존 Ops 호출 계약으로 바꾼다. */
	private static <T> T runOperation(AbstractThreadedExecution<T> operation) {
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
			throw new IllegalStateException("robot twin operation failed", cause);
		}
	}

}
