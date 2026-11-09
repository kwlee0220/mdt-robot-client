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

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;

/**
 * 가짜 트윈의 단순한 물리 모형으로 되먹임 정렬을 검증한다.
 * <p>
 * peg 바닥점 = 현재 도구 기준점 + 파지 오프셋 + 이동마다 정한 흔들림(drift). 따라서 k번째 회차의 오차는
 * |drift[k] - drift[k-1]|이다(x축만 흔든다).
 */
@Timeout(15)
class AlignHeldObjectTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final String BATCH = "/variables?names=ee_pose,scene_objects";
	private static final Position ENTRY = new Position(0.5, -0.1, 0.028);
	private static final double TRAVEL_Z = 0.15;
	private static final Quaternion ORIENTATION = Geometry.downWithYaw(-Math.PI / 2);
	/** 파지 오프셋: 바닥점 - 도구 기준점. */
	private static final double[] HELD = { 0.003, -0.002, -0.054 };
	private static final AlignHeldObject.Options FAST = AlignHeldObject.Options.DEFAULT
			.withCorrection(new MoveLinear.Options(0.005, 0.15, Duration.ZERO))
			.withStill(new WaitUntilObjectStill.Options(Duration.ofSeconds(2), 0.0002, Math.toRadians(0.5),
														Duration.ofMillis(50), Duration.ofMillis(10)))
			.withMeasure(new MeasureObjectOffset.Options(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(10)));

	private FakeTwinServer server;
	// 아래 모형 상태는 this로 보호한다.
	private double[] m_tcp = { 0.49, -0.095, TRAVEL_Z };
	private double[] m_drifts = { 0.0 };
	private int m_moves;
	private double m_miss;
	private String m_quality = "OK";
	private String m_type = "cylinder";
	private final List<JsonNode> m_commands = new ArrayList<>();

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync");
		server.on("GET", "/variables/scene_objects", req -> FakeTwinServer.json(200, sceneEnvelope()));
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, Map.of("values", Map.of(
				"ee_pose", Map.of("quality", "OK", "received_at", Instant.now().toString(), "value", eeValue()),
				"scene_objects", sceneEnvelope()))));
		server.on("POST", "/operations/move_linear", req -> {
			try {
				JsonNode pose = MAPPER.readTree(req.getBody().readUtf8()).path("inputs").path("pose");
				Map<String,Object> reached;
				synchronized ( this ) {
					m_commands.add(pose);
					JsonNode p = pose.path("position");
					m_tcp = new double[] { p.path("x").asDouble() + m_miss, p.path("y").asDouble(), p.path("z").asDouble() };
					++m_moves;
					reached = Map.of("pose", Map.of("position", position(m_tcp), "orientation", pose.path("orientation")));
				}
				return FakeTwinServer.json(200, Map.of("status", "COMPLETED",
						"outputs", Map.of("final_pose", reached, "closed_loop", true)));
			}
			catch ( java.io.IOException e ) { throw new AssertionError(e); }
		});
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void convergesWhenErrorFallsBelowAcceptRadius() throws Exception {
		drifts(0, 0.0003, 0.00045);
		AlignHeldObject.Check check = align(0.0002).run();
		assertTrue(check.goalReached());
		assertEquals(AlignHeldObject.StopReason.ALIGNED, check.stopReason());
		assertErrors(check, 0.0003, 0.00015);
		assertNull(check.failureReason());
		assertEquals(List.of(AlignHeldObject.Stage.SETTLE, AlignHeldObject.Stage.MEASURE, AlignHeldObject.Stage.CORRECT,
							AlignHeldObject.Stage.VERIFY, AlignHeldObject.Stage.MEASURE, AlignHeldObject.Stage.CORRECT,
							AlignHeldObject.Stage.VERIFY), stages(check));

		// 첫 보정 목표 = 도구 기준점 + (입구 - 바닥점) = 입구 - 파지 오프셋 - drift[0]. 높이와 자세는 고정값이다.
		synchronized ( this ) {
			assertEquals(2, m_commands.size());
			JsonNode first = m_commands.get(0);
			assertEquals(ENTRY.x() - HELD[0], first.path("position").path("x").asDouble(), 1e-9);
			assertEquals(ENTRY.y() - HELD[1], first.path("position").path("y").asDouble(), 1e-9);
			assertEquals(TRAVEL_Z, first.path("position").path("z").asDouble(), 1e-12);
			assertEquals(ORIENTATION.z(), first.path("orientation").path("z").asDouble(), 1e-12);
			assertEquals(ORIENTATION.w(), first.path("orientation").path("w").asDouble(), 1e-12);
		}
		assertEquals(TRAVEL_Z, check.lastTarget().orElseThrow().position().z(), 1e-12);
	}

	@Test void firstPassMayAlreadyAlign() throws Exception {
		drifts(0, 0.0001);
		AlignHeldObject.Check check = align(0.0002).run();
		assertTrue(check.goalReached());
		assertEquals(1, check.passes());
		assertEquals(4, check.steps().size());
	}

	@Test void stopsWhenErrorDoesNotShrink() throws Exception {
		drifts(0, 0.0003, 0.0006);
		AlignHeldObject.Check check = align(0.0002).run();
		assertFalse(check.goalReached());
		assertEquals(AlignHeldObject.StopReason.NOT_IMPROVING, check.stopReason());
		assertErrors(check, 0.0003, 0.0003);
		assertTrue(check.failureReason().startsWith("오차가 더 줄지 않는다"), check.failureReason());
		assertNull(check.failedStage());
	}

	@Test void stopsAfterMaxPasses() throws Exception {
		drifts(0, 0.0008, 0.0012, 0.0014, 0.00145);
		AlignHeldObject.Check check = align(0.00015).run();
		assertFalse(check.goalReached());
		assertEquals(AlignHeldObject.StopReason.MAX_PASSES, check.stopReason());
		assertErrors(check, 0.0008, 0.0004, 0.0002);
		synchronized ( this ) { assertEquals(3, m_moves, "no fourth correction"); }
	}

	@Test void unreachedCorrectionStopsBeforeVerifying() throws Exception {
		synchronized ( this ) { m_miss = 0.01; }	// 10 mm 빗나가면 위치 허용 5 mm를 넘는다.
		AlignHeldObject.Check check = align(0.0002).run();
		assertFalse(check.goalReached());
		assertEquals(AlignHeldObject.StopReason.STEP_FAILED, check.stopReason());
		assertEquals(AlignHeldObject.Stage.CORRECT, check.failedStage());
		assertNull(check.errorM());
		assertTrue(check.lastTarget().isPresent());
		assertEquals(List.of(AlignHeldObject.Stage.SETTLE, AlignHeldObject.Stage.MEASURE, AlignHeldObject.Stage.CORRECT),
						stages(check));
	}

	@Test void unsettledObjectStopsBeforeAnyMotion() throws Exception {
		synchronized ( this ) { m_quality = "STALE"; }
		AlignHeldObject.Options options = FAST.withStill(FAST.still().withTimeout(Duration.ofMillis(200)));
		AlignHeldObject.Check check = new AlignHeldObject(server.client(), "peg", ENTRY, TRAVEL_Z, ORIENTATION, 0.0002, options).run();
		assertEquals(AlignHeldObject.StopReason.STEP_FAILED, check.stopReason());
		assertEquals(AlignHeldObject.Stage.SETTLE, check.failedStage());
		assertTrue(check.failureReason().startsWith("물체 정착 확인 불가"), check.failureReason());
		assertTrue(check.lastTarget().isEmpty());
		assertTrue(server.requests("POST").isEmpty());
	}

	@Test void nonCylinderFailsWithoutMoving() {
		synchronized ( this ) { m_type = "box"; }
		ExecutionException error = assertThrows(ExecutionException.class, () -> align(0.0002).run());
		IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, error.getCause());
		assertTrue(cause.getMessage().startsWith("not a cylinder"), cause.getMessage());
		assertTrue(server.requests("POST").isEmpty());
	}

	@Test void invalidInputsAreRejectedBeforeCommunication() {
		var twin = server.client();
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, " peg", ENTRY, TRAVEL_Z, ORIENTATION, 0.0002));
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, "peg", new Position(Double.NaN, 0, 0),
																			TRAVEL_Z, ORIENTATION, 0.0002));
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, "peg", ENTRY, Double.NaN, ORIENTATION, 0.0002));
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, "peg", ENTRY, TRAVEL_Z,
																			new Quaternion(0, 0, 0, 0), 0.0002));
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, "peg", ENTRY, TRAVEL_Z, ORIENTATION, 0));
		assertThrows(IllegalArgumentException.class, () -> new AlignHeldObject(twin, "peg", ENTRY, TRAVEL_Z, ORIENTATION,
																			0.0002, FAST, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> FAST.withMaxPasses(0));
		assertTrue(server.requests.isEmpty());
	}

	@Test void cancellationDuringCorrectionStopsBeforeVerifying() throws Exception {
		drifts(0, 0.0003, 0.00045);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", "/operations/move_linear", req -> {
			entered.countDown(); await(release);
			return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("closed_loop", true)));
		});
		AlignHeldObject align = align(0.0002);
		align.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(AlignHeldObject.Stage.CORRECT, align.getProgress().activeStage().orElseThrow());
			assertTrue(align.cancel(true));
			int observed = server.requests("GET /variables/scene_objects").size();
			release.countDown();
			assertThrows(CancellationException.class, () -> align.get(3, TimeUnit.SECONDS));
			List<AlignHeldObject.Stage> stages = align.getProgress().steps().stream().map(StepResult::stage).toList();
			assertEquals(List.of(AlignHeldObject.Stage.SETTLE, AlignHeldObject.Stage.MEASURE, AlignHeldObject.Stage.CORRECT),
						stages);
			assertEquals(observed, server.requests("GET /variables/scene_objects").size(), "no verify after cancellation");
			assertEquals(1, server.requests("POST").size());
		}
		finally { release.countDown(); }
	}

	@Test void cancellationDuringVerifyObservationStopsFurtherPasses() throws Exception {
		drifts(0, 0.0003, 0.00045);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", "/variables/scene_objects", req -> {
			boolean verifying;
			synchronized ( this ) { verifying = m_moves > 0; }
			if ( verifying ) { entered.countDown(); await(release); }
			return FakeTwinServer.json(200, sceneEnvelope());
		});
		AlignHeldObject align = align(0.0002);
		align.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertEquals(AlignHeldObject.Stage.VERIFY, align.getProgress().activeStage().orElseThrow());
			assertTrue(align.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> align.get(3, TimeUnit.SECONDS));
			assertEquals(1, server.requests("POST").size(), "no second correction after cancellation");
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	private static void await(CountDownLatch latch) {
		try { latch.await(5, TimeUnit.SECONDS); }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
	}

	@Test void builderRequiresEveryRoleAndMatchesTheConstructor() throws Exception {
		var twin = server.client();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> AlignHeldObject.builder(twin, "peg").entry(ENTRY).commandZ(TRAVEL_Z).commandOrientation(ORIENTATION).build());
		assertEquals("acceptRadiusM is not set", e.getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> AlignHeldObject.builder(twin, "peg").entry(ENTRY).commandOrientation(ORIENTATION).acceptRadiusM(0.001).build());
		assertThrows(IllegalArgumentException.class,
				() -> AlignHeldObject.builder(twin, " peg").entry(ENTRY).commandZ(TRAVEL_Z).commandOrientation(ORIENTATION)
										.acceptRadiusM(0.001).build());
		assertTrue(server.requests.isEmpty());

		drifts(0, 0.0003, 0.00045);
		AlignHeldObject.Check check = AlignHeldObject.builder(twin, "peg").entry(ENTRY).commandZ(TRAVEL_Z)
				.commandOrientation(ORIENTATION).acceptRadiusM(0.0002).options(FAST).build().run();
		assertTrue(check.goalReached());
		assertEquals(TRAVEL_Z, check.lastTarget().orElseThrow().position().z(), 1e-12);
	}

	private AlignHeldObject align(double acceptRadius) {
		return new AlignHeldObject(server.client(), "peg", ENTRY, TRAVEL_Z, ORIENTATION, acceptRadius, FAST);
	}

	private synchronized void drifts(double... values) { m_drifts = values; }

	private synchronized Map<String,Object> sceneEnvelope() {
		double drift = m_drifts[Math.min(m_moves, m_drifts.length - 1)];
		// 똑바로 선 원기둥이므로 중심은 바닥점에서 반 길이(25 mm) 위다.
		double[] center = { m_tcp[0] + HELD[0] + drift, m_tcp[1] + HELD[1], m_tcp[2] + HELD[2] + 0.025 };
		Map<String,Object> peg = Map.of("type", m_type, "dimensions", "box".equals(m_type) ? List.of(0.01, 0.01, 0.05) : List.of(0.05, 0.008),
				"pose", Map.of("position", position(center), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		return Map.of("quality", m_quality, "received_at", Instant.now().toString(),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", Map.of("peg", peg)));
	}

	private synchronized Map<String,Object> eeValue() {
		return Map.of("header", Map.of("frame_id", "panda_link0"), "pose", Map.of("position", position(m_tcp),
				"orientation", Map.of("x", ORIENTATION.x(), "y", ORIENTATION.y(), "z", ORIENTATION.z(), "w", ORIENTATION.w())));
	}

	private static Map<String,Object> position(double[] p) { return Map.of("x", p[0], "y", p[1], "z", p[2]); }

	private static List<AlignHeldObject.Stage> stages(AlignHeldObject.Check check) {
		return check.steps().stream().map(StepResult::stage).toList();
	}

	private static void assertErrors(AlignHeldObject.Check check, double... expected) {
		assertEquals(expected.length, check.passErrorsM().size(), check.passErrorsM().toString());
		for ( int i = 0; i < expected.length; ++i ) {
			assertEquals(expected[i], check.passErrorsM().get(i), 1e-9, "pass " + (i + 1));
		}
		assertEquals(expected[expected.length - 1], check.errorM(), 1e-9);
	}
}
