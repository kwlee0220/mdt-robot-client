package mdt.robot.client.examples;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.List;

import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;

/**
 * <b>{@code move_linear} — 직선 이동과 도달 판정.</b> 로봇을 움직인다.
 * <p>
 * 지금 자리에서 위로 {@code dz}(기본 5 cm) 올라갔다가 제자리로 돌아온다. 보이는 것 셋:
 * <ol>
 *   <li><b>서버 기본 EE 기준</b> — ee_pose에서 만든 목표를 그대로 보내며 추가 tip 변환은 하지 않는다.</li>
 *   <li><b>목표는 시작할 때 한 번 읽은 값에서 절대값으로 만든다</b> — 매 이동 뒤 현재 자세를 읽어 다음 목표로
 *       이어 주면 처짐 오차가 쌓인다.</li>
 *   <li><b>{@code COMPLETED} 는 도달이 아니다</b> — {@code MoveLinear} 가 정착을 기다린 뒤
 *       위치를 비교한다. 폐루프 완료 응답은 final_pose로 판정하고, 관측한 경우에만 settledPose를 준다.</li>
 * </ol>
 *
 * <pre>
 *   gradle runExample -Pexample=MoveLinearExample                   # 5 cm
 *   gradle runExample -Pexample=MoveLinearExample --args='0.10'     # 10 cm
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class MoveLinearExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		List<String> rest = ExampleSupport.positional(args);
		double dz = rest.isEmpty() ? 0.05 : Double.parseDouble(rest.get(0));

		if ( !"READY".equals(twin.health().path("move_group").asText()) ) {
			throw new IllegalStateException("MoveGroup is not ready");
		}

		// 시작 자세를 한 번만 읽고, 두 목표를 모두 여기서 절대값으로 만든다.
		Pose start = Pose.fromJson(twin.read("ee_pose"));
		Pose up = start.withPosition(start.position().plus(0.0, 0.0, dz));
		System.out.println("시작 " + ExampleSupport.fmt(start.position()) + " → 위 " + ExampleSupport.fmt(up.position()));

		// 정착 확인 상한 10초 — 고정 대기가 아니라 멈추면 바로 끝난다. 개루프(펑션베이)에서는 필수다.
		MoveLinear.Options options = MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10))
														.withVelocityScaling(0.2);

		MoveCheck check = new MoveLinear(twin, up, options).run();
		if ( !ExampleSupport.report("상승", check) ) {
			// 실패하면 다음 이동을 하지 않는다 — 어디서 멈췄는지 모르는 채 이어 가면 엉뚱한 곳에서 이어진다.
			return;
		}
		// 정착 후 위치가 필요하면 ee_pose 를 다시 읽지 말고 판정에 쓴 값을 쓴다.
		if ( check.settledPose() != null ) {
			System.out.println("  정착 위치 " + ExampleSupport.fmt(Pose.fromJson(check.settledPose()).position()));
		}
		else {
			System.out.println("  폐루프 완료 응답의 final_pose로 판정했다 (추가 정착 관측 생략)");
		}

		check = new MoveLinear(twin, start, options).run();
		ExampleSupport.report("복귀", check);
	}
}
