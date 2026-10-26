package mdt.robot.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 자세를 나타내는 쿼터니언. 성분 순서는 ROS 와 같은 {@code x, y, z, w} 다.
 * <p>
 * 회전 계산은 유효한 단위 쿼터니언을 전제하며 입력을 자동 정규화하지 않는다.
 *
 * @param x	x 성분.
 * @param y	y 성분.
 * @param z	z 성분.
 * @param w	w 성분.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record Quaternion(@JsonProperty("x") double x,
							@JsonProperty("y") double y,
							@JsonProperty("z") double z,
							@JsonProperty("w") double w) {
	/**
	 * Hamilton 곱 {@code this ⊗ other} 를 반환한다.
	 *
	 * @param b	오른쪽 피연산자.
	 * @return	곱.
	 */
	public Quaternion multiply(Quaternion b) {
		return new Quaternion(w * b.x + x * b.w + y * b.z - z * b.y,
								w * b.y - x * b.z + y * b.w + z * b.x,
								w * b.z + x * b.y - y * b.x + z * b.w,
								w * b.w - x * b.x - y * b.y - z * b.z);
	}

	/**
	 * 벡터를 이 쿼터니언으로 회전한다.
	 *
	 * @param vx	x 성분.
	 * @param vy	y 성분.
	 * @param vz	z 성분.
	 * @return	회전한 벡터 {@code [x, y, z]}.
	 */
	public double[] rotate(double vx, double vy, double vz) {
		// t = 2 * (q_vec × v);  v' = v + q_w * t + q_vec × t
		double tx = 2.0 * (y * vz - z * vy);
		double ty = 2.0 * (z * vx - x * vz);
		double tz = 2.0 * (x * vy - y * vx);
		return new double[] { vx + w * tx + y * tz - z * ty,
								vy + w * ty + z * tx - x * tz,
								vz + w * tz + x * ty - y * tx };
	}

	/**
	 * z축 회전각(yaw, rad)을 반환한다.
	 *
	 * @return	yaw(rad).
	 */
	public double yaw() {
		return Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));
	}

	/**
	 * z축 둘레로 {@code rad} 만큼 도는 쿼터니언을 반환한다.
	 *
	 * @param rad	회전각(rad).
	 * @return	회전 쿼터니언.
	 */
	public static Quaternion aboutZ(double rad) {
		return new Quaternion(0.0, 0.0, Math.sin(rad / 2.0), Math.cos(rad / 2.0));
	}
}
