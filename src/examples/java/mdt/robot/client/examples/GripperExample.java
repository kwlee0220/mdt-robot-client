package mdt.robot.client.examples;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.GripperCheck;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MoveGripperToNamedTarget;

/**
 * <b>{@code move_gripper_to_target} — 그리퍼.</b> 그리퍼를 움직인다.
 * <p>
 * 목표는 심볼 {@code open} · {@code close} · {@code grasp} 다(숫자는 로봇 쪽 그리퍼 노드가 갖는다).
 * <b>성공 판정은 {@code at_goal} 하나다</b> — {@code open}/{@code close} 는 목표 자세 도달, {@code grasp} 는
 * 물체에 막혀 멈춤이다. 트윈은 {@code at_goal} 이 설 때까지 기다리므로 끝내 서지 않으면 {@code FAILED}
 * ({@code TIMEOUT}) 이다. 연산 객체는 {@code atGoal=false}와 원격 실패 결과를 반환한다.
 * <p>
 * ⚠️ mock 은 손가락에 힘 상태가 없어 {@code grasp} 가 언제나 시간 초과로 끝난다.
 *
 * <pre>
 *   gradle runExample -Pexample=GripperExample                    # open → close → open
 *   gradle runExample -Pexample=GripperExample --args='grasp'     # 물체가 손 사이에 있을 때
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class GripperExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		List<String> targets = ExampleSupport.positional(args);
		if ( targets.isEmpty() ) {
			targets = List.of("open", "close", "open");
		}

		for ( String target : targets ) {
			GripperCheck check = new MoveGripperToNamedTarget(twin, target).run();
			Map<String,JsonNode> out = check.result().outputs();
			System.out.printf("%-6s status=%s at_goal=%s stalled=%s width=%s%n", target,
					check.result().status(), out.get("at_goal"), out.get("stalled"), out.get("width"));
			if ( !check.atGoal() ) {
				System.out.println("       다음 동작을 진행하지 않는다: " + check.why());
				return;
			}
		}
		// 주기 발행되는 연속 상태로도 확인할 수 있다 — 갱신됐다고 명령이 끝났다는 뜻은 아니다.
		System.out.println("gripper_state: " + twin.read("gripper_state"));
	}
}
