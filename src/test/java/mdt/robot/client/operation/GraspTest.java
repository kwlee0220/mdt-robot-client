package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.TwinException;

@Timeout(10)
class GraspTest {
	private static final String GRIPPER = "/operations/move_gripper_to_target";
	private static final String MOVE = "/operations/move_linear";
	private static final String SESSION = "/operations/sessions/m1";
	private static final Pose ABOVE = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
	private static final Pose TARGET = ABOVE.withPosition(new Position(0.4, 0.1, 0.1));
	private static final Grasp.Options FAST = new Grasp.Options(MoveLinear.Options.DEFAULT,
			MoveLinear.Options.DEFAULT.withVelocityScaling(0.1), "open", "grasp");
	private FakeTwinServer server;
	private final List<JsonNode> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final AtomicInteger calls = new AtomicInteger();

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync").operation("move_gripper_to_target", "sync");
		server.on("POST", GRIPPER, this::success).on("POST", MOVE, this::success);
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test
	void invalidMoveOptionsFailBeforeAnyCompositeStage() {
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), ABOVE, TARGET, new Grasp.Options(FAST.approach(),
				MoveLinear.Options.DEFAULT.withVelocityScaling(Double.NaN), "open", "grasp")));
		assertTrue(server.requests.isEmpty());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void runsExactlyFourStagesAndPreservesInputsAndEvidence(boolean async) throws Exception {
		Grasp.Options options = new Grasp.Options(FAST.approach(),
				FAST.descent(), "custom_open", "custom_grasp");
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, options);
		assertTrue(server.requests.isEmpty());
		assertTrue(grasp.getProgress().steps().isEmpty());
		Grasp.Progress initial = grasp.getProgress();
		assertFalse(initial.graspAttempted());
		assertEquals(ChildCancellationState.NOT_REQUESTED, initial.childCancellationState());
		Grasp.Check check;
		if ( async ) { grasp.start(); check = grasp.get(3, TimeUnit.SECONDS); }
		else { check = grasp.run(); }
		assertTrue(check.goalReached());
		assertTrue(check.graspAttempted());
		assertTrue(grasp.getProgress().graspAttempted());
		assertFalse(initial.graspAttempted(), "earlier snapshot is unchanged");
		assertTrue(grasp.isCompleted());
		assertNull(check.failedStage());
		assertNull(check.failureReason());
		assertEquals(List.of(Grasp.Stage.values()), check.steps().stream().map(StepResult::stage).toList());
		assertTrue(check.steps().stream().allMatch(StepResult::goalReached));
		assertEquals("custom_open", bodies.get(0).path("target").asText());
		assertEquals(ABOVE.toJson(), bodies.get(1).get("pose"));
		assertEquals(TARGET.toJson(), bodies.get(2).get("pose"));
		assertEquals(0.2, bodies.get(1).path("velocity_scaling").asDouble());
		assertEquals(0.1, bodies.get(2).path("velocity_scaling").asDouble());
		assertFalse(bodies.get(2).has("frame"));
		assertEquals("custom_grasp", bodies.get(3).path("target").asText());
		GripperCheck gripper = check.steps().get(3).verdictAs(GripperCheck.class).orElseThrow();
		assertTrue(gripper.goalReached());
		assertFalse(gripper.stalled(), "stalled does not override at_goal");
		assertTrue(check.steps().stream().allMatch(s -> s.remoteResult().orElseThrow().isCompleted()));
		assertEquals(check.steps(), grasp.getProgress().steps());
		assertTrue(grasp.getProgress().activeStage().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> check.steps().clear());
		assertEquals(4, server.requests("POST").size(), "no lift, recovery, or additional command");
		assertTrue(server.requests("GET /variables").isEmpty());
		assertFalse(grasp.cancel(true));
	}

	@ParameterizedTest @ValueSource(ints = { 1, 2, 3, 4 })
	void unconfirmedGoalStopsAtThatStage(int failed) throws Exception {
		installFailure(failed, "goal");
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		Grasp.Check check = grasp.run();
		assertFalse(check.goalReached());
		assertEquals(Grasp.Stage.values()[failed - 1], check.failedStage());
		assertNotNull(check.failureReason());
		assertEquals(failed, check.steps().size());
		assertEquals(failed == 4, check.graspAttempted());
		assertEquals(check.graspAttempted(), grasp.getProgress().graspAttempted());
		assertEquals(failed, server.requests("POST").size());
		assertTrue(grasp.isCompleted());
	}

	@ParameterizedTest
	@CsvSource({ "1,FAILED", "2,FAILED", "3,FAILED", "4,FAILED", "1,CANCELLED", "2,CANCELLED", "3,CANCELLED", "4,CANCELLED" })
	void remoteFailureRemainsNormalResultAndNoAutomaticRecovery(int failed, String status) throws Exception {
		installFailure(failed, status);
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		Grasp.Check check = grasp.run();
		assertFalse(check.goalReached());
		assertEquals(status, check.steps().get(failed - 1).remoteResult().orElseThrow().status().name());
		assertEquals(failed == 4, check.graspAttempted());
		assertEquals(check.graspAttempted(), grasp.getProgress().graspAttempted());
		assertEquals(failed, calls.get());
		assertTrue(grasp.isCompleted());
	}

	@ParameterizedTest @ValueSource(ints = { 1, 2, 3, 4 })
	void rejectionKeepsPriorStepsAndOriginalDomainException(int failed) {
		installFailure(failed, "rejected");
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		ExecutionException failure = assertThrows(ExecutionException.class, grasp::run);
		TwinException cause = assertInstanceOf(TwinException.class, failure.getCause());
		assertEquals(409, cause.getHttpStatus());
		assertTrue(grasp.isFailed());
		Grasp.Progress progress = grasp.getProgress();
		assertEquals(failed == 4, progress.graspAttempted(), "attempt does not imply server acceptance");
		assertEquals(failed, progress.steps().size());
		assertSame(cause, progress.steps().get(failed - 1).failure());
		assertTrue(progress.steps().get(failed - 1).remoteResult().isEmpty());
		assertTrue(progress.activeStage().isEmpty());
		assertEquals(failed, calls.get());
	}

	@Test void defaultOptionsAndSettlingFailure() throws Exception {
		assertEquals(Duration.ofSeconds(10), Grasp.Options.DEFAULT.approach().settleTimeout());
		assertEquals(Duration.ofSeconds(10), Grasp.Options.DEFAULT.descent().settleTimeout());
		server.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(200, Map.of("quality", "STALE")));
		Grasp.Options options = new Grasp.Options(FAST.approach().withSettleTimeout(Duration.ofMillis(80)), FAST.descent(), "open", "grasp");
		Grasp.Check check = new Grasp(server.client(), ABOVE, TARGET, options).run();
		assertEquals(Grasp.Stage.APPROACH, check.failedStage());
		assertEquals(MoveCheck.SETTLE_TIMEOUT, check.steps().get(1).verdictAs(MoveCheck.class).orElseThrow().failureCode());
		assertTrue(check.failureReason().contains("NO_VALID_SAMPLE"));
		assertEquals(2, calls.get());
	}

	@ParameterizedTest @ValueSource(ints = { 1, 4 })
	void pendingCancellationDuringSyncGripperWaitsAndPreservesCompletedResult(int stopAt) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", GRIPPER, req -> {
			if ( calls.get() + 1 == stopAt ) { entered.countDown(); await(release); }
			return success(req);
		});
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST, Duration.ofMillis(1));
		grasp.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			Grasp.Progress snapshot = grasp.getProgress();
			assertEquals(stopAt == 4, snapshot.graspAttempted());
			assertEquals(Grasp.Stage.values()[stopAt - 1], snapshot.activeStage().orElseThrow());
			assertFalse(grasp.cancel(false));
			assertTrue(grasp.cancel(true));
			assertFalse(grasp.getProgress().childCancellationAccepted());
			assertEquals(ChildCancellationState.REJECTED, grasp.getProgress().childCancellationState());
			assertTrue(grasp.getProgress().cancellationRequested());
			assertThrows(TimeoutException.class, () -> grasp.get(20, TimeUnit.MILLISECONDS));
			release.countDown();
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			assertEquals(stopAt, grasp.getProgress().steps().size());
			assertEquals(stopAt == 4, grasp.getProgress().graspAttempted());
			assertTrue(grasp.getProgress().steps().get(stopAt - 1).goalReached());
			assertEquals(stopAt - 1, snapshot.steps().size(), "old progress list remains unchanged");
			assertEquals(stopAt, calls.get());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	@ParameterizedTest @ValueSource(ints = { 202, 409, 500 })
	void asyncCancelIsForwardedAndItsFailureNeverStartsNextStage(int deleteStatus) throws Exception {
		AtomicBoolean done = new AtomicBoolean();
		CountDownLatch polled = new CountDownLatch(1);
		asyncMove(done, polled);
		server.on("DELETE", SESSION, req -> deleteStatus == 202
				? FakeTwinServer.json(202, Map.of("status", "RUNNING", "phase", "CANCELING"))
				: FakeTwinServer.json(deleteStatus, Map.of("error", Map.of("code", "REJECTED", "message", "cannot cancel"))));
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		grasp.start();
		try {
			assertTrue(polled.await(3, TimeUnit.SECONDS));
			assertTrue(grasp.cancel(true));
			assertEquals(deleteStatus == 202, grasp.getProgress().childCancellationAccepted());
			if ( deleteStatus == 500 ) { assertInstanceOf(TwinException.class, grasp.getProgress().cancellationFailure()); }
			else { assertNull(grasp.getProgress().cancellationFailure()); }
			assertTrue(grasp.cancel(true));
			assertThrows(TimeoutException.class, () -> grasp.get(20, TimeUnit.MILLISECONDS));
			done.set(true);
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			assertEquals(2, server.requests("POST").size());
			assertEquals(1, server.requests("DELETE").size());
			assertTrue(grasp.getProgress().steps().get(1).remoteResult().orElseThrow().isCompleted());
		}
		finally { done.set(true); }
	}

	@Test void cancelBeforeAsyncSessionExistsIsDeliveredOnceTheSessionExists() throws Exception {
		// 이동 단계의 시작 POST 응답을 기다리는 동안의 취소: 하위 연산이 접수하고 세션을 받는 즉시 DELETE를 보낸다.
		server.operation("move_linear", "async");
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean deleted = new AtomicBoolean();
		server.on("POST", MOVE, req -> {
			entered.countDown(); await(release);
			return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + SESSION));
		});
		server.on("DELETE", SESSION, req -> {
			deleted.set(true);
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		server.on("GET", SESSION, req -> deleted.get() ? FakeTwinServer.json(200, Map.of("status", "CANCELLED"))
														: FakeTwinServer.json(200, Map.of("status", "RUNNING")));
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		grasp.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(grasp.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			assertEquals(2, server.requests("POST").size());
			assertEquals(1, server.requests("DELETE").size());
			assertEquals(ChildCancellationState.ACCEPTED, grasp.getProgress().childCancellationState());
		}
		finally { release.countDown(); }
	}

	@ParameterizedTest @ValueSource(ints = { 202, 409, 500 })
	void slowDeleteDoesNotBlockProgressOrRepeatedCancellation(int deleteStatus) throws Exception {
		AtomicBoolean done = new AtomicBoolean();
		CountDownLatch polled = new CountDownLatch(1), completedPoll = new CountDownLatch(1);
		CountDownLatch deleting = new CountDownLatch(1), releaseDelete = new CountDownLatch(1);
		asyncMove(done, polled);
		server.on("GET", SESSION, req -> {
			polled.countDown();
			if ( done.get() ) {
				completedPoll.countDown();
				return completed(Map.of("final_pose", ABOVE));
			}
			return FakeTwinServer.json(200, Map.of("status", "RUNNING"));
		});
		server.on("DELETE", SESSION, req -> {
			deleting.countDown(); await(releaseDelete);
			return deleteStatus == 202 ? FakeTwinServer.json(202, Map.of("status", "RUNNING", "phase", "CANCELING"))
					: FakeTwinServer.json(deleteStatus, Map.of("error", Map.of("code", "REJECTED", "message", "cannot cancel")));
		});
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		var observers = Executors.newFixedThreadPool(2);
		grasp.start();
		try {
			assertTrue(polled.await(3, TimeUnit.SECONDS));
			var cancellation = observers.submit(() -> grasp.cancel(true));
			assertTrue(deleting.await(3, TimeUnit.SECONDS));
			Grasp.Progress during = observers.submit(grasp::getProgress).get(1, TimeUnit.SECONDS);
			assertEquals(ChildCancellationState.IN_PROGRESS, during.childCancellationState());
			assertTrue(during.cancellationRequested());
			assertFalse(during.childCancellationAccepted());
			assertFalse(during.graspAttempted());
			assertFalse(cancellation.isDone());
			assertTrue(observers.submit(() -> grasp.cancel(true)).get(1, TimeUnit.SECONDS));
			done.set(true);
			assertTrue(completedPoll.await(3, TimeUnit.SECONDS));
			// 원격 완료와 지연 DELETE가 겹쳐도 부모 진행 조회를 막지 않는다.
			Grasp.Progress completing = observers.submit(grasp::getProgress).get(1, TimeUnit.SECONDS);
			assertEquals(ChildCancellationState.IN_PROGRESS, completing.childCancellationState());
			assertEquals(2, server.requests("POST").size());
			releaseDelete.countDown();
			assertTrue(cancellation.get(3, TimeUnit.SECONDS));
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			Grasp.Progress after = grasp.getProgress();
			assertEquals(switch ( deleteStatus ) {
				case 202 -> ChildCancellationState.ACCEPTED;
				case 409 -> ChildCancellationState.REJECTED;
				default -> ChildCancellationState.FAILED;
			}, after.childCancellationState());
			assertEquals(deleteStatus == 202, after.childCancellationAccepted());
			if ( deleteStatus == 500 ) { assertInstanceOf(TwinException.class, after.cancellationFailure()); }
			else { assertNull(after.cancellationFailure()); }
			assertTrue(after.steps().get(1).remoteResult().orElseThrow().isCompleted());
			assertEquals(ChildCancellationState.IN_PROGRESS, during.childCancellationState(), "old snapshot is stable");
			assertEquals(1, server.requests("DELETE").size());
			assertEquals(2, server.requests("POST").size());
		}
		finally {
			done.set(true); releaseDelete.countDown();
			observers.shutdownNow();
			assertTrue(observers.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test void cancellationDuringSettlingStopsObservationAndKeepsRemoteResult() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", "/variables/ee_pose", req -> {
			entered.countDown(); await(release);
			return FakeTwinServer.json(200, Map.of("quality", "STALE"));
		});
		Grasp.Options options = new Grasp.Options(FAST.approach().withSettleTimeout(Duration.ofSeconds(3)), FAST.descent(), "open", "grasp");
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, options);
		grasp.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(grasp.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			assertTrue(grasp.getProgress().steps().get(1).remoteResult().orElseThrow().isCompleted());
			assertTrue(server.requests("DELETE").isEmpty());
			assertEquals(2, calls.get());
		}
		finally { release.countDown(); }
	}

	@Test void queuedRunAndPreStartCancellationMakeNoRequests() throws Exception {
		Grasp cancelled = new Grasp(server.client(), ABOVE, TARGET, FAST);
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertThrows(IllegalStateException.class, cancelled::run);
		assertFalse(cancelled.getProgress().graspAttempted());
		assertTrue(cancelled.getProgress().cancellationRequested());
		assertEquals(ChildCancellationState.NOT_NEEDED, cancelled.getProgress().childCancellationState());
		assertTrue(server.requests.isEmpty());
		List<Runnable> queued = new ArrayList<>();
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		grasp.setExecutor(queued::add);
		grasp.start();
		assertThrows(IllegalStateException.class, grasp::start);
		assertThrows(IllegalStateException.class, grasp::run);
		assertEquals(1, queued.size());
		queued.get(0).run();
		assertTrue(grasp.get().goalReached());
		assertEquals(4, calls.get());
	}

	@ParameterizedTest @CsvSource({ "false,1", "true,1", "false,4", "true,4" })
	void externalInterruptDuringSyncHttpCancelsWithoutLaterCommands(boolean async, int stopAt) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", GRIPPER, req -> {
			if ( calls.get() + 1 != stopAt ) { return success(req); }
			entered.countDown(); await(release);
			return completed(Map.of("at_goal", true, "padding", "x".repeat(65536)));
		});
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Thread> workerRef = new AtomicReference<>();
		AtomicBoolean flag = new AtomicBoolean();
		if ( async ) {
			grasp.setExecutor(task -> {
				Thread worker = new Thread(task, "grasp-start-interrupt-test");
				workerRef.set(worker); worker.start();
			});
			grasp.start();
		}
		else {
			Thread worker = new Thread(() -> {
				try { grasp.run(); }
				catch ( Throwable e ) { thrown.set(e); }
				finally { flag.set(Thread.currentThread().isInterrupted()); }
			}, "grasp-run-interrupt-test");
			workerRef.set(worker); worker.start();
		}
		Thread worker = workerRef.get();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			worker.interrupt(); release.countDown(); worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(grasp.isCancelled());
			if ( async ) { assertThrows(CancellationException.class, grasp::get); }
			else {
				assertInstanceOf(InterruptedException.class, thrown.get());
				assertFalse(flag.get());
			}
			assertEquals(stopAt == 4, grasp.getProgress().graspAttempted());
			StepResult<Grasp.Stage> step = grasp.getProgress().steps().get(stopAt - 1);
			assertInstanceOf(InterruptedException.class, step.failure());
			assertTrue(step.remoteResult().isEmpty());
			assertEquals(stopAt, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); worker.join(3000); }
	}

	@Test void asyncTimeoutPreservesPriorResultsWithoutAdvancingOrCancellingRemote() throws Exception {
		AtomicBoolean done = new AtomicBoolean();
		asyncMove(done, new CountDownLatch(1));
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST, Duration.ofMillis(80));
		try {
			ExecutionException error = assertThrows(ExecutionException.class, grasp::run);
			assertInstanceOf(OperationTimeoutException.class, error.getCause());
			assertTrue(grasp.isFailed());
			assertEquals(2, grasp.getProgress().steps().size());
			assertTrue(grasp.getProgress().steps().get(0).goalReached());
			assertTrue(grasp.getProgress().steps().get(1).remoteResult().isEmpty());
			assertSame(error.getCause(), grasp.getProgress().steps().get(1).failure());
			assertEquals(2, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { done.set(true); }
	}

	@Test void acceptedCancellationWinsOverLateHttpFailure() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", GRIPPER, req -> {
			entered.countDown(); await(release);
			return FakeTwinServer.json(400, Map.of("error", Map.of("code", "INVALID_INPUT", "message", "bad target")));
		});
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET, FAST);
		grasp.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(grasp.cancel(true)); release.countDown();
			assertThrows(CancellationException.class, () -> grasp.get(3, TimeUnit.SECONDS));
			assertInstanceOf(TwinException.class, grasp.getProgress().steps().get(0).failure());
			assertEquals(1, server.requests("POST").size());
		}
		finally { release.countDown(); }
	}

	@ParameterizedTest @ValueSource(doubles = { -0.1, 0.0, 1.1, Double.NaN, Double.POSITIVE_INFINITY })
	void invalidDescentSpeedFailsBeforeOpening(double speed) {
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), ABOVE, TARGET,
				new Grasp.Options(FAST.approach(), FAST.descent().withVelocityScaling(speed), "open", "grasp")));
		assertTrue(server.requests.isEmpty());
	}

	@Test void invalidInputsFailBeforeOpening() {
		assertThrows(IllegalArgumentException.class, () -> new Grasp(null, ABOVE, TARGET));
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), null, TARGET));
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), ABOVE, TARGET, null));
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), ABOVE,
				TARGET.withPosition(new Position(Double.NaN, 0, 0)), FAST));
		assertThrows(IllegalArgumentException.class, () -> new Grasp.Options(FAST.approach(), FAST.descent(), "open", " grasp"));
		assertThrows(IllegalArgumentException.class, () -> new Grasp.Options(FAST.approach(), FAST.descent().withPositionToleranceM(Double.NaN), "open", "grasp"));
		assertThrows(IllegalArgumentException.class, () -> new Grasp(server.client(), ABOVE, TARGET, FAST, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new Grasp.Options(FAST.approach(),
				FAST.descent().withSettleTimeout(Duration.ofSeconds(Long.MAX_VALUE)), "open", "grasp"));
		assertTrue(server.requests.isEmpty());
	}

	private MockResponse success(RecordedRequest req) {
		calls.incrementAndGet();
		JsonNode inputs = inputs(req);
		bodies.add(inputs);
		return inputs.has("pose") ? completed(Map.of("final_pose", inputs.get("pose")))
				: completed(Map.of("at_goal", true, "stalled", false));
	}

	private void installFailure(int at, String kind) {
		java.util.function.Function<RecordedRequest,MockResponse> handler = req -> {
			if ( calls.get() + 1 != at ) { return success(req); }
			calls.incrementAndGet();
			JsonNode inputs = inputs(req);
			if ( kind.equals("goal") ) {
				return inputs.has("pose") ? completed(Map.of("final_pose", TARGET.withPosition(new Position(9, 9, 9))))
						: completed(Map.of("at_goal", false, "stalled", true));
			}
			if ( kind.equals("rejected") ) { return FakeTwinServer.json(409, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy"))); }
			return FakeTwinServer.json(200, Map.of("status", kind, "error", Map.of("code", "FAILED_STAGE", "message", "failed")));
		};
		server.on("POST", GRIPPER, handler).on("POST", MOVE, handler);
	}

	private void asyncMove(AtomicBoolean done, CountDownLatch polled) {
		server.operation("move_linear", "async");
		server.on("POST", MOVE, req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + SESSION)));
		server.on("GET", SESSION, req -> {
			polled.countDown();
			return done.get() ? completed(Map.of("final_pose", ABOVE)) : FakeTwinServer.json(200, Map.of("status", "RUNNING"));
		});
	}

	private static JsonNode inputs(RecordedRequest req) {
		try { return JsonMapper.builder().build().readTree(req.getBody().readUtf8()).path("inputs"); }
		catch ( java.io.IOException e ) { throw new IllegalStateException(e); }
	}

	private static MockResponse completed(Map<String,?> outputs) { return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs)); }
	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(5, TimeUnit.SECONDS) ) { throw new IllegalStateException("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
}
