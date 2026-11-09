package mdt.robot.client.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.AlignHeldObject;
import mdt.robot.client.operation.MeasureObjectOffset;
import mdt.robot.client.operation.Pick;

/**
 * <b>연산 조합 — peg 집기 · 입구 위 정렬.</b> 로봇을 움직인다. 펑션베이 전용.
 * <p>
 * 서 있는 peg 을 {@link Pick}으로 집어 들고(들기 전후 물린 위치로 유지 확인) → 목적지 고정물의 입구 바로 위로
 * {@link AlignHeldObject}로 맞춘다. <b>삽입·개방은 하지 않는다</b> — 정렬이 끝나면 peg 을 쥔 채 이동 높이에서 멈춘다.
 * 삽입까지 하는 흐름은 워크플로 {@code wf_peg_in_hole}을 참고한다.
 * <ul>
 *   <li>좌표는 모두 펑션베이 {@code grasp_center} 기준이다. {@code ee_pose}와 {@code move_linear} 기준점이 같아
 *       PandaHand 변환을 하지 않는다. 측정 기준점도 {@code AS_REPORTED}다.</li>
 *   <li>파지 높이는 손끝(grasp_center 아래 44 mm)이 바닥에서 10 mm 뜨도록 잡는다. 바닥은 peg 아래가 고정물이면
 *       그 상면, 아니면 탁자면(0)이다.</li>
 *   <li>목적지는 인자로 주거나, 생략하면 peg 이 놓이지 않은 유일한 고정물을 고른다({@code fixtures} 변수).</li>
 *   <li>정렬 허용 반경은 (구멍 반지름 − peg 반지름)의 40% 다.</li>
 * </ul>
 *
 * <pre>
 *   gradle runExample -Pexample=PegAlignExample                       # peg → 다른 고정물
 *   gradle runExample -Pexample=PegAlignExample --args='peg peg_tray'  # 물체 이름, 목적지
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class PegAlignExample {
	// wf_peg_in_hole·robot_twin_client.peg_in_hole의 상수와 같다(단위 m).
	private static final double FINGERTIP_OFFSET_M = 0.044;
	private static final double FINGERTIP_CLEARANCE_M = 0.010;
	private static final double APPROACH_Z = 0.120;
	private static final double TRAVEL_Z = 0.150;
	private static final double ALIGN_MARGIN = 0.4;
	private static final Quaternion GRASP_ORIENTATION = Geometry.downWithYaw(-Math.PI / 2.0);

	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		twin.requireBackend("peg alignment (grasp_center frame, fixtures variable)", "panda_functionbay");
		List<String> rest = ExampleSupport.positional(args);
		String objectName = rest.size() > 0 ? rest.get(0) : "peg";
		String destination = rest.size() > 1 ? rest.get(1) : null;

		// 1. 장면과 고정물을 읽는다. 고정물은 scene_objects에 없고 fixtures 변수에만 있다.
		JsonNode peg = twin.read("scene_objects").path("objects").get(objectName);
		if ( peg == null ) {
			System.out.println("장면에 물체가 없다: " + objectName);
			return;
		}
		JsonNode fixtures = twin.read("fixtures").path("fixtures");
		Position center = Geometry.centerOf(peg);
		String source = fixtureUnder(fixtures, center);
		if ( destination == null ) {
			List<String> others = new ArrayList<>();
			fixtures.fieldNames().forEachRemaining(name -> { if ( !name.equals(source) ) others.add(name); });
			if ( others.size() != 1 ) {
				System.out.println("목적지를 고를 수 없다. 인자로 지정한다: " + others);
				return;
			}
			destination = others.get(0);
		}
		JsonNode fixture = fixtures.path(destination);
		if ( !fixture.has("entry_point") || !fixture.has("inner_diameter") ) {
			System.out.println("목적지에 입구 위치나 안지름 정보가 없다: " + destination);
			return;
		}
		Position entry = positionOf(fixture.path("entry_point"));
		double clearance = fixture.path("inner_diameter").asDouble() / 2.0 - radiusOf(peg);

		// 2. 파지 자세. 실효 바닥은 peg 아래 고정물의 상면이거나 탁자면이다.
		double floor = source != null ? fixtures.path(source).path("entry_point").path("z").asDouble(0.0) : 0.0;
		double bottom = Math.max(center.z() - Geometry.verticalHalfExtentOf(peg), floor);
		Pose grasp = new Pose(new Position(center.x(), center.y(), bottom + FINGERTIP_OFFSET_M + FINGERTIP_CLEARANCE_M),
								GRASP_ORIENTATION);
		Pose approach = grasp.withPosition(new Position(center.x(), center.y(), APPROACH_Z));
		Pose lift = grasp.withPosition(new Position(center.x(), center.y(), TRAVEL_Z));
		System.out.printf("%s 을 %s 에서 집어 %s 입구 %s 위로 맞춘다 (반경 여유 %.2f mm)%n", objectName,
							source != null ? source : "탁자", destination, ExampleSupport.fmt(entry), clearance * 1000);

		// 3. Pick — 들기 전후 물린 위치를 비교해 peg 이 따라 올라왔는지 확인한다.
		Pick.Check picked = Pick.builder(twin).approachPose(approach).graspPose(grasp).liftPose(lift)
				.holdCheck(Pick.HoldCheck.of(objectName, MeasureObjectOffset.TcpReference.AS_REPORTED)).build().run();
		if ( !picked.goalReached() ) {
			System.out.printf("  집기 확인 실패: %s (유지 판정=%s) — %s%n", picked.failedStage(), picked.holdVerdict(),
								picked.failureReason());
			return;
		}
		Pick.Hold hold = picked.hold().orElseThrow();
		System.out.printf("  집기 완료: 들기 전후 물린 위치 차이 %.1f mm, 물린 위치 %s%n", hold.driftM() * 1000,
							ExampleSupport.fmt(hold.afterLift().offset()));

		// 4. AlignHeldObject — peg 아래쪽 끝면 중심을 입구 바로 위로 맞춘다. 이동 높이와 자세는 절대값으로 고정한다.
		AlignHeldObject.Check aligned = AlignHeldObject.builder(twin, objectName).entry(entry).commandZ(TRAVEL_Z)
				.commandOrientation(GRASP_ORIENTATION).acceptRadiusM(ALIGN_MARGIN * clearance).build().run();
		System.out.printf("  정렬: %s, 회차 %d, 회차별 오차(mm) %s%n", aligned.stopReason(), aligned.passes(),
							aligned.passErrorsM().stream().map(e -> String.format("%.2f", e * 1000)).toList());
		if ( !aligned.goalReached() ) {
			System.out.println("  정렬 실패: " + aligned.failureReason());
		}
		System.out.println("  peg 을 쥔 채 이동 높이에서 멈춘다. 삽입·개방은 이 예제의 범위가 아니다.");
	}

	/** (x, y)를 발자국(outer_extent_xy)으로 덮는 고정물 이름. 없으면 {@code null}. */
	private static String fixtureUnder(JsonNode fixtures, Position p) {
		for ( Map.Entry<String,JsonNode> e : (Iterable<Map.Entry<String,JsonNode>>)fixtures::fields ) {
			JsonNode extent = e.getValue().path("outer_extent_xy");
			JsonNode c = e.getValue().path("position");
			if ( extent.size() == 2 && Math.abs(p.x() - c.path("x").asDouble()) <= extent.get(0).asDouble() / 2.0
					&& Math.abs(p.y() - c.path("y").asDouble()) <= extent.get(1).asDouble() / 2.0 ) {
				return e.getKey();
			}
		}
		return null;
	}

	private static Position positionOf(JsonNode node) {
		return new Position(node.path("x").asDouble(), node.path("y").asDouble(), node.path("z").asDouble());
	}

	/** 원기둥 dimensions = [길이, 반지름]. */
	private static double radiusOf(JsonNode cylinder) {
		return cylinder.path("dimensions").path(1).asDouble();
	}
}
