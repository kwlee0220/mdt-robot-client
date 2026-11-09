package mdt.robot.client.examples;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.Position;
import mdt.robot.client.PandaHand;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;

/**
 * 예제들이 함께 쓰는 접속 · 좌표 도우미.
 * <p>
 * 접속 인자는 {@code --url <트윈 서버 루트 URL> --twin-id <id>} 이며 모두 생략할 수 있다(기본
 * {@code http://127.0.0.1:8801 / panda01}, Isaac 트윈). 펑션베이 트윈은 {@code --twin-id rdfp01}이다.
 * 나머지 인자는 예제마다 뜻이 다른 위치 인자다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
final class ExampleSupport {
	private ExampleSupport() {
		throw new AssertionError("Should not be called: " + getClass());
	}

	/**
	 * 접속 인자로 클라이언트를 만들고 붙은 트윈을 출력한다.
	 *
	 * @param args	명령행 인자.
	 * @return	클라이언트.
	 */
	static RobotTwinClient connect(String[] args) {
		RobotTwinClient.Builder builder = RobotTwinClient.builder();
		for ( int i = 0; i + 1 < args.length; ++i ) {
			switch ( args[i] ) {
				case "--url" -> builder.baseUrl(args[++i]);
				case "--twin-id" -> builder.twinId(args[++i]);
				default -> { }
			}
		}
		RobotTwinClient twin = builder.build();
		JsonNode meta = twin.meta();
		System.out.printf("트윈 %s — backend=%s, 실행 모드=%s%n", twin.getTwinUrl(),
							meta.path("backend").asText("?"), meta.path("move_group_mode").asText("?"));
		return twin;
	}

	/**
	 * 접속 인자를 뺀 위치 인자를 반환한다.
	 *
	 * @param args	명령행 인자.
	 * @return	위치 인자.
	 */
	static List<String> positional(String[] args) {
		List<String> rest = new ArrayList<>();
		for ( int i = 0; i < args.length; ++i ) {
			if ( args[i].startsWith("--") ) {
				++i;	// 값을 함께 건너뛴다
			}
			else {
				rest.add(args[i]);
			}
		}
		return rest;
	}

	/**
	 * 트윈이 보고한 {@code ee_pose}를 같은 자세로 다시 명령할 {@code move_linear} 목표로 바꾼다.
	 * <p>
	 * Panda Hand 스택(Isaac)에서는 {@code ee_pose}가 {@code panda_hand}, 명령 기준이 {@code panda_link8}이라
	 * 자세가 z축 45° 다르다({@link PandaHand#armCommandFromArmPose}). 펑션베이는 둘 다 {@code grasp_center}라
	 * 그대로 쓴다. 이 변환을 빼면 Isaac에서 명령마다 손목이 45° 돈다.
	 *
	 * @param twin		트윈 클라이언트(백엔드 확인용).
	 * @param observed	읽은 {@code ee_pose}.
	 * @return	{@code move_linear}에 보낼 목표.
	 */
	static Pose commandFromObserved(RobotTwinClient twin, Pose observed) {
		String backend = twin.meta().path("backend").asText("");
		// panda_mock도 Panda Hand 스택이다(mock 백엔드는 제거 예정).
		boolean pandaHand = backend.equals("panda_isaac") || backend.equals("panda_mock");
		return pandaHand ? PandaHand.armCommandFromArmPose(observed) : observed;
	}

	static String fmt(Position p) {
		return String.format("(%+.4f, %+.4f, %+.4f)", p.x(), p.y(), p.z());
	}

	/**
	 * 이동 검증 결과를 출력한다.
	 *
	 * @param label	이동 이름.
	 * @param check	검증 결과.
	 * @return	도달했으면 {@code true}.
	 */
	static boolean report(String label, MoveCheck check) {
		if ( check.goalReached() ) {
			System.out.printf("  %s — 도달 (오차 %.2f mm)%n", label, check.errorM() * 1000);
		}
		else {
			System.out.printf("  %s — 도달하지 못했다: status=%s, failureCode=%s, 오차=%s, why=%s%n", label,
								check.result().status(), check.failureCode(),
								check.errorM() != null ? String.format("%.2f mm", check.errorM() * 1000) : "-",
								check.failureReason());
		}
		return check.goalReached();
	}
}
