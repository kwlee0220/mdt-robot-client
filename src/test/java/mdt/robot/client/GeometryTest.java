package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

class GeometryTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final double EPS = 1e-9;

	private static JsonNode json(String text) throws Exception {
		return MAPPER.readTree(text.replace('\'', '"'));
	}

	private static void assertQuat(Quaternion want, Quaternion got) {
		// q 와 -q 는 같은 회전이다.
		double dot = want.x()*got.x() + want.y()*got.y() + want.z()*got.z() + want.w()*got.w();
		assertEquals(1.0, Math.abs(dot), 1e-9, () -> want + " != " + got);
	}

	@Test
	void centerOfAcceptsBothShapes() throws Exception {
		Position fromReset = Geometry.centerOf(json("{'name':'cube_0','position':{'x':0.4,'y':-0.1,'z':0.025}}"));
		Position fromScene = Geometry.centerOf(json(
				"{'type':'box','pose':{'position':{'x':0.4,'y':-0.1,'z':0.025},'orientation':{'x':0,'y':0,'z':0,'w':1}}}"));
		assertEquals(new Position(0.4, -0.1, 0.025), fromReset);
		assertEquals(fromReset, fromScene);
	}

	@Test
	void centerOfNamesTheKeysWhenNeitherShapeMatches() throws Exception {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
												() -> Geometry.centerOf(json("{'objects':{}}")));
		assertTrue(e.getMessage().contains("objects"));
	}

	@Test
	void downWithYawIsDownRotatedAboutBaseZ() {
		double yaw = Math.toRadians(30);
		assertQuat(Quaternion.aboutZ(yaw).multiply(Geometry.DOWN), Geometry.downWithYaw(yaw));
	}

	@Test
	void rotatedAboutBaseZTurnsPositionAndOrientationTogether() {
		Pose pose = new Pose(new Position(1.0, 0.0, 0.3), Geometry.DOWN);
		Pose turned = Geometry.rotatedAboutBaseZ(pose, 90.0);
		assertEquals(0.0, turned.position().x(), EPS);
		assertEquals(1.0, turned.position().y(), EPS);
		assertEquals(0.3, turned.position().z(), EPS);
		assertQuat(Geometry.downWithYaw(Math.toRadians(90)), turned.orientation());
	}

	@Test
	void aboveOfKeepsOrientationAndRaisesZ() {
		Pose grasp = new Pose(new Position(0.4, 0.0, 0.025), Geometry.DOWN);
		Pose above = Geometry.aboveOf(grasp, 0.2);
		assertEquals(0.225, above.position().z(), EPS);
		assertEquals(grasp.orientation(), above.orientation());
	}

	@Test
	void graspYawFoldsBySymmetry() throws Exception {
		// 정사각 단면 상자를 70° 돌려 놓으면 90° 주기로 접어 -20° 로 잡는다.
		Quaternion q70 = Quaternion.aboutZ(Math.toRadians(70));
		JsonNode cube = MAPPER.valueToTree(java.util.Map.of(
				"type", "box", "dimensions", java.util.List.of(0.05, 0.05, 0.05),
				"pose", java.util.Map.of("position", new Position(0.4, 0, 0.025), "orientation", q70)));
		assertEquals(-20.0, Math.toDegrees(Geometry.graspYawOf(cube)), 1e-6);

		JsonNode cylinder = json("{'type':'cylinder','dimensions':[0.06,0.03],'position':{'x':0,'y':0,'z':0}}");
		assertEquals(0.0, Geometry.graspYawOf(cylinder), EPS);
	}

	@Test
	void graspPoseLooksDownAtTheCenter() throws Exception {
		Pose grasp = Geometry.graspPoseOf(json("{'position':{'x':0.42,'y':-0.08,'z':0.025}}"));
		assertEquals(new Position(0.42, -0.08, 0.025), grasp.position());
		assertQuat(Geometry.DOWN, grasp.orientation());
	}

	/** scene_objects 모양의 원기둥 peg (길이 50 mm, 반지름 8 mm, 중심 (0.5, 0, 0.028)). */
	private static JsonNode peg(double qx, double qy, double qz, double qw) {
		return MAPPER.valueToTree(java.util.Map.of(
				"type", "cylinder", "dimensions", java.util.List.of(0.05, 0.008),
				"pose", java.util.Map.of("position", java.util.Map.of("x", 0.5, "y", 0.0, "z", 0.028),
										"orientation", java.util.Map.of("x", qx, "y", qy, "z", qz, "w", qw))));
	}

	private static void assertVector(double[] want, double[] got) {
		for ( int i = 0; i < want.length; ++i ) {
			assertEquals(want[i], got[i], 1e-12, "component " + i);
		}
	}

	private static void assertPosition(double x, double y, double z, Position got) {
		assertEquals(x, got.x(), 1e-12);
		assertEquals(y, got.y(), 1e-12);
		assertEquals(z, got.z(), 1e-12);
	}

	// 기대값은 robot-twin-client peg_in_hole.py 의 axis_of / bottom_of / tilt_deg / vertical_half_extent 결과다.

	@Test
	void uprightCylinderBottomIsHalfLengthBelowCenter() {
		JsonNode peg = peg(0, 0, 0, 1);
		assertVector(new double[] { 0, 0, 1 }, Geometry.axisOf(peg));
		assertPosition(0.5, 0.0, 0.003, Geometry.bottomOf(peg));
		assertEquals(0.0, Geometry.tiltDegOf(peg), 1e-12);
		assertEquals(0.025, Geometry.verticalHalfExtentOf(peg), 1e-12);
	}

	@Test
	void slightTiltMovesBottomAwayFromCenter() {
		// 0.74° 기울면 바닥이 중심에서 0.32 mm 벗어난다 — 트레이 반경 여유(0.50 mm)에 견줄 만하다.
		double t = Math.toRadians(0.74);
		JsonNode peg = peg(Math.sin(t / 2), 0, 0, Math.cos(t / 2));
		assertVector(new double[] { 0.0, -0.012915077400324205, 0.9999165969098341 }, Geometry.axisOf(peg));
		assertPosition(0.5, 0.00032287693500810516, 0.0030020850772541456, Geometry.bottomOf(peg));
		assertEquals(0.74, Geometry.tiltDegOf(peg), 1e-9);
		assertEquals(0.025101235541948402, Geometry.verticalHalfExtentOf(peg), 1e-12);
	}

	@Test
	void lyingCylinderExtendsByItsRadiusNotHalfLength() {
		JsonNode peg = peg(0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4));
		assertEquals(90.0, Geometry.tiltDegOf(peg), 1e-9);
		assertEquals(0.008, Geometry.verticalHalfExtentOf(peg), 1e-12);
		assertPosition(0.475, 0.0, 0.028, Geometry.bottomOf(peg));
	}

	@Test
	void flippedCylinderIsTheSameAsUpright() {
		JsonNode peg = peg(1, 0, 0, 0);
		assertVector(new double[] { 0, 0, 1 }, Geometry.axisOf(peg));
		assertEquals(0.0, Geometry.tiltDegOf(peg), 1e-12);
		assertPosition(0.5, 0.0, 0.003, Geometry.bottomOf(peg));
	}

	@Test
	void generalOrientationMatchesPython() {
		double n = Math.sqrt(0.1 * 0.1 + 0.2 * 0.2 + 0.3 * 0.3 + 0.9 * 0.9);
		JsonNode peg = peg(0.1 / n, 0.2 / n, 0.3 / n, 0.9 / n);
		assertVector(new double[] { 0.4421052631578947, -0.06315789473684214, 0.8947368421052632 }, Geometry.axisOf(peg));
		assertPosition(0.48894736842105263, 0.0015789473684210535, 0.0056315789473684215, Geometry.bottomOf(peg));
		assertEquals(26.525352016609688, Geometry.tiltDegOf(peg), 1e-9);
		assertEquals(0.025941171104942557, Geometry.verticalHalfExtentOf(peg), 1e-12);
	}

	@Test
	void missingOrientationMeansNoRotationAndResetShapeIsAccepted() throws Exception {
		// reset_scene 출력 모양이며, 레시피에 회전이 없어 orientation 이 생략됐다.
		JsonNode placed = json("{'name':'peg','type':'cylinder','dimensions':[0.05,0.008],'position':{'x':0.5,'y':0,'z':0.028}}");
		assertPosition(0.5, 0.0, 0.003, Geometry.bottomOf(placed));
		assertEquals(0.0, Geometry.tiltDegOf(placed), 1e-12);
	}

	@Test
	void slightlyNonUnitQuaternionIsNormalizedButOthersAreRejected() {
		assertPosition(0.5, 0.0, 0.003, Geometry.bottomOf(peg(0, 0, 0, 1.0005)));
		assertThrows(IllegalArgumentException.class, () -> Geometry.axisOf(peg(0, 0, 0, 2)));
		assertThrows(IllegalArgumentException.class, () -> Geometry.axisOf(peg(0, 0, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> Geometry.axisOf(peg(Double.NaN, 0, 0, 1)));
	}

	@Test
	void invalidCylinderInputsAreRejectedInsteadOfBecomingZero() throws Exception {
		// 빠지거나 null 인 성분이 0 으로 바뀌면 엉뚱한 바닥점을 돌려주므로 거부해야 한다.
		assertThrows(IllegalArgumentException.class, () -> Geometry.bottomOf(json(
				"{'type':'cylinder','dimensions':[0.05,0.008],'pose':{'position':{'x':0.5,'z':0.028}}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.bottomOf(json(
				"{'type':'cylinder','dimensions':[0.05,0.008],'pose':{'position':{'x':0.5,'y':null,'z':0.028}}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.axisOf(json(
				"{'pose':{'position':{'x':0,'y':0,'z':0},'orientation':{'x':0,'y':0,'z':0}}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.bottomOf(json(
				"{'type':'box','dimensions':[0.05,0.05,0.05],'position':{'x':0,'y':0,'z':0}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.verticalHalfExtentOf(json(
				"{'type':'cylinder','dimensions':[0.05],'position':{'x':0,'y':0,'z':0}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.verticalHalfExtentOf(json(
				"{'type':'cylinder','dimensions':[0.05,-0.008],'position':{'x':0,'y':0,'z':0}}")));
		assertThrows(IllegalArgumentException.class, () -> Geometry.bottomOf(json(
				"{'dimensions':[0.05,0.008],'position':{'x':0,'y':0,'z':0}}")));
	}

	@Test
	void poseReadsPoseStamped() throws Exception {
		Pose pose = Pose.fromJson(json(
				"{'header':{'frame_id':'panda_link0'},'pose':{'position':{'x':1,'y':2,'z':3},'orientation':{'x':0,'y':0,'z':0,'w':1}}}"));
		assertEquals(new Position(1, 2, 3), pose.position());
	}
}
