package mdt.robot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 좌표·기하 헬퍼 — **기반 층**이다. 트윈을 부르지 않는 순수 계산이다.
 * <p>
 * 여기서 만드는 pose 는 **손끝(TCP) 기준**이다. 팔에 명령할 프레임으로 바꾸는 것은 그리퍼에 묶인
 * 규약이라 여기 두지 않는다 — Panda Hand(mock · Isaac)는 {@link PandaHand} 가 맡고, 프레임을 스스로
 * 변환하는 트윈(펑션베이)에는 그대로 넘긴다.
 * <p>
 * 물체는 트윈이 주는 JSON 그대로 받는다. 같은 물체가 출처에 따라 두 모양으로 온다.
 * <pre>
 *   reset_scene / begin_task 의 outputs.objects 원소
 *       {"name":…, "type":…, "dimensions":[…], "position":{x,y,z}}
 *   scene_objects 변수의 objects[이름]
 *       {"type":…, "dimensions":[…], "pose":{"position":{…}, "orientation":{…}}}
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class Geometry {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** x축 180° — 그리퍼가 아래를 본다. 손끝(TCP) 기준 자세다. */
	public static final Quaternion DOWN = new Quaternion(1.0, 0.0, 0.0, 0.0);

	private Geometry() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * {@link #DOWN}(아래를 보는 자세)을 베이스 z축으로 {@code yawRad} 만큼 돌린 자세를 반환한다.
	 * 접근축이 정확히 연직 하향이다.
	 * <p>
	 * <b>자세는 절대값으로 만든다 — 현재 자세를 읽어 다음 목표로 주면 안 된다.</b> 처짐은 위치만이 아니라
	 * 자세도 틀어놓으므로 그 오차가 지워지지 않고 쌓인다(펑션베이 실측: 16회 이동에 접근축이 22.3° 기울었다).
	 *
	 * @param yawRad	베이스 z축 회전각(rad).
	 * @return	자세.
	 */
	public static Quaternion downWithYaw(double yawRad) {
		double half = yawRad / 2.0;
		return new Quaternion(Math.cos(half), Math.sin(half), 0.0, 0.0);
	}

	/**
	 * 파지 지점 바로 위의 접근 지점을 반환한다. 자세는 같고 z 만 띄운다.
	 *
	 * @param grasp			파지 pose.
	 * @param clearanceM	띄울 높이(m).
	 * @return	접근 pose.
	 */
	public static Pose aboveOf(Pose grasp, double clearanceM) {
		return grasp.withPosition(grasp.position().plus(0.0, 0.0, clearanceM));
	}

	/**
	 * 베이스 z축({@code panda_link0}) 둘레로 돌린 pose 를 반환한다. 양수면 위에서 볼 때 반시계다.
	 * <p>
	 * 위치는 베이스 원점 둘레의 원호를 따라 돌고, <b>자세도 같은 각만큼 함께 돈다</b> — 그래야 쥔 물체와
	 * 그리퍼의 상대 자세가 유지된다. 높이는 건드리지 않는다.
	 *
	 * @param pose		원래 pose.
	 * @param angleDeg	회전각(도).
	 * @return	회전한 pose.
	 */
	public static Pose rotatedAboutBaseZ(Pose pose, double angleDeg) {
		double rad = Math.toRadians(angleDeg);
		double sin = Math.sin(rad), cos = Math.cos(rad);
		Position p = pose.position();
		// 자세는 회전 쿼터니언을 왼쪽에서 곱한다 (world frame 기준 회전).
		Quaternion q = Quaternion.aboutZ(rad).multiply(pose.orientation());
		return new Pose(new Position(p.x() * cos - p.y() * sin, p.x() * sin + p.y() * cos, p.z()), q);
	}

	/**
	 * 물체의 중심 좌표를 반환한다. 두 모양(클래스 설명 참조)을 모두 받는다.
	 * <p>
	 * 앞의 것만 받으면 씬을 읽어서 집으려는 쪽이 실패한다. 호출자가 출처를 기억해야 하는 것 자체가
	 * 함정이라 여기서 흡수한다.
	 *
	 * @param obj	물체 한 개의 JSON. (목록이나 {@code objects} 전체가 아니다)
	 * @return	중심 위치.
	 * @throws IllegalArgumentException	어느 모양도 아닌 경우.
	 */
	public static Position centerOf(JsonNode obj) {
		JsonNode position = obj.has("position") ? obj.get("position") : obj.path("pose").get("position");
		if ( position == null || !position.isObject() ) {
			List<String> keys = new ArrayList<>();
			obj.fieldNames().forEachRemaining(keys::add);
			throw new IllegalArgumentException("object has no 'position' nor 'pose.position' — it must be an "
												+ "element of reset_scene/begin_task objects or a value of "
												+ "scene_objects (keys: " + keys + ")");
		}
		return MAPPER.convertValue(position, Position.class);
	}

	/**
	 * 물체의 자세를 반환한다. {@link #centerOf(JsonNode)} 와 같은 두 모양을 받는다.
	 *
	 * @param obj	물체 한 개의 JSON.
	 * @return	자세. 없으면 {@link Optional#empty()}.
	 */
	public static Optional<Quaternion> orientationOf(JsonNode obj) {
		JsonNode orientation = obj.has("orientation") ? obj.get("orientation")
														: obj.path("pose").get("orientation");
		if ( orientation == null || !orientation.isObject() ) {
			return Optional.empty();
		}
		return Optional.of(MAPPER.convertValue(orientation, Quaternion.class));
	}

	/**
	 * 물체를 <b>면으로</b> 잡기 위한 손목 yaw(rad)를 반환한다.
	 * <p>
	 * 물체가 돌아가 있으면 손목도 그만큼 돌려야 손가락이 면에 닿는다. 모서리를 물면 손가락 힘이 물체를
	 * 죄는 대신 돌리는 데 쓰여 파지 판정이 서지 않는다(Isaac 실측). <b>대칭 주기만큼 접는다.</b>
	 * <ul>
	 *   <li>실린더 · 구 · 원뿔: 회전 대칭이라 0.</li>
	 *   <li>단면이 정사각인 상자: 90° 주기로 접는다.</li>
	 *   <li>그 밖의 상자: 180° 주기로 접는다. 어느 변을 가로질러 무는지는 검증하지 않았다.</li>
	 * </ul>
	 *
	 * @param obj	물체 한 개의 JSON.
	 * @return	손목 yaw(rad).
	 */
	public static double graspYawOf(JsonNode obj) {
		String kind = obj.path("type").asText("box");
		if ( kind.equals("cylinder") || kind.equals("sphere") || kind.equals("cone") ) {
			return 0.0;
		}
		Optional<Quaternion> orientation = orientationOf(obj);
		if ( orientation.isEmpty() ) {
			return 0.0;
		}
		JsonNode dims = obj.path("dimensions");
		boolean square = dims.size() >= 2 && Math.abs(dims.get(0).asDouble() - dims.get(1).asDouble()) < 1e-3;
		double period = square ? Math.PI / 2.0 : Math.PI;
		// 파이썬의 `%` 와 같은 floor 나머지로 접는다(음수에서 Java 의 `%` 와 다르다).
		double shifted = orientation.get().yaw() + period / 2.0;
		return shifted - period * Math.floor(shifted / period) - period / 2.0;
	}

	/**
	 * 배치된 물체의 파지 지점을 반환한다. 물체 중심을 위에서 잡는다고 본다.
	 * <p>
	 * <b>손끝(TCP) 기준 pose 를 돌려준다</b> — Panda Hand 스택(mock · Isaac)에서는 팔에 명령하기 전에
	 * {@link PandaHand#toArmCommand(Pose)} 를 거쳐야 한다. 자세도 물체를 따라간다({@link #graspYawOf}).
	 * 충돌·도달 가능성이나 실제 파지는 판정하지 않는다.
	 *
	 * @param obj	물체 한 개의 JSON.
	 * @return	파지 pose.
	 */
	public static Pose graspPoseOf(JsonNode obj) {
		return new Pose(centerOf(obj), downWithYaw(graspYawOf(obj)));
	}

	/**
	 * 물체의 로컬 z축을 베이스 좌표로 돌린 <b>위를 향하는</b> 단위벡터를 반환한다. 원기둥이면 축 방향이다.
	 * <p>
	 * 아래를 향하면 부호를 뒤집는다 — 원기둥은 위아래가 대칭이라 뒤집혀 놓여도 같은 물체다.
	 * 자세가 없으면 회전이 없는 것으로 본다(레시피에 회전이 없으면 로봇 트윈은 orientation을 생략한다).
	 * 쿼터니언 길이가 1에서 0.001보다 많이 벗어나면 거부하고, 그 안이면 정규화해서 쓴다.
	 *
	 * @param obj	물체 한 개의 JSON. 두 모양(클래스 설명 참조)을 모두 받는다.
	 * @return	단위벡터 {@code [x, y, z]}, {@code z >= 0}.
	 * @throws IllegalArgumentException	자세 성분이 유한한 숫자가 아니거나 단위 쿼터니언이 아닌 경우.
	 */
	public static double[] axisOf(JsonNode obj) {
		double[] axis = unitOrientationOf(obj).rotate(0.0, 0.0, 1.0);
		return (axis[2] >= 0) ? axis : new double[] { -axis[0], -axis[1], -axis[2] };
	}

	/**
	 * 물체의 로컬 z축이 연직에서 벗어난 각(도)을 반환한다. 원기둥이면 0 이 서 있는 것이고 90 이 누운 것이다.
	 * 위아래 대칭이라 뒤집혀 놓인 것도 0 이다.
	 *
	 * @param obj	물체 한 개의 JSON.
	 * @return	기울기(도), {@code [0, 90]}.
	 * @throws IllegalArgumentException	{@link #axisOf(JsonNode)} 와 같다.
	 */
	public static double tiltDegOf(JsonNode obj) {
		return Math.toDegrees(Math.acos(Math.min(1.0, axisOf(obj)[2])));
	}

	/**
	 * 원기둥 <b>아래쪽 끝면의 중심</b>을 반환한다.
	 * <p>
	 * <b>구멍에 들어가는 것은 중심이 아니라 이 점이다.</b> 기울기가 작아도 지렛대가 길어서 무시할 수 없다 —
	 * 50 mm peg 이 0.74° 기울면 바닥은 중심에서 0.32 mm 벗어나는데, 모따기 없는 트레이의 반경 여유가
	 * 0.50 mm 뿐이라 그 하나로 한계에 닿는다(펑션베이 실측).
	 *
	 * @param obj	{@code type} 이 {@code cylinder} 인 물체 한 개의 JSON. {@code dimensions} 는 {@code [길이, 반지름]}.
	 * @return	아래쪽 끝면의 중심.
	 * @throws IllegalArgumentException	원기둥이 아니거나 위치·자세·크기가 유효하지 않은 경우.
	 */
	public static Position bottomOf(JsonNode obj) {
		double halfLength = cylinderDimensionsOf(obj)[0] / 2.0;
		double[] center = finiteVector(positionNodeOf(obj), "position", "x", "y", "z");
		double[] axis = axisOf(obj);
		return new Position(center[0] - halfLength * axis[0], center[1] - halfLength * axis[1],
							center[2] - halfLength * axis[2]);
	}

	/**
	 * 원기둥이 중심에서 위아래로 얼마나 뻗는지(m)를 반환한다 — <b>자세를 반영한다.</b>
	 * <p>
	 * 원기둥 길이를 그냥 수직 크기로 쓰면 <b>누운 물체에서 틀린다.</b> 50 mm peg 이 누우면 수직 크기는 길이가
	 * 아니라 지름(16 mm)이다. 그렇게 계산하면 바닥을 17 mm 낮게 잡아 손끝 목표가 탁자 아래로 내려간다.
	 *
	 * @param obj	{@code type} 이 {@code cylinder} 인 물체 한 개의 JSON.
	 * @return	중심에서 위(또는 아래) 끝까지의 수직 거리(m).
	 * @throws IllegalArgumentException	원기둥이 아니거나 자세·크기가 유효하지 않은 경우.
	 */
	public static double verticalHalfExtentOf(JsonNode obj) {
		double[] dims = cylinderDimensionsOf(obj);
		double axisZ = axisOf(obj)[2];
		double horizontal = Math.sqrt(Math.max(0.0, 1.0 - axisZ * axisZ));
		return axisZ * (dims[0] / 2.0) + horizontal * dims[1];
	}

	private static final double UNIT_QUATERNION_TOLERANCE = 0.001;

	private static Quaternion unitOrientationOf(JsonNode obj) {
		JsonNode node = obj.has("orientation") ? obj.get("orientation") : obj.path("pose").get("orientation");
		if ( node == null || node.isNull() ) {
			return new Quaternion(0.0, 0.0, 0.0, 1.0);
		}
		double[] q = finiteVector(node, "orientation", "x", "y", "z", "w");
		double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		if ( Math.abs(norm - 1.0) > UNIT_QUATERNION_TOLERANCE ) {
			throw new IllegalArgumentException("orientation is not a unit quaternion (norm " + norm + "): " + node);
		}
		return new Quaternion(q[0] / norm, q[1] / norm, q[2] / norm, q[3] / norm);
	}

	private static JsonNode positionNodeOf(JsonNode obj) {
		return obj.has("position") ? obj.get("position") : obj.path("pose").path("position");
	}

	/** {@code [길이, 반지름]}. 원기둥이 아니거나 크기가 양의 유한수가 아니면 거부한다. */
	private static double[] cylinderDimensionsOf(JsonNode obj) {
		String type = obj.path("type").asText(null);
		if ( !"cylinder".equals(type) ) {
			throw new IllegalArgumentException("not a cylinder (type=" + type + ")");
		}
		JsonNode dims = obj.path("dimensions");
		if ( !dims.isArray() || dims.size() < 2 ) {
			throw new IllegalArgumentException("cylinder dimensions must be [length, radius]: " + dims);
		}
		double length = dims.get(0).asDouble(Double.NaN), radius = dims.get(1).asDouble(Double.NaN);
		if ( !dims.get(0).isNumber() || !dims.get(1).isNumber() || !(length > 0) || !(radius > 0)
			|| !Double.isFinite(length) || !Double.isFinite(radius) ) {
			throw new IllegalArgumentException("cylinder dimensions must be positive finite numbers: " + dims);
		}
		return new double[] { length, radius };
	}

	/** null 이나 빠진 성분이 0 으로 바뀌지 않도록 각 성분이 유한한 숫자인지 확인한다. */
	private static double[] finiteVector(JsonNode node, String name, String... keys) {
		if ( node == null || !node.isObject() ) {
			throw new IllegalArgumentException("object has no '" + name + "': " + node);
		}
		double[] values = new double[keys.length];
		for ( int i = 0; i < keys.length; ++i ) {
			JsonNode value = node.get(keys[i]);
			if ( value == null || !value.isNumber() || !Double.isFinite(value.doubleValue()) ) {
				throw new IllegalArgumentException(name + "." + keys[i] + " must be a finite number: " + node);
			}
			values[i] = value.doubleValue();
		}
		return values;
	}
}
