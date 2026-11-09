package mdt.robot.client.examples;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToNamedTarget;
import mdt.robot.client.Geometry;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.Ops;
import mdt.robot.client.PandaHand;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MeasureObjectOffset;
import mdt.robot.client.operation.Pick;
import mdt.robot.client.operation.Place;
import mdt.robot.client.operation.ResetScene;
import mdt.robot.client.operation.WaitForFreshVariable;

/**
 * <b>연산 조합 — 장면 재배치 · 집기 · 회전 이송 · 놓기.</b> 로봇과 장면을 움직인다. mock · Isaac 전용.
 * <p>
 * {@code reset_scene} → 첫 물체를 {@link Pick}으로 집어 들고 → 베이스 z축 둘레로 {@code angle}(기본 30°) 돌린 자리에
 * 놓고 → {@code ready} 로 돌아간다. 각 단계의 판정이 서지 않으면 거기서 멈춘다.
 * <ul>
 *   <li>파지 좌표는 리셋 후 갱신을 확인한 관측값에서 만든다. 새 발행도 물리적 정착을 보장하지 않는다.</li>
 *   <li>Isaac에서는 {@link Pick.HoldCheck}로 들기 전후의 물린 위치를 비교해 물체가 따라 올라왔는지 확인하고,
 *       놓친 경우 놓기로 넘어가지 않는다. 놓기 자세는 들어 올린 뒤 잰 물린 위치({@code hold().afterLift()})로
 *       보정해 물체 중심이 목표에 오게 한다.</li>
 *   <li>현재 제공 mock·Isaac의 서버 기본 EE가 panda_link8인 구성을 전제로 한다.
 *       물체에서 만든 손끝(TCP) 목표를 서버 기본 기준점에 맞추기 위해 보내기 직전에만 {@code PandaHand.toArmCommand} 로 바꾼다.
 *       펑션베이 트윈에 쓰면 보정이 이중으로 걸려 약 10 cm 아래를 겨냥하므로 처음에 거른다.</li>
 *   <li>mock 은 물리가 없어 {@code grasp} 판정이 서지 않고 물체가 따라 올라오지도 않는다 — mock 에서는
 *       {@code close} 로 닫기만 하고 유지 확인을 하지 않는다. mock 분기는 mock 백엔드와 함께 제거할 대상이다.</li>
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
		// mock 분기(제거 예정): 물리가 없어 파지·유지를 판정할 수 없다.
		boolean mock = backend.equals("panda_mock");
		String closeTarget = mock ? "close" : "grasp";

		// 1. 장면 재배치와 관측 갱신. seed=0 기본값은 반복 가능한 요청 배치를 만든다.
		ResetScene.Check reset = new ResetScene(twin, scene, (long)seed, twin.getOperationTimeout(),
				WaitForFreshVariable.Options.DEFAULT).run();
		if ( !reset.goalReached() ) {
			System.out.println("장면 재배치 확인 실패: " + reset.failureReason());
			return;
		}
		JsonNode objects = reset.result().output("objects");
		if ( objects.isEmpty() ) {
			System.out.println("장면 재배치는 완료됐지만 집을 물체가 없다.");
			return;
		}
		WaitForFreshVariable.Result observed = reset.observation().orElseThrow();
		if ( !observed.goalReached() ) {
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

		// 2. Pick은 열기 → 접근 → 하강 → 파지 → (파지 직후 측정) → 들어 올리기 → (들어 올린 뒤 측정)이다.
		//    Isaac Panda Hand의 측정 기준점은 손끝(PANDA_HAND_TCP)이며 명령 좌표 변환은 태스크가 맡는다.
		Pose above = PandaHand.toArmCommand(Geometry.aboveOf(grasp, CLEARANCE_M));
		Pick.HoldCheck holdCheck = mock ? null : Pick.HoldCheck.of(objectName, MeasureObjectOffset.TcpReference.PANDA_HAND_TCP);
		Pick picking = Pick.builder(twin).approachPose(above).graspPose(PandaHand.toArmCommand(grasp)).liftPose(above)
								.holdCheck(holdCheck).options(new Pick.Options(move, move, move, "open", closeTarget)).build();
		Pick.Check pickCheck;
		try { pickCheck = picking.run(); }
		catch ( InterruptedException | ExecutionException | java.util.concurrent.CancellationException e ) {
			Pick.Progress progress = picking.getProgress();
			System.out.printf("  집기 중단: 진행 단계=%s, 파지 시도=%s%n", progress.activeStage().orElse(null),
								progress.graspAttempted());
			throw e;
		}
		if ( !pickCheck.goalReached() ) {
			// 유지 판정 실패는 그리퍼를 닫은 채 들어 올린 자세에서 멈춘다. 자동 개방·재시도는 하지 않는다.
			System.out.printf("  집기 확인 실패: %s (유지 판정=%s) — %s%n", pickCheck.failedStage(),
								pickCheck.holdVerdict(), pickCheck.failureReason());
			return;
		}
		Pose placeTcp = target;
		if ( pickCheck.hold().isPresent() ) {
			// 물린 위치(물체 중심 - 손끝)를 손과 함께 돌려, 놓았을 때 물체 중심이 목표에 오도록 손끝 목표를 옮긴다.
			Pick.Hold hold = pickCheck.hold().get();
			Position offset = Geometry.rotatedAboutBaseZ(new Pose(hold.afterLift().offset(), Geometry.DOWN), angle).position();
			placeTcp = target.withPosition(new Position(target.position().x() - offset.x(),
														target.position().y() - offset.y(),
														target.position().z() - offset.z()));
			System.out.printf("  집기 완료: 들기 전후 물린 위치 차이 %.1f mm, 물린 위치 %s%n", hold.driftM() * 1000,
								ExampleSupport.fmt(hold.afterLift().offset()));
		}
		else {
			System.out.println("  집기까지 단계별 목표 확인 완료 (mock: 유지 확인 없음)");
		}

		// 3. Place — 접근 → 하강 → 개방 확인 → 후퇴. 개방 이후의 실패를 구분한다.
		Pose aboveTarget = PandaHand.toArmCommand(Geometry.aboveOf(placeTcp, CLEARANCE_M));
		Place placing = Place.builder(twin).approachPose(aboveTarget).placePose(PandaHand.toArmCommand(placeTcp))
				.options(new Place.Options(move, move, move, "open")).build();   // 후퇴는 approachPose로
		Place.Check placeCheck;
		try { placeCheck = placing.run(); }
		catch ( InterruptedException | ExecutionException | java.util.concurrent.CancellationException e ) {
			Place.Progress progress = placing.getProgress();
			System.out.printf("  배치 중단: 개방 시도=%s, 개방 목표 확인=%s, 후퇴 도달=%s%n",
					progress.releaseAttempted(), progress.releaseConfirmed(), progress.retreatReached());
			throw e;
		}
		if ( !placeCheck.goalReached() ) {
			System.out.printf("  배치 단계 확인 실패: %s — %s (개방 시도=%s, 개방 목표 확인=%s, 후퇴 도달=%s)%n",
					placeCheck.failedStage(), placeCheck.failureReason(), placeCheck.releaseAttempted(),
					placeCheck.releaseConfirmed(), placeCheck.retreatReached());
			return;
		}

		// 4. 복귀 — 실패해도 이미 놓은 결과를 뒤집지 않는다.
		ClosedLoopCheck home = Ops.moveToNamedTargetVerified(twin, "ready");
		System.out.println("  ready 복귀: " + (home.goalReached() ? "도달" : home.failureReason()));
		// 물체가 실제로 목표에 놓였는지는 scene_objects 로 따로 확인한다.
		System.out.println("  놓인 뒤 물체: " + twin.read("scene_objects").path("objects")
														.path(objectName).path("pose").path("position"));
	}
}
