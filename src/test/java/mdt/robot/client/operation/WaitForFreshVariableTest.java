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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import okhttp3.mockwebserver.MockResponse;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.RobotTwinClient;

@Timeout(10)
class WaitForFreshVariableTest {
	private static final String VARIABLE = "/variables/scene_objects";
	private static final WaitForFreshVariable.Options FAST = new WaitForFreshVariable.Options(
			2, Duration.ofSeconds(2), Duration.ofMillis(5));
	private FakeTwinServer server;
	@BeforeEach void setUp() throws Exception { server = new FakeTwinServer(); }
	@AfterEach void tearDown() throws Exception { server.close(); }

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void baselineAndDuplicatesDoNotCountAndReturnedSampleIsTheEvidence(boolean stamp) throws Exception {
		AtomicInteger reads = new AtomicInteger();
		long[] times = { 1, 1, 2, 3 };
		server.on("GET", VARIABLE, req -> sample(times[Math.min(reads.getAndIncrement(), 3)], stamp));
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects", FAST);
		assertTrue(server.requests.isEmpty());
		WaitForFreshVariable.Result result = wait.run();
		assertTrue(result.fresh());
		assertEquals(4, reads.get());
		assertEquals(2, result.diagnostics().updates());
		assertEquals(1, result.diagnostics().duplicates());
		assertEquals(3, result.sample().orElseThrow().at("/value/revision").asInt());
		assertEquals(4, result.diagnostics().validSamples());
		assertTrue(server.requests.stream().allMatch(r -> r.equals("GET " + VARIABLE)));
	}

