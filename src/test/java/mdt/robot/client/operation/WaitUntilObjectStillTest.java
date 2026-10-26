package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
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
class WaitUntilObjectStillTest {
	private static final String SCENE = "/variables/scene_objects";
	private static final String NAME = "part.1/peg";
	private static final WaitUntilObjectStill.Options FAST = new WaitUntilObjectStill.Options(
			Duration.ofSeconds(2), 0.0002, 0.005, Duration.ofMillis(80), Duration.ofMillis(10));
	private FakeTwinServer server;

	@BeforeEach void setUp() throws Exception { server = new FakeTwinServer(); }
	@AfterEach void tearDown() throws Exception { server.close(); }

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void stationaryPositionAndOrientationSettleWithOrWithoutStamp(boolean withStamp) throws Exception {
		server.on("GET", SCENE, req -> {
			ObjectNode body = sample(0.4, Quaternion.aboutZ(0.7), Instant.now());
			if ( !withStamp ) { body.remove("stamp"); }
			return FakeTwinServer.json(200, body);
		});
		WaitUntilObjectStill wait = new WaitUntilObjectStill(server.client(), NAME, FAST);
		assertTrue(server.requests.isEmpty());
		assertEquals(WaitUntilObjectStill.Outcome.NOT_STARTED, wait.getProgress().outcome());
		WaitUntilObjectStill.Result result;
		if ( withStamp ) { wait.start(); result = wait.get(3, TimeUnit.SECONDS); }
		else { result = wait.run(); }
		assertTrue(result.settled());
		assertTrue(wait.isCompleted());
		assertSame(result, wait.getProgress());
		assertEquals(0.4, result.object().orElseThrow().path("pose").path("position").path("x").asDouble());
		assertTrue(result.sample().orElseThrow().has("received_at"));
		assertTrue(result.elapsed().compareTo(FAST.quiet()) >= 0);
		assertTrue(result.diagnostics().freshSamples() >= 2);
		assertCounts(result.diagnostics());
		assertFalse(wait.cancel(true));
		assertThrows(IllegalStateException.class, wait::run);
		assertTrue(server.requests.stream().allMatch(r -> r.equals("GET " + SCENE)));
	}

	@ParameterizedTest @ValueSource(strings = { "antipodal", "near_unit", "timezone", "other_object_moving" })
	void equivalentRotationsAndOtherObjectsDoNotPreventSettling(String mode) throws Exception {
		AtomicInteger counter = new AtomicInteger();
		Quaternion q = Quaternion.aboutZ(1.1);
		server.on("GET", SCENE, req -> {
			int n = counter.incrementAndGet();
			double scale = mode.equals("antipodal") && n % 2 == 0 ? -1 : mode.equals("near_unit") ? 1.0001 : 1;
			ObjectNode body = sample(0.4, new Quaternion(q.x()*scale, q.y()*scale, q.z()*scale, q.w()*scale), Instant.now());
			if ( mode.equals("timezone") ) { body.put("received_at", Instant.now().atOffset(java.time.ZoneOffset.ofHours(9)).toString()); }
			if ( mode.equals("other_object_moving") ) {
				((ObjectNode)body.path("value").path("objects")).putObject("other").put("movement", n);
			}
			return FakeTwinServer.json(200, body);
		});
		var options = mode.equals("antipodal") ? new WaitUntilObjectStill.Options(FAST.timeout(), FAST.positionToleranceM(), 0, FAST.quiet(), FAST.poll()) : FAST;
		assertTrue(new WaitUntilObjectStill(server.client(), NAME, options).run().settled());
	}

