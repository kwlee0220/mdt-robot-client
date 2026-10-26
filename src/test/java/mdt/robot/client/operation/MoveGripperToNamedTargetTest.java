package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;
import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

import utils.RuntimeInterruptedException;
import utils.http.RESTfulIOException;

@Timeout(10)
class MoveGripperToNamedTargetTest {
	private static final String MOVE = "/operations/move_gripper_to_target";
	private FakeTwinServer m_server;

	@BeforeEach
	void setUp() throws Exception {
		m_server = new FakeTwinServer().operation("move_gripper_to_target", "sync");
	}

	@AfterEach
	void tearDown() throws Exception {
		m_server.close();
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void preservesOutputsWithoutAddingAliasOrJudgingStalled(boolean atGoal) throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed(Map.of("at_goal", atGoal, "stalled", !atGoal, "width", 0.04));
		});
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "grasp");
		assertTrue(m_server.requests.isEmpty());
		assertTrue(move.getRemoteResult().isEmpty());
		GripperCheck check = move.run();
		Map<String,JsonNode> outputs = check.result().outputs();
		assertTrue(move.isCompleted(), "at_goal=false is not an execution failure");
		assertEquals(atGoal, outputs.get("at_goal").booleanValue());
		assertEquals(atGoal, check.atGoal());
		assertEquals(!atGoal, check.stalled());
		assertFalse(outputs.containsKey("reached_goal"));
		assertEquals(!atGoal, outputs.get("stalled").booleanValue());
		assertEquals(0.04, outputs.get("width").doubleValue());
		OperationResult remote = move.getRemoteResult().orElseThrow();
		assertEquals("move_gripper_to_target", remote.operation());
		assertFalse(remote.outputs().containsKey("reached_goal"), "do not modify the remote snapshot");
		JsonNode inputs = JsonMapper.builder().build().readTree(body.get()).get("inputs");
		assertEquals(Map.of("target", "grasp"), JsonMapper.builder().build().convertValue(inputs, Map.class));
		assertTrue(m_server.requests("GET /variables").isEmpty());
	}

	@Test
	void preservesExistingAliasEvenWhenItDiffersFromAtGoal() throws Exception {
		m_server.on("POST", MOVE, req -> completed(Map.of("at_goal", true, "reached_goal", false)));
		GripperCheck check = new MoveGripperToNamedTarget(m_server.client(), "open").run();
		Map<String,JsonNode> outputs = check.result().outputs();
		assertTrue(outputs.get("at_goal").booleanValue());
		assertFalse(outputs.get("reached_goal").booleanValue());
	}

	@Test
	void missingAtGoalDoesNotInventAnAliasOrInferSuccessFromStalled() throws Exception {
		m_server.on("POST", MOVE, req -> completed(Map.of("stalled", true)));
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "grasp");
		GripperCheck check = move.run();
		Map<String,JsonNode> outputs = check.result().outputs();
		assertEquals(1, outputs.size());
		assertTrue(outputs.get("stalled").booleanValue());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED" })
	void unsuccessfulRemoteResultCompletesLocally(String status) throws Exception {
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(200, Map.of("status", status,
				"error", Map.of("code", "TIMEOUT", "message", "gripper did not reach target"))));
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "grasp");
		GripperCheck check = move.run();
		assertTrue(move.isCompleted());
		assertFalse(check.atGoal());
		assertNotNull(check.why());
		assertEquals(status, check.result().status());
		assertSame(check.result(), move.getRemoteResult().orElseThrow());
		if ( status.equals("FAILED") ) { assertEquals("TIMEOUT", check.result().errorCode()); }
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 409 })
	void rejectionPreservesDomainExceptionInAsyncExecution(int status) {
		String code = status == 400 ? "INVALID_INPUT" : "RESOURCE_BUSY";
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(status,
				Map.of("error", Map.of("code", code, "message", "rejected"))));
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "unknown");
		move.start();
		ExecutionException error = assertThrows(ExecutionException.class, () -> move.get(3, TimeUnit.SECONDS));
		TwinException cause = assertInstanceOf(TwinException.class, error.getCause());
		assertEquals(status, cause.getHttpStatus());
		assertEquals(code, cause.getCode());
		assertTrue(move.isFailed());
		assertTrue(move.getRemoteResult().isEmpty());
	}

	@Test
	void queuedExecutionRunsOnceAndAllowsDeploymentSpecificNames() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		m_server.on("POST", MOVE, req -> {
			body.set(req.getBody().readUtf8());
			return completed(Map.of("at_goal", true));
		});
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "half_open");
		List<Runnable> queued = new ArrayList<>();
		move.setExecutor(queued::add);
		RobotTwinOperation<GripperCheck> operation = move;
		operation.start();
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, move::run);
		assertFalse(operation.cancel(true));
		assertEquals(1, queued.size());
		assertTrue(m_server.requests.isEmpty());
		queued.get(0).run();
		assertTrue(operation.get().atGoal());
		assertThrows(IllegalStateException.class, move::run);
		assertEquals(1, m_server.requests("POST " + MOVE).size());
		assertEquals("half_open", JsonMapper.builder().build().readTree(body.get()).at("/inputs/target").asText());
	}

	@Test
	void preStartCancellationSendsNoRequests() {
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "open");
		assertTrue(move.cancel(false));
		assertThrows(CancellationException.class, move::get);
		assertThrows(IllegalStateException.class, move::start);
		assertTrue(move.getRemoteResult().isEmpty());
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void localAsyncExecutionCannotCancelSyncHttpOrApplyAsyncTimeoutToIt() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("POST", MOVE, req -> {
			entered.countDown();
			try {
				if ( !release.await(5, TimeUnit.SECONDS) ) { return new MockResponse().setResponseCode(500); }
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return new MockResponse().setResponseCode(500);
			}
			return completed(Map.of("at_goal", true));
		});
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "open", Duration.ofMillis(1));
		move.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertFalse(move.cancel(false));
			assertFalse(move.cancel(true));
			assertThrows(TimeoutException.class, () -> move.get(30, TimeUnit.MILLISECONDS));
			assertFalse(move.isDone());
			assertTrue(move.getRemoteResult().isEmpty());
		}
		finally {
			release.countDown();
			assertTrue(move.get(3, TimeUnit.SECONDS).atGoal());
		}
		assertTrue(move.isCompleted());
		assertTrue(m_server.requests("DELETE").isEmpty());
	}

	@Test
	void followsAsyncCatalogWhenDeploymentProvidesSessions() throws Exception {
		String session = "/operations/sessions/gripper1";
		m_server.operation("move_gripper_to_target", "async");
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + session)));
		m_server.on("GET", session, req -> completed(Map.of("at_goal", true)));
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(m_server.client(), "open", Duration.ofSeconds(3));
		assertTrue(move.run().atGoal());
		assertEquals(1, m_server.requests("POST " + MOVE).size());
		assertFalse(m_server.requests("GET " + session).isEmpty());
		assertTrue(move.getRemoteResult().orElseThrow().isCompleted());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n", " open", "open ", "\topen", "open\u2003" })
	void invalidTargetIsRejectedBeforeCommunication(String target) {
		assertThrows(IllegalArgumentException.class, () -> new MoveGripperToNamedTarget(m_server.client(), target));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void invalidClientAndTimeoutAreRejectedBeforeCommunication() {
		assertThrows(IllegalArgumentException.class, () -> new MoveGripperToNamedTarget(null, "open"));
		assertThrows(IllegalArgumentException.class,
				() -> new MoveGripperToNamedTarget(m_server.client(), "open", null));
		assertThrows(IllegalArgumentException.class,
				() -> new MoveGripperToNamedTarget(m_server.client(), "open", Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> new MoveGripperToNamedTarget(m_server.client(), "open", Duration.ofSeconds(-1)));
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "false", "null", "missing", "\"true\"", "1", "{}", "[]" })
	void strictBooleanJudgmentWithoutAlias(String value) throws Exception {
		m_server.on("POST", MOVE, req -> new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"status\":\"COMPLETED\",\"outputs\":{" +
						(value.equals("missing") ? "" : "\"at_goal\":" + value + ",") + "\"stalled\":true}}"));
		GripperCheck check = new MoveGripperToNamedTarget(m_server.client(), "grasp").run();
		assertEquals(value.equals("true"), check.atGoal());
		assertTrue(check.stalled());
		assertFalse(check.result().outputs().containsKey("reached_goal"));
		if ( value.equals("true") ) { assertNull(check.why()); }
		else if ( value.equals("false") ) { assertTrue(check.why().contains("at_goal=false")); }
		else if ( value.equals("null") || value.equals("missing") ) {
			assertTrue(check.why().contains("정보 없음"));
		}
		else { assertTrue(check.why().contains("받은 값: " + value)); }
	}

	@ParameterizedTest
	@ValueSource(strings = { "false", "null", "missing", "\"true\"", "1" })
	void stalledIsStrictObservationAndDoesNotOverrideAtGoal(String value) throws Exception {
		m_server.on("POST", MOVE, req -> new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"status\":\"COMPLETED\",\"outputs\":{\"at_goal\":true" +
						(value.equals("missing") ? "" : ",\"stalled\":" + value) + "}}"));
		GripperCheck check = new MoveGripperToNamedTarget(m_server.client(), "open").run();
		assertTrue(check.atGoal());
		assertFalse(check.stalled());
	}

	@ParameterizedTest
	@ValueSource(strings = { "run", "start", "client" })
	void syncHttpInterruptionPreservesEachEntryPointContract(String entryPoint) throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		String name = entryPoint.equals("client") ? "reset_scene" : "move_gripper_to_target";
		m_server.operation(name, "sync");
		m_server.on("POST", "/operations/" + name, req -> {
			entered.countDown();
			try {
				if ( !release.await(5, TimeUnit.SECONDS) ) { return new MockResponse().setResponseCode(500); }
			}
			catch ( InterruptedException e ) { throw new AssertionError(e); }
			// A large body requires another socket read after the blocked read returns.
			return completed(Map.of("at_goal", true, "padding", "x".repeat(65536)));
		});
		RobotTwinClient twin = m_server.client();
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(twin, "open");
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean flag = new AtomicBoolean();
		AtomicReference<Thread> workerRef = new AtomicReference<>();
		if ( entryPoint.equals("start") ) {
			move.setExecutor(task -> {
				Thread worker = new Thread(task, "gripper-sync-start-test");
				workerRef.set(worker);
				worker.start();
			});
			move.start();
		}
		else {
			Thread worker = new Thread(() -> {
				try {
					switch ( entryPoint ) {
						case "run" -> move.run();
						case "client" -> twin.run("reset_scene", Map.of());
						default -> throw new AssertionError(entryPoint);
					}
				}
				catch ( Throwable e ) { thrown.set(e); }
				finally { flag.set(Thread.currentThread().isInterrupted()); }
			}, "gripper-sync-run-test");
			workerRef.set(worker);
			worker.start();
		}
		Thread worker = workerRef.get();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			worker.interrupt();
			release.countDown();
			worker.join(3000);
			assertFalse(worker.isAlive());
			if ( entryPoint.equals("start") ) {
				assertThrows(CancellationException.class, move::get);
				assertTrue(move.isCancelled());
			}
			else {
				InterruptedException cause;
				if ( entryPoint.equals("run") ) {
					cause = assertInstanceOf(InterruptedException.class, thrown.get());
					assertTrue(move.isCancelled());
					assertFalse(flag.get());
				}
				else {
					cause = assertInstanceOf(RuntimeInterruptedException.class, thrown.get()).getCause();
					assertTrue(flag.get());
				}
				RESTfulIOException transport = assertInstanceOf(RESTfulIOException.class, cause.getCause());
				assertInstanceOf(InterruptedIOException.class, transport.getCause());
			}
			assertTrue(move.getRemoteResult().isEmpty());
			assertTrue(m_server.requests("DELETE").isEmpty());
		}
		finally {
			release.countDown();
			worker.join(3000);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void syncReadTimeoutRemainsFailureNotCancellation(boolean asynchronous) throws Exception {
		m_server.on("POST", MOVE, req -> completed(Map.of("at_goal", true)).setHeadersDelay(500, TimeUnit.MILLISECONDS));
		RobotTwinClient twin = RobotTwinClient.builder().baseUrl(m_server.client().getBaseUrl())
				.httpTimeout(Duration.ofMillis(100)).build();
		MoveGripperToNamedTarget move = new MoveGripperToNamedTarget(twin, "open");
		ExecutionException error;
		if ( asynchronous ) {
			move.start();
			error = assertThrows(ExecutionException.class, () -> move.get(3, TimeUnit.SECONDS));
		}
		else { error = assertThrows(ExecutionException.class, move::run); }
		RESTfulIOException cause = assertInstanceOf(RESTfulIOException.class, error.getCause());
		assertInstanceOf(SocketTimeoutException.class, cause.getCause());
		assertTrue(move.isFailed());
		assertFalse(Thread.currentThread().isInterrupted());
		assertTrue(move.getRemoteResult().isEmpty());
		assertTrue(m_server.requests("DELETE").isEmpty());
	}

	private static MockResponse completed(Map<String,?> outputs) {
		return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs));
	}
}
