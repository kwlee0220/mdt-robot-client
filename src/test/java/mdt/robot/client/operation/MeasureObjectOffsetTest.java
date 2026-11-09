package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;

@Timeout(10)
class MeasureObjectOffsetTest {
	private static final String BATCH = "/variables?names=ee_pose,scene_objects";
	private static final String NAME = "part.1/peg";
	private static final Instant TIME = Instant.parse("2026-10-24T00:00:00Z");
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final MeasureObjectOffset.Options FAST = new MeasureObjectOffset.Options(
			Duration.ofMillis(220), Duration.ofMillis(100), Duration.ofMillis(10));
	private FakeTwinServer server;
	@BeforeEach void setUp() throws Exception { server = new FakeTwinServer(); }
	@AfterEach void tearDown() throws Exception { server.close(); }

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void measuresSignedOffsetAndRetainsExactEvidence(boolean async) throws Exception {
		ObjectNode response = response();
		// 물체 중심 측정에는 물체 orientation이나 dimensions가 필요하지 않다.
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response));
		MeasureObjectOffset operation = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST);
		var initial = operation.getProgress();
		assertEquals(MeasureObjectOffset.Outcome.NOT_STARTED, initial.outcome());
		assertTrue(server.requests.isEmpty());
		MeasureObjectOffset.Result result;
		if ( async ) { operation.start(); result = operation.get(3, TimeUnit.SECONDS); }
		else { result = operation.run(); }
		assertTrue(result.goalReached());
		assertSame(result, operation.getProgress());
		var measurement = result.measurement().orElseThrow();
		assertEquals(MeasureObjectOffset.TcpReference.AS_REPORTED, measurement.tcpReference());
		assertEquals("panda_link0", measurement.frame());
		assertPosition(measurement.tcpPose().position(), .4, .1, .3);
		assertPosition(measurement.objectCenter(), .42, .08, .25);
		assertPosition(measurement.offset(), .02, -.02, -.05);
		assertEquals(measurement.tcpPose().position(), measurement.toolPositionFor(measurement.objectCenter()));
		assertEquals(Duration.ofMillis(50), measurement.timeSkew());
		assertEquals(TIME, measurement.eeReceivedAt());
		assertEquals(TIME.plusMillis(50), measurement.sceneReceivedAt());
		assertEquals(response.path("values").path("ee_pose"), measurement.eeSample());
		assertEquals(response.path("values").path("scene_objects"), measurement.sceneSample());
		assertEquals(0, initial.diagnostics().reads());
		assertEquals(1, operation.getDiagnostics().validSamples());
		assertCounts(operation.getDiagnostics());
		assertFalse(operation.cancel(true));
		assertThrows(IllegalStateException.class, operation::run);
		assertEquals(java.util.List.of("GET " + BATCH), server.requests);
	}

	@ParameterizedTest @ValueSource(strings = {"identity", "rotated", "near_unit", "antipodal"})
	void explicitPandaPolicyConvertsUsingOrientationWithoutChangingEvidence(String mode) throws Exception {
		ObjectNode response = response();
		ObjectNode q = (ObjectNode)response.at("/values/ee_pose/value/pose/orientation");
		boolean rotated = !mode.equals("identity");
		double scale = mode.equals("near_unit") ? 1.0001 : mode.equals("antipodal") ? -1 : 1;
		if ( rotated ) { q.put("y", Math.sqrt(.5)*scale); q.put("w", Math.sqrt(.5)*scale); }
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response));
		var measurement = operation(MeasureObjectOffset.TcpReference.PANDA_HAND_TCP, FAST).run().measurement().orElseThrow();
		assertPosition(measurement.tcpPose().position(), rotated ? .5034 : .4, .1, rotated ? .3 : .4034);
		assertPosition(measurement.offset(), rotated ? -.0834 : .02, -.02, rotated ? -.05 : -.1534);
		assertPosition(measurement.toolPositionFor(measurement.objectCenter()),
				rotated ? .5034 : .4, .1, rotated ? .3 : .4034);
		assertEquals(q, measurement.eeSample().at("/value/pose/orientation"));
	}

	@ParameterizedTest @ValueSource(strings = {"exact_bound", "zero_bound", "timezone", "different_stamp_clocks"})
	void comparesReceivedTimesAndAcceptsInclusiveSkewBoundary(String mode) throws Exception {
		ObjectNode response = response();
		ObjectNode ee = (ObjectNode)response.at("/values/ee_pose"), scene = (ObjectNode)response.at("/values/scene_objects");
		Duration bound = mode.equals("zero_bound") ? Duration.ZERO : Duration.ofMillis(100);
		scene.put("received_at", TIME.plus(bound).toString());
		if ( mode.equals("timezone") ) { ee.put("received_at", TIME.atOffset(java.time.ZoneOffset.ofHours(9)).toString()); }
		if ( mode.equals("different_stamp_clocks") ) {
			ee.putObject("stamp").put("sec", 1).put("nanosec", 0);
			scene.putObject("stamp").put("sec", 999999).put("nanosec", 0);
		}
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response));
		var result = operation(MeasureObjectOffset.TcpReference.AS_REPORTED,
				new MeasureObjectOffset.Options(FAST.timeout(), bound, FAST.poll())).run();
		assertTrue(result.goalReached());
		assertEquals(bound, result.measurement().orElseThrow().timeSkew());
	}

	@ParameterizedTest
	@CsvSource({"missing_ee,EE_RESPONSE_MISSING", "missing_scene,SCENE_RESPONSE_MISSING",
			"ee_quality,EE_QUALITY_NOT_OK", "scene_quality,SCENE_QUALITY_NOT_OK",
			"ee_time,EE_TIMESTAMP_INVALID", "scene_time,SCENE_TIMESTAMP_INVALID",
			"ee_frame,EE_FRAME_INVALID", "scene_frame,SCENE_FRAME_INVALID", "blank_frame,SCENE_FRAME_INVALID",
			"frame_mismatch,FRAME_MISMATCH", "skew_positive,TIME_SKEW_TOO_LARGE", "skew_negative,TIME_SKEW_TOO_LARGE",
			"missing_object,OBJECT_MISSING", "object_position,OBJECT_POSITION_INVALID",
			"ee_position,EE_POSITION_INVALID", "missing_q,EE_ORIENTATION_INVALID", "zero_q,EE_ORIENTATION_INVALID",
			"bad_norm,EE_ORIENTATION_INVALID", "overflow,NON_FINITE_OFFSET"})
	void unusablePairsTimeOutWithSpecificDiagnostics(String mode, MeasureObjectOffset.Rejection reason) throws Exception {
		ObjectNode response = response();
		ObjectNode values = (ObjectNode)response.get("values");
		ObjectNode ee = (ObjectNode)values.get("ee_pose"), scene = (ObjectNode)values.get("scene_objects");
		switch (mode) {
			case "missing_ee" -> values.remove("ee_pose");
			case "missing_scene" -> values.remove("scene_objects");
			case "ee_quality" -> ee.put("quality", "STALE");
			case "scene_quality" -> scene.put("quality", "NO_DATA");
			case "ee_time" -> ee.put("received_at", "2026-10-24T00:00:00");
			case "scene_time" -> scene.remove("received_at");
			case "ee_frame" -> ((ObjectNode)ee.path("value")).remove("header");
			case "scene_frame" -> ((ObjectNode)scene.at("/value/header")).put("frame_id", 1);
			case "blank_frame" -> ((ObjectNode)scene.at("/value/header")).put("frame_id", " ");
			case "frame_mismatch" -> ((ObjectNode)scene.at("/value/header")).put("frame_id", "world");
			case "skew_positive" -> scene.put("received_at", TIME.plusMillis(101).toString());
			case "skew_negative" -> scene.put("received_at", TIME.minusMillis(101).toString());
			case "missing_object" -> ((ObjectNode)scene.at("/value/objects")).remove(NAME);
			case "object_position" -> ((ObjectNode)scene.path("value").path("objects").path(NAME).path("pose").path("position")).put("x", "0.42");
			case "ee_position" -> ((ObjectNode)ee.at("/value/pose/position")).remove("z");
			case "missing_q" -> ((ObjectNode)ee.at("/value/pose")).remove("orientation");
			case "zero_q" -> ((ObjectNode)ee.at("/value/pose/orientation")).put("w", 0);
			case "bad_norm" -> ((ObjectNode)ee.at("/value/pose/orientation")).put("w", 2);
			case "overflow" -> {
				((ObjectNode)ee.at("/value/pose/position")).put("x", -Double.MAX_VALUE);
				((ObjectNode)scene.path("value").path("objects").path(NAME).path("pose").path("position")).put("x", Double.MAX_VALUE);
			}
			default -> throw new AssertionError(mode);
		}
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response));
		var operation = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST);
		var result = operation.run();
		assertTrue(operation.isCompleted());
		assertEquals(MeasureObjectOffset.Outcome.TIMEOUT, result.outcome());
		assertFalse(result.goalReached());
		assertTrue(result.measurement().isEmpty());
		assertEquals(reason, result.diagnostics().lastRejection());
		assertTrue(result.diagnostics().rejectionCounts().get(reason) > 0);
		assertCounts(result.diagnostics());
		assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().rejectionCounts().clear());
	}

	@Test void retriesWholePairAndUsesOnlyTheSuccessfulBatch() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", BATCH, req -> {
			ObjectNode response = response();
			int n = reads.incrementAndGet();
			if ( n == 1 ) { ((ObjectNode)response.at("/values/scene_objects")).put("received_at", TIME.minusSeconds(2).toString()); }
			else { ((ObjectNode)response.at("/values/ee_pose/value/pose/position")).put("x", .3); }
			return FakeTwinServer.json(200, response);
		});
		var result = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST).run();
		assertTrue(result.goalReached());
		assertEquals(2, result.diagnostics().reads());
		assertEquals(1, result.diagnostics().rejectedSamples());
		assertEquals(.12, result.measurement().orElseThrow().offset().x(), 1e-12);
		assertCounts(result.diagnostics());
	}

	@Test void zeroTimeoutAndLateResponseNeverProduceAMeasurement() throws Exception {
		var zero = operation(MeasureObjectOffset.TcpReference.AS_REPORTED,
				new MeasureObjectOffset.Options(Duration.ZERO, FAST.maxTimeSkew(), FAST.poll())).run();
		assertFalse(zero.goalReached());
		assertTrue(server.requests.isEmpty());
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response()).setHeadersDelay(350, TimeUnit.MILLISECONDS));
		var result = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST).run();
		assertEquals(MeasureObjectOffset.Outcome.TIMEOUT, result.outcome());
		assertTrue(result.measurement().isEmpty());
		assertEquals(1, result.diagnostics().lateSamples());
		assertCounts(result.diagnostics());
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void acceptedCancellationWinsOverPendingBatchOrError(boolean error) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", BATCH, req -> {
			entered.countDown(); await(release);
			return error ? FakeTwinServer.json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "missing")))
					: FakeTwinServer.json(200, response());
		});
		var operation = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST);
		operation.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertFalse(operation.cancel(false));
			assertTrue(operation.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> operation.get(3, TimeUnit.SECONDS));
			assertEquals(MeasureObjectOffset.Outcome.CANCELLED, operation.getProgress().outcome());
			assertTrue(operation.getProgress().measurement().isEmpty());
			assertTrue(server.requests.stream().allMatch(r -> r.equals("GET " + BATCH)));
		}
		finally { release.countDown(); }
	}

	@Test void httpTimeoutIsFailureNotObservationTimeout() {
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, response()).setHeadersDelay(350, TimeUnit.MILLISECONDS));
		var twin = RobotTwinClient.builder().baseUrl(server.client().getBaseUrl()).httpTimeout(Duration.ofMillis(80)).build();
		var operation = new MeasureObjectOffset(twin, NAME, MeasureObjectOffset.TcpReference.AS_REPORTED);
		assertThrows(ExecutionException.class, operation::run);
		assertTrue(operation.isFailed());
		assertEquals(MeasureObjectOffset.Outcome.FAILED, operation.getProgress().outcome());
		assertEquals(0, operation.getDiagnostics().reads());
	}

	@Test void externalInterruptDuringPollingPreservesCheckedContractAndDiagnostics() throws Exception {
		server.on("GET", BATCH, req -> FakeTwinServer.json(200, Map.of("values", Map.of())));
		var operation = operation(MeasureObjectOffset.TcpReference.AS_REPORTED,
				new MeasureObjectOffset.Options(Duration.ofSeconds(5), FAST.maxTimeSkew(), Duration.ofSeconds(2)));
		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicReference<Boolean> interrupted = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { operation.run(); } catch (Throwable e) { error.set(e); }
			finally { interrupted.set(Thread.currentThread().isInterrupted()); }
		});
		worker.start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while ( operation.getDiagnostics().reads() == 0 && System.nanoTime() < deadline ) { Thread.sleep(5); }
			assertEquals(1, operation.getDiagnostics().reads());
			worker.interrupt(); worker.join(3000);
			assertFalse(worker.isAlive());
			assertInstanceOf(InterruptedException.class, error.get());
			assertEquals(false, interrupted.get());
			assertEquals(MeasureObjectOffset.Outcome.CANCELLED, operation.getProgress().outcome());
			assertEquals(1, operation.getDiagnostics().rejectedSamples());
		}
		finally { worker.interrupt(); worker.join(3000); }
	}

	@Test void prestartCancellationAndQueuedDuplicateStartsSendNoRequests() throws Exception {
		var cancelled = operation(MeasureObjectOffset.TcpReference.AS_REPORTED, FAST);
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertThrows(IllegalStateException.class, cancelled::start);
		assertEquals(MeasureObjectOffset.Outcome.CANCELLED, cancelled.getProgress().outcome());
		var queued = new ArrayList<Runnable>();
		var operation = operation(MeasureObjectOffset.TcpReference.AS_REPORTED,
				new MeasureObjectOffset.Options(Duration.ZERO, Duration.ZERO, FAST.poll()));
		operation.setExecutor(queued::add); operation.start();
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, operation::run);
		queued.get(0).run();
		assertFalse(operation.get().goalReached());
		assertTrue(server.requests.isEmpty());
	}

	@Test void invalidArgumentsAreRejectedWithoutRequests() {
		var twin = server.client();
		var reference = MeasureObjectOffset.TcpReference.AS_REPORTED;
		assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset(null, NAME, reference));
		for (String name : new String[] {null, "", " ", " peg", "peg "}) {
			assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset(twin, name, reference));
		}
		assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset(twin, NAME, null));
		assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset(twin, NAME, reference, null));
		for (Duration bad : new Duration[] {null, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
			assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset.Options(bad, Duration.ZERO, FAST.poll()));
			assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset.Options(FAST.timeout(), bad, FAST.poll()));
			assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset.Options(FAST.timeout(), Duration.ZERO, bad));
		}
		assertThrows(IllegalArgumentException.class, () -> new MeasureObjectOffset.Options(FAST.timeout(), Duration.ZERO, Duration.ZERO));
		assertTrue(server.requests.isEmpty());
	}

	@ParameterizedTest
	@CsvSource({"0.012,0.004,-0.010,0.488,-0.104,0.210",
			"-0.012,-0.004,0.010,0.512,-0.096,0.190", "0,0,0,0.5,-0.1,0.2"})
	void toolPositionForAppliesObjectMinusToolSignWithoutChangingEvidence(
			double dx, double dy, double dz, double x, double y, double z) {
		Position offset = new Position(dx, dy, dz);
		var measurement = measurementWithOffset(offset);
		Position desiredCenter = new Position(.5, -.1, .2);
		assertPosition(measurement.toolPositionFor(desiredCenter), x, y, z);
		assertEquals(new Position(.5, -.1, .2), desiredCenter);
		assertSame(offset, measurement.offset());
		assertTrue(server.requests.isEmpty(), "target calculation must not send commands or fetch observations");
	}

	@ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
	void toolPositionForRejectsNonFiniteTargetsAndManuallyConstructedOffsets(String text) {
		double invalid = Double.parseDouble(text);
		var measurement = measurementWithOffset(new Position(.012, -.02, .03));
		for (Position bad : new Position[] {new Position(invalid, 0, 0), new Position(0, invalid, 0), new Position(0, 0, invalid)}) {
			assertThrows(IllegalArgumentException.class, () -> measurement.toolPositionFor(bad));
			assertThrows(IllegalArgumentException.class,
					() -> measurementWithOffset(bad).toolPositionFor(new Position(.5, 0, .2)));
		}
		assertTrue(server.requests.isEmpty());
	}

	@Test void toolPositionForRejectsNullTargetOrOffset() {
		assertThrows(IllegalArgumentException.class, () -> measurementWithOffset(new Position(0, 0, 0)).toolPositionFor(null));
		assertThrows(IllegalArgumentException.class, () -> measurementWithOffset(null).toolPositionFor(new Position(0, 0, 0)));
	}

	@Test void toolPositionForRejectsOverflowOfFiniteCoordinates() {
		for (int axis = 0; axis < 3; ++axis) {
			double[] target = {0, 0, 0}, offset = {0, 0, 0};
			target[axis] = Double.MAX_VALUE;
			offset[axis] = -Double.MAX_VALUE;
			var measurement = measurementWithOffset(new Position(offset[0], offset[1], offset[2]));
			assertThrows(IllegalArgumentException.class,
					() -> measurement.toolPositionFor(new Position(target[0], target[1], target[2])));
		}
	}

	private static MeasureObjectOffset.Measurement measurementWithOffset(Position offset) {
		return new MeasureObjectOffset.Measurement(MeasureObjectOffset.TcpReference.AS_REPORTED, "panda_link0",
				new Pose(new Position(0, 0, 0), new Quaternion(0, 0, 0, 1)), offset, offset,
				TIME, TIME, Duration.ZERO, MAPPER.createObjectNode(), MAPPER.createObjectNode());
	}

	private MeasureObjectOffset operation(MeasureObjectOffset.TcpReference reference, MeasureObjectOffset.Options options) {
		return new MeasureObjectOffset(server.client(), NAME, reference, options);
	}
	private static ObjectNode response() {
		var eeValue = Map.of("header", Map.of("frame_id", "panda_link0"), "pose", Map.of(
				"position", Map.of("x", .4, "y", .1, "z", .3), "orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)));
		var sceneValue = Map.of("header", Map.of("frame_id", "panda_link0"), "objects", Map.of(NAME,
				Map.of("pose", Map.of("position", Map.of("x", .42, "y", .08, "z", .25)))));
		return MAPPER.valueToTree(Map.of("values", Map.of(
				"ee_pose", Map.of("quality", "OK", "received_at", TIME.toString(), "value", eeValue),
				"scene_objects", Map.of("quality", "OK", "received_at", TIME.plusMillis(50).toString(), "value", sceneValue))));
	}
	private static void assertPosition(Position p, double x, double y, double z) {
		assertEquals(x, p.x(), 1e-12); assertEquals(y, p.y(), 1e-12); assertEquals(z, p.z(), 1e-12);
	}
	private static void assertCounts(ObservationDiagnostics<?> d) {
		assertEquals(d.reads(), d.validSamples() + d.rejectedSamples() + d.lateSamples());
		assertEquals(d.validSamples(), d.newSamples() + d.duplicateSamples());
	}
	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(5, TimeUnit.SECONDS) ) { throw new IllegalStateException("test gate timeout"); } }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
}
