package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.MockResponse;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.OperationResult;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

@Timeout(10)
class MoveLinearTest {

	private static final Pose TARGET = new Pose(new Position(0.4, 0.0, 0.1), Geometry.DOWN);
	private static final String MOVE = "/operations/move_linear";
	private FakeTwinServer m_server;

	@BeforeEach
	void setUp() throws Exception {
		m_server = new FakeTwinServer().operation("move_linear", "sync");
	}

	@AfterEach
	void tearDown() throws Exception {
		m_server.close();
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.401, 0.42 })
	void snapshotDeterminesReachedAndRequestUsesOptions(double finalX) throws Exception {
		List<String> bodies = new CopyOnWriteArrayList<>();
		m_server.on("POST", MOVE, req -> {
			bodies.add(req.getBody().readUtf8());
			return completed(finalX);
		});
		MoveLinear.Options options = MoveLinear.Options.DEFAULT.withVelocityScaling(0.3);
		MoveLinear move = new MoveLinear(m_server.client(), TARGET, options);
		assertTrue(move.isNotStarted());
		assertTrue(m_server.requests.isEmpty(), "construction must not perform HTTP requests");

		MoveCheck actual = move.run();
		assertEquals(finalX == 0.401, actual.goalReached());
		assertEquals(finalX == 0.401 ? 0.001 : 0.02, actual.errorM(), 1e-9);
		assertTrue(move.isCompleted());
		assertEquals(actual, move.get());
		assertNull(actual.settledPose());
		assertTrue(m_server.requests("GET /variables").isEmpty());

		JsonMapper mapper = JsonMapper.builder().build();
		assertEquals(1, bodies.size());
		JsonNode inputs = mapper.readTree(bodies.get(0)).get("inputs");
		assertFalse(inputs.has("frame"));
		assertEquals(0.3, inputs.get("velocity_scaling").asDouble());
		assertEquals(TARGET, Pose.fromJson(inputs.get("pose")), "no implicit TCP conversion");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void missingOrNullFinalPoseCannotEstablishReached(boolean explicitNull) throws Exception {
		m_server.on("POST", MOVE, req -> new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"status\":\"COMPLETED\",\"outputs\":"
						+ (explicitNull ? "{\"final_pose\":null}" : "{}") + "}"));
		MoveCheck check = runCompletedMove(MoveLinear.Options.DEFAULT);
		assertFalse(check.goalReached());
		assertNull(check.errorM());
		assertEquals("no final_pose in outputs", check.failureReason());
	}

	@ParameterizedTest
	@ValueSource(strings = { "FAILED", "CANCELLED" })
	void remoteFailureOrCancellationCompletesWithUnreachedResult(String status) throws Exception {
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(200, Map.of("status", status,
				"error", Map.of("code", "PLANNING_FAILED", "message", "no plan"))));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		move.run();
		if ( status.equals("FAILED") ) {
			assertNotNull(move.get().failureReason());
			assertEquals("PLANNING_FAILED", move.get().result().errorCode());
		}
		assertFalse(move.get().goalReached());
		assertEquals(status, move.get().result().status().name());
		assertTrue(move.isCompleted(), "remote outcome and local execution state are different");
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.401, 0.42 })
	void settledObservationReplacesSnapshot(double settledX) throws Exception {
		m_server.on("POST", MOVE, req -> completed(0.4))
				.on("GET", "/variables/ee_pose", req -> observation(settledX, "OK"));
		MoveCheck actual = runCompletedMove(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(3)));
		assertEquals(finalPose(settledX), Pose.fromJson(actual.settledPose()));
		assertEquals(settledX == 0.401, actual.goalReached());
		assertEquals(settledX == 0.401 ? 0.001 : 0.02, actual.errorM(), 1e-9);
	}

	@Test
	void frozenObservationTimesOutEvenWhenSnapshotMatchesTarget() throws Exception {
		String frozen = java.time.Instant.now().toString();
		m_server.on("POST", MOVE, req -> completed(0.4))
				.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(200,
						Map.of("quality", "OK", "received_at", frozen, "value", Map.of("pose", TARGET))));
		// 정숙 구간(600ms)보다 오래 기다려도 같은 수신 시각의 캐시는 정착 근거가 될 수 없다.
		MoveCheck check = runCompletedMove(MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofMillis(900)));
		assertFalse(check.goalReached());
		assertEquals(MoveCheck.SETTLE_TIMEOUT, check.failureCode());
		assertNull(check.errorM());
		assertNull(check.settledPose());
	}

	@Test
	void settleTimeoutDoesNotFallBackToSuccessfulSnapshot() throws Exception {
		m_server.on("POST", MOVE, req -> completed(0.4))
				.on("GET", "/variables/ee_pose", req -> observation(0.4, "STALE"));
		MoveCheck actual = runCompletedMove(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofMillis(100)));
		assertFalse(actual.goalReached());
		assertEquals(MoveCheck.SETTLE_TIMEOUT, actual.failureCode());
		assertNull(actual.settledPose());
		assertTrue(actual.failureReason().contains("NO_VALID_SAMPLE"));
		assertTrue(actual.failureReason().contains("유효 표본 0건"));
		assertTrue(actual.failureReason().contains("QUALITY_NOT_OK"));
	}

	@Test
	void observationFailurePreservesDomainExceptionAndRemoteResult() {
		m_server.on("POST", MOVE, req -> completed(0.4))
				.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(404,
						Map.of("error", Map.of("code", "NOT_FOUND", "message", "no pose"))));
		MoveLinear.Options options = MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(2));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET, options);
		ExecutionException failure = assertThrows(ExecutionException.class, move::run);
		TwinException cause = assertInstanceOf(TwinException.class, failure.getCause());
		assertEquals("NOT_FOUND", cause.getCode());
		assertTrue(move.isFailed());
		assertEquals("COMPLETED", move.getRemoteResult().orElseThrow().status().name());
		assertSame(cause, assertThrows(ExecutionException.class, move::get).getCause());
	}

	@Test
	void acceptedObservationCancellationWinsOverCheckedFailure() throws Exception {
		m_server.on("POST", MOVE, req -> completed(0.4));
		CountDownLatch observing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AbstractRobotTwinOperation<Void> operation = new AbstractRobotTwinOperation<>(
				m_server.client(), "move_linear", Duration.ofSeconds(2)) {
			@Override protected Map<String,?> buildInputs() { return Map.of(); }
			@Override protected boolean observesAfterRemote(OperationResult result) { return true; }
			@Override protected Void judgeResult(OperationResult result) throws ExecutionException {
				observing.countDown();
				// Model a nested observation that reports a failure after cancellation was accepted.
				boolean interrupted = false;
				try {
					while ( true ) {
						try {
							if ( !release.await(3, TimeUnit.SECONDS) ) { throw new IllegalStateException("not released"); }
							break;
						}
						catch ( InterruptedException e ) { interrupted = true; }
					}
				}
				finally { if ( interrupted ) { Thread.currentThread().interrupt(); } }
				throw new ExecutionException(new IllegalStateException("observation failed"));
			}
		};
		operation.start();
		try {
			assertTrue(observing.await(3, TimeUnit.SECONDS));
			assertTrue(operation.cancel(true));
			release.countDown();
			assertThrows(CancellationException.class, () -> operation.get(3, TimeUnit.SECONDS));
			assertTrue(operation.isCancelled());
			assertEquals("COMPLETED", operation.getRemoteResult().orElseThrow().status().name());
		}
		finally { release.countDown(); }
	}

	@ParameterizedTest
	@CsvSource({ "400, false", "400, true", "409, false", "409, true" })
	void rejectionFailsExecutionAndPreservesOriginalCause(int status, boolean asynchronous) throws Exception {
		String code = status == 400 ? "INVALID_INPUT" : "RESOURCE_BUSY";
		m_server.on("POST", MOVE, req -> FakeTwinServer.json(status,
				Map.of("error", Map.of("code", code, "message", "rejected"))));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		ExecutionException failure;
		if ( asynchronous ) {
			move.start();
			failure = assertThrows(ExecutionException.class, () -> move.get(3, TimeUnit.SECONDS));
		}
		else {
			failure = assertThrows(ExecutionException.class, move::run);
		}
		TwinException actual = assertInstanceOf(TwinException.class, failure.getCause());
		assertEquals(status, actual.getHttpStatus());
		assertEquals(code, actual.getCode());
		assertTrue(move.isFailed());
		assertSame(actual, assertThrows(ExecutionException.class, move::get).getCause());
	}

	@Test
	void asyncRemoteSessionWaitsForTerminalResult() throws Exception {
		AtomicInteger polls = new AtomicInteger();
		m_server.operation("move_linear", "async").on("POST", MOVE, req -> {
			polls.set(0);
			return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint",
					FakeTwinServer.BASE + "/operations/sessions/s1"));
		}).on("GET", "/operations/sessions/s1", req -> polls.incrementAndGet() < 2
				? FakeTwinServer.json(200, Map.of("status", "RUNNING")) : completed(0.401));
		assertTrue(runCompletedMove(MoveLinear.Options.DEFAULT).goalReached());
		assertEquals(2, m_server.requests("GET /operations/sessions/s1").size());
	}

	@Test
	void asynchronousExecutionWaitTimeoutAndCancelLeaveRunningWorkIntact() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("POST", MOVE, req -> {
			entered.countDown();
			try {
				if ( !release.await(5, TimeUnit.SECONDS) ) {
					return FakeTwinServer.json(500, Map.of());
				}
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return FakeTwinServer.json(500, Map.of());
			}
			return completed(0.401);
		});
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		RobotTwinOperation<MoveCheck> operation = move;
		try {
			operation.start();
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			assertTrue(operation.isRunning());
			assertThrows(TimeoutException.class, () -> operation.get(10, TimeUnit.MILLISECONDS));
			assertFalse(operation.cancel(false));
			assertFalse(operation.cancel(true));
			assertTrue(operation.isRunning());
		}
		finally {
			release.countDown();
		}
		assertTrue(operation.get(3, TimeUnit.SECONDS).goalReached());
		assertTrue(operation.isCompleted());
		assertTrue(m_server.requests("DELETE").isEmpty());
		assertThrows(IllegalStateException.class, operation::start);
		assertThrows(IllegalStateException.class, move::run);
		assertEquals(1, m_server.requests("POST " + MOVE).size());
	}

	@Test
	void queuedExecutionRejectsDuplicateStartAndRun() throws Exception {
		m_server.on("POST", MOVE, req -> completed(0.401));
		List<Runnable> queued = new ArrayList<>();
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		move.setExecutor(queued::add);
		move.start();
		assertThrows(IllegalStateException.class, move::start);
		assertThrows(IllegalStateException.class, move::run);
		assertEquals(1, queued.size());
		assertTrue(m_server.requests.isEmpty());
		queued.get(0).run();
		assertTrue(move.get().goalReached());
		assertEquals(1, m_server.requests("POST " + MOVE).size());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void preStartCancellationDoesNotSendRequests(boolean interrupt) {
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		assertTrue(move.cancel(interrupt));
		assertTrue(move.isCancelled());
		assertThrows(CancellationException.class, move::get);
		assertThrows(IllegalStateException.class, move::start);
		assertThrows(IllegalStateException.class, move::run);
		assertTrue(m_server.requests.isEmpty());
	}

	@ParameterizedTest
	@CsvSource({ "false, false", "false, true", "true, false", "true, true" })
	void workerInterruptionCancelsLocalExecution(boolean asynchronous, boolean settling) throws Exception {
		AtomicBoolean remoteFinished = new AtomicBoolean();
		CountDownLatch remoteCompletionPolled = new CountDownLatch(1);
		if ( settling ) {
			m_server.on("POST", MOVE, req -> completed(0.4))
					.on("GET", "/variables/ee_pose", req -> observation(0.4, "STALE"));
		}
		else {
			m_server.operation("move_linear", "async").on("POST", MOVE,
					req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint",
							FakeTwinServer.BASE + "/operations/sessions/s1")))
					.on("GET", "/operations/sessions/s1", req -> {
						if ( remoteFinished.get() ) {
							remoteCompletionPolled.countDown();
							return completed(0.4);
						}
						return FakeTwinServer.json(200, Map.of("status", "RUNNING"));
					});
		}

		MoveLinear move = new MoveLinear(m_server.client(), TARGET,
				MoveLinear.Options.DEFAULT.withSettleTimeout(settling ? Duration.ofSeconds(5) : Duration.ZERO));
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean interruptedAtExit = new AtomicBoolean(true);
		List<Runnable> queued = new ArrayList<>();
		if ( asynchronous ) {
			move.setExecutor(queued::add);
			move.start();
		}
		Thread worker = new Thread(() -> {
			try {
				if ( asynchronous ) {
					queued.get(0).run();
				}
				else {
					move.run();
				}
			}
			catch ( Throwable e ) {
				thrown.set(e);
			}
			finally {
				interruptedAtExit.set(Thread.currentThread().isInterrupted());
			}
		}, "move-linear-interruption-test");
		worker.setDaemon(true);
		worker.start();
		try {
			// HTTP I/O를 마치고 대상 sleep/get 대기에 들어간 뒤 인터럽트한다.
			awaitWaitingIn(worker, settling ? WaitUntilArmStill.class : RobotTwinClient.class,
					settling ? "sleepNanos" : "runAsync");
			worker.interrupt();
			worker.join(3000);
			assertFalse(worker.isAlive());
			assertTrue(move.isCancelled());
			assertFalse(interruptedAtExit.get(), "interruption is propagated through the exception");
			if ( asynchronous ) {
				assertNull(thrown.get());
			}
			else {
				assertInstanceOf(InterruptedException.class, thrown.get());
			}
			assertThrows(CancellationException.class, move::get);
		}
		finally {
			remoteFinished.set(true);
			worker.interrupt();
			worker.join(3000);
		}
		if ( !settling ) {
			assertTrue(remoteCompletionPolled.await(3, TimeUnit.SECONDS),
					"the remote poller continues after local cancellation");
		}
		assertTrue(m_server.requests("DELETE").isEmpty(), "interruption must not cancel remotely");
	}

	@Test
	void remoteCancelWaitsForTerminalStateAfterAcceptedResponse() throws Exception {
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1", req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "phase", "CANCELING")));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		Thread worker = startOnWorker(move);
		try {
			awaitWaitingIn(worker, RobotTwinClient.class, "runAsync");
			awaitPoll();
			assertFalse(move.cancel(false));
			assertTrue(move.cancel(true));
			assertTrue(move.cancel(true));
			assertFalse(move.isDone(), "202 is not a terminal response");
			assertThrows(TimeoutException.class, () -> move.get(30, TimeUnit.MILLISECONDS));
			assertEquals(1, m_server.requests("DELETE").size());
		}
		finally {
			status.set("CANCELLED");
			worker.join(3000);
		}
		assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
		assertTrue(move.isCancelled());
	}

	@ParameterizedTest
	@ValueSource(ints = { 409, 500 })
	void rejectedOrFailedCancelLeavesExecutionRunning(int httpStatus) throws Exception {
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1", req -> FakeTwinServer.json(httpStatus,
				Map.of("error", Map.of("code", "CANCEL_REJECTED", "message", "cannot cancel"))));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		Thread worker = startOnWorker(move);
		try {
			awaitWaitingIn(worker, RobotTwinClient.class, "runAsync");
			awaitPoll();
			if ( httpStatus == 409 ) {
				assertFalse(move.cancel(true));
			}
			else {
				assertEquals(httpStatus, assertThrows(TwinException.class, () -> move.cancel(true)).getHttpStatus());
			}
			assertTrue(move.isRunning());
		}
		finally {
			status.set("COMPLETED");
			worker.join(3000);
		}
		assertTrue(move.get(3, TimeUnit.SECONDS).goalReached());
	}

	@Test
	void cancelDuringSettlingStopsOnlyLocalObservation() throws Exception {
		m_server.on("POST", MOVE, req -> completed(0.4))
				.on("GET", "/variables/ee_pose", req -> observation(0.4, "STALE"));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET,
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(5)));
		Thread worker = startOnWorker(move);
		try {
			awaitWaitingIn(worker, WaitUntilArmStill.class, "sleepNanos");
			OperationResult remote = move.getRemoteResult().orElseThrow();
			assertTrue(remote.isCompleted());
			assertFalse(move.cancel(false));
			assertTrue(move.cancel(true));
			assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
			assertTrue(move.isCancelled());
			assertSame(remote, move.getRemoteResult().orElseThrow());
			assertTrue(m_server.requests("DELETE").isEmpty());
		}
		finally {
			worker.interrupt();
			worker.join(3000);
		}
	}

	@Test
	void cancelWhileStartingIsRecordedAndSendsNothing() throws Exception {
		// 시작 직후(STARTING)의 cancel(true)는 기록되고, 작업 스레드는 아무 요청도 보내지 않고 취소로 끝낸다.
		m_server.on("POST", MOVE, req -> completed(0.401));
		List<Runnable> queued = new ArrayList<>();
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		move.setExecutor(queued::add);
		move.start();
		assertTrue(move.cancel(true));
		assertTrue(move.cancel(true));
		queued.get(0).run();
		assertTrue(move.isCancelled());
		assertThrows(CancellationException.class, move::get);
		assertTrue(m_server.requests.isEmpty(), "no catalog read, no POST");
		assertTrue(move.getRemoteResult().isEmpty());
	}

	@Test
	void cancelBeforeSessionIsDeliveredOnceTheSessionExists() throws Exception {
		// 시작 POST의 응답을 기다리는 동안의 취소는 접수되고, 세션을 받는 즉시 DELETE가 나간다.
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1", req -> {
			status.set("CANCELLED");
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		m_server.on("POST", MOVE, req -> {
			entered.countDown();
			try {
				if ( !release.await(3, TimeUnit.SECONDS) ) { return FakeTwinServer.json(500, Map.of()); }
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return FakeTwinServer.json(500, Map.of());
			}
			return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint",
					FakeTwinServer.BASE + "/operations/sessions/s1"));
		});
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		Thread worker = startOnWorker(move);
		try {
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			assertTrue(move.cancel(true));
			assertTrue(m_server.requests("DELETE").isEmpty(), "no session yet");
		}
		finally {
			release.countDown();
			worker.join(3000);
		}
		assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
		assertEquals(1, m_server.requests("DELETE").size());
	}

	@Test
	void explicitTimeoutOverridesClientDefaultWithoutCancellingRemote() throws Exception {
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1", req -> {
			status.set("CANCELLED");
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		Duration timeout = Duration.ofMillis(200);
		MoveLinear move = new MoveLinear(m_server.client(), TARGET, MoveLinear.Options.DEFAULT, timeout);
		try {
			OperationTimeoutException cause = assertInstanceOf(OperationTimeoutException.class,
					assertThrows(ExecutionException.class, move::run).getCause());
			assertEquals(timeout, cause.getTimeout());
			assertTrue(move.isFailed());
			assertTrue(m_server.requests("DELETE").isEmpty());
			assertTrue(cause.getSessionUrl().endsWith("/operations/sessions/s1"), cause.getSessionUrl());
			assertTrue(cause.cancel());
			assertEquals(1, m_server.requests("DELETE").size());
		}
		finally {
			status.set("CANCELLED");
		}
	}

	@Test
	void waitTimeoutAfterAcceptedCancelIsCancellationNotFailure() throws Exception {
		// 취소가 접수된 뒤 팔 감속이 길어 대기 시간을 넘겨도 FAILED가 아니라 CANCELLED다.
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1",
				req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "phase", "CANCELING")));
		MoveLinear move = new MoveLinear(m_server.client(), TARGET, MoveLinear.Options.DEFAULT, Duration.ofMillis(400));
		move.start();
		awaitPoll();
		assertTrue(move.cancel(true));
		assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
		assertTrue(move.isCancelled());
		assertFalse(move.isFailed());
		status.set("CANCELLED");
	}

	@Test
	void slowDeleteDoesNotBlockRemoteResultQueries() throws Exception {
		// DELETE는 잠금 밖에서 보낸다. 응답을 기다리는 동안 다른 스레드의 조회가 막히지 않는다.
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1);
		m_server.on("DELETE", "/operations/sessions/s1", req -> {
			deleting.countDown();
			try { release.await(3, TimeUnit.SECONDS); }
			catch ( InterruptedException e ) { Thread.currentThread().interrupt(); }
			status.set("CANCELLED");
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		MoveLinear move = new MoveLinear(m_server.client(), TARGET);
		move.start();
		awaitPoll();
		Thread canceller = new Thread(() -> move.cancel(true));
		canceller.start();
		try {
			assertTrue(deleting.await(2, TimeUnit.SECONDS));
			assertTimeoutPreemptively(Duration.ofMillis(500), move::getRemoteResult);
		}
		finally {
			release.countDown();
			canceller.join(3000);
		}
		assertThrows(CancellationException.class, () -> move.get(3, TimeUnit.SECONDS));
	}

	@Test
	void maxDurationExtendsTheDefaultWaitLikeOtherMotions() {
		// 대기 상한을 주지 않고 maxDuration만 주면 max(클라이언트 기본값, maxDuration + 30초)다.
		RobotTwinClient twin = m_server.client();		// 기본 대기 120초
		assertEquals(Duration.ofSeconds(120), new MoveLinear(twin, TARGET).getRemoteTimeout());
		assertEquals(Duration.ofSeconds(120),
				MoveLinear.builder(twin, TARGET).maxDuration(Duration.ofSeconds(60)).build().getRemoteTimeout());
		assertEquals(Duration.ofSeconds(230),
				MoveLinear.builder(twin, TARGET).maxDuration(Duration.ofSeconds(200)).build().getRemoteTimeout());
		assertEquals(Duration.ofSeconds(10), MoveLinear.builder(twin, TARGET).maxDuration(Duration.ofSeconds(200))
				.timeout(Duration.ofSeconds(10)).build().getRemoteTimeout());
	}

	@Test
	void directConstructionValidatesThePose() {
		// Pick 등과 같은 검사를 MoveLinear를 직접 만들 때도 한다.
		Pose nan = new Pose(new Position(Double.NaN, 0, 0.3), TARGET.orientation());
		assertThrows(IllegalArgumentException.class, () -> new MoveLinear(m_server.client(), nan));
		assertThrows(IllegalArgumentException.class, () -> MoveLinear.builder(m_server.client(), nan));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void timeoutMustBePositiveAndDefaultsToClientConfiguration() {
		assertEquals(Duration.ofSeconds(120), m_server.client().getOperationTimeout());
		assertEquals(Duration.ofSeconds(7), RobotTwinClient.builder()
				.operationTimeout(Duration.ofSeconds(7)).build().getOperationTimeout());
		assertThrows(IllegalArgumentException.class,
				() -> new MoveLinear(m_server.client(), TARGET, MoveLinear.Options.DEFAULT, Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> new MoveLinear(m_server.client(), TARGET, MoveLinear.Options.DEFAULT, Duration.ofSeconds(-1)));
		assertTrue(m_server.requests.isEmpty());
	}

	@Test
	void invalidAsyncWaitTimeoutDoesNotSubmitRemoteOperation() {
		m_server.operation("move_linear", "async");
		assertThrows(IllegalArgumentException.class,
				() -> m_server.client().run("move_linear", Map.of("pose", TARGET), Duration.ZERO));
		assertTrue(m_server.requests("POST").isEmpty());
	}

	@Test
	void publicAsyncHandleSupportsAwaitAndCancel() throws Exception {
		AtomicReference<String> status = new AtomicReference<>("RUNNING");
		configureRemote(status);
		m_server.on("DELETE", "/operations/sessions/s1", req -> {
			status.set("CANCELLED");
			return FakeTwinServer.json(200, Map.of("status", "CANCELLED"));
		});
		RobotTwinClient.AsyncOperation remote = m_server.client().startAsync("move_linear", Map.of("pose", TARGET));
		try {
			awaitPoll();
			assertTrue(remote.cancel());
			assertEquals("CANCELLED", remote.await(Duration.ofSeconds(2)).status().name());
		}
		finally {
			status.set("CANCELLED");
		}
	}

	private void configureRemote(AtomicReference<String> status) {
		m_server.operation("move_linear", "async").on("POST", MOVE,
				req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint",
						FakeTwinServer.BASE + "/operations/sessions/s1")))
				.on("GET", "/operations/sessions/s1", req -> "COMPLETED".equals(status.get())
						? completed(0.4) : FakeTwinServer.json(200, Map.of("status", status.get())));
	}

	private void awaitPoll() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while ( m_server.requests("GET /operations/sessions/s1").isEmpty() && System.nanoTime() < deadline ) {
			Thread.sleep(5);
		}
		assertFalse(m_server.requests("GET /operations/sessions/s1").isEmpty());
	}

	private static Thread startOnWorker(MoveLinear move) {
		AtomicReference<Thread> worker = new AtomicReference<>();
		move.setExecutor(command -> {
			Thread thread = new Thread(command, "move-linear-cancel-test");
			thread.setDaemon(true);
			worker.set(thread);
			thread.start();
		});
		move.start();
		return worker.get();
	}

	private static void awaitWaitingIn(Thread worker, Class<?> owner, String method) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while ( System.nanoTime() < deadline && worker.isAlive() ) {
			Thread.State state = worker.getState();
			if ( state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING ) {
				for ( StackTraceElement frame : worker.getStackTrace() ) {
					if ( frame.getClassName().equals(owner.getName()) && frame.getMethodName().equals(method) ) {
						return;
					}
				}
			}
			Thread.sleep(5);
		}
		fail("worker did not wait in " + owner.getSimpleName() + "." + method);
	}

	private MoveCheck runCompletedMove(MoveLinear.Options options) throws Exception {
		MoveLinear move = new MoveLinear(m_server.client(), TARGET, options);
		MoveCheck actual = move.run();
		assertTrue(move.isCompleted());
		return actual;
	}

	private static Pose finalPose(double x) {
		return new Pose(new Position(x, 0, 0.1), Geometry.DOWN);
	}

	private static MockResponse completed(double x) {
		return FakeTwinServer.json(200, Map.of("status", "COMPLETED",
				"outputs", Map.of("final_pose", Map.of("pose", finalPose(x)))));
	}

	private static MockResponse observation(double x, String quality) {
		Instant now = Instant.now();
		return FakeTwinServer.json(200, Map.of("quality", quality, "received_at", now.toString(),
				"stamp", Map.of("sec", now.getEpochSecond(), "nanosec", now.getNano()),
				"value", Map.of("pose", finalPose(x))));
	}
}
