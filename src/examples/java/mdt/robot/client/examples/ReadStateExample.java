package mdt.robot.client.examples;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;

/**
 * <b>상태 읽기</b> — 로봇을 움직이지 않는다.
 * <p>
 * 트윈 메타 · 준비 상태 · 손끝 위치 · 관절값 · 그리퍼 · 장면 물체 · named target 을 읽는다. 값을 쓰기 전에
 * 품질({@code quality})을 확인하는 두 가지 방법을 보인다 — {@code read()} 는 {@code OK} 가 아니면 예외,
 * {@code readRaw()} 는 품질을 그대로 돌려준다.
 *
 * <pre>
 *   gradle runExample -Pexample=ReadStateExample
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class ReadStateExample {
	public static void main(String[] args) {
		RobotTwinClient twin = ExampleSupport.connect(args);

		// 준비 상태와 변수 품질은 별개다 — 관측값이 OK 여도 MoveGroup 이 준비됐는지는 따로 본다.
		JsonNode health = twin.health();
		System.out.printf("move_group=%s, estop=%s%n", health.path("move_group").asText(),
							health.path("estop").asText());

		// readRaw: 품질 · 수신 시각을 함께 본다.
		JsonNode raw = twin.readRaw("ee_pose");
		System.out.printf("ee_pose quality=%s, received_at=%s%n", raw.path("quality").asText(),
							raw.path("received_at").asText("-"));
		if ( "OK".equals(raw.path("quality").asText()) ) {
			Pose ee = Pose.fromJson(raw.get("value"));		// PoseStamped → pose
			System.out.println("  위치 " + ExampleSupport.fmt(ee.position()) + ", 자세 " + ee.orientation());
		}

		// read: OK 가 아니면 VariableNotOkException — 낡은 값을 모르고 쓰지 않게 한다.
		JsonNode joints = twin.read("joint_states");
		System.out.println("관절값(rad):");
		joints.path("position").fields().forEachRemaining(e ->
				System.out.printf("  %-20s %+.4f%n", e.getKey(), e.getValue().asDouble()));

		// 그리퍼 성공 판정은 at_goal 하나다. width 는 개구 폭(m)이며 못 구하면 NaN 이다.
		JsonNode gripper = twin.read("gripper_state");
		System.out.printf("그리퍼 goal=%s, at_goal=%s, width=%s%n", gripper.path("goal").asText("-"),
							gripper.path("at_goal").asText(), gripper.path("width").asText());

		// 장면 물체 — 이름 → {type, dimensions, pose}. 파지 좌표는 여기서 얻는다.
		JsonNode objects = twin.read("scene_objects").path("objects");
		System.out.println("장면 물체:");
		objects.fields().forEachRemaining(e -> System.out.printf("  %-12s %s %s%n", e.getKey(),
				e.getValue().path("type").asText(), e.getValue().path("dimensions")));

		// move_to_named_target 의 target 에 넣을 수 있는 이름 — 지어내지 말고 여기서 고른다.
		System.out.println("named targets: " + twin.read("named_targets"));

		// 여러 변수를 한 번에 — 각 값이 같은 시각에 생성되었다는 뜻은 아니다.
		Map<String,JsonNode> many = twin.readMany("ee_pose", "joint_states");
		many.forEach((name, body) -> System.out.printf("readMany %s → quality=%s%n", name,
														body.path("quality").asText()));
	}
}
