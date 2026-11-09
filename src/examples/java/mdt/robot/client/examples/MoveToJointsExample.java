package mdt.robot.client.examples;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToJoints;

/**
 * <b>{@code move_to_joints} — 관절값으로 이동.</b> 로봇을 움직인다.
 * <p>
 * 손목(joint7)만 {@code delta}(기본 0.2 rad) 돌렸다가 되돌린다. 입력은 {@code {관절이름: 라디안}} 이며
 * <b>{@code panda_arm} 관절만</b> 넣는다 — {@code joint_states} 를 그대로 돌려보내면 손가락 관절이 섞여
 * 계획이 실패한다. 일부 관절만 줘도 트윈이 나머지를 현재값으로 채워 고정한다.
 *
 * <pre>
 *   gradle runExample -Pexample=MoveToJointsExample
 *   gradle runExample -Pexample=MoveToJointsExample --args='0.4'
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class MoveToJointsExample {
	private static final String JOINT = "panda_joint7";

	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		List<String> rest = ExampleSupport.positional(args);
		double delta = rest.isEmpty() ? 0.2 : Double.parseDouble(rest.get(0));
		if ( !Double.isFinite(delta) ) { throw new IllegalArgumentException("delta must be finite"); }

		JsonNode position = twin.read("joint_states").path("position");
		JsonNode initial = position.path(JOINT);
		if ( !initial.isNumber() || !Double.isFinite(initial.doubleValue()) ) {
			throw new IllegalStateException("현재 관절값을 확인할 수 없다: " + JOINT + "=" + initial);
		}
		double start = initial.doubleValue();
		System.out.printf("%s 현재 %+.4f rad%n", JOINT, start);

		for ( double target : new double[] { start + delta, start } ) {
			ClosedLoopCheck check = new MoveToJoints(twin, Map.of(JOINT, target)).run();
			OperationResult result = check.result();
			// 종료 시점 관절값이 출력에 실린다(있으면). 개루프 스택에서는 정착 전 값일 수 있다.
			JsonNode finalJoints = result.output("final_joints");
			double reached = (finalJoints != null) ? finalJoints.path("position").path(JOINT).asDouble(Double.NaN)
													: Double.NaN;
			System.out.printf("  목표 %+.4f → 종료 %+.4f rad (closed_loop=%s)%n", target, reached,
								result.output("closed_loop"));
			if ( !check.goalReached() ) {
				System.out.printf("도달 확인 불가: %s — %s. 다음 이동을 진행하지 않는다.%n", result.status(), check.failureReason());
				return;
			}
		}
	}
}
