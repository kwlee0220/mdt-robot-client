package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;
import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

import utils.http.RESTfulIOException;

@Timeout(10)
class WaitUntilArmStillTest {
	private static final String POSE = "/variables/ee_pose";
	private static final WaitUntilArmStill.Options FAST = new WaitUntilArmStill.Options(Duration.ofSeconds(2), 0.0002,
			Duration.ofMillis(60), Duration.ofMillis(10));
	private FakeTwinServer m_server;

	@BeforeEach
	void setUp() throws Exception { m_server = new FakeTwinServer(); }

	@AfterEach
	void tearDown() throws Exception { m_server.close(); }

	@Test
	void stillPoseSettles() throws Exception {
		m_server.on("GET", "/variables/ee_pose", req -> sample(0.4, Instant.now(), true, "OK"));
		assertTrue(new WaitUntilArmStill(m_server.client(), FAST).run().goalReached());
	}

	@Test
	void frozenCacheNeverSettles() throws Exception {
		Instant frozen = Instant.now();
		m_server.on("GET", "/variables/ee_pose", req -> sample(0.4, frozen, true, "OK"));
		assertFalse(new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(300))).run().goalReached());
	}

	@Test
	void slowDriftCannotAccumulateIntoAFalseStop() throws Exception {
		// 인접 표본 간 변화는 허용 범위 안이어도 구간 시작점에서 계속 멀어진다.
		AtomicInteger n = new AtomicInteger();
		m_server.on("GET", "/variables/ee_pose",
					req -> sample(0.4 + n.incrementAndGet() * 0.00015, Instant.now(), true, "OK"));
		assertFalse(new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(400))).run().goalReached());
	}

	@Test
	void staleSamplesAreNotEvidence() throws Exception {
		m_server.on("GET", "/variables/ee_pose", req -> sample(0.4, Instant.now(), true, "STALE"));
		assertFalse(new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(300))).run().goalReached());
	}

	@Test
	void settleOptionsRejectNonsense() throws Exception {
		assertThrows(IllegalArgumentException.class,
					() -> new WaitUntilArmStill.Options(Duration.ofSeconds(1), 0.0, Duration.ZERO, Duration.ofMillis(10)));
		// null 인자도 IllegalArgumentException이다.
		assertThrows(IllegalArgumentException.class,
					() -> new WaitUntilArmStill.Options(null, 0.0, Duration.ofMillis(10), Duration.ofMillis(10)));
		assertThrows(IllegalArgumentException.class,
					() -> new WaitUntilArmStill.Options(Duration.ofSeconds(1), Double.NaN, Duration.ofMillis(10), Duration.ofMillis(10)));
		assertThrows(IllegalArgumentException.class,
					() -> MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(-1)));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void freshStillSamplesReturnLastPose(boolean stamp) throws Exception {
		m_server.on("GET", POSE, req -> sample(0.4, Instant.now(), stamp, "OK"));
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		assertTrue(m_server.requests.isEmpty());
		assertEquals(WaitUntilArmStill.Outcome.NOT_STARTED, wait.getProgress().outcome());
		WaitUntilArmStill.Result result = wait.run();
		assertTrue(result.goalReached());
		assertEquals(0.4, Pose.fromJson(result.pose().orElseThrow()).position().x());
		assertTrue(wait.isCompleted());
		assertTrue(m_server.requests.size() >= 2);
		assertTrue(m_server.requests.stream().allMatch(r -> r.equals("GET " + POSE)));
		assertFalse(wait.cancel(true));
		assertEquals(WaitUntilArmStill.Outcome.SETTLED, wait.getProgress().outcome());
		assertTrue(wait.getDiagnostics().newSamples() >= 2);
		assertCountsConsistent(wait.getDiagnostics());
	}

	@ParameterizedTest
	@ValueSource(strings = { "frozen", "duplicate_stamp", "drift", "stale", "missing_time", "no_timezone",
			"bad_position", "missing_position", "rewound", "stamp_kind_changed" })
	void invalidOrChangingObservationsNeverBecomeEvidence(String mode) throws Exception {
		Instant initial = Instant.now();
		AtomicInteger counter = new AtomicInteger();
		m_server.on("GET", POSE, req -> {
			int n = counter.incrementAndGet();
			Instant time = mode.equals("frozen") ? initial : mode.equals("rewound") ? initial.minusSeconds(n) : Instant.now();
			Map<String,Object> body = body(mode.equals("drift") ? 0.4 + n * 0.00015 : 0.4, time,
					!mode.equals("stamp_kind_changed") || n % 2 == 0, mode.equals("stale") ? "STALE" : "OK");
			if ( mode.equals("duplicate_stamp") ) { body.put("stamp", Map.of("sec", 1, "nanosec", 0)); }
			if ( mode.equals("missing_time") ) { body.remove("received_at"); }
			if ( mode.equals("no_timezone") ) { body.put("received_at", "2026-10-24T00:00:00"); }
			if ( mode.equals("bad_position") ) {
				body.put("value", Map.of("pose", Map.of("position", Map.of("x", "0.4", "y", 0, "z", 0))));
			}
			if ( mode.equals("missing_position") ) { body.put("value", Map.of("pose", Map.of())); }
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), new WaitUntilArmStill.Options(Duration.ofMillis(250),
				FAST.positionToleranceM(), Duration.ofMillis(150), FAST.poll()));
		assertFalse(wait.run().goalReached());
		assertTrue(wait.isCompleted(), "observation timeout is a normal empty result");
		assertTrue(counter.get() >= 2);
		WaitUntilArmStill.Diagnostics diagnostics = wait.getDiagnostics();
		WaitUntilArmStill.Rejection reason = switch ( mode ) {
			case "stale" -> WaitUntilArmStill.Rejection.QUALITY_NOT_OK;
			case "missing_time" -> WaitUntilArmStill.Rejection.RECEIVED_AT_MISSING;
			case "no_timezone" -> WaitUntilArmStill.Rejection.RECEIVED_AT_INVALID;
			case "bad_position" -> WaitUntilArmStill.Rejection.POSITION_INVALID;
			case "missing_position" -> WaitUntilArmStill.Rejection.POSITION_MISSING;
			default -> null;
		};
		assertEquals(reason == null ? WaitUntilArmStill.Outcome.TIMEOUT : WaitUntilArmStill.Outcome.NO_VALID_SAMPLE,
				wait.getProgress().outcome());
		assertEquals(reason, diagnostics.lastRejection());
		if ( reason != null ) {
			assertEquals(0, diagnostics.validSamples());
			assertEquals(diagnostics.rejectedSamples(), diagnostics.rejectionCounts().get(reason));
		}
		if ( mode.equals("frozen") || mode.equals("duplicate_stamp") ) {
			assertEquals(1, diagnostics.newSamples());
			assertTrue(diagnostics.duplicateSamples() > 0);
		}
		WaitUntilArmStill.ResetReason reset = switch ( mode ) {
			case "drift" -> WaitUntilArmStill.ResetReason.POSITION_OUT_OF_RANGE;
			case "rewound" -> WaitUntilArmStill.ResetReason.TIME_REWOUND;
			case "stamp_kind_changed" -> WaitUntilArmStill.ResetReason.STAMP_KIND_CHANGED;
			default -> null;
		};
		assertEquals(reset, diagnostics.lastReset());
		assertCountsConsistent(diagnostics);
	}

	@Test
	void zeroTimeoutDoesNotReadOrCreateSession() throws Exception {
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ZERO));
		assertFalse(wait.run().goalReached());
		assertTrue(wait.isCompleted());
		assertTrue(m_server.requests.isEmpty());
		assertEquals(WaitUntilArmStill.Outcome.NO_VALID_SAMPLE, wait.getProgress().outcome());
		assertEquals(0, wait.getDiagnostics().reads());
	}

	@Test
	void queuedExecutionRunsOnce() throws Exception {
		List<Runnable> queued = new ArrayList<>();
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ZERO));
		wait.setExecutor(queued::add);
		RobotTwinOperation<WaitUntilArmStill.Result> operation = wait;
		operation.start();
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, wait::run);
		assertEquals(1, queued.size());
		queued.get(0).run();
		assertFalse(operation.get().goalReached());
		assertThrows(IllegalStateException.class, operation::start);
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void preStartCancellationPreventsRequests(boolean interrupt) {
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client());
		assertTrue(wait.cancel(interrupt));
		assertThrows(CancellationException.class, wait::get);
		assertThrows(IllegalStateException.class, wait::start);
		assertTrue(m_server.requests.isEmpty());
		assertEquals(WaitUntilArmStill.Outcome.CANCELLED, wait.getProgress().outcome());
	}

	@Test
	void callerTimeoutDoesNotStopObservationButCancelTrueDoes() throws Exception {
		m_server.on("GET", POSE, req -> sample(0.4, Instant.now(), true, "STALE"));
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), slowPoll());
		AtomicBoolean flagAtExit = new AtomicBoolean(true);
		Thread worker = startWorker(wait, flagAtExit);
		try {
			awaitSleep(worker);
			assertFalse(wait.cancel(false));
			assertThrows(TimeoutException.class, () -> wait.get(20, TimeUnit.MILLISECONDS));
			assertFalse(wait.isDone());
			assertTrue(wait.cancel(true));
			assertThrows(CancellationException.class, () -> wait.get(3, TimeUnit.SECONDS));
			assertTrue(wait.isCancelled());
			assertTrue(wait.cancel(true));
		}
		finally {
			worker.join(3000);
			if ( worker.isAlive() ) { worker.interrupt(); worker.join(1000); }
		}
		assertFalse(worker.isAlive());
		assertFalse(flagAtExit.get());
		assertEquals(WaitUntilArmStill.Outcome.CANCELLED, wait.getProgress().outcome());
		assertTrue(m_server.requests.stream().allMatch(r -> r.equals("GET " + POSE)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "run", "start" })
	void externalInterruptionPreservesEntryPointContracts(String entryPoint) throws Exception {
		m_server.on("GET", POSE, req -> sample(0.4, Instant.now(), true, "STALE"));
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), slowPoll());
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean flag = new AtomicBoolean();
		Thread worker;
		if ( entryPoint.equals("start") ) { worker = startWorker(wait, flag); }
		else {
			worker = new Thread(() -> {
				try {
					wait.run();
				}
				catch ( Throwable e ) { thrown.set(e); }
				finally { flag.set(Thread.currentThread().isInterrupted()); }
			}, "wait-until-still-interruption");
			worker.start();
		}
		try {
			awaitSleep(worker);
			worker.interrupt();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(wait.isCancelled());
			if ( entryPoint.equals("run") ) { assertInstanceOf(InterruptedException.class, thrown.get()); }
			else { assertThrows(CancellationException.class, wait::get); }
			assertFalse(flag.get());
		}
		finally { worker.interrupt(); worker.join(3000); }
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cancellationDuringReadWinsOverResponseOrError(boolean error) throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("GET", POSE, req -> {
			entered.countDown();
			awaitRelease(release);
			return error ? FakeTwinServer.json(400, Map.of("error", Map.of("code", "BAD_REQUEST", "message", "rejected")))
					: sample(0.4, Instant.now(), true, "OK");
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		Thread worker = startWorker(wait, new AtomicBoolean());
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(wait.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> wait.get(3, TimeUnit.SECONDS));
			assertTrue(wait.isCancelled());
			assertEquals(1, m_server.requests.size());
		}
		finally { release.countDown(); worker.join(3000); }
	}

	@Test
	void externalInterruptionDuringHttpPreservesTransportCause() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("GET", POSE, req -> {
			entered.countDown();
			awaitRelease(release);
			Map<String,Object> body = body(0.4, Instant.now(), true, "OK");
			body.put("padding", "x".repeat(65536));
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { wait.run(); } catch ( Throwable e ) { thrown.set(e); }
		}, "wait-until-still-http-interruption");
		worker.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			worker.interrupt();
			release.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			InterruptedException error = assertInstanceOf(InterruptedException.class, thrown.get());
			RESTfulIOException transport = assertInstanceOf(RESTfulIOException.class, error.getCause());
			assertInstanceOf(InterruptedIOException.class, transport.getCause());
			assertTrue(wait.isCancelled());
		}
		finally { release.countDown(); worker.join(3000); }
	}

	@Test
	void httpTimeoutIsFailureNotObservationTimeoutOrCancellation() {
		m_server.on("GET", POSE, req -> sample(0.4, Instant.now(), true, "OK").setHeadersDelay(400, TimeUnit.MILLISECONDS));
		RobotTwinClient twin = RobotTwinClient.builder().baseUrl(m_server.client().getBaseUrl())
				.httpTimeout(Duration.ofMillis(100)).build();
		WaitUntilArmStill wait = new WaitUntilArmStill(twin, FAST);
		ExecutionException error = assertThrows(ExecutionException.class, wait::run);
		RESTfulIOException transport = assertInstanceOf(RESTfulIOException.class, error.getCause());
		assertInstanceOf(SocketTimeoutException.class, transport.getCause());
		assertTrue(wait.isFailed());
		assertEquals(WaitUntilArmStill.Outcome.FAILED, wait.getProgress().outcome());
	}

	@Test
	void domainErrorSurvivesExecutionWrapper() {
		m_server.on("GET", POSE, req -> FakeTwinServer.json(404,
				Map.of("error", Map.of("code", "NOT_FOUND", "message", "no variable"))));
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		ExecutionException error = assertThrows(ExecutionException.class, wait::run);
		TwinException cause = assertInstanceOf(TwinException.class, error.getCause());
		assertEquals(404, cause.getHttpStatus());
		assertTrue(wait.isFailed());
		assertEquals("NOT_FOUND", cause.getCode());
	}

	@Test
	void lateReadCannotEstablishSettling() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		m_server.on("GET", POSE, req -> {
			MockResponse response = sample(0.4, Instant.now(), true, "OK");
			return reads.incrementAndGet() == 1 ? response : response.setHeadersDelay(300, TimeUnit.MILLISECONDS);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(200)));
		assertFalse(wait.run().goalReached());
		assertTrue(wait.isCompleted());
		assertEquals(2, reads.get());
		assertEquals(WaitUntilArmStill.Outcome.TIMEOUT, wait.getProgress().outcome());
		assertEquals(1, wait.getDiagnostics().validSamples());
		assertEquals(1, wait.getDiagnostics().lateSamples());
		assertCountsConsistent(wait.getDiagnostics());
	}

	@ParameterizedTest
	@CsvSource({
		"9223372036854775808, 0, STAMP_OUT_OF_RANGE",
		"18446744073709551616, 0, STAMP_OUT_OF_RANGE",
		"-9223372036854775809, 0, STAMP_OUT_OF_RANGE",
		"9223372036854775807, 0, STAMP_OUT_OF_RANGE",
		"-9223372036854775808, 0, STAMP_OUT_OF_RANGE",
		"9223372036, 854775808, STAMP_OUT_OF_RANGE",
		"0, 18446744073709551616, STAMP_OUT_OF_RANGE",
		"0, -18446744073709551616, STAMP_OUT_OF_RANGE",
		"0, 1000000000, STAMP_OUT_OF_RANGE",
		"0, -1, STAMP_OUT_OF_RANGE",
		"0.5, 0, STAMP_INVALID",
		"0, 0.5, STAMP_INVALID",
		"null, 0, STAMP_INVALID"
	})
	void invalidStampIsRejectedBeforeNarrowingOrArithmetic(String sec, String nsec,
			WaitUntilArmStill.Rejection reason) throws Exception {
		JsonNode stamp = JsonMapper.builder().build().readTree("{\"sec\":" + sec + ",\"nanosec\":" + nsec + "}");
		m_server.on("GET", POSE, req -> {
			Map<String,Object> body = body(0.4, Instant.now(), false, "OK");
			body.put("stamp", stamp);
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(250)));
		assertFalse(wait.run().goalReached());
		assertEquals(WaitUntilArmStill.Outcome.NO_VALID_SAMPLE, wait.getProgress().outcome());
		assertEquals(reason, wait.getDiagnostics().lastRejection());
		assertTrue(wait.getDiagnostics().rejectedSamples() > 0);
		assertCountsConsistent(wait.getDiagnostics());
	}

	@ParameterizedTest
	@ValueSource(strings = { "Z", "+09:00" })
	void receivedAtAcceptsUtcAndExplicitOffsets(String offset) throws Exception {
		m_server.on("GET", POSE, req -> {
			Map<String,Object> body = body(0.4, Instant.now(), false, "OK");
			body.put("received_at", Instant.now().atOffset(java.time.ZoneOffset.of(offset)).toString());
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		assertTrue(wait.run().goalReached());
		assertEquals(0, wait.getDiagnostics().rejectedSamples());
	}

	@ParameterizedTest
	@CsvSource({ "9223372036, 854775807", "-9223372036, 0" })
	void representableStampNearLongBoundaryRemainsValid(long sec, long nsec) throws Exception {
		m_server.on("GET", POSE, req -> {
			Map<String,Object> body = body(0.4, Instant.now(), false, "OK");
			body.put("stamp", Map.of("sec", sec, "nanosec", nsec));
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofMillis(250)));
		assertFalse(wait.run().goalReached(), "a constant valid stamp cannot establish settling");
		assertEquals(WaitUntilArmStill.Outcome.TIMEOUT, wait.getProgress().outcome());
		assertEquals(0, wait.getDiagnostics().rejectedSamples());
		assertEquals(1, wait.getDiagnostics().newSamples());
		assertTrue(wait.getDiagnostics().duplicateSamples() > 0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "rewound", "stamp_added" })
	void timeChangeIsNotHiddenBySharedTimestamp(String mode) throws Exception {
		// 두 번째 표본이 첫 표본과 시각 값 하나(stamp 또는 received_at)를 공유한다. 중복으로 넘기지 않고 구간을
		// 다시 시작해야 한다.
		Instant base = Instant.parse("2026-11-08T00:01:40Z");
		AtomicInteger calls = new AtomicInteger();
		m_server.on("GET", POSE, req -> {
			int n = calls.getAndIncrement();
			Map<String,Object> body;
			if ( mode.equals("rewound") ) {
				// received_at 100초 → 99초(stamp는 같음) → 101초, 102초 …
				Instant received = n == 0 ? base : n == 1 ? base.minusSeconds(1) : base.plusSeconds(n - 1);
				body = body(0.4, received, false, "OK");
				body.put("stamp", Map.of("sec", n <= 1 ? 10 : 10 + n - 1, "nanosec", 0));
			}
			else {
				// received_at이 같은 채 stamp가 새로 붙는다.
				Instant received = n <= 1 ? base : base.plusSeconds(n - 1);
				body = body(0.4, received, n >= 1, "OK");
			}
			return FakeTwinServer.json(200, body);
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST);
		assertTrue(wait.run().goalReached());
		WaitUntilArmStill.Diagnostics diagnostics = wait.getDiagnostics();
		assertEquals(1, diagnostics.resets());
		assertEquals(mode.equals("rewound") ? WaitUntilArmStill.ResetReason.TIME_REWOUND
											: WaitUntilArmStill.ResetReason.STAMP_KIND_CHANGED, diagnostics.lastReset());
		assertCountsConsistent(diagnostics);
	}

	@Test
	void runningDiagnosticsAreImmutableSnapshots() throws Exception {
		CountDownLatch enteredSecond = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger reads = new AtomicInteger();
		m_server.on("GET", POSE, req -> {
			if ( reads.incrementAndGet() == 2 ) {
				enteredSecond.countDown();
				awaitRelease(release);
			}
			return sample(0.4, Instant.now(), false, "STALE");
		});
		WaitUntilArmStill wait = new WaitUntilArmStill(m_server.client(), FAST.withTimeout(Duration.ofSeconds(1)));
		Thread worker = startWorker(wait, new AtomicBoolean());
		try {
			assertTrue(enteredSecond.await(3, TimeUnit.SECONDS));
			WaitUntilArmStill.Diagnostics snapshot = wait.getDiagnostics();
			assertEquals(WaitUntilArmStill.Outcome.OBSERVING, wait.getProgress().outcome());
			assertEquals(1, snapshot.reads());
			assertThrows(UnsupportedOperationException.class, () -> snapshot.rejectionCounts()
					.put(WaitUntilArmStill.Rejection.QUALITY_NOT_OK, 99L));
			release.countDown();
			assertFalse(wait.get(3, TimeUnit.SECONDS).goalReached());
			assertEquals(1, snapshot.reads());
			assertEquals(1L, snapshot.rejectionCounts().get(WaitUntilArmStill.Rejection.QUALITY_NOT_OK));
			assertTrue(wait.getDiagnostics().reads() > snapshot.reads());
			assertEquals(WaitUntilArmStill.Outcome.NO_VALID_SAMPLE, wait.getProgress().outcome());
			assertCountsConsistent(wait.getDiagnostics());
		}
		finally { release.countDown(); worker.join(3000); }
	}

	private static void assertCountsConsistent(WaitUntilArmStill.Diagnostics diagnostics) {
		assertEquals(diagnostics.reads(), diagnostics.validSamples() + diagnostics.rejectedSamples() + diagnostics.lateSamples());
		assertEquals(diagnostics.validSamples(), diagnostics.newSamples() + diagnostics.duplicateSamples());
		assertEquals(diagnostics.rejectedSamples(), diagnostics.rejectionCounts().values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	void constructorRejectsNullArgumentsAndUnrepresentableDurationsWithoutRequests() {
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilArmStill(null));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilArmStill(m_server.client(), null));
		assertThrows(IllegalArgumentException.class, () -> new WaitUntilArmStill(m_server.client(),
				FAST.withTimeout(Duration.ofSeconds(Long.MAX_VALUE))));
		assertTrue(m_server.requests.isEmpty());
	}

	private static WaitUntilArmStill.Options slowPoll() {
		return new WaitUntilArmStill.Options(Duration.ofSeconds(5), 0.0002, Duration.ofMillis(60), Duration.ofSeconds(1));
	}

	private static Thread startWorker(WaitUntilArmStill wait, AtomicBoolean flag) {
		AtomicReference<Thread> thread = new AtomicReference<>();
		wait.setExecutor(task -> {
			Thread worker = new Thread(() -> {
				try { task.run(); } finally { flag.set(Thread.currentThread().isInterrupted()); }
			}, "wait-until-still-test");
			worker.setDaemon(true);
			thread.set(worker);
			worker.start();
		});
		wait.start();
		return thread.get();
	}

	private static void awaitSleep(Thread thread) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while ( thread.isAlive() && System.nanoTime() < deadline ) {
			if ( thread.getState() == Thread.State.TIMED_WAITING ) {
				for ( StackTraceElement frame : thread.getStackTrace() ) {
					if ( frame.getClassName().equals(WaitUntilArmStill.class.getName()) && frame.getMethodName().equals("sleepNanos") ) {
						return;
					}
				}
			}
			Thread.sleep(5);
		}
		fail("worker did not enter observation sleep");
	}

	private static void awaitRelease(CountDownLatch release) {
		try {
			if ( !release.await(5, TimeUnit.SECONDS) ) { throw new IllegalStateException("response was not released"); }
		}
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}

	private static MockResponse sample(double x, Instant time, boolean stamp, String quality) {
		return FakeTwinServer.json(200, body(x, time, stamp, quality));
	}

	private static Map<String,Object> body(double x, Instant time, boolean stamp, String quality) {
		Map<String,Object> result = new HashMap<>();
		result.put("quality", quality);
		result.put("received_at", time.toString());
		if ( stamp ) { result.put("stamp", Map.of("sec", time.getEpochSecond(), "nanosec", time.getNano())); }
		result.put("value", Map.of("pose", new Pose(new Position(x, 0.0, 0.1), Geometry.DOWN)));
		return result;
	}
}
