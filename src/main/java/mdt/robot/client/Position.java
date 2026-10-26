package mdt.robot.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 베이스 좌표계({@code panda_link0}) 위의 위치. 단위는 m 이다.
 * <p>
 * 트윈이 주고받는 JSON {@code {"x":…, "y":…, "z":…}} 과 그대로 대응한다.
 *
 * @param x	x 좌표(m).
 * @param y	y 좌표(m).
 * @param z	z 좌표(m).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public record Position(@JsonProperty("x") double x,
						@JsonProperty("y") double y,
						@JsonProperty("z") double z) {
	/**
	 * 각 축 차이의 절댓값 중 최댓값을 반환한다. 도달 판정의 오차 정의다(유클리드 거리가 아니다).
	 *
	 * @param other	비교할 위치.
	 * @return	{@code max(|dx|, |dy|, |dz|)}.
	 */
	public double maxAxisDistance(Position other) {
		return Math.max(Math.abs(x - other.x), Math.max(Math.abs(y - other.y), Math.abs(z - other.z)));
	}

	/**
	 * 각 축에 주어진 값을 더한 위치를 반환한다.
	 *
	 * @param dx	x 증분(m).
	 * @param dy	y 증분(m).
	 * @param dz	z 증분(m).
	 * @return	이동한 위치.
	 */
	public Position plus(double dx, double dy, double dz) {
		return new Position(x + dx, y + dy, z + dz);
	}
}