	@ParameterizedTest @ValueSource(strings = { "missing", "bad_time", "bad_quality", "missing_value", "frozen", "overflow_stamp" })
	void unusableOrFrozenDataNeverBecomesFresh(String mode) throws Exception {
		server.on("GET", VARIABLE, req -> new MockResponse().setHeader("Content-Type", "application/json")
				.setBody(switch ( mode ) {
					case "missing" -> "{\"quality\":\"OK\",\"value\":{}}";
					case "bad_time" -> "{\"quality\":\"OK\",\"value\":{},\"received_at\":\"2026-10-24T00:00:00\"}";
					case "bad_quality" -> "{\"quality\":\"STALE\",\"value\":{}}";
					case "missing_value" -> "{\"quality\":\"OK\",\"value\":null}";
					case "overflow_stamp" -> "{\"quality\":\"OK\",\"value\":{},\"stamp\":{\"sec\":18446744073709551616,\"nanosec\":0}}";
					default -> "{\"quality\":\"OK\",\"value\":{},\"received_at\":\"2026-10-24T00:00:00Z\"}";
				}));
		WaitForFreshVariable.Result result = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(2, Duration.ofMillis(200), FAST.poll())).run();
		assertFalse(result.fresh());
		assertTrue(result.sample().isEmpty());
		assertEquals(switch ( mode ) {
			case "missing", "bad_time", "overflow_stamp" -> WaitForFreshVariable.Outcome.NO_VALID_TIMESTAMP;
			case "frozen" -> WaitForFreshVariable.Outcome.TIMEOUT;
			default -> WaitForFreshVariable.Outcome.NO_VALID_SAMPLE;
		}, result.outcome());
		assertThrows(UnsupportedOperationException.class,
				() -> result.diagnostics().rejections().put(WaitForFreshVariable.Rejection.VALUE_MISSING, 1L));
	}

	@ParameterizedTest @ValueSource(strings = { "rewind", "kind", "invalid" })
	void brokenSequenceRequiresANewBaseline(String mode) throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", VARIABLE, req -> {
			int n = reads.incrementAndGet();
			if ( mode.equals("invalid") && n == 3 ) { return FakeTwinServer.json(200, Map.of("quality", "STALE")); }
			long t = mode.equals("rewind") && n >= 3 ? n - 3 : n;
			return sample(t, mode.equals("kind") && n >= 3);
		});
		WaitForFreshVariable.Result result = new WaitForFreshVariable(server.client(), "scene_objects", FAST).run();
		assertTrue(result.fresh());
		assertEquals(mode.equals("invalid") ? 6 : 5, reads.get());
		assertEquals(1, result.diagnostics().resets());
		assertEquals(2, result.diagnostics().updates());
	}

	@Test void equivalentOffsetDoesNotCountAsUpdate() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", VARIABLE, req -> {
			int n = reads.incrementAndGet();
			String time = n == 1 ? "2026-10-24T00:00:00Z" : n == 2 ? "2026-10-24T09:00:00+09:00"
					: "2026-10-24T00:00:0" + (n - 2) + "Z";
			return FakeTwinServer.json(200, Map.of("quality", "OK", "received_at", time, "value", Map.of()));
		});
		assertTrue(new WaitForFreshVariable(server.client(), "scene_objects", FAST).run().fresh());
		assertEquals(4, reads.get());
	}

	@Test void lateResponseCannotEstablishFreshness() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", VARIABLE, req -> {
			int n = reads.incrementAndGet();
			MockResponse response = sample(n, false);
			return n == 2 ? response.setHeadersDelay(250, TimeUnit.MILLISECONDS) : response;
		});
		WaitForFreshVariable.Result result = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(1, Duration.ofMillis(150), FAST.poll())).run();
		assertFalse(result.fresh());
		assertEquals(1, result.diagnostics().lateSamples());
	}

	@Test void zeroTimeoutAndPreStartCancellationDoNotRead() throws Exception {
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(2, Duration.ZERO, FAST.poll()));
		assertEquals(WaitForFreshVariable.Outcome.NO_VALID_SAMPLE, wait.run().outcome());
		WaitForFreshVariable cancelled = new WaitForFreshVariable(server.client(), "scene_objects");
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertThrows(IllegalStateException.class, cancelled::start);
		assertTrue(server.requests.isEmpty());
	}

	@Test void queuedExecutionRunsOnce() throws Exception {
		List<Runnable> queued = new ArrayList<>();
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(1, Duration.ZERO, FAST.poll()));
		wait.setExecutor(queued::add);
		wait.start();
		assertThrows(IllegalStateException.class, wait::start);
		assertThrows(IllegalStateException.class, wait::run);
		assertFalse(wait.cancel(true));
		assertEquals(1, queued.size());
		queued.get(0).run();
		assertFalse(wait.get().fresh());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void cancellationDuringReadWinsOverResponseAndError(boolean error) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", VARIABLE, req -> {
			entered.countDown();
			await(release);
			return error ? FakeTwinServer.json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "missing")))
					: sample(1, false);
		});
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects");
		wait.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertFalse(wait.cancel(false));
			assertTrue(wait.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> wait.get(3, TimeUnit.SECONDS));
			assertTrue(wait.isCancelled());
		}
		finally { release.countDown(); }
	}

	@Test void externalInterruptInSleepPreservesCheckedContract() throws Exception {
		server.on("GET", VARIABLE, req -> sample(1, false));
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(2, Duration.ofSeconds(5), Duration.ofSeconds(1)));
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Boolean> flag = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try { wait.run(); } catch ( Throwable e ) { thrown.set(e); }
			finally { flag.set(Thread.currentThread().isInterrupted()); }
		});
		worker.start();
		try {
			long due = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			boolean sleeping = false;
			while ( !sleeping && System.nanoTime() < due ) {
				sleeping = worker.getState() == Thread.State.TIMED_WAITING
						&& java.util.Arrays.stream(worker.getStackTrace()).anyMatch(f ->
							f.getClassName().equals(WaitForFreshVariable.class.getName()) && f.getMethodName().equals("observe"));
				if ( !sleeping ) { Thread.sleep(5); }
			}
			assertTrue(sleeping);
			worker.interrupt();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertInstanceOf(InterruptedException.class, thrown.get());
			assertEquals(false, flag.get());
			assertTrue(wait.isCancelled());
		}
		finally { worker.interrupt(); worker.join(3000); }
	}

	@Test void transportTimeoutIsFailureAndNotAnObservationOutcome() {
		server.on("GET", VARIABLE, req -> sample(1, false).setHeadersDelay(300, TimeUnit.MILLISECONDS));
		RobotTwinClient twin = RobotTwinClient.builder().baseUrl(server.client().getBaseUrl())
				.httpTimeout(Duration.ofMillis(80)).build();
		WaitForFreshVariable wait = new WaitForFreshVariable(twin, "scene_objects", FAST);
		ExecutionException error = assertThrows(ExecutionException.class, wait::run);
		assertInstanceOf(utils.http.RESTfulIOException.class, error.getCause());
		assertTrue(wait.isFailed());
	}

	@Test void invalidOptionsAreRejectedBeforeCommunication() {
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable(null, "scene_objects"));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable(server.client(), " "));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable(server.client(), "scene_objects", null));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable.Options(0, Duration.ZERO, FAST.poll()));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable.Options(1, Duration.ofSeconds(-1), FAST.poll()));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable.Options(1, FAST.timeout(), Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new WaitForFreshVariable.Options(1, Duration.ofSeconds(Long.MAX_VALUE), FAST.poll()));
		assertTrue(server.requests.isEmpty());
	}


	@ParameterizedTest @ValueSource(booleans = { false, true })
	void externalHttpInterruptPreservesCancellationContract(boolean async) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", VARIABLE, req -> {
			entered.countDown();
			await(release);
			return FakeTwinServer.json(200, Map.of("padding", "x".repeat(65536)));
		});
		WaitForFreshVariable wait = new WaitForFreshVariable(server.client(), "scene_objects");
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Thread> thread = new AtomicReference<>();
		AtomicReference<Boolean> flag = new AtomicReference<>();
		if ( async ) {
			wait.setExecutor(task -> {
				Thread worker = new Thread(() -> {
					try { task.run(); } finally { flag.set(Thread.currentThread().isInterrupted()); }
				});
				thread.set(worker);
				worker.start();
			});
			wait.start();
		}
		else {
			Thread worker = new Thread(() -> {
				try { wait.run(); } catch ( Throwable e ) { thrown.set(e); }
				finally { flag.set(Thread.currentThread().isInterrupted()); }
			});
			thread.set(worker);
			worker.start();
		}
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			thread.get().interrupt();
			release.countDown();
			thread.get().join(3000);
			assertFalse(thread.get().isAlive());
			assertTrue(wait.isCancelled());
			assertEquals(false, flag.get());
			if ( async ) { assertThrows(CancellationException.class, wait::get); }
			else {
				InterruptedException error = assertInstanceOf(InterruptedException.class, thrown.get());
				assertInstanceOf(utils.http.RESTfulIOException.class, error.getCause());
				assertInstanceOf(java.io.InterruptedIOException.class, error.getCause().getCause());
			}
		}
		finally { release.countDown(); thread.get().interrupt(); thread.get().join(3000); }
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing_ns", "negative_ns", "large_ns", "fraction", "beyond_long", "beyond_instant", "bad_received" })
	void malformedTimestampNeverCountsEvenWithChangingValues(String mode) throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", VARIABLE, req -> {
			String stamp = switch ( mode ) {
				case "missing_ns" -> "{\"sec\":1}";
				case "negative_ns" -> "{\"sec\":1,\"nanosec\":-1}";
				case "large_ns" -> "{\"sec\":1,\"nanosec\":1000000000}";
				case "fraction" -> "{\"sec\":1.5,\"nanosec\":0}";
				case "beyond_long" -> "{\"sec\":1,\"nanosec\":18446744073709551616}";
				case "beyond_instant" -> "{\"sec\":9223372036854775807,\"nanosec\":0}";
				default -> "{\"sec\":" + reads.incrementAndGet() + ",\"nanosec\":0}";
			};
			return new MockResponse().setHeader("Content-Type", "application/json")
					.setBody("{\"quality\":\"OK\",\"value\":{},\"stamp\":" + stamp
							+ (mode.equals("bad_received") ? ",\"received_at\":\"invalid\"" : "") + "}");
		});
		WaitForFreshVariable.Result result = new WaitForFreshVariable(server.client(), "scene_objects",
				new WaitForFreshVariable.Options(1, Duration.ofMillis(200), FAST.poll())).run();
		assertEquals(WaitForFreshVariable.Outcome.NO_VALID_TIMESTAMP, result.outcome());
		assertTrue(result.diagnostics().rejections().containsKey(WaitForFreshVariable.Rejection.TIMESTAMP_INVALID));
	}

	@Test void unchangedValueWithNewTimestampIsStillFresh() throws Exception {
		AtomicInteger reads = new AtomicInteger();
		server.on("GET", VARIABLE, req -> FakeTwinServer.json(200, Map.of(
				"quality", "OK", "received_at", Instant.ofEpochSecond(reads.incrementAndGet()).toString(),
				"value", Map.of("objects", Map.of()))));
		WaitForFreshVariable.Result result = new WaitForFreshVariable(server.client(), "scene_objects", FAST).run();
		assertTrue(result.fresh());
		assertEquals(3, reads.get());
		assertTrue(result.sample().orElseThrow().at("/value/objects").isEmpty());
	}

	static MockResponse sample(long second, boolean stamp) {
		Map<String,Object> body = new java.util.HashMap<>();
		body.put("quality", "OK");
		body.put("value", Map.of("revision", second));
		if ( stamp ) { body.put("stamp", Map.of("sec", second, "nanosec", 0)); }
		else { body.put("received_at", Instant.ofEpochSecond(second).toString()); }
		return FakeTwinServer.json(200, body);
	}

	static void await(CountDownLatch release) {
		try { if ( !release.await(3, TimeUnit.SECONDS) ) { throw new IllegalStateException("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
}
