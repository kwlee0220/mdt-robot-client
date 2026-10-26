package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.TwinException;

@Timeout(10)
class ResetSceneTest {
	private static final String RESET = "/operations/reset_scene";
	private static final String OBJECT = """
		{"name":"cube_0","type":"box","dimensions":[0.05,0.05,0.05],
		 "position":{"x":0.4,"y":0,"z":0.025},
		 "orientation":{"x":0,"y":0,"z":0,"w":1},"yaw_deg":0}
		""";
	private FakeTwinServer m_server;

	@BeforeEach void setUp() throws Exception { m_server = new FakeTwinServer().operation("reset_scene", "sync"); }
	@AfterEach void tearDown() throws Exception { m_server.close(); }

	@ParameterizedTest
	@NullSource
	@ValueSource(longs = { 0, -7, 42, Long.MIN_VALUE, Long.MAX_VALUE })
	void sendsSceneAndOptionalInt64SeedWithoutInventingPlacement(Long seed) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		String outputs = "{\"scene\":\"custom_recipe\",\"seed\":17,\"objects\":[" + OBJECT
				+ "],\"applied_count\":1,\"custom_output\":{\"revision\":3}}";
		m_server.on("POST", RESET, req -> { body.set(req.getBody().readUtf8()); return completed(outputs); });
		ResetScene reset = seed == null ? new ResetScene(m_server.client(), "custom_recipe")
				: new ResetScene(m_server.client(), "custom_recipe", seed.longValue());
		assertTrue(m_server.requests.isEmpty());
		assertTrue(reset.getRemoteResult().isEmpty());
		ResetScene.Check check = reset.run();
		assertTrue(check.applied());
		assertNull(check.why());
		assertTrue(reset.isCompleted());
		assertSame(check.result(), reset.getRemoteResult().orElseThrow());
		assertEquals(JsonMapper.builder().build().readTree(outputs),
				JsonMapper.builder().build().valueToTree(check.result().outputs()));
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).path("inputs");
		assertEquals("custom_recipe", inputs.path("scene").textValue());
		assertEquals(seed != null, inputs.has("seed"));
		if ( seed != null ) { assertEquals(seed.longValue(), inputs.path("seed").longValue()); }
		assertEquals(seed == null ? 1 : 2, inputs.size());
		assertEquals(1, m_server.requests("POST " + RESET).size());
		assertTrue(m_server.requests("GET /variables").isEmpty(), "no implicit physical settling verification");
		assertTrue(check.observation().isEmpty());
	}

	@Test
	void emptySceneIsAValidReset() throws Exception {
		m_server.on("POST", RESET, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		ResetScene.Check check = new ResetScene(m_server.client(), "empty").run();
		assertTrue(check.applied());
		assertTrue(check.result().output("objects").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing", "null", "{}", "1", "\"objects\"", "[null]", "[{}]",
			"[{\"name\":\"cube\"}]",
			"[{\"name\":\" \" ,\"position\":{\"x\":0,\"y\":0,\"z\":0}}]",
			"[{\"name\":\"cube\",\"position\":{\"x\":\"0.4\",\"y\":0,\"z\":0}}]",
			"[{\"name\":\"cube\",\"position\":{\"x\":1e999,\"y\":0,\"z\":0}}]" })
	void missingOrMalformedPlacementDoesNotImplyApplied(String objects) throws Exception {
		m_server.on("POST", RESET, req -> completed("{\"applied_count\":1"
				+ (objects.equals("missing") ? "" : ",\"objects\":" + objects) + "}"));
		ResetScene reset = new ResetScene(m_server.client(), "scene");
		ResetScene.Check check = reset.run();
		assertFalse(check.applied());
		assertNotNull(check.why());
		assertTrue(reset.isCompleted());
		assertTrue(check.result().isCompleted(), "remote completion is independent from placement validity");
		assertSame(check.result(), reset.getRemoteResult().orElseThrow());
	}

	@Test
	void duplicateNamesAreNotAUsablePlacement() throws Exception {
		m_server.on("POST", RESET, req -> completed("{\"objects\":[" + OBJECT + "," + OBJECT + "],\"applied_count\":2}"));
		ResetScene.Check check = new ResetScene(m_server.client(), "scene").run();
		assertFalse(check.applied());
		assertTrue(check.why().contains("중복 물체 이름"));
		assertEquals(2, check.result().output("objects").size());
	}

	@ParameterizedTest
	@ValueSource(strings = { "missing", "null", "\"1\"", "true", "1.0", "-1", "0", "2", "18446744073709551617" })
	void appliedCountMustBeExactIntegerMatchingObjectCount(String count) throws Exception {
		m_server.on("POST", RESET, req -> completed("{\"objects\":[" + OBJECT + "]"
				+ (count.equals("missing") ? "" : ",\"applied_count\":" + count) + "}"));
		ResetScene reset = new ResetScene(m_server.client(), "scene");
		ResetScene.Check check = reset.run();
		assertFalse(check.applied());
		assertTrue(check.why().contains("applied_count"));
		assertTrue(reset.isCompleted());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED" })
	void remoteFailureIsAResultAndRemainsInspectable(String status) throws Exception {
		m_server.on("POST", RESET, req -> FakeTwinServer.json(200, Map.of("status", status,
				"error", Map.of("code", "BACKEND_UNAVAILABLE", "message", "scene service unavailable"))));
		ResetScene reset = new ResetScene(m_server.client(), "scene");
		reset.start();
		ResetScene.Check check = reset.get(3, TimeUnit.SECONDS);
		assertFalse(check.applied());
		assertNotNull(check.why());
		assertEquals(status, check.result().status());
		assertSame(check.result(), reset.getRemoteResult().orElseThrow());
		assertTrue(reset.isCompleted());
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 409 })
	void rejectionIsAnExecutionFailureWithoutRetry(int status) {
		m_server.on("POST", RESET, req -> FakeTwinServer.json(status,
				Map.of("error", Map.of("code", "REJECTED", "message", "scene rejected"))));
		ResetScene reset = new ResetScene(m_server.client(), "unknown_scene");
		ExecutionException error = assertThrows(ExecutionException.class, reset::run);
		assertEquals(status, assertInstanceOf(TwinException.class, error.getCause()).getHttpStatus());
		assertTrue(reset.isFailed());
		assertTrue(reset.getRemoteResult().isEmpty());
		assertEquals(1, m_server.requests("POST " + RESET).size());
	}

	@Test
	void queuedExecutionRunsOnlyOnceAndPreStartCancellationSendsNothing() throws Exception {
		m_server.on("POST", RESET, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		ResetScene cancelled = new ResetScene(m_server.client(), "scene");
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertThrows(IllegalStateException.class, cancelled::start);
		assertTrue(m_server.requests.isEmpty());
		ResetScene reset = new ResetScene(m_server.client(), "scene");
		List<Runnable> queued = new ArrayList<>();
		reset.setExecutor(queued::add);
		reset.start();
		assertThrows(IllegalStateException.class, reset::start);
		assertThrows(IllegalStateException.class, reset::run);
		assertFalse(reset.cancel(true));
		assertEquals(1, queued.size());
		queued.get(0).run();
		assertTrue(reset.get().applied());
		assertEquals(1, m_server.requests("POST " + RESET).size());
	}

	@Test
	void synchronousResetCannotBeCancelledOrLimitedByAsyncTimeout() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("POST", RESET, req -> {
			entered.countDown();
			try { if ( !release.await(3, TimeUnit.SECONDS) ) { throw new IllegalStateException("not released"); } }
			catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
			return completed("{\"objects\":[],\"applied_count\":0}");
		});
		ResetScene reset = new ResetScene(m_server.client(), "scene", null, Duration.ofMillis(1));
		reset.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertFalse(reset.cancel(true));
			assertThrows(TimeoutException.class, () -> reset.get(30, TimeUnit.MILLISECONDS));
			assertFalse(reset.isDone());
		}
		finally { release.countDown(); }
		assertTrue(reset.get(3, TimeUnit.SECONDS).applied());
		assertTrue(m_server.requests("DELETE").isEmpty());
	}

	@Test
	void followsAsyncDeploymentCatalog() throws Exception {
		String session = "/operations/sessions/reset1";
		m_server.operation("reset_scene", "async");
		m_server.on("POST", RESET, req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + session)));
		m_server.on("GET", session, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		ResetScene reset = new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(2));
		assertTrue(reset.run().applied());
		assertFalse(m_server.requests("GET " + session).isEmpty());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n", " scene", "scene ", "scene\u2003" })
	void invalidSceneFailsBeforeCommunication(String scene) {
		assertThrows(IllegalArgumentException.class, () -> new ResetScene(m_server.client(), scene));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void invalidClientOrTimeoutFailsBeforeCommunication() {
		assertThrows(IllegalArgumentException.class, () -> new ResetScene(null, "scene"));
		assertThrows(IllegalArgumentException.class, () -> new ResetScene(m_server.client(), "scene", null, null));
		assertThrows(IllegalArgumentException.class, () -> new ResetScene(m_server.client(), "scene", null, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(-1)));
		assertTrue(m_server.requests.isEmpty());
	}


	@ParameterizedTest
	@ValueSource(strings = { "fresh", "missing_time", "frozen" })
	void optionalObservationKeepsApplicationAndFreshnessSeparate(String mode) throws Exception {
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		m_server.on("POST", RESET, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		m_server.on("GET", "/variables/scene_objects", req -> {
			int n = reads.incrementAndGet();
			assertEquals(1, m_server.requests("POST " + RESET).size(), "baseline follows the reset response");
			return mode.equals("missing_time") ? FakeTwinServer.json(200, Map.of("quality", "OK", "value", Map.of()))
					: WaitForFreshVariableTest.sample(mode.equals("frozen") ? 1 : n, false);
		});
		ResetScene reset = new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(2),
				new WaitForFreshVariable.Options(2, Duration.ofMillis(250), Duration.ofMillis(5)));
		ResetScene.Check check = reset.run();
		assertTrue(check.applied());
		assertNull(check.why());
		assertTrue(reset.isCompleted());
		WaitForFreshVariable.Result observation = check.observation().orElseThrow();
		assertEquals(mode.equals("fresh"), observation.fresh());
		if ( mode.equals("fresh") ) {
			assertEquals(3, reads.get());
			assertEquals(3, observation.sample().orElseThrow().at("/value/revision").asInt());
		}
		else { assertTrue(observation.sample().isEmpty()); }
		assertEquals(1, m_server.requests("POST " + RESET).size(), "observation failure never retries reset");
		assertSame(check.result(), reset.getRemoteResult().orElseThrow());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void applicationFailureSkipsOptionalObservation(boolean remoteFailure) throws Exception {
		m_server.on("POST", RESET, req -> remoteFailure
				? FakeTwinServer.json(200, Map.of("status", "FAILED", "error",
						Map.of("code", "BACKEND_UNAVAILABLE", "message", "reset failed")))
				: completed("{\"objects\":[],\"applied_count\":1}"));
		ResetScene.Check check = new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(2),
				WaitForFreshVariable.Options.DEFAULT).run();
		assertFalse(check.applied());
		assertTrue(check.observation().isEmpty());
		assertTrue(m_server.requests("GET /variables").isEmpty());
	}

	@Test
	void observationTransportFailureRetainsCompletedRemoteResult() {
		m_server.on("POST", RESET, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		m_server.on("GET", "/variables/scene_objects", req -> FakeTwinServer.json(404,
				Map.of("error", Map.of("code", "NOT_FOUND", "message", "no variable"))));
		ResetScene reset = new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(2),
				WaitForFreshVariable.Options.DEFAULT);
		ExecutionException failure = assertThrows(ExecutionException.class, reset::run);
		assertInstanceOf(TwinException.class, failure.getCause());
		assertTrue(reset.isFailed());
		assertTrue(reset.getRemoteResult().orElseThrow().isCompleted());
		assertEquals(1, m_server.requests("POST " + RESET).size());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cancellingObservationRetainsRemoteCompletionAndSendsNoDelete(boolean error) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		m_server.on("POST", RESET, req -> completed("{\"objects\":[],\"applied_count\":0}"));
		m_server.on("GET", "/variables/scene_objects", req -> {
			entered.countDown();
			WaitForFreshVariableTest.await(release);
			return error ? FakeTwinServer.json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "missing")))
					: WaitForFreshVariableTest.sample(1, false);
		});
		ResetScene reset = new ResetScene(m_server.client(), "scene", null, Duration.ofSeconds(2),
				WaitForFreshVariable.Options.DEFAULT);
		reset.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(reset.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> reset.get(3, TimeUnit.SECONDS));
			assertTrue(reset.isCancelled());
			assertTrue(reset.getRemoteResult().orElseThrow().isCompleted());
			assertTrue(m_server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	private static MockResponse completed(String outputs) {
		return new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"status\":\"COMPLETED\",\"outputs\":" + outputs + "}");
	}
}
