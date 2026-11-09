package mdt.robot.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.Preconditions;

/**
 * 위치와 자세. 트윈의 {@code pose} JSON({@code {"position": {...}, "orientation": {...}}})과 대응한다.
 * <p>
 * pose 자체에는 프레임 이름이 없다. 위치는 베이스 좌표계({@code panda_link0}) 기준이며, 그 pose 가
 * 어느 점(손끝 TCP · {@code panda_link8} · {@code grasp_center})의 것인지는 호출자가 일관되게 지킨다.
 *
 * @param position		위치.
 * @param orientation	자세.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record Pose(@JsonProperty("position") Position position,
					@JsonProperty("orientation") Quaternion orientation) {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	public Pose {
		Preconditions.checkNotNullArgument(position, "position is null");
		Preconditions.checkNotNullArgument(orientation, "orientation is null");
	}

	/**
	 * pose JSON 을 읽는다.
	 * <p>
	 * {@code ee_pose} 변수처럼 {@code geometry_msgs/PoseStamped} 형태({@code {"header":…, "pose":…}})로
	 * 오는 값도 받는다 — 그때는 안쪽 {@code pose} 를 읽는다.
	 *
	 * @param node	pose 또는 PoseStamped JSON.
	 * @return	읽은 pose.
	 * @throws IllegalArgumentException	{@code position}/{@code orientation} 이 없는 경우.
	 */
	public static Pose fromJson(JsonNode node) {
		Preconditions.checkNotNullArgument(node, "pose json is null");
		JsonNode pose = (node.has("pose") && node.get("pose").isObject()) ? node.get("pose") : node;
		if ( !pose.has("position") || !pose.has("orientation") ) {
			throw new IllegalArgumentException("not a pose (no 'position'/'orientation'): " + node);
		}
		// 성분이 빠지거나 숫자가 아니면 거부하고, 쿼터니언은 단위 길이인지 확인한다.
		return new Pose(Geometry.readPosition(pose.get("position")), Geometry.readUnitQuaternion(pose.get("orientation")));
	}

	/**
	 * 트윈 연산 입력으로 보낼 JSON 으로 바꾼다.
	 *
	 * @return	pose JSON.
	 */
	public JsonNode toJson() {
		return MAPPER.valueToTree(this);
	}

	/**
	 * 위치만 바꾼 pose 를 반환한다.
	 *
	 * @param newPosition	새 위치.
	 * @return	새 pose.
	 */
	public Pose withPosition(Position newPosition) {
		return new Pose(newPosition, orientation);
	}
}