	@ParameterizedTest @ValueSource(strings = { "drift", "rotation", "frozen", "frozen_stamp", "rewound", "rewound_stamp",
			"stamp_kind", "frame", "slow_received" })
	void motionAndUnusableTimeProgressCannotEstablishSettling(String mode) throws Exception {
		Instant initial = Instant.now();
		AtomicInteger counter = new AtomicInteger();
		server.on("GET", SCENE, req -> {
			int n = counter.incrementAndGet();
			Instant time = switch ( mode ) {
				case "frozen" -> initial;
				case "rewound" -> initial.minusSeconds(n);
				case "slow_received" -> initial.plusNanos(n);
				default -> Instant.now();
			};
			ObjectNode body = sample(mode.equals("drift") ? 0.4 + n * 0.00015 : 0.4,
					Quaternion.aboutZ(mode.equals("rotation") ? n * 0.004 : 0), time);
			if ( mode.equals("frozen_stamp") ) { body.set("stamp", JsonMapper.builder().build().valueToTree(Map.of("sec", 1, "nanosec", 0))); }
			if ( mode.equals("rewound_stamp") ) { ((ObjectNode)body.get("stamp")).put("sec", -n); }
			if ( mode.equals("stamp_kind") && n % 2 == 0 ) { body.remove("stamp"); }
			if ( mode.equals("frame") ) { ((ObjectNode)body.path("value").path("header")).put("frame_id", "frame" + n); }
			return FakeTwinServer.json(200, body);
		});
		var options = new WaitUntilObjectStill.Options(Duration.ofMillis(450), FAST.positionToleranceM(), FAST.angleToleranceRad(),
				Duration.ofMillis(220), FAST.poll());
		WaitUntilObjectStill.Result result = new WaitUntilObjectStill(server.client(), NAME, options).run();
		assertFalse(result.settled());
		assertEquals(WaitUntilObjectStill.Outcome.TIMEOUT, result.outcome());
		assertTrue(result.sample().isPresent(), "timeout keeps last valid observation without claiming settling");
		assertCounts(result.diagnostics());
		if ( mode.startsWith("frozen") ) {
			assertEquals(1, result.diagnostics().freshSamples());
			assertTrue(result.diagnostics().duplicates() > 0);
		}
		else if ( !mode.equals("slow_received") ) { assertTrue(result.diagnostics().resets() > 0); }
		if ( mode.equals("rotation") ) { assertEquals(WaitUntilObjectStill.ResetReason.ORIENTATION_CHANGED, result.diagnostics().lastReset()); }
	}

