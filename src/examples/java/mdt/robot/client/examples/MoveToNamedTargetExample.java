package mdt.robot.client.examples;

import java.util.List;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToNamedTarget;

/**
 * <b>{@code move_to_named_target} — 이름 자세로 이동.</b> 로봇을 움직인다.
 * <p>
 * 이름은 SRDF 의 {@code panda_arm} 그룹에 정의된 것만 된다 — {@code named_targets} 변수에서 고른다. 없는
 * 이름은 트윈이 접수 단계에서 {@code 400 INVALID_INPUT} 으로 거부한다
 * ({@code ExecutionException}의 원인이 {@code TwinException}).
 * <p>
 * 관절 공간 이동이라 부분 실행은 없지만, <b>개루프(JGPC · 펑션베이)에서는 정상 종료해도 도달을 보장하지
 * 않는다</b> — {@code goalReached=false} 와 안내가 정상일 수 있다.
 *
 * <pre>
 *   gradle runExample -Pexample=MoveToNamedTargetExample               # ready
 *   gradle runExample -Pexample=MoveToNamedTargetExample --args='home'
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class MoveToNamedTargetExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		List<String> rest = ExampleSupport.positional(args);
		String target = rest.isEmpty() ? "ready" : rest.get(0);

		System.out.println("쓸 수 있는 이름: " + twin.read("named_targets"));

		ClosedLoopCheck check = new MoveToNamedTarget(twin, target, 0.3).run();
		if ( check.goalReached() ) {
			System.out.printf("'%s' 도달 (폐루프 실행)%n", target);
		}
		else if ( check.result().isCompleted() ) {
			// 개루프 스택: 끝났지만 도달은 모른다 — 필요하면 ee_pose · joint_states 로 직접 확인한다.
			System.out.printf("'%s' 이동은 끝났지만 도달을 보장하지 않는다: %s%n", target, check.failureReason());
		}
		else {
			System.out.printf("'%s' 이동 실패: %s %s — %s%n", target, check.result().status(),
								check.result().errorCode(), check.failureReason());
		}
	}
}
