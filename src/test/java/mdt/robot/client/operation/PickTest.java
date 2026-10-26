package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
			"open", "grasp", new MeasureObjectOffset.Options(Duration.ofMillis(300), Duration.ofMillis(100), Duration.ofMillis(10)),
			0.01);

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
	private final List<JsonNode> m_bodies = new ArrayList<>();

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync").operation("move_gripper_to_target", "sync");
		server.on("POST", GRIPPER, this::gripper).on("POST", MOVE, this::move);
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, batch()));
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void withoutObjectNameRunsFiveStagesWithoutObserving() throws Exception {
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, LIFT, null,
									MeasureObjectOffset.TcpReference.AS_REPORTED, FAST).run();
		assertTrue(check.goalsReached());
		assertTrue(check.graspAttempted());
		assertNull(check.failedStage());
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
		Pick.Check check = pick().run();
		assertTrue(check.goalsReached(), check.why());
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
		assertTrue(loose.goalsReached());
		assertEquals(0.003, loose.hold().orElseThrow().driftM(), 1e-9);

		synchronized ( this ) { reset(); m_slip = 0.003; }
		Pick.Check strict = new Pick(server.client(), ABOVE, TARGET, LIFT, "box", MeasureObjectOffset.TcpReference.AS_REPORTED,
									FAST.withHoldTolerance(0.002)).run();
		assertFalse(strict.goalsReached());
		assertEquals(Pick.Stage.MEASURE_HELD, strict.failedStage());
		assertTrue(strict.why().contains("3.0 mm (허용 2.0 mm)"), strict.why());
	}

	@Test void objectLeftBehindFailsAtMeasureHeld() throws Exception {
		synchronized ( this ) { m_attach = false; }
		Pick.Check check = pick().run();
		assertFalse(check.goalsReached());
		assertTrue(check.graspAttempted());
		assertEquals(Pick.Stage.MEASURE_HELD, check.failedStage());
		assertTrue(check.why().startsWith("물체가 그리퍼와 함께 움직이지 않았다"), check.why());
		assertTrue(check.steps().stream().allMatch(StepResult::goalReached), "each step reached its own goal");
		// 물체가 탁자에 남으면 물린 위치가 들어 올린 높이만큼 벌어진다.
		assertEquals(LIFT.position().z() - TARGET.position().z(), check.hold().orElseThrow().driftM(), 1e-9);
	}

	@Test void unconfirmedGraspStopsBeforeMeasuringOrLifting() throws Exception {
		synchronized ( this ) { m_failAt = "grasp"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalsReached());
		assertTrue(check.graspAttempted());
		assertEquals(Pick.Stage.GRASP, check.failedStage());
		assertEquals(4, check.steps().size());
		assertTrue(check.hold().isEmpty());
		assertEquals(4, server.requests("POST").size());
		assertTrue(server.requests("GET /variables").isEmpty());
	}

	@Test void unreachedLiftStopsBeforeSecondMeasurement() throws Exception {
		synchronized ( this ) { m_failAt = "lift"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalsReached());
		assertEquals(Pick.Stage.LIFT, check.failedStage());
		assertEquals(List.of(Pick.Stage.OPEN, Pick.Stage.APPROACH, Pick.Stage.DESCEND, Pick.Stage.GRASP,
							Pick.Stage.MEASURE_GRIP, Pick.Stage.LIFT), stages(check));
		assertTrue(check.hold().isEmpty());
	}

	@Test void unavailableMeasurementStopsBeforeLifting() throws Exception {
		synchronized ( this ) { m_sceneQuality = "STALE"; }
		Pick.Check check = pick().run();
		assertFalse(check.goalsReached());
		assertEquals(Pick.Stage.MEASURE_GRIP, check.failedStage());
		assertTrue(check.why().startsWith("물체·도구 기준점 측정 불가"), check.why());
		assertEquals(4, server.requests("POST").size(), "no lift without a grip measurement");
	}

	@Test void missingObjectStopsAtFirstMeasurement() throws Exception {
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, LIFT, "other", MeasureObjectOffset.TcpReference.AS_REPORTED,
									FAST).run();
		assertEquals(Pick.Stage.MEASURE_GRIP, check.failedStage());
		assertEquals(MeasureObjectOffset.Rejection.OBJECT_MISSING,
					check.steps().get(4).measurement().diagnostics().lastRejection());
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

	@Test void liftShorterThanTwiceHoldToleranceIsRejectedWhenMeasuring() {
		var twin = server.client();
		var ref = MeasureObjectOffset.TcpReference.AS_REPORTED;
		Pose low = TARGET.withPosition(TARGET.position().plus(0, 0, 0.005));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new Pick(twin, ABOVE, TARGET, low, "box", ref, FAST));
		assertTrue(e.getMessage().startsWith("liftPose must be at least 20.0 mm"), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, TARGET, "box", ref, FAST));
		new Pick(twin, ABOVE, TARGET, low, null, ref, FAST);		// 재지 않으면 상승 거리를 제한하지 않는다.
		new Pick(twin, ABOVE, TARGET, TARGET.withPosition(TARGET.position().plus(0, 0, 0.02)), "box", ref, FAST);
		assertTrue(server.requests.isEmpty());
	}

	@Test void staleObservationAfterLiftCannotConfirmHold() throws Exception {
		// 물체가 탁자에 남았는데, 상승 뒤에도 상승 전 관측 쌍이 다시 온다.
		synchronized ( this ) { m_attach = false; m_replay = true; }
		Pick.Check check = pick().run();
		assertFalse(check.goalsReached());
		assertEquals(Pick.Stage.MEASURE_HELD, check.failedStage());
		assertTrue(check.why().startsWith("유지 판정 불가"), check.why());
		Pick.Hold hold = check.hold().orElseThrow();
		assertEquals(0, hold.liftM(), 1e-12);
		assertEquals(0, hold.driftM(), 1e-12, "the stale pair alone would have looked like a hold");
	}

	@Test void wristRotationDuringLiftKeepsRigidHold() throws Exception {
		// x축으로 30도 기울여 들어 올린다. 물린 위치를 기준 좌표계에서 비교하면 약 15 mm 달라진다.
		double half = Math.toRadians(30) / 2;
		Quaternion tilted = new Quaternion(Math.sin(half), 0, 0, Math.cos(half)).multiply(Geometry.DOWN);
		Pose lift = new Pose(LIFT.position(), tilted);
		Pick.Check check = new Pick(server.client(), ABOVE, TARGET, lift, "box", MeasureObjectOffset.TcpReference.AS_REPORTED,
									FAST).run();
		assertTrue(check.goalsReached(), check.why());
		Pick.Hold hold = check.hold().orElseThrow();
		assertEquals(0, hold.driftM(), 1e-9);
		assertEquals(LIFT.position().z() - TARGET.position().z(), hold.liftM(), 1e-9);
		Position b = hold.beforeLift().offset(), a = hold.afterLift().offset();
		double baseDrift = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
		assertTrue(baseDrift > FAST.holdToleranceM(), "base-frame drift " + baseDrift);
	}

	@Test void invalidInputsAreRejectedBeforeCommunication() {
		var twin = server.client();
		var ref = MeasureObjectOffset.TcpReference.AS_REPORTED;
		Pose nan = ABOVE.withPosition(new Position(Double.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, nan));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, nan, TARGET, LIFT));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, LIFT, " box", ref, FAST));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, LIFT, "box", null, FAST));
		assertThrows(IllegalArgumentException.class, () -> new Pick(twin, ABOVE, TARGET, LIFT, "box", ref, FAST, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> FAST.withHoldTolerance(0));
		assertThrows(IllegalArgumentException.class, () -> FAST.withHoldTolerance(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> FAST.withLift(null));
		assertTrue(server.requests.isEmpty());
	}

	@Test void defaultOptionsSettleAfterEachMove() {
		assertEquals(Duration.ofSeconds(10), Pick.Options.DEFAULT.lift().settleTimeout());
		assertEquals("grasp", Pick.Options.DEFAULT.graspTarget());
		assertEquals(0.01, Pick.Options.DEFAULT.holdToleranceM());
	}

	private Pick pick() {
		return new Pick(server.client(), ABOVE, TARGET, LIFT, "box", MeasureObjectOffset.TcpReference.AS_REPORTED, FAST);
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
		m_tcp = new double[] { p.path("x").asDouble(), p.path("y").asDouble(), p.path("z").asDouble() };
		JsonNode o = pose.path("orientation");
		m_q = new Quaternion(o.path("x").asDouble(), o.path("y").asDouble(), o.path("z").asDouble(), o.path("w").asDouble());
		if ( m_attached ) {
			m_lifted = true;
			if ( "lift".equals(m_failAt) ) {
				return completed(Map.of("final_pose", TARGET.withPosition(new Position(9, 9, 9))));
			}
		}
		return completed(Map.of("final_pose", pose));
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