	@ParameterizedTest @ValueSource(strings = { "quality", "object", "position", "orientation", "zero_quaternion", "nonunit",
			"missing_received", "bad_received", "stamp_shape", "stamp_ns", "stamp_bigint", "stamp_overflow", "frame" })
	void malformedSamplesHaveExplicitRejections(String mode) throws Exception {
		server.on("GET", SCENE, req -> {
			ObjectNode body = sample(0.4, Quaternion.aboutZ(0), Instant.now());
			ObjectNode pose = (ObjectNode)body.path("value").path("objects").path(NAME).path("pose");
			switch ( mode ) {
				case "quality" -> body.put("quality", "STALE");
				case "object" -> ((ObjectNode)body.path("value").path("objects")).remove(NAME);
				case "position" -> ((ObjectNode)pose.get("position")).put("x", "0.4");
				case "orientation" -> pose.remove("orientation");
				case "zero_quaternion" -> ((ObjectNode)pose.get("orientation")).put("w", 0);
				case "nonunit" -> ((ObjectNode)pose.get("orientation")).put("w", 2);
				case "missing_received" -> body.remove("received_at");
				case "bad_received" -> body.put("received_at", "2026-10-24T12:00:00");
				case "stamp_shape" -> ((ObjectNode)body.get("stamp")).put("sec", "1");
				case "stamp_ns" -> ((ObjectNode)body.get("stamp")).put("nanosec", 1_000_000_000);
				case "stamp_bigint" -> ((ObjectNode)body.get("stamp")).put("sec", BigInteger.ONE.shiftLeft(80));
				case "stamp_overflow" -> ((ObjectNode)body.get("stamp")).put("sec", Long.MAX_VALUE);
				case "frame" -> ((ObjectNode)body.path("value").path("header")).put("frame_id", 9);
			}
			return FakeTwinServer.json(200, body);
		});
		WaitUntilObjectStill.Result result = new WaitUntilObjectStill(server.client(), NAME, FAST.withTimeout(Duration.ofMillis(180))).run();
		assertEquals(WaitUntilObjectStill.Outcome.NO_VALID_SAMPLE, result.outcome());
		assertTrue(result.sample().isEmpty());
		var expected = switch ( mode ) {
			case "quality" -> WaitUntilObjectStill.Rejection.QUALITY_NOT_OK;
			case "object" -> WaitUntilObjectStill.Rejection.OBJECT_MISSING;
			case "position" -> WaitUntilObjectStill.Rejection.POSITION_INVALID;
			case "missing_received" -> WaitUntilObjectStill.Rejection.RECEIVED_AT_MISSING;
			case "bad_received" -> WaitUntilObjectStill.Rejection.RECEIVED_AT_INVALID;
			case "stamp_shape" -> WaitUntilObjectStill.Rejection.STAMP_INVALID;
			case "stamp_ns", "stamp_bigint", "stamp_overflow" -> WaitUntilObjectStill.Rejection.STAMP_OUT_OF_RANGE;
			case "frame" -> WaitUntilObjectStill.Rejection.FRAME_INVALID;
			default -> WaitUntilObjectStill.Rejection.ORIENTATION_INVALID;
		};
		assertEquals(expected, result.diagnostics().lastRejection());
		assertCounts(result.diagnostics());
		assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().rejections().clear());
	}

	@Test void temporaryMissingObjectResetsWindowButCanRecover() throws Exception {
		AtomicInteger counter = new AtomicInteger();
		server.on("GET", SCENE, req -> {
			ObjectNode body = sample(0.4, Quaternion.aboutZ(0), Instant.now());
			if ( counter.incrementAndGet() == 2 ) { ((ObjectNode)body.path("value").path("objects")).remove(NAME); }
			return FakeTwinServer.json(200, body);
		});
		WaitUntilObjectStill.Result result = new WaitUntilObjectStill(server.client(), NAME, FAST).run();
		assertTrue(result.settled());
		assertEquals(1, result.diagnostics().resets());
		assertEquals(WaitUntilObjectStill.Rejection.OBJECT_MISSING, result.diagnostics().lastRejection());
		assertCounts(result.diagnostics());
	}

	@Test void disappearingObjectRetainsLastValidSampleWithoutCallingItSettled() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", SCENE, req -> {
			ObjectNode body = sample(0.4, Quaternion.aboutZ(0), Instant.now());
			if ( reads.incrementAndGet() > 1 ) { body.put("quality", "STALE"); }
			return FakeTwinServer.json(200, body);
		});
		var result = new WaitUntilObjectStill(server.client(), NAME, FAST.withTimeout(Duration.ofMillis(250))).run();
		assertEquals(WaitUntilObjectStill.Outcome.TIMEOUT, result.outcome());
		assertEquals(1, result.diagnostics().validSamples());
		assertEquals("OK", result.sample().orElseThrow().path("quality").asText());
	}

	@Test void lateResponseIsNotUsedAndZeroTimeoutDoesNotRead() throws Exception {
		var zero = new WaitUntilObjectStill(server.client(), NAME, FAST.withTimeout(Duration.ZERO));
		assertEquals(WaitUntilObjectStill.Outcome.NO_VALID_SAMPLE, zero.run().outcome());
		assertTrue(server.requests.isEmpty());
		server.on("GET", SCENE, req -> FakeTwinServer.json(200, sample(0.4, Quaternion.aboutZ(0), Instant.now()))
				.setHeadersDelay(200, TimeUnit.MILLISECONDS));
		var result = new WaitUntilObjectStill(server.client(), NAME, FAST.withTimeout(Duration.ofMillis(80))).run();
		assertFalse(result.settled());
		assertTrue(result.sample().isEmpty());
		assertEquals(1, result.diagnostics().lateSamples());
		assertCounts(result.diagnostics());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void cancellationDuringHttpWinsOverLateResponseOrFailure(boolean error) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", SCENE, req -> {
			entered.countDown(); await(release);
			return error ? FakeTwinServer.json(500, Map.of("error", Map.of("code", "TEST", "message", "failed")))
					: FakeTwinServer.json(200, sample(0.4, Quaternion.aboutZ(0), Instant.now()));
		});
		var wait = new WaitUntilObjectStill(server.client(), NAME, FAST);
		wait.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			var before = wait.getProgress();
			assertFalse(wait.cancel(false));
			assertThrows(TimeoutException.class, () -> wait.get(20, TimeUnit.MILLISECONDS));
			assertTrue(wait.cancel(true)); release.countDown();
			assertThrows(CancellationException.class, () -> wait.get(3, TimeUnit.SECONDS));
			assertEquals(WaitUntilObjectStill.Outcome.CANCELLED, wait.getProgress().outcome());
			assertEquals(WaitUntilObjectStill.Outcome.OBSERVING, before.outcome());
			assertTrue(server.requests("POST").isEmpty());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	@Test void httpTimeoutIsFailureNotObservationTimeout() {
		server.on("GET", SCENE, req -> FakeTwinServer.json(200, sample(0.4, Quaternion.aboutZ(0), Instant.now()))
				.setHeadersDelay(400, TimeUnit.MILLISECONDS));
		RobotTwinClient twin = RobotTwinClient.builder().baseUrl(server.client().getBaseUrl()).httpTimeout(Duration.ofMillis(80)).build();
		var wait = new WaitUntilObjectStill(twin, NAME, FAST);
		assertThrows(ExecutionException.class, wait::run);
		assertTrue(wait.isFailed());
		assertEquals(WaitUntilObjectStill.Outcome.FAILED, wait.getProgress().outcome());
	}

	@Test void externalInterruptDuringSleepCancelsAndPreservesSample() throws Exception {
		server.on("GET", SCENE, req -> FakeTwinServer.json(200, sample(0.4, Quaternion.aboutZ(0), Instant.now())));
		var options = new WaitUntilObjectStill.Options(Duration.ofSeconds(5), 0.001, 0.01, Duration.ofSeconds(2), Duration.ofSeconds(2));
		var wait = new WaitUntilObjectStill(server.client(), NAME, options);
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { wait.run(); } catch (Throwable e) { error.set(e); }
		});
		worker.start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while ( wait.getProgress().sample().isEmpty() && System.nanoTime() < deadline ) { Thread.sleep(5); }
			assertTrue(wait.getProgress().sample().isPresent());
			worker.interrupt(); worker.join(3000);
			assertFalse(worker.isAlive());
			assertInstanceOf(InterruptedException.class, error.get());
			assertEquals(WaitUntilObjectStill.Outcome.CANCELLED, wait.getProgress().outcome());
			assertTrue(wait.getProgress().sample().isPresent());
		}
		finally { worker.interrupt(); worker.join(3000); }
	}

	@Test void preStartCancelAndQueuedDuplicateStartDoNotRead() throws Exception {
		var cancelled = new WaitUntilObjectStill(server.client(), NAME, FAST);
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertEquals(WaitUntilObjectStill.Outcome.CANCELLED, cancelled.getProgress().outcome());
		var wait = new WaitUntilObjectStill(server.client(), NAME, FAST.withTimeout(Duration.ZERO));
		List<Runnable> queue = new ArrayList<>();
		wait.setExecutor(queue::add); wait.start();
		assertThrows(IllegalStateException.class, wait::start);
		assertThrows(IllegalStateException.class, wait::run);
		assertFalse(wait.cancel(true));
		queue.get(0).run();
		assertFalse(wait.get().settled());
		assertTrue(server.requests.isEmpty());
	}

	@Test void validatesAllInputsWithoutCommunication() {
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill(null, NAME));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill(server.client(), " "));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill(server.client(), " peg"));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill(server.client(), NAME, null));
		for ( double invalid : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY } ) {
			assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill.Options(FAST.timeout(), invalid, 0.1, FAST.quiet(), FAST.poll()));
			assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill.Options(FAST.timeout(), 0.1, invalid, FAST.quiet(), FAST.poll()));
		}
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill.Options(FAST.timeout(), 0.1, 4, FAST.quiet(), FAST.poll()));
		assertThrows(IllegalArgumentException.class, () -> FAST.withTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
		assertThrows(IllegalArgumentException.class, () -> FAST.withTimeout(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill.Options(FAST.timeout(), 0.1, 0.1, Duration.ZERO, FAST.poll()));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilObjectStill.Options(FAST.timeout(), 0.1, 0.1, FAST.quiet(), Duration.ZERO));
		assertTrue(server.requests.isEmpty());
	}

	private static ObjectNode sample(double x, Quaternion q, Instant time) {
		return JsonMapper.builder().build().valueToTree(Map.of("quality", "OK", "received_at", time.toString(),
				"stamp", Map.of("sec", time.getEpochSecond(), "nanosec", time.getNano()),
				"value", Map.of("header", Map.of("frame_id", "panda_link0"), "objects", Map.of(NAME,
						Map.of("type", "cylinder", "pose", new Pose(new Position(x, 0, 0.1), q))))));
	}
	private static void assertCounts(WaitUntilObjectStill.Diagnostics d) {
		assertEquals(d.reads(), d.validSamples() + d.rejectedSamples() + d.lateSamples());
		assertEquals(d.validSamples(), d.freshSamples() + d.duplicates());
		assertEquals(d.rejectedSamples(), d.rejections().values().stream().mapToLong(Long::longValue).sum());
	}
	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(5, TimeUnit.SECONDS) ) { throw new AssertionError("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
