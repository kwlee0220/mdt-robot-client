package mdt.robot.client.examples;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.MoveToNamedTarget;
import mdt.robot.client.Geometry;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.Ops;
import mdt.robot.client.PandaHand;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.Grasp;
import mdt.robot.client.operation.Place;
import mdt.robot.client.operation.ResetScene;
import mdt.robot.client.operation.WaitForFreshVariable;

/**
 * <b>연산 조합 — 장면 재배치 · 집기 · 회전 이송 · 놓기.</b> 로봇과 장면을 움직인다. mock · Isaac 전용.
 * <p>
 * {@code reset_scene} → 첫 물체를 위에서 집어 → 베이스 z축 둘레로 {@code angle}(기본 30°) 돌린 자리에
 * 놓고 → {@code ready} 로 돌아간다. 각 단계의 판정이 서지 않으면 거기서 멈춘다.
 * <ul>
 *   <li>파지 좌표는 리셋 후 갱신을 확인한 관측값에서 만든다. 새 발행도 물리적 정착을 보장하지 않는다.</li>
 *   <li>현재 제공 mock·Isaac의 서버 기본 EE가 panda_link8인 구성을 전제로 한다.
 *       물체에서 만든 손끝(TCP) 목표를 서버 기본 기준점에 맞추기 위해 보내기 직전에만 {@code PandaHand.toArmCommand} 로 바꾼다.
 *       펑션베이 트윈에 쓰면 보정이 이중으로 걸려 약 10 cm 아래를 겨냥하므로 처음에 거른다.</li>
 *   <li>mock 은 물리가 없어 {@code grasp} 판정이 서지 않는다 — mock 에서는 {@code close} 로 닫기만 한다.</li>
 * </ul>
 *
 * <pre>
 *   gradle runExample -Pexample=PickAndPlaceExample                         # mock: one_cube
 *   gradle runExample -Pexample=PickAndPlaceExample --args='two_objects 7 45' # Isaac: 레시피 seed 각도
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class PickAndPlaceExample {
	private static final double CLEARANCE_M = 0.10;

	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		String backend = twin.requireBackend("pick-and-place (Panda Hand correction)", "panda_mock", "panda_isaac");
		List<String> rest = ExampleSupport.positional(args);
		String scene = rest.size() > 0 ? rest.get(0) : (backend.equals("panda_mock") ? "one_cube" : "two_objects");
		int seed = rest.size() > 1 ? Integer.parseInt(rest.get(1)) : 0;
		double angle = rest.size() > 2 ? Double.parseDouble(rest.get(2)) : 30.0;
		String closeTarget = backend.equals("panda_mock") ? "close" : "grasp";

		// 1. 장면 재배치와 관측 갱신. seed=0 기본값은 반복 가능한 요청 배치를 만든다.
		ResetScene.Check reset = new ResetScene(twin, scene, (long)seed, twin.getOperationTimeout(),
				WaitForFreshVariable.Options.DEFAULT).run();
		if ( !reset.applied() ) {
			System.out.println("장면 재배치 확인 실패: " + reset.why());
			return;
		}
		JsonNode objects = reset.result().output("objects");
		if ( objects.isEmpty() ) {
			System.out.println("장면 재배치는 완료됐지만 집을 물체가 없다.");
			return;
		}
		WaitForFreshVariable.Result observed = reset.observation().orElseThrow();
		if ( !observed.fresh() ) {
			System.out.println("관측 갱신 확인 불가: " + observed.summary());
			return;
		}
		String objectName = objects.get(0).path("name").asText();
		JsonNode object = observed.sample().orElseThrow().path("value").path("objects").path(objectName);
		for ( String axis : new String[] { "x", "y", "z" } ) {
			JsonNode coordinate = object.path("pose").path("position").path(axis);
			if ( !coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue()) ) {
				System.out.println("관측값에 대상 물체의 유효한 위치가 없다: " + objectName);
				return;
			}
		}
		Pose grasp = Geometry.graspPoseOf(object);
		Pose target = Geometry.rotatedAboutBaseZ(grasp, angle);
		System.out.printf("%s 를 %s 에서 집어 %.0f° 돌린 %s 에 놓는다%n", objectName,
							ExampleSupport.fmt(grasp.position()), angle, ExampleSupport.fmt(target.position()));

		MoveLinear.Options move = MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10));

		// 2. Grasp는 열기 → 접근 → 하강 → 파지 확인까지. 좌표 변환과 이후 상승은 태스크가 맡는다.
		Grasp operation = new Grasp(twin,
				PandaHand.toArmCommand(Geometry.aboveOf(grasp, CLEARANCE_M)),
				PandaHand.toArmCommand(grasp), new Grasp.Options(move, move, "open", closeTarget));
		Grasp.Check graspCheck = operation.run();
		if ( !graspCheck.goalsReached() ) {
			System.out.printf("  파지 단계 확인 실패: %s — %s%n", graspCheck.failedStage(), graspCheck.why());
			return;
		}
		System.out.println("  파지까지 단계별 목표 확인 완료 (실제 물체 파지 검증은 별도)");
		if ( !moveTo(twin, "상승", Geometry.aboveOf(grasp, CLEARANCE_M), move) ) {
			return;
		}

		// 3. Place — 접근 → 하강 → 개방 확인 → 후퇴. 개방 이후의 실패를 구분한다.
		Pose aboveTarget = PandaHand.toArmCommand(Geometry.aboveOf(target, CLEARANCE_M));
		Place placing = new Place(twin, aboveTarget, PandaHand.toArmCommand(target), aboveTarget,
				new Place.Options(move, move, move, "open"));
		Place.Check placeCheck;
		try { placeCheck = placing.run(); }
		catch ( InterruptedException | ExecutionException | java.util.concurrent.CancellationException e ) {
			Place.Progress progress = placing.getProgress();
			System.out.printf("  배치 중단: 개방 시도=%s, 개방 목표 확인=%s, 후퇴 도달=%s%n",
					progress.releaseAttempted(), progress.releaseConfirmed(), progress.retreatReached());
			throw e;
		}
		if ( !placeCheck.goalsReached() ) {
			System.out.printf("  배치 단계 확인 실패: %s — %s (개방 시도=%s, 개방 목표 확인=%s, 후퇴 도달=%s)%n",
					placeCheck.failedStage(), placeCheck.why(), placeCheck.releaseAttempted(),
					placeCheck.releaseConfirmed(), placeCheck.retreatReached());
			return;
		}

		// 4. 복귀 — 실패해도 이미 놓은 결과를 뒤집지 않는다.
		MoveToNamedTarget.Check home = Ops.moveToNamedTargetVerified(twin, "ready");
		System.out.println("  ready 복귀: " + (home.reached() ? "도달" : home.why()));
		// 물체가 실제로 목표에 놓였는지는 scene_objects 로 따로 확인한다.
		System.out.println("  놓인 뒤 물체: " + twin.read("scene_objects").path("objects")
														.path(objectName).path("pose").path("position"));
	}

	private static boolean moveTo(RobotTwinClient twin, String label, Pose tcpTarget, MoveLinear.Options options)
			throws InterruptedException, ExecutionException {
		return ExampleSupport.report(label, new MoveLinear(twin, PandaHand.toArmCommand(tcpTarget), options).run());
	}
}
