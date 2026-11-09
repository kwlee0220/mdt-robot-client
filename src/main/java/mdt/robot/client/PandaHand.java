package mdt.robot.client;

/**
 * 손끝(TCP) ↔ {@code panda_link8} 변환 — <b>Panda Hand 그리퍼(mock · Isaac 스택) 전용 규약</b>이다.
 * <p>
 * {@link Geometry} 가 만드는 pose 는 손끝 기준인데, mock · Isaac 트윈의 {@code move_linear} 는 planning
 * group 의 tip link 인 {@code panda_link8} 을 움직인다. 둘 사이는 Panda Hand 의 기하(손끝까지 0.1034 m,
 * z축 45°)로 정해지므로 기하 일반이 아니라 그리퍼에 묶인 값이다.
 * <p>
 * <b>프레임을 스스로 변환하는 트윈에는 쓰지 않는다.</b> 펑션베이 트윈은 {@code move_linear} 에
 * {@code frame} 입력이 있고 기본이 {@code grasp_center} 라 손끝 기준 목표를 그대로 받는다. 거기에
 * {@link #toArmCommand(Pose)} 를 얹으면 보정이 두 번 걸려 그리퍼가 10.3 cm 아래를 겨냥한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class PandaHand {
	/**
	 * panda_hand 원점에서 손끝까지의 거리(m). Franka 공식 description 의 {@code panda_hand_tcp} 와 같다.
	 */
	public static final double TCP_OFFSET_M = 0.1034;

	// panda_link8 → panda_hand 는 이동 0, z축 -45° 회전이다 (URDF `panda_hand_joint`).
	// 명령은 link8 기준으로 나가므로 반대 부호로 되돌려 줘야 손가락이 의도한 방향에 선다.
	private static final double HAND_YAW_RAD = Math.PI / 4.0;

	private PandaHand() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * <b>손끝(TCP) 기준</b> 목표를 팔에 명령할 <b>{@code panda_link8} 기준</b> pose 로 바꾼다.
	 * <p>
	 * 이 보정이 없으면 그리퍼가 바닥을 파고든다(5 cm 큐브 중심 z=0.025 를 그대로 명령하면 손끝이
	 * z=-0.078). 두 가지를 되돌린다.
	 * <ol>
	 *   <li><b>위치</b> — 목표 자세로 회전시킨 오프셋만큼 빼서 link8 을 뒤로 물린다.
	 *       {@link Geometry#DOWN} 자세면 z 가 0.1034 m 올라간다.</li>
	 *   <li><b>자세</b> — link8 기준 명령에 로컬 z축 +45° 를 곱한다.</li>
	 * </ol>
	 * 두 프레임의 위치는 같으므로(이동 0) 변환한 목표 위치를 관측한 {@code panda_hand} 위치와 비교할 수 있다.
	 *
	 * @param tcpPose	손끝 기준 목표.
	 * @return	{@code panda_link8} 기준 명령.
	 */
	public static Pose toArmCommand(Pose tcpPose) {
		Quaternion q = tcpPose.orientation();
		double[] o = q.rotate(0.0, 0.0, TCP_OFFSET_M);
		Position p = tcpPose.position();
		return new Pose(new Position(p.x() - o[0], p.y() - o[1], p.z() - o[2]),
						q.multiply(Quaternion.aboutZ(HAND_YAW_RAD)));
	}

	/**
	 * 팔이 보고한 pose({@code ee_pose} / {@code outputs.final_pose})를 <b>손끝(TCP) 기준</b>으로 바꾼다.
	 * <p>
	 * {@link #toArmCommand(Pose)} 의 역이며 <b>위치만</b> 되돌린다. 팔이 보고하는 자세는 이미
	 * {@code panda_hand} 의 것이므로 그대로 쓴다. 쥔 물체를 어디에 물었는지 잴 때 쓴다.
	 *
	 * @param armPose	팔이 보고한 pose.
	 * @return	손끝 기준 pose.
	 */
	public static Pose tcpFromArmPose(Pose armPose) {
		Quaternion q = armPose.orientation();
		double[] o = q.rotate(0.0, 0.0, TCP_OFFSET_M);
		Position p = armPose.position();
		return new Pose(new Position(p.x() + o[0], p.y() + o[1], p.z() + o[2]), q);
	}

	/**
	 * 팔이 보고한 pose({@code ee_pose} / {@code outputs.final_pose}, {@code panda_hand} 기준)를 그 자세로 다시
	 * 명령할 <b>{@code panda_link8} 기준</b> pose 로 바꾼다.
	 * <p>
	 * 두 프레임의 위치는 같으므로 위치는 그대로 두고, 자세에 로컬 z축 +45° 를 곱한다.
	 * {@code toArmCommand(tcpFromArmPose(armPose))}와 같다. 관측한 pose 를 조금 옮겨 다시 명령할 때
	 * (예: 현재 자리에서 위로 5 cm) 이 변환을 빼면 명령마다 손목이 45° 씩 돈다. 위치만 비교하는 도달 판정은
	 * 이 회전을 잡지 못한다.
	 *
	 * @param armPose	팔이 보고한 pose.
	 * @return	같은 손 자세를 만드는 {@code panda_link8} 기준 명령.
	 */
	public static Pose armCommandFromArmPose(Pose armPose) {
		return new Pose(armPose.position(), armPose.orientation().multiply(Quaternion.aboutZ(HAND_YAW_RAD)));
	}
}
