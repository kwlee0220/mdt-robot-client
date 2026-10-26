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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.OperationResult;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.Ops;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

import utils.RuntimeInterruptedException;

@Timeout(10)
class MoveToNamedTargetTest {
	private static final String MOVE = "/operations/move_to_named_target";
	private static final String SESSION = "/operations/sessions/named1";
	private FakeTwinServer m_server;
	private AtomicReference<String> m_remoteStatus;
	private CountDownLatch m_terminalPolled;

	@BeforeEach
	void setUp() throws Exception {
		m_server = new FakeTwinServer().operation("move_to_named_target", "sync");
	}

	@AfterEach
	void tearDown() throws Exception {
		try {
			if ( m_remoteStatus != null ) {
				m_remoteStatus.set("COMPLETED");
				assertTrue(m_terminalPolled.await(3, TimeUnit.SECONDS), "poller must finish before server shutdown");
			}
		}
		finally {
			m_server.close();
		}
	}

	@Test
	void optionsCanOmitSpeedAndSendFractionalServerLimit() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		MoveToNamedTarget.Options options = MoveToNamedTarget.Options.DEFAULT.withVelocityScaling(null)
				.withMaxDuration(Duration.ofMillis(1250)).withTimeout(Duration.ofSeconds(90));
		assertTrue(new MoveToNamedTarget(m_server.client(), "ready", options).run().reached());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).path("inputs");
		assertEquals("ready", inputs.path("target").asText());
		assertFalse(inputs.has("velocity_scaling"));
		assertEquals(1.25, inputs.path("max_duration_sec").doubleValue());
		assertEquals(0.3, MoveToNamedTarget.Options.DEFAULT.velocityScaling().doubleValue());
	}

	@Test
	void optionsKeepDefaultLimitOrUseServerMarginUnlessTimeoutIsExplicit() {
		MoveToNamedTarget.Options options = MoveToNamedTarget.Options.DEFAULT;
		Duration clientDefault = Duration.ofSeconds(120);
		assertEquals(clientDefault, options.resolveTimeout(clientDefault));
		assertEquals(clientDefault, options.withMaxDuration(Duration.ofSeconds(60)).resolveTimeout(clientDefault));
		assertEquals(Duration.ofSeconds(150), options.withMaxDuration(Duration.ofSeconds(120)).resolveTimeout(clientDefault));
		assertEquals(Duration.ofSeconds(90), options.withMaxDuration(Duration.ofSeconds(120))
				.withTimeout(Duration.ofSeconds(90)).resolveTimeout(clientDefault));
		assertThrows(IllegalArgumentException.class, () -> options.withMaxDuration(Duration.ofSeconds(Long.MAX_VALUE))
				.resolveTimeout(clientDefault));
	}

	@Test
	void invalidOptionsAreRejectedBeforeCommunication() {
		for (double speed : new double[]{0, -1, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertThrows(IllegalArgumentException.class, () -> MoveToNamedTarget.Options.DEFAULT.withVelocityScaling(speed));
		}
		for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
			assertThrows(IllegalArgumentException.class, () -> MoveToNamedTarget.Options.DEFAULT.withMaxDuration(duration));
			assertThrows(IllegalArgumentException.class, () -> MoveToNamedTarget.Options.DEFAULT.withTimeout(duration));
		}
		assertThrows(IllegalArgumentException.class, () -> new MoveToNamedTarget(m_server.client(), "ready", (MoveToNamedTarget.Options)null));
		assertThrows(IllegalArgumentException.class, () -> new MoveToNamedTarget(m_server.client(), "ready", 0.3, null));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "false", "null", "missing", "\"true\"", "\"false\"", "1", "0", "{}", "[]" })
	void closedLoopDeterminesReachedWithoutAdditionalObservation(String closedLoop) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			String outputs = "missing".equals(closedLoop) ? "{}" : "{\"closed_loop\":" + closedLoop + "}";
			return new MockResponse().setHeader("Content-Type", "application/json")
					.setBody("{\"status\":\"COMPLETED\",\"outputs\":" + outputs + "}");
		});
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		assertTrue(m_server.requests.isEmpty());
		assertTrue(move.getRemoteResult().isEmpty());
		MoveToNamedTarget.Check check = move.run();
		assertSame(check.result(), move.getRemoteResult().orElseThrow());
		assertEquals("true".equals(closedLoop), check.reached());
		assertEquals("move_to_named_target", check.result().operation());
		assertTrue(move.isCompleted());
		if ( check.reached() ) { assertNull(check.why()); }
		else if ( closedLoop.equals("false") ) { assertTrue(check.why().contains("open loop")); }
		else if ( closedLoop.equals("missing") || closedLoop.equals("null") ) {
			assertTrue(check.why().contains("정보 없음"));
			assertFalse(check.why().contains("open loop"));
		}
		else {
			assertTrue(check.why().contains("형식 오류"));
			assertTrue(check.why().contains("받은 값: " + closedLoop));
		}
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals("ready", inputs.get("target").asText());
		assertEquals(MoveToNamedTarget.DEFAULT_VELOCITY_SCALING, inputs.get("velocity_scaling").asDouble());
		assertTrue(m_server.requests("GET /variables").isEmpty());
		assertEquals(check, Ops.moveToNamedTargetVerified(m_server.client(), "ready"));
		JsonNode legacyInputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals(inputs, legacyInputs, "the default overload must use the same inputs as the constructor");
	}

	@Test
	void explicitSpeedIsPreservedAndQueuedExecutionRunsOnce() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "home", 0.15, Duration.ofSeconds(5));
		List<Runnable> queued = new ArrayList<>();
		move.setExecutor(queued::add);
		RobotTwinOperation<MoveToNamedTarget.Check> operation = move;
		operation.start();
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, move::run);
		assertFalse(operation.cancel(true));
		assertEquals(1, queued.size());
		assertTrue(m_server.requests.isEmpty());
		queued.get(0).run();
		assertTrue(operation.get().reached());
		assertThrows(IllegalStateException.class, move::run);
		assertEquals(1, m_server.requests("POST " + MOVE).size());
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals("home", inputs.get("target").asText());
		assertEquals(0.15, inputs.get("velocity_scaling").asDouble());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED" })
	void remoteFailureIsAnUnreachedResult(String status) throws Exception {
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(200, Map.of("status", status,
				"error", Map.of("code", "PLANNING_FAILED", "message", "no plan"))));
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		MoveToNamedTarget.Check check = move.run();
		assertFalse(check.reached());
		assertEquals(status, check.result().status());
		if ( status.equals("FAILED") ) {
			assertEquals(Ops.FAILURE_HINTS.get("PLANNING_FAILED"), check.why());
		}
		else {
			assertEquals("원격 연산 상태: CANCELLED", check.why());
		}
		assertTrue(move.isCompleted());
		assertEquals(check, Ops.moveToNamedTargetVerified(m_server.client(), "ready"));
	}

	@ParameterizedTest
	@CsvSource({ "400,false", "400,true", "409,false", "409,true" })
	void rejectionPreservesDomainExceptionAndCompatibilityContract(int status, boolean asynchronous) throws Exception {
		String code = status == 400 ? "INVALID_INPUT" : "RESOURCE_BUSY";
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(status,
				Map.of("error", Map.of("code", code, "message", "rejected"))));
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "unknown");
		ExecutionException failure;
		if ( asynchronous ) {
			move.start();
			failure = assertThrows(ExecutionException.class, () -> move.get(3, TimeUnit.SECONDS));
		}
		else { failure = assertThrows(ExecutionException.class, move::run); }
		TwinException cause = assertInstanceOf(TwinException.class, failure.getCause());
		assertEquals(status, cause.getHttpStatus());
		assertEquals(code, cause.getCode());
		assertTrue(move.isFailed());
		assertTrue(move.getRemoteResult().isEmpty());
		TwinException legacy = assertThrows(TwinException.class,
				() -> Ops.moveToNamedTargetVerified(m_server.client(), "unknown"));
		assertEquals(cause.getCode(), legacy.getCode());
	}

	@Test
	void preStartCancelSendsNoRequests() {
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		assertTrue(move.cancel(false));
		assertTrue(move.getRemoteResult().isEmpty());
		assertThrows(CancellationException.class, move::get);
		assertThrows(IllegalStateException.class, move::start);
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "CANCELLED", "COMPLETED", "FAILED" })
	void acceptedCancelPreservesActualRemoteTerminalState(String terminalStatus) throws Exception {
		configureRemote();
		m_server.on("DELETE", SESSION, req -> FakeTwinServer.json(202, Map.of("status", "RUNNING")));
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		Thread worker = startOnWorker(move);
		try {
			awaitRemoteWait(worker);
			assertFalse(move.cancel(false));
			assertTrue(move.cancel(true));
			assertTrue(move.cancel(true));
			assertFalse(move.isDone());
			assertTrue(move.getRemoteResult().isEmpty());
			assertThrows(TimeoutException.class, () -> move.get(20, TimeUnit.MILLISECONDS));
			assertEquals(1, m_server.requests("DELETE").size());
			m_remoteStatus.set(terminalStatus);
			assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
			assertTrue(move.isCancelled());
			OperationResult remote = move.getRemoteResult().orElseThrow();
			assertEquals(terminalStatus, remote.status());
			if ( terminalStatus.equals("COMPLETED") ) { assertTrue(remote.output("closed_loop").booleanValue()); }
			if ( terminalStatus.equals("FAILED") ) { assertEquals("EXECUTION_ABORTED", remote.errorCode()); }
		}
		finally { finishWorker(worker); }
	}

	@Test
	void rejectedCancelKeepsExecutionRunning() throws Exception {
		configureRemote();
		m_server.on("DELETE", SESSION, req -> FakeTwinServer.json(409,
				Map.of("error", Map.of("code", "CANCEL_REJECTED", "message", "cannot cancel"))));
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		Thread worker = startOnWorker(move);
		try {
			awaitRemoteWait(worker);
			assertFalse(move.cancel(true));
			assertTrue(move.isRunning());
			m_remoteStatus.set("COMPLETED");
			assertTrue(move.get(3, TimeUnit.SECONDS).reached());
		}
		finally { finishWorker(worker); }
	}

	@Test
	void explicitTimeoutDoesNotAutomaticallyCancelRemote() throws Exception {
		configureRemote();
		m_server.on("DELETE", SESSION, req -> {
			m_remoteStatus.set("CANCELLED");
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		Duration timeout = Duration.ofMillis(200);
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready", 0.3, timeout);
		OperationTimeoutException cause = assertInstanceOf(OperationTimeoutException.class,
				assertThrows(ExecutionException.class, move::run).getCause());
		assertEquals(timeout, cause.getTimeout());
		assertEquals("move_to_named_target", cause.getOperation());
		assertTrue(move.isFailed());
		assertTrue(m_server.requests("DELETE").isEmpty());
		assertTrue(move.getRemoteResult().isEmpty());
		cause.cancel();
		assertEquals(1, m_server.requests("DELETE").size());
		assertTrue(m_terminalPolled.await(3, TimeUnit.SECONDS));
		assertTrue(move.getRemoteResult().isEmpty(), "late poller completion must not fill the snapshot");
	}

	@ParameterizedTest
	@ValueSource(strings = { "run", "start", "legacy" })
	void interruptionPreservesEachEntryPointContract(String entryPoint) throws Exception {
		configureRemote();
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean flag = new AtomicBoolean();
		List<Runnable> queued = new ArrayList<>();
		if ( entryPoint.equals("start") ) {
			move.setExecutor(queued::add);
			move.start();
		}
		Thread worker = new Thread(() -> {
			try {
				switch ( entryPoint ) {
					case "run" -> move.run();
					case "start" -> queued.get(0).run();
					case "legacy" -> Ops.moveToNamedTargetVerified(m_server.client(), "ready");
				}
			}
			catch ( Throwable e ) { thrown.set(e); }
			finally { flag.set(Thread.currentThread().isInterrupted()); }
		}, "named-target-interruption-test");
		worker.setDaemon(true);
		worker.start();
		try {
			awaitRemoteWait(worker);
			worker.interrupt();
			worker.join(3000);
			assertFalse(worker.isAlive());
			if ( entryPoint.equals("legacy") ) {
				assertInstanceOf(RuntimeInterruptedException.class, thrown.get());
				assertInstanceOf(InterruptedException.class, thrown.get().getCause());
				assertTrue(flag.get());
			}
			else {
				if ( entryPoint.equals("run") ) { assertInstanceOf(InterruptedException.class, thrown.get()); }
				else { assertNull(thrown.get()); }
				assertTrue(move.isCancelled());
				assertThrows(CancellationException.class, move::get);
				assertFalse(flag.get());
			}
			assertTrue(m_server.requests("DELETE").isEmpty());
		}
		finally { finishWorker(worker); }
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n", "\u2003", " ready", "ready ", "\tready", "ready\u2003" })
	void invalidTargetIsRejectedBeforeAnyRequest(String target) {
		assertThrows(IllegalArgumentException.class, () -> new MoveToNamedTarget(m_server.client(), target));
		assertThrows(IllegalArgumentException.class, () -> Ops.moveToNamedTargetVerified(m_server.client(), target));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0, -0.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
	void invalidVelocityIsRejectedBeforeAnyRequest(double velocity) {
		assertThrows(IllegalArgumentException.class,
				() -> new MoveToNamedTarget(m_server.client(), "ready", velocity));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(doubles = { Double.MIN_VALUE, 0.005, 0.01, 1.0 })
	void commonValidVelocityRangeDoesNotHardcodeDeploymentMinimum(double velocity) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed();
		});
		assertTrue(new MoveToNamedTarget(m_server.client(), "ready", velocity).run().reached());
		assertEquals(velocity, JsonMapper.builder().build().readTree(body.get())
				.path("inputs").path("velocity_scaling").asDouble());
	}

	@Test
	void deploymentSpecificMinimumIsStillValidatedByServer() {
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(400,
				Map.of("error", Map.of("code", "INVALID_INPUT", "message", "minimum is 0.01"))));
		ExecutionException error = assertThrows(ExecutionException.class,
				() -> new MoveToNamedTarget(m_server.client(), "ready", 0.005).run());
		assertEquals("INVALID_INPUT", assertInstanceOf(TwinException.class, error.getCause()).getCode());
		assertEquals(1, m_server.requests("POST " + MOVE).size());
	}

	@Test
	void communicationFailureLeavesRemoteResultEmpty() {
		m_server.on("POST", MOVE, req -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
		MoveToNamedTarget move = new MoveToNamedTarget(m_server.client(), "ready");
		assertThrows(ExecutionException.class, move::run);
		assertTrue(move.isFailed());
		assertTrue(move.getRemoteResult().isEmpty());
	}

	private void configureRemote() {
		m_remoteStatus = new AtomicReference<>("RUNNING");
		m_terminalPolled = new CountDownLatch(1);
		m_server.operation("move_to_named_target", "async")
				.on("POST", MOVE, req -> FakeTwinServer.json(202, Map.of("status", "RUNNING",
						"session_endpoint", FakeTwinServer.BASE + SESSION)))
				.on("GET", SESSION, req -> {
					String status = m_remoteStatus.get();
					if ( !status.equals("RUNNING") ) { m_terminalPolled.countDown(); }
					if ( status.equals("FAILED") ) {
						return FakeTwinServer.json(200, Map.of("status", status,
								"error", Map.of("code", "EXECUTION_ABORTED", "message", "aborted")));
					}
					return status.equals("COMPLETED") ? completed() : FakeTwinServer.json(200, Map.of("status", status));
				});
	}

	private void awaitRemoteWait(Thread worker) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while ( worker.isAlive() && System.nanoTime() < deadline ) {
			Thread.State state = worker.getState();
			if ( !m_server.requests("GET " + SESSION).isEmpty()
					&& (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) ) {
				for ( StackTraceElement frame : worker.getStackTrace() ) {
					if ( frame.getClassName().equals(RobotTwinClient.class.getName()) && frame.getMethodName().equals("runAsync") ) {
						return;
					}
				}
			}
			Thread.sleep(5);
		}
		fail("worker did not enter the remote result wait");
	}

	private void finishWorker(Thread worker) throws Exception {
		m_remoteStatus.set("COMPLETED");
		worker.join(3000);
		if ( worker.isAlive() ) { worker.interrupt(); worker.join(1000); }
		assertFalse(worker.isAlive());
	}

	private static Thread startOnWorker(MoveToNamedTarget move) {
		AtomicReference<Thread> worker = new AtomicReference<>();
		move.setExecutor(command -> {
			Thread thread = new Thread(command, "named-target-execution-test");
			thread.setDaemon(true);
			worker.set(thread);
			thread.start();
		});
		move.start();
		return worker.get();
	}

	private static MockResponse completed() {
		return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("closed_loop", true)));
	}
}
