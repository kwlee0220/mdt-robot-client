package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

@Timeout(10)
class MoveToJointsTest {
	private static final String MOVE = "/operations/move_to_joints";
	private static final String SESSION = "/operations/sessions/joints1";
	private FakeTwinServer m_server;
	private AtomicReference<String> m_status;
	private CountDownLatch m_polled;
	private CountDownLatch m_finished;

	@BeforeEach
	void setUp() throws Exception {
		m_server = new FakeTwinServer().operation("move_to_joints", "sync");
	}

	@AfterEach
	void tearDown() throws Exception {
		try {
			if ( m_status != null ) {
				m_status.set("COMPLETED");
				assertTrue(m_finished.await(3, TimeUnit.SECONDS), "background poller must finish");
			}
		}
		finally { m_server.close(); }
	}

	@Test
	void snapshotsPartialJointMapAndSendsDefaultsWithoutReadingVariables() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		Map<String,Double> joints = new HashMap<>(Map.of("panda_joint7", -0.2));
		MoveToJoints move = new MoveToJoints(m_server.client(), joints);
		joints.put("panda_joint7", 1.0);
		joints.put("panda_finger_joint1", 0.04);
		assertTrue(m_server.requests.isEmpty());
		assertTrue(move.getRemoteResult().isEmpty());
		MoveToJoints.Check check = move.run();
		assertTrue(check.reached());
		assertSame(check.result(), move.getRemoteResult().orElseThrow());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals(1, inputs.get("joints").size());
		assertEquals(-0.2, inputs.at("/joints/panda_joint7").doubleValue());
		assertEquals(MoveToJoints.DEFAULT_VELOCITY_SCALING, inputs.get("velocity_scaling").doubleValue());
		assertFalse(inputs.has("max_duration_sec"));
		assertTrue(m_server.requests("GET /variables").isEmpty());
	}

	@Test
	void nullVelocityUsesServerDefaultWithoutChangingLibraryDefault() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		MoveToJoints.Options options = MoveToJoints.Options.DEFAULT.withVelocityScaling(null)
				.withMaxDuration(Duration.ofSeconds(60)).withTimeout(Duration.ofSeconds(90));
		assertTrue(new MoveToJoints(m_server.client(), Map.of("joint_a", 0.2), options).run().reached());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertFalse(inputs.has("velocity_scaling"));
		assertEquals(60, inputs.path("max_duration_sec").asInt());
		assertEquals(0.3, MoveToJoints.Options.DEFAULT.velocityScaling().doubleValue());
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "false", "null", "missing", "\"true\"", "\"false\"", "1", "0", "{}", "[]" })
	void onlyBooleanClosedLoopConfirmsArrivalAndSnapshotsDoNotOverrideIt(String value) throws Exception {
		m_server.on("POST", MOVE, req -> new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"status\":\"COMPLETED\",\"outputs\":{" +
						(value.equals("missing") ? "" : "\"closed_loop\":" + value + ",") +
						"\"final_joints\":{\"position\":{\"joint_a\":0.2}},\"measured_age_ms\":1000}}"));
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.2));
		MoveToJoints.Check check = move.run();
		assertTrue(move.isCompleted());
		assertEquals(value.equals("true"), check.reached());
		assertEquals(0.2, check.result().output("final_joints").at("/position/joint_a").doubleValue());
		assertEquals(1000, check.result().output("measured_age_ms").intValue());
		if ( value.equals("true") ) { assertNull(check.why()); }
		else if ( value.equals("false") ) { assertTrue(check.why().contains("open loop")); }
		else if ( value.equals("null") || value.equals("missing") ) {
			assertTrue(check.why().contains("정보 없음"));
		}
		else { assertTrue(check.why().contains("받은 값: " + value)); }
		assertTrue(m_server.requests("GET /variables").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED" })
	void remoteFailureIsNormalUnreachedResult(String status) throws Exception {
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(200, Map.of("status", status,
				"error", Map.of("code", "PLANNING_FAILED", "message", "no plan"))));
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0));
		MoveToJoints.Check check = move.run();
		assertTrue(move.isCompleted());
		assertFalse(check.reached());
		assertNotNull(check.why());
		assertEquals(status, check.result().status());
		assertSame(check.result(), move.getRemoteResult().orElseThrow());
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 409 })
	void rejectionIsExecutionFailureWithDomainCause(int status) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return FakeTwinServer.json(status, Map.of("error", Map.of("code", "REJECTED", "message", "deployment constraint")));
		});
		// Do not hard-code Panda joint names, joint limits, or deployment speed minimum 0.01.
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("custom_joint", 10.0), 0.005);
		move.start();
		ExecutionException error = assertThrows(ExecutionException.class, () -> move.get(3, TimeUnit.SECONDS));
		assertEquals(status, assertInstanceOf(TwinException.class, error.getCause()).getHttpStatus());
		assertTrue(move.isFailed());
		assertTrue(move.getRemoteResult().isEmpty());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals(10.0, inputs.at("/joints/custom_joint").doubleValue());
		assertEquals(0.005, inputs.get("velocity_scaling").doubleValue());
	}

	@Test
	void queuedExecutionRunsOnceWithExplicitSpeedAndTimeout() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0, "joint_b", -1.0),
				1.0, Duration.ofSeconds(5));
		List<Runnable> queued = new ArrayList<>();
		move.setExecutor(queued::add);
		RobotTwinOperation<MoveToJoints.Check> operation = move;
		operation.start();
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, move::run);
		assertFalse(operation.cancel(true));
		assertEquals(1, queued.size());
		assertTrue(m_server.requests.isEmpty());
		queued.get(0).run();
		assertTrue(operation.get().reached());
		assertEquals(1, m_server.requests("POST " + MOVE).size());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals(2, inputs.get("joints").size());
		assertEquals(1.0, inputs.get("velocity_scaling").doubleValue());
	}

	@Test
	void preStartCancellationSendsNoRequest() {
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0));
		assertTrue(move.cancel(false));
		assertThrows(CancellationException.class, move::get);
		assertThrows(IllegalStateException.class, move::start);
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void acceptedCancellationPreservesRacingRemoteCompletion() throws Exception {
		configureAsync();
		m_server.on("DELETE", SESSION, req -> FakeTwinServer.json(202, Map.of("status", "RUNNING")));
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0));
		move.start();
		try {
			assertTrue(m_polled.await(3, TimeUnit.SECONDS));
			assertFalse(move.cancel(false));
			assertTrue(move.cancel(true));
			m_status.set("COMPLETED");
			assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
			assertTrue(move.isCancelled());
			assertTrue(move.getRemoteResult().orElseThrow().isCompleted());
			assertEquals(1, m_server.requests("DELETE " + SESSION).size());
		}
		finally {
			m_status.set("COMPLETED");
			try { move.get(3, TimeUnit.SECONDS); } catch ( CancellationException expected ) { }
		}
	}

	@Test
	void asyncTimeoutDoesNotCancelRemoteOrAcquireLateSnapshot() throws Exception {
		configureAsync();
		MoveToJoints.Options options = MoveToJoints.Options.DEFAULT.withVelocityScaling(0.2)
				.withMaxDuration(Duration.ofSeconds(60)).withTimeout(Duration.ofMillis(200));
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0), options);
		ExecutionException error = assertThrows(ExecutionException.class, move::run);
		assertEquals(Duration.ofMillis(200), assertInstanceOf(OperationTimeoutException.class, error.getCause()).getTimeout());
		assertTrue(move.isFailed());
		assertTrue(m_server.requests("DELETE").isEmpty());
		m_status.set("COMPLETED");
		assertTrue(m_finished.await(3, TimeUnit.SECONDS));
		assertTrue(move.getRemoteResult().isEmpty());
	}

	@Test
	void asyncCatalogReturnsSuccessfulResult() throws Exception {
		configureAsync();
		m_status.set("COMPLETED");
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0));
		move.start();
		assertTrue(move.get(3, TimeUnit.SECONDS).reached());
		assertTrue(m_polled.await(3, TimeUnit.SECONDS));
		assertEquals("move_to_joints", move.getRemoteResult().orElseThrow().operation());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t", " joint_a", "joint_a ", "joint_a\u2003" })
	void invalidJointNameFailsBeforeCommunication(String name) {
		Map<String,Double> joints = new HashMap<>();
		joints.put(name, 0.0);
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(m_server.client(), joints));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(doubles = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
	void nonFiniteJointValueFailsBeforeCommunication(double value) {
		assertThrows(IllegalArgumentException.class,
				() -> new MoveToJoints(m_server.client(), Map.of("joint_a", value)));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0, -0.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
	void invalidSpeedFailsBeforeCommunication(double speed) {
		assertThrows(IllegalArgumentException.class,
				() -> new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0), speed));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void invalidMapClientAndTimeoutFailBeforeCommunication() {
		RobotTwinClient twin = m_server.client();
		Map<String,Double> joints = new HashMap<>();
		joints.put("joint_a", null);
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(twin, joints));
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(twin, null));
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(twin, Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(null, Map.of("joint_a", 0.0)));
		for ( Duration timeout : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1) } ) {
			assertThrows(IllegalArgumentException.class,
					() -> new MoveToJoints(twin, Map.of("joint_a", 0.0), 0.3, timeout));
		}
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void preservesCapturedJointOrderAndFractionalServerLimitInRequest() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		Map<String,Double> joints = new LinkedHashMap<>();
		joints.put("joint_z", 0.3);
		joints.put("joint_a", -0.4);
		joints.put("joint_m", 0.0);
		MoveToJoints.Options options = MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofMillis(1250));
		MoveToJoints move = new MoveToJoints(m_server.client(), joints, options);
		joints.clear();
		joints.put("wrong_joint", 9.0);
		assertTrue(m_server.requests.isEmpty());
		assertTrue(move.run().reached());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		List<String> names = new ArrayList<>();
		inputs.get("joints").fieldNames().forEachRemaining(names::add);
		assertEquals(List.of("joint_z", "joint_a", "joint_m"), names);
		assertEquals(-0.4, inputs.at("/joints/joint_a").doubleValue());
		assertTrue(inputs.get("max_duration_sec").isNumber());
		assertEquals(1.25, inputs.get("max_duration_sec").doubleValue());
	}

	@ParameterizedTest
	@CsvSource({ "120,60,120", "120,120,150", "10,60,90", "90,60,90" })
	void automaticTimeoutUsesLargerOfClientDefaultAndServerLimitWithMargin(long client, long server, long expected) {
		MoveToJoints.Options options = MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofSeconds(server));
		assertEquals(Duration.ofSeconds(expected), options.resolveTimeout(Duration.ofSeconds(client)));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void absentServerLimitAndExplicitClientTimeoutKeepExistingPolicy() {
		Duration clientDefault = Duration.ofSeconds(75);
		assertEquals(clientDefault, MoveToJoints.Options.DEFAULT.resolveTimeout(clientDefault));
		MoveToJoints.Options options = MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofSeconds(60))
				.withTimeout(Duration.ofSeconds(1));
		assertEquals(Duration.ofSeconds(1), options.resolveTimeout(clientDefault));
		assertEquals(Duration.ofSeconds(90), options.withTimeout(null).resolveTimeout(clientDefault));
		assertEquals(clientDefault, options.withTimeout(null).withMaxDuration(null).resolveTimeout(clientDefault));
	}

	@ParameterizedTest
	@ValueSource(longs = { 1, 500, 121000 })
	void deploymentValidatesServerLimitRangeInsteadOfClient(long millis) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return FakeTwinServer.json(400, Map.of("error", Map.of("code", "INVALID_INPUT", "message", "server range")));
		});
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0),
				MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofMillis(millis)));
		ExecutionException error = assertThrows(ExecutionException.class, move::run);
		assertEquals("INVALID_INPUT", assertInstanceOf(TwinException.class, error.getCause()).getCode());
		assertEquals(millis / 1000.0, JsonMapper.builder().build().readTree(body.get()).at("/inputs/max_duration_sec").doubleValue());
	}

	@Test
	void invalidOptionsAndUnrepresentableAutomaticTimeoutAreRejectedLocally() {
		for ( Duration duration : new Duration[] { Duration.ZERO, Duration.ofNanos(-1) } ) {
			assertThrows(IllegalArgumentException.class, () -> MoveToJoints.Options.DEFAULT.withMaxDuration(duration));
			assertThrows(IllegalArgumentException.class, () -> MoveToJoints.Options.DEFAULT.withTimeout(duration));
		}
		assertThrows(IllegalArgumentException.class, () -> MoveToJoints.Options.DEFAULT.withVelocityScaling(Double.NaN));
		assertThrows(IllegalArgumentException.class,
				() -> new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0), (MoveToJoints.Options)null));
		assertThrows(IllegalArgumentException.class, () -> new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0),
				MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofSeconds(Long.MAX_VALUE))));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void serverTimeoutReturnsItsFailureResultWithConfiguredServerLimit() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.operation("move_to_joints", "async");
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + SESSION));
		});
		m_server.on("GET", SESSION, req -> FakeTwinServer.json(200,
				Map.of("status", "FAILED", "error", Map.of("code", "TIMEOUT", "message", "server limit exceeded"))));
		MoveToJoints move = new MoveToJoints(m_server.client(), Map.of("joint_a", 0.0),
				MoveToJoints.Options.DEFAULT.withMaxDuration(Duration.ofSeconds(2)));
		MoveToJoints.Check check = move.run();
		assertTrue(move.isCompleted());
		assertFalse(check.reached());
		assertEquals("TIMEOUT", check.result().errorCode());
		assertSame(check.result(), move.getRemoteResult().orElseThrow());
		assertEquals(2, JsonMapper.builder().build().readTree(body.get()).at("/inputs/max_duration_sec").intValue());
		assertTrue(m_server.requests("DELETE").isEmpty());
	}

	private void configureAsync() {
		m_status = new AtomicReference<>("RUNNING");
		m_polled = new CountDownLatch(1);
		m_finished = new CountDownLatch(1);
		m_server.operation("move_to_joints", "async");
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + SESSION)));
		m_server.on("GET", SESSION, req -> {
			m_polled.countDown();
			if ( m_status.get().equals("COMPLETED") ) {
				m_finished.countDown();
				return completed();
			}
			return FakeTwinServer.json(200, Map.of("status", "RUNNING"));
		});
	}

	private static MockResponse completed() {
		return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("closed_loop", true)));
	}
}
