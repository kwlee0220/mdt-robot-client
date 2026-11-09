package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PandaHandTest {
	private static final double EPS = 1e-9;
	// URDF `panda_hand_joint` — link8 에서 hand 로 가는 회전(z축 -45°).
	private static final Quaternion LINK8_TO_HAND = Quaternion.aboutZ(-Math.PI / 4);

	@Test
	void downwardTargetLiftsLink8ByTheTcpOffset() {
		Pose tcp = new Pose(new Position(0.4, 0.0, 0.025), Geometry.DOWN);
		Pose cmd = PandaHand.toArmCommand(tcp);
		assertEquals(0.4, cmd.position().x(), EPS);
		assertEquals(0.0, cmd.position().y(), EPS);
		assertEquals(0.025 + PandaHand.TCP_OFFSET_M, cmd.position().z(), EPS);
	}

	@Test
	void handEndsUpInTheRequestedOrientation() {
		Pose tcp = new Pose(new Position(0.4, 0.1, 0.1), Geometry.downWithYaw(Math.toRadians(25)));
		Quaternion hand = PandaHand.toArmCommand(tcp).orientation().multiply(LINK8_TO_HAND);
		Quaternion want = tcp.orientation();
		double dot = want.x()*hand.x() + want.y()*hand.y() + want.z()*hand.z() + want.w()*hand.w();
		assertEquals(1.0, Math.abs(dot), 1e-9);
	}

	@Test
	void armCommandFromArmPoseReproducesTheCommand() {
		// 팔이 보고한 자세(hand)를 다시 명령하면 처음 명령(link8)이 나와야 손목이 돌지 않는다.
		Pose tcp = new Pose(new Position(0.3, -0.2, 0.05), Geometry.downWithYaw(0.4));
		Pose cmd = PandaHand.toArmCommand(tcp);
		Pose reported = new Pose(cmd.position(), cmd.orientation().multiply(LINK8_TO_HAND));
		Pose again = PandaHand.armCommandFromArmPose(reported);
		assertEquals(cmd.position().x(), again.position().x(), EPS);
		assertEquals(cmd.position().y(), again.position().y(), EPS);
		assertEquals(cmd.position().z(), again.position().z(), EPS);
		Quaternion a = again.orientation(), c = cmd.orientation();
		assertEquals(1.0, Math.abs(a.x()*c.x() + a.y()*c.y() + a.z()*c.z() + a.w()*c.w()), 1e-9);
		// toArmCommand(tcpFromArmPose(·))와 같다.
		Pose composed = PandaHand.toArmCommand(PandaHand.tcpFromArmPose(reported));
		assertEquals(composed.position().z(), again.position().z(), EPS);
	}

	@Test
	void tcpFromArmPoseRestoresThePosition() {
		Pose tcp = new Pose(new Position(0.3, -0.2, 0.05), Geometry.downWithYaw(0.4));
		Pose cmd = PandaHand.toArmCommand(tcp);
		// 팔이 보고하는 자세는 hand 의 것이다 — 그 자세로 위치만 되돌린다.
		Pose reported = new Pose(cmd.position(), cmd.orientation().multiply(LINK8_TO_HAND));
		Pose back = PandaHand.tcpFromArmPose(reported);
		assertEquals(tcp.position().x(), back.position().x(), EPS);
		assertEquals(tcp.position().y(), back.position().y(), EPS);
		assertEquals(tcp.position().z(), back.position().z(), EPS);
	}
}
