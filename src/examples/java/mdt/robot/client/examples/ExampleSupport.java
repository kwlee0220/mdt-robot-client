package mdt.robot.client.examples;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;

/**
 * 예제들이 함께 쓰는 접속 · 좌표 도우미.
 * <p>
 * 접속 인자는 {@code --url <트윈 서버 루트 URL> --twin-id <id>} 이며 모두 생략할 수 있다(기본
 * {@code http://127.0.0.1:8801 / panda01}). 나머지 인자는 예제마다 뜻이 다른 위치 인자다.
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
		if ( check.reached() ) {
			System.out.printf("  %s — 도달 (오차 %.2f mm)%n", label, check.errorM() * 1000);
		}
		else {
			System.out.printf("  %s — 도달하지 못했다: status=%s, reason=%s, 오차=%s, why=%s%n", label,
								check.result().status(), check.reason(),
								check.errorM() != null ? String.format("%.2f mm", check.errorM() * 1000) : "-",
								check.why());
		}
		return check.reached();
	}
}
