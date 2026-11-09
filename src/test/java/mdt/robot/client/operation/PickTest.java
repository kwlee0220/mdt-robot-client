package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.TwinException;

/**
 * 가짜 트윈의 단순한 물리 모형으로 들기 전후 유지 판정을 검증한다.
 * <p>
 * 물체 중심은 처음에 파지 자세 + HELD에 있다. 파지 시 붙으면(m_attach) 이후 도구 기준점 + 도구 자세로 돌린 HELD_TOOL
 * + slip을 따라가고, 붙지 않으면 그 자리에 남는다. slip은 들어 올린 뒤에만 더한다. m_replay이면 첫 관측 쌍을 계속 돌려준다.
 */
@Timeout(10)
class PickTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String GRIPPER = "/operations/move_gripper_to_target";
	private static final String MOVE = "/operations/move_linear";
	private static final String BATCH = "/variables?names=ee_pose,scene_objects";
	private static final Pose ABOVE = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
	private static final Pose TARGET = ABOVE.withPosition(new Position(0.4, 0.1, 0.1));
	private static final Pose LIFT = ABOVE.withPosition(new Position(0.4, 0.1, 0.25));
	/** 물린 위치: 물체 중심 - 도구 기준점. */
	private static final double[] HELD = { 0.001, -0.002, -0.03 };
	/** 같은 물린 위치를 도구 좌표계로 표시한 값. 아래를 보는 자세(DOWN)로 돌리면 HELD가 된다. */
	private static final double[] HELD_TOOL = { 0.001, 0.002, 0.03 };
	private static final Pick.Options FAST = new Pick.Options(MoveLinear.Options.DEFAULT,
			MoveLinear.Options.DEFAULT.withVelocityScaling(0.1), MoveLinear.Options.DEFAULT.withVelocityScaling(0.15),
			"open", "grasp");
	private static final Pick.HoldCheck BOX = Pick.HoldCheck.of("box", MeasureObjectOffset.TcpReference.AS_REPORTED)
			.withMeasure(new MeasureObjectOffset.Options(Duration.ofMillis(300), Duration.ofMillis(100), Duration.ofMillis(10)));

	private FakeTwinServer server;
	// 아래 모형 상태는 this로 보호한다.
	private double[] m_tcp = { 0.3, 0.0, 0.4 };
	private Quaternion m_q = Geometry.DOWN;
	private boolean m_replay;
	private Map<String,Object> m_firstBatch;
	private double[] m_object = { TARGET.position().x() + HELD[0], TARGET.position().y() + HELD[1],
								TARGET.position().z() + HELD[2] };
	private boolean m_attach = true;
	private boolean m_attached;
	private boolean m_lifted;
	private double m_slip;
	private String m_sceneQuality = "OK";
	private String m_failAt;
	/** 이동이 목표 앞에서 멈추는 거리(m). 허용 오차보다 작으면 도달로 판정된다. */
	private double m_shortfall;
	private final List<JsonNode> m_bodies = new ArrayList<>();

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync").operation("move_gripper_to_target", "sync");
		server.on("POST", GRIPPER, this::gripper).on("POST", MOVE, this::move);
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, batch()));
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void withoutObjectNameRunsFiveStagesWithoutObserving() throws Exception {
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, LIFT, null, FAST).run();
		assertTrue(check.goalReached());
		assertTrue(check.graspAttempted());
		assertNull(check.failedStage());
		assertEquals(Pick.HoldVerdict.NOT_CHECKED, check.holdVerdict());
		assertTrue(check.hold().isEmpty());
		assertEquals(List.of(Pick.Stage.OPEN, Pick.Stage.APPROACH, Pick.Stage.DESCEND, Pick.Stage.GRASP, Pick.Stage.LIFT),
						stages(check));
		synchronized ( this ) {
			assertEquals(5, m_bodies.size());
			assertEquals(LIFT.toJson(), m_bodies.get(4).get("pose"));
			assertEquals(0.15, m_bodies.get(4).path("velocity_scaling").asDouble());
		}
		assertTrue(server.requests("GET /variables").isEmpty());
	}

	@Test void heldObjectPassesHoldCheck() throws Exception {
		Pick pick = pick();
		Pick.Check check = pick.run();
		assertTrue(check.goalReached(), check.failureReason());
		assertEquals(Pick.HoldVerdict.HELD, check.holdVerdict());
		assertEquals(Pick.HoldVerdict.HELD, pick.getProgress().holdVerdict());
		assertEquals(List.of(Pick.Stage.values()), stages(check));
		Pick.Hold hold = check.hold().orElseThrow();
		assertEquals(0, hold.driftM(), 1e-9);
		assertEquals(HELD[2], hold.afterLift().offset().z(), 1e-9);
		assertEquals(TARGET.position().z(), hold.beforeLift().tcpPose().position().z(), 1e-12);
		assertEquals(LIFT.position().z(), hold.afterLift().tcpPose().position().z(), 1e-12);
		assertEquals(5, server.requests("POST").size(), "measurements do not command motion");
	}

	@Test void slipWithinToleranceIsAcceptedAndBeyondIsRejected() throws Exception {
		synchronized ( this ) { m_slip = 0.003; }
		Pick.Check loose = pick().run();
		assertTrue(loose.goalReached());
		assertEquals(0.003, loose.hold().orElseThrow().driftM(), 1e-9);

		synchronized ( this ) { reset(); m_slip = 0.003; }
		Pick.Check strict = new Pick(server.client(), ABOVE, TARGET, LIFT, BOX.withTolerance(0.002), FAST).run();
		assertFalse(strict.goalReached());
		assertEquals(Pick.HoldVerdict.NOT_HELD, strict.holdVerdict());
		assertEquals(Pick.Stage.MEASURE_HELD, strict.failedStage());
		assertTrue(strict.failureReason().contains("3.0 mm (허용 2.0 mm)"), strict.failureReason());
	}

	@Test void objectLeftBehindFailsAtMeasureHeld() throws Exception {
		synchronized ( this ) { m_attach = false; }
		Pick pick = pick();
		Pick.Check check = pick.run();
		assertFalse(check.goalReached());
		assertTrue(check.graspAttempted());
		assertEquals(Pick.Stage.MEASURE_HELD, check.failedStage());
		assertEquals(Pick.HoldVerdict.NOT_HELD, check.holdVerdict());
		assertTrue(check.failureReason().startsWith("물체가 그리퍼와 함께 움직이지 않았다"), check.failureReason());
		// 단계 기록은 측정 자체의 성공이고, 유지 판정은 진행 스냅샷의 holdVerdict로 읽는다.
		assertTrue(check.steps().stream().allMatch(StepResult::goalReached), "each step reached its own goal");
		assertEquals(Pick.HoldVerdict.NOT_HELD, pick.getProgress().holdVerdict());
		// 물체가 탁자에 남으면 물린 위치가 들어 올린 높이만큼 벌어진다.
		assertEquals(LIFT.position().z() - TARGET.position().z(), check.hold().orElseThrow().driftM(), 1e-9);
	}

	@Test void unconfirmedGraspStopsBeforeMeasuringOrLifting() throws Exception {
		synchronized ( this ) { m_failAt = "grasp"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalReached());
		assertTrue(check.graspAttempted());
		assertEquals(Pick.Stage.GRASP, check.failedStage());
		assertEquals(Pick.HoldVerdict.NOT_CHECKED, check.holdVerdict());
		assertEquals(4, check.steps().size());
		assertTrue(check.hold().isEmpty());
		assertEquals(4, server.requests("POST").size());
		assertTrue(server.requests("GET /variables").isEmpty());
	}

	@Test void unreachedLiftStopsBeforeSecondMeasurement() throws Exception {
		synchronized ( this ) { m_failAt = "lift"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalReached());
		assertEquals(Pick.Stage.LIFT, check.failedStage());
		assertEquals(List.of(Pick.Stage.OPEN, Pick.Stage.APPROACH, Pick.Stage.DESCEND, Pick.Stage.GRASP,
							Pick.Stage.MEASURE_GRIP, Pick.Stage.LIFT), stages(check));
		assertTrue(check.hold().isEmpty());
	}

	@Test void unavailableMeasurementStopsBeforeLifting() throws Exception {
		synchronized ( this ) { m_sceneQuality = "STALE"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalReached());
		assertEquals(Pick.Stage.MEASURE_GRIP, check.failedStage());
		assertTrue(check.failureReason().startsWith("물체·도구 기준점 측정 불가"), check.failureReason());
		assertEquals(4, server.requests("POST").size(), "no lift without a grip measurement");
	}

	@Test void missingObjectStopsAtFirstMeasurement() throws Exception {
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, LIFT,
									Pick.HoldCheck.of("other", MeasureObjectOffset.TcpReference.AS_REPORTED), FAST).run();
		assertEquals(Pick.Stage.MEASURE_GRIP, check.failedStage());
		assertEquals(MeasureObjectOffset.Rejection.OBJECT_MISSING,
					check.steps().get(4).verdictAs(MeasureObjectOffset.Result.class).orElseThrow().diagnostics().lastRejection());
	}

	@Test void rejectionPropagatesAsExecutionException() {
		server.on("POST", MOVE, req -> FakeTwinServer.json(409, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy"))));
		Pick pick = pick();
		ExecutionException failure = assertThrows(ExecutionException.class, pick::run);
		TwinException cause = assertInstanceOf(TwinException.class, failure.getCause());
		assertEquals(409, cause.getHttpStatus());
		Pick.Progress progress = pick.getProgress();
		assertEquals(List.of(Pick.Stage.OPEN, Pick.Stage.APPROACH), progress.steps().stream().map(StepResult::stage).toList());
		assertSame(cause, progress.steps().get(1).failure());
		assertFalse(progress.graspAttempted());
	}

	@Test void liftShorterThanTheMeasurableMinimumIsRejectedWhenMeasuring() {
		// 수직 상승의 하한 = 2 × holdTolerance(10 mm) + descent·lift 허용 오차(각 5 mm) = 30 mm.
		var twin = server.client();
		Pose low = TARGET.withPosition(TARGET.position().plus(0, 0, 0.005));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new Pick(twin, ABOVE, TARGET, low, BOX, FAST));
		assertTrue(e.getMessage().startsWith("liftPose is too close to graspPose"), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, TARGET, BOX, FAST));
		new Pick(twin, ABOVE, TARGET, low, null, FAST);		// 재지 않으면 상승 거리를 제한하지 않는다.
		// 판정 하한(20 mm)만 넘는 명령은 실제 상승이 허용 오차만큼 짧으면 판정할 수 없으므로 거부한다.
		Pose twenty = TARGET.withPosition(TARGET.position().plus(0, 0, 0.02));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, twenty, BOX, FAST));
		new Pick(twin, ABOVE, TARGET, TARGET.withPosition(TARGET.position().plus(0, 0, 0.03)), BOX, FAST);
		assertTrue(server.requests.isEmpty());
	}

	@Test void diagonalLiftMustLeaveTheHoldDistanceAfterPerAxisTolerances() {
		// 도달 판정은 축별 최대 오차라 대각선 끝점은 축마다 5 mm씩 가까워질 수 있다. 30 mm 대각선 명령의 최소 실제 상승은
		// 30 − 2 × 5√3 ≈ 12.7 mm로 판정 하한(20 mm)보다 짧으므로 거부한다.
		var twin = server.client();
		double c30 = 0.03 / Math.sqrt(3);
		Pose diagonal30 = TARGET.withPosition(TARGET.position().plus(c30, c30, c30));
		assertEquals(0.03, Pick.shortestReachedDistance(TARGET.position(), diagonal30.position(), 0) , 1e-12);
		assertEquals(0.03 - 2 * 0.005 * Math.sqrt(3),
					Pick.shortestReachedDistance(TARGET.position(), diagonal30.position(), 0.01), 1e-12);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new Pick(twin, ABOVE, TARGET, diagonal30, BOX, FAST));
		assertTrue(e.getMessage().contains("as short as 12.7 mm"), e.getMessage());
		// 축마다 22 mm(합 38.1 mm) 움직이면 각 축 12 mm가 남아 최소 20.8 mm다.
		new Pick(twin, ABOVE, TARGET, TARGET.withPosition(TARGET.position().plus(0.022, 0.022, 0.022)), BOX, FAST);
		assertTrue(server.requests.isEmpty());
	}

	@Test void staleObservationAfterLiftCannotConfirmHold() throws Exception {
		// 물체가 탁자에 남았는데, 상승 뒤에도 상승 전 관측 쌍이 다시 온다.
		synchronized ( this ) { m_attach = false; m_replay = true; }
		Pick.Check check = pick().run();
		assertFalse(check.goalReached());
		assertEquals(Pick.Stage.MEASURE_HELD, check.failedStage());
		assertEquals(Pick.HoldVerdict.UNDETERMINED, check.holdVerdict());
		assertTrue(check.failureReason().startsWith("유지 판정 불가"), check.failureReason());
		Pick.Hold hold = check.hold().orElseThrow();
		assertEquals(0, hold.liftM(), 1e-12);
		assertEquals(0, hold.driftM(), 1e-12, "the stale pair alone would have looked like a hold");
	}

	@Test void wristRotationDuringLiftKeepsRigidHold() throws Exception {
		// x축으로 30도 기울여 들어 올린다. 물린 위치를 기준 좌표계에서 비교하면 약 15 mm 달라진다.
		double half = Math.toRadians(30) / 2;
		Quaternion tilted = new Quaternion(Math.sin(half), 0, 0, Math.cos(half)).multiply(Geometry.DOWN);
		Pose lift = new Pose(LIFT.position(), tilted);
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, lift, BOX, FAST).run();
		assertTrue(check.goalReached(), check.failureReason());
		Pick.Hold hold = check.hold().orElseThrow();
		assertEquals(0, hold.driftM(), 1e-9);
		assertEquals(LIFT.position().z() - TARGET.position().z(), hold.liftM(), 1e-9);
		Position b = hold.beforeLift().offset(), a = hold.afterLift().offset();
		double baseDrift = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
		assertTrue(baseDrift > BOX.toleranceM(), "base-frame drift " + baseDrift);
	}

	@Test void invalidInputsAreRejectedBeforeCommunication() {
		var twin = server.client();
		var ref = MeasureObjectOffset.TcpReference.AS_REPORTED;
		Pose nan = ABOVE.withPosition(new Position(Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, nan));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, nan, TARGET, LIFT));
		assertThrows(IllegalArgumentException.class, () -> Pick.HoldCheck.of(" box", ref));
		assertThrows(IllegalArgumentException.class, () -> Pick.HoldCheck.of("box", null));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, LIFT, BOX, null));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, LIFT, BOX, FAST, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> BOX.withTolerance(0));
		assertThrows(IllegalArgumentException.class, () -> BOX.withTolerance(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> BOX.withMeasure(null));
		assertThrows(IllegalArgumentException.class, () -> FAST.withLift(null));
		assertTrue(server.requests.isEmpty());
	}

	@Test void defaultOptionsSettleAfterEachMove() {
		assertEquals(Duration.ofSeconds(10), Pick.Options.DEFAULT.lift().settleTimeout());
		assertEquals("grasp", Pick.Options.DEFAULT.graspTarget());
		assertEquals(0.01, Pick.HoldCheck.of("box", MeasureObjectOffset.TcpReference.AS_REPORTED).toleranceM());
	}

	@Test void minimumCommandedLiftStillMeasuresEnoughWhenBothMovesStopShort() throws Exception {
		// 명령 30 mm, 하강·상승이 각각 허용 오차(5 mm) 안에서 4.9 mm씩 덜 가면 실제 상승은 20.2 mm로
		// 판정 하한(20 mm)을 넘는다.
		synchronized ( this ) { m_shortfall = 0.0049; }
		Pose lift = TARGET.withPosition(TARGET.position().plus(0, 0, 0.03));
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, lift, BOX, FAST).run();
		assertTrue(check.goalReached(), check.failureReason());
		assertEquals(Pick.HoldVerdict.HELD, check.holdVerdict());
		assertEquals(0.0202, check.hold().orElseThrow().liftM(), 1e-9);
	}

	@Test void cancellationDuringGraspStopsBeforeMeasuring() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", GRIPPER, req -> {
			if ( req.getBody().clone().readUtf8().contains("\"grasp\"") ) { entered.countDown(); await(release); }
			return gripper(req);
		});
		Pick pick = pick();
		pick.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(Pick.Stage.GRASP, pick.getProgress().activeStage().orElseThrow());
			assertTrue(pick.getProgress().graspAttempted());
			assertTrue(pick.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> pick.get(3, TimeUnit.SECONDS));
			Pick.Progress progress = pick.getProgress();
			assertEquals(4, progress.steps().size(), "the grasp step completes and is recorded");
			assertEquals(Pick.HoldVerdict.NOT_CHECKED, progress.holdVerdict());
			assertTrue(server.requests("GET /variables").isEmpty(), "no measurement after cancellation");
			assertEquals(4, server.requests("POST").size(), "no lift after cancellation");
		}
		finally { release.countDown(); }
	}

	@Test void cancellationDuringGripMeasurementStopsBeforeLifting() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", BATCH, req -> { entered.countDown(); await(release); return FakeTwinServer.json(200, batch()); });
		Pick pick = pick();
		pick.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(Pick.Stage.MEASURE_GRIP, pick.getProgress().activeStage().orElseThrow());
			assertTrue(pick.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> pick.get(3, TimeUnit.SECONDS));
			Pick.Progress progress = pick.getProgress();
			assertTrue(progress.graspAttempted());
			assertEquals(Pick.HoldVerdict.NOT_CHECKED, progress.holdVerdict());
			assertEquals(4, server.requests("POST").size(), "no lift after cancellation");
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	@Test void cancellationDuringLiftSettlingStopsBeforeSecondMeasurement() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", "/variables/ee_pose", req -> {
			entered.countDown(); await(release);
			return FakeTwinServer.json(200, Map.of("quality", "STALE"));
		});
		Pick pick = new Pick(server.client(), ABOVE, TARGET, LIFT, BOX,
							FAST.withLift(FAST.lift().withSettleTimeout(Duration.ofSeconds(3))));
		pick.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(Pick.Stage.LIFT, pick.getProgress().activeStage().orElseThrow());
			assertTrue(pick.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> pick.get(3, TimeUnit.SECONDS));
			Pick.Progress progress = pick.getProgress();
			StepResult<Pick.Stage> lift = progress.steps().get(progress.steps().size() - 1);
			assertEquals(Pick.Stage.LIFT, lift.stage());
			assertTrue(lift.remoteResult().orElseThrow().isCompleted(), "the remote lift result is kept");
			assertEquals(Pick.HoldVerdict.NOT_CHECKED, progress.holdVerdict());
			assertEquals(1, server.requests("GET /variables?").size(), "only the grip measurement ran");
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	private static void await(CountDownLatch latch) {
		try { latch.await(5, TimeUnit.SECONDS); }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
	}

	@Test void builderNamesEachPoseAndDefaultsLiftToApproach() throws Exception {
		var twin = server.client();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Pick.builder(twin).approachPose(ABOVE).build());
		assertEquals("graspPose is not set", e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> Pick.builder(twin).graspPose(TARGET).build());
		// 생성자와 같은 검증을 build()에서 한다.
		assertThrows(IllegalArgumentException.class,
				() -> Pick.builder(twin).approachPose(ABOVE).graspPose(TARGET).liftPose(TARGET).holdCheck(BOX).build());
		assertTrue(server.requests.isEmpty());

		Pick.Check check = Pick.builder(twin).approachPose(ABOVE).graspPose(TARGET).options(FAST).build().run();
		assertTrue(check.goalReached(), check.failureReason());
		synchronized ( this ) {
			assertEquals(ABOVE.toJson(), m_bodies.get(4).get("pose"), "liftPose defaults to approachPose");
		}
	}

	@Test void builderWithHoldCheckJudgesTheHold() throws Exception {
		Pick.Check check = Pick.builder(server.client()).approachPose(ABOVE).graspPose(TARGET).liftPose(LIFT)
								.holdCheck(BOX).options(FAST).timeout(Duration.ofSeconds(5)).build().run();
		assertEquals(Pick.HoldVerdict.HELD, check.holdVerdict());
	}

	private Pick pick() {
		return new Pick(server.client(), ABOVE, TARGET, LIFT, BOX, FAST);
	}

	private void reset() {
		m_object = new double[] { TARGET.position().x() + HELD[0], TARGET.position().y() + HELD[1], TARGET.position().z() + HELD[2] };
		m_attached = false;
		m_lifted = false;
		m_firstBatch = null;
		m_bodies.clear();
	}

	private synchronized MockResponse gripper(RecordedRequest req) {
		JsonNode inputs = inputs(req);
		m_bodies.add(inputs);
		boolean grasp = "grasp".equals(inputs.path("target").asText());
		if ( grasp && "grasp".equals(m_failAt) ) { return completed(Map.of("at_goal", false, "stalled", false)); }
		if ( grasp ) { m_attached = m_attach; }
		return completed(Map.of("at_goal", true, "stalled", grasp));
	}

	private synchronized MockResponse move(RecordedRequest req) {
		JsonNode inputs = inputs(req);
		m_bodies.add(inputs);
		JsonNode pose = inputs.path("pose");
		JsonNode p = pose.path("position");
		double z = p.path("z").asDouble();
		// 수직 이동은 진행 방향의 반대쪽으로 m_shortfall만큼 덜 간다.
		double stopZ = z + Math.signum(m_tcp[2] - z) * Math.min(m_shortfall, Math.abs(m_tcp[2] - z));
		m_tcp = new double[] { p.path("x").asDouble(), p.path("y").asDouble(), stopZ };
		JsonNode o = pose.path("orientation");
		m_q = new Quaternion(o.path("x").asDouble(), o.path("y").asDouble(), o.path("z").asDouble(), o.path("w").asDouble());
		if ( m_attached ) {
			m_lifted = true;
			if ( "lift".equals(m_failAt) ) {
				return completed(Map.of("final_pose", TARGET.withPosition(new Position(9, 9, 9))));
			}
		}
		return completed(Map.of("final_pose", Map.of("position", position(m_tcp), "orientation", pose.path("orientation"))));
	}

	private synchronized Map<String,Object> sceneEnvelope() {
		double[] center = m_object;
		if ( m_attached ) {
			double slip = m_lifted ? m_slip : 0;
			double[] held = m_q.rotate(HELD_TOOL[0], HELD_TOOL[1], HELD_TOOL[2]);
			center = new double[] { m_tcp[0] + held[0] + slip, m_tcp[1] + held[1], m_tcp[2] + held[2] };
		}
		Map<String,Object> box = Map.of("type", "box", "dimensions", List.of(0.04, 0.04, 0.04),
				"pose", Map.of("position", position(center), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		return Map.of("quality", m_sceneQuality, "received_at", Instant.now().toString(),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", Map.of("box", box)));
	}

	private synchronized Map<String,Object> batch() {
		if ( m_replay && m_firstBatch != null ) { return m_firstBatch; }
		Map<String,Object> batch = Map.of("values", Map.of(
				"ee_pose", Map.of("quality", "OK", "received_at", Instant.now().toString(), "value", eeValue()),
				"scene_objects", sceneEnvelope()));
		if ( m_firstBatch == null ) { m_firstBatch = batch; }
		return batch;
	}

	private synchronized Map<String,Object> eeValue() {
		return Map.of("header", Map.of("frame_id", "panda_link0"), "pose", Map.of("position", position(m_tcp),
				"orientation", Map.of("x", m_q.x(), "y", m_q.y(), "z", m_q.z(), "w", m_q.w())));
	}

	private static Map<String,Object> position(double[] p) { return Map.of("x", p[0], "y", p[1], "z", p[2]); }

	private static List<Pick.Stage> stages(Pick.Check check) { return check.steps().stream().map(StepResult::stage).toList(); }

	private static JsonNode inputs(RecordedRequest req) {
		try { return MAPPER.readTree(req.getBody().readUtf8()).path("inputs"); }
		catch ( java.io.IOException e ) { throw new IllegalStateException(e); }
	}

	private static MockResponse completed(Map<String,?> outputs) {
		return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs));
	}
}
