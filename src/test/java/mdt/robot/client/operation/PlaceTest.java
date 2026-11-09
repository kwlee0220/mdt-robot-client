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
class PlaceTest {
	private static final String MOVE = "/operations/move_linear";
	private static final String GRIPPER = "/operations/move_gripper_to_target";
	private static final String SESSION = "/operations/sessions/p1";
	private static final Pose ABOVE = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
	private static final Pose TARGET = ABOVE.withPosition(new Position(0.4, 0.1, 0.1));
	private static final Pose RETREAT = ABOVE.withPosition(new Position(0.3, 0.2, 0.4));
	private static final Place.Options FAST = new Place.Options(MoveLinear.Options.DEFAULT,
			MoveLinear.Options.DEFAULT.withVelocityScaling(0.1), MoveLinear.Options.DEFAULT.withVelocityScaling(0.15), "open");
	private FakeTwinServer server;
	private final AtomicInteger calls = new AtomicInteger();
	private final List<JsonNode> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync").operation("move_gripper_to_target", "sync");
		server.on("POST", MOVE, this::success).on("POST", GRIPPER, this::success);
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test
	void invalidMoveOptionsFailBeforeAnyCompositeStage() {
		assertThrows(IllegalArgumentException.class, () -> new Place(server.client(), ABOVE, TARGET, RETREAT, new Place.Options(FAST.approach(),
				FAST.descent(), MoveLinear.Options.DEFAULT.withVelocityScaling(Double.NaN), "open")));
		assertTrue(server.requests.isEmpty());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void executesFourStagesWithIndependentPosesAndOptions(boolean async) throws Exception {
		Place.Options options = new Place.Options(FAST.approach(),
				FAST.descent(), FAST.retreat(), "custom_open");
		Place place = new Place(server.client(), ABOVE, TARGET, RETREAT, options);
		Place.Progress before = place.getProgress();
		assertTrue(server.requests.isEmpty());
		assertFalse(before.releaseAttempted());
		Place.Check check;
		if ( async ) { place.start(); check = place.get(3, TimeUnit.SECONDS); }
		else { check = place.run(); }
		assertTrue(place.isCompleted());
		assertTrue(check.goalReached());
		assertTrue(check.releaseAttempted());
		assertTrue(check.releaseConfirmed());
		assertTrue(check.retreatReached());
		assertNull(check.failedStage());
		assertNull(check.failureReason());
		assertEquals(List.of(Place.Stage.values()), check.steps().stream().map(StepResult::stage).toList());
		assertEquals(ABOVE.toJson(), bodies.get(0).get("pose"));
		assertEquals(TARGET.toJson(), bodies.get(1).get("pose"));
		assertEquals("custom_open", bodies.get(2).path("target").asText());
		assertEquals(RETREAT.toJson(), bodies.get(3).get("pose"));
		assertEquals(0.2, bodies.get(0).path("velocity_scaling").asDouble());
		assertEquals(0.1, bodies.get(1).path("velocity_scaling").asDouble());
		assertEquals(0.15, bodies.get(3).path("velocity_scaling").asDouble());
		assertFalse(bodies.get(3).has("frame"));
		assertEquals(check.steps(), place.getProgress().steps());
		assertTrue(place.getProgress().releaseConfirmed());
		assertTrue(place.getProgress().retreatReached());
		assertTrue(place.getProgress().activeStage().isEmpty());
		assertTrue(before.steps().isEmpty());
		assertFalse(before.releaseAttempted());
		assertThrows(UnsupportedOperationException.class, () -> check.steps().clear());
		assertEquals(4, server.requests("POST").size());
		assertEquals(1, server.requests("POST " + GRIPPER).size(), "no initial open or automatic regrasp");
		assertTrue(server.requests("GET /variables").isEmpty());
		assertFalse(place.cancel(true));
	}

	@ParameterizedTest @CsvSource({ "1,goal", "2,goal", "3,goal", "4,goal",
		"1,FAILED", "2,FAILED", "3,FAILED", "4,FAILED", "1,CANCELLED", "2,CANCELLED", "3,CANCELLED", "4,CANCELLED" })
	void unconfirmedStepStopsAndRetainsReleaseEvidence(int failed, String kind) throws Exception {
		installFailure(failed, kind);
		Place place = place();
		Place.Check check = place.run();
		assertTrue(place.isCompleted());
		assertFalse(check.goalReached());
		assertEquals(Place.Stage.values()[failed - 1], check.failedStage());
		assertNotNull(check.failureReason());
		assertEquals(failed >= 3, check.releaseAttempted());
		assertEquals(failed == 4, check.releaseConfirmed(), "retreat failure must not erase confirmed opening");
		assertFalse(check.retreatReached());
		assertEquals(failed, check.steps().size());
		assertEquals(failed, server.requests("POST").size());
		assertEquals(check.releaseConfirmed(), place.getProgress().releaseConfirmed());
		assertEquals(check.releaseAttempted(), place.getProgress().releaseAttempted());
		assertTrue(check.steps().get(failed - 1).remoteResult().isPresent());
	}

	@ParameterizedTest @ValueSource(ints = { 1, 2, 3, 4 })
	void rejectionKeepsOriginalErrorAndEarlierRelease(int failed) {
		installFailure(failed, "rejected");
		Place place = place();
		ExecutionException error = assertThrows(ExecutionException.class, place::run);
		assertInstanceOf(TwinException.class, error.getCause());
		assertTrue(place.isFailed());
		Place.Progress progress = place.getProgress();
		assertSame(error.getCause(), progress.steps().get(failed - 1).failure());
		assertTrue(progress.steps().get(failed - 1).remoteResult().isEmpty());
		assertEquals(failed >= 3, progress.releaseAttempted());
		assertEquals(failed == 4, progress.releaseConfirmed());
		assertFalse(progress.retreatReached());
		assertEquals(failed, server.requests("POST").size());
	}

	@ParameterizedTest @ValueSource(strings = { "true", "1", "missing" })
	void malformedOpeningResultCannotAuthorizeRetreat(String value) throws Exception {
		server.on("POST", GRIPPER, req -> completed(value.equals("missing") ? Map.of()
				: Map.of("at_goal", value.equals("1") ? 1 : value, "stalled", true)));
		Place.Check check = place().run();
		assertEquals(Place.Stage.RELEASE, check.failedStage());
		assertTrue(check.releaseAttempted());
		assertFalse(check.releaseConfirmed());
		assertFalse(check.retreatReached());
		assertEquals(3, server.requests("POST").size());
	}

	@ParameterizedTest @ValueSource(ints = { 1, 2, 4 })
	void eachMovementCanRequireSettling(int stage) throws Exception {
		var settle = MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofMillis(80));
		Place.Options options = new Place.Options(stage == 1 ? settle : FAST.approach(),
				stage == 2 ? settle : FAST.descent(), stage == 4 ? settle : FAST.retreat(), "open");
		server.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(200, Map.of("quality", "STALE")));
		Place.Check check = new Place(server.client(), ABOVE, TARGET, RETREAT, options).run();
		assertEquals(Place.Stage.values()[stage - 1], check.failedStage());
		assertTrue(check.failureReason().contains("NO_VALID_SAMPLE"));
		assertEquals(stage == 4, check.releaseConfirmed());
		assertEquals(stage, server.requests("POST").size());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void cancelDuringSyncOpeningWaitsThenSkipsRetreat(boolean reject) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("POST", GRIPPER, req -> {
			entered.countDown(); await(release);
			return reject ? FakeTwinServer.json(400, Map.of("error", Map.of("code", "INVALID_INPUT", "message", "bad")))
					: completed(Map.of("at_goal", true));
		});
		Place place = new Place(server.client(), ABOVE, TARGET, RETREAT, FAST, Duration.ofMillis(1));
		place.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			Place.Progress during = place.getProgress();
			assertTrue(during.releaseAttempted());
			assertFalse(during.releaseConfirmed());
			assertFalse(place.cancel(false));
			assertTrue(place.cancel(true));
			assertEquals(ChildCancellationState.REJECTED, place.getProgress().childCancellationState());
			assertThrows(TimeoutException.class, () -> place.get(20, TimeUnit.MILLISECONDS));
			release.countDown();
			assertThrows(CancellationException.class, () -> place.get(3, TimeUnit.SECONDS));
			assertTrue(place.getProgress().releaseAttempted());
			assertEquals(!reject, place.getProgress().releaseConfirmed());
			assertFalse(place.getProgress().retreatReached());
			assertFalse(during.releaseConfirmed(), "snapshot remains unchanged");
			assertEquals(3, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	@ParameterizedTest @CsvSource({ "1,202", "1,409", "1,500", "4,202", "4,409", "4,500" })
	void delayedRemoteCancelKeepsProgressReadableAndStopsSubsequentStages(int stage, int status) throws Exception {
		AtomicBoolean done = new AtomicBoolean();
		CountDownLatch polled = new CountDownLatch(1), deleting = new CountDownLatch(1), release = new CountDownLatch(1);
		asyncMove(stage, done, polled);
		server.on("DELETE", SESSION, req -> {
			deleting.countDown(); await(release);
			return status == 202 ? FakeTwinServer.json(202, Map.of("status", "RUNNING", "phase", "CANCELING"))
					: FakeTwinServer.json(status, Map.of("error", Map.of("code", "REJECTED", "message", "cannot cancel")));
		});
		Place place = place();
		var observers = Executors.newFixedThreadPool(2);
		place.start();
		try {
			assertTrue(polled.await(3, TimeUnit.SECONDS));
			var cancellation = observers.submit(() -> place.cancel(true));
			assertTrue(deleting.await(3, TimeUnit.SECONDS));
			Place.Progress during = observers.submit(place::getProgress).get(1, TimeUnit.SECONDS);
			assertEquals(ChildCancellationState.IN_PROGRESS, during.childCancellationState());
			assertEquals(stage == 4, during.releaseConfirmed());
			assertTrue(observers.submit(() -> place.cancel(true)).get(1, TimeUnit.SECONDS));
			done.set(true); release.countDown();
			assertTrue(cancellation.get(3, TimeUnit.SECONDS));
			assertThrows(CancellationException.class, () -> place.get(3, TimeUnit.SECONDS));
			Place.Progress after = place.getProgress();
			assertEquals(stage == 4, after.releaseConfirmed());
			assertEquals(stage == 4, after.releaseAttempted());
			assertTrue(after.steps().get(stage - 1).remoteResult().orElseThrow().isCompleted());
			assertEquals(status == 202, after.childCancellationAccepted());
			assertEquals(status == 500, after.cancellationFailure() instanceof TwinException);
			assertEquals(stage, server.requests("POST").size());
			assertEquals(1, server.requests("DELETE").size());
			assertEquals(ChildCancellationState.IN_PROGRESS, during.childCancellationState());
		}
		finally { done.set(true); release.countDown(); observers.shutdownNow(); assertTrue(observers.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	@ParameterizedTest @ValueSource(ints = { 1, 4 })
	void asyncTimeoutKeepsReleaseEvidenceAndDoesNotCancelRemote(int stage) {
		AtomicBoolean done = new AtomicBoolean();
		asyncMove(stage, done, new CountDownLatch(1));
		// 앞선 비동기 이동의 POST+폴링은 완료할 여유를 주고, 지정 단계만 RUNNING으로 유지한다.
		Place place = new Place(server.client(), ABOVE, TARGET, RETREAT, FAST, Duration.ofMillis(500));
		try {
			ExecutionException error = assertThrows(ExecutionException.class, place::run);
			assertInstanceOf(OperationTimeoutException.class, error.getCause());
			assertTrue(place.isFailed());
			assertEquals(stage, place.getProgress().steps().size());
			assertEquals(stage == 4, place.getProgress().releaseConfirmed());
			assertTrue(place.getProgress().steps().get(stage - 1).remoteResult().isEmpty());
			assertEquals(stage, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { done.set(true); }
	}

	@Test void preStartCancelAndQueuedDuplicateStartMakeNoUnexpectedRequests() throws Exception {
		Place cancelled = place();
		assertTrue(cancelled.cancel(false));
		assertThrows(CancellationException.class, cancelled::get);
		assertFalse(cancelled.getProgress().releaseAttempted());
		assertThrows(IllegalStateException.class, cancelled::run);
		List<Runnable> queue = new ArrayList<>();
		Place place = place();
		place.setExecutor(queue::add);
		place.start();
		assertThrows(IllegalStateException.class, place::start);
		assertThrows(IllegalStateException.class, place::run);
		assertTrue(server.requests.isEmpty());
		queue.get(0).run();
		assertTrue(place.get().goalReached());
	}

	@ParameterizedTest @ValueSource(ints = { 3, 4 })
	void externalInterruptKeepsEvidenceOfOpening(int stopAt) throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		java.util.function.Function<RecordedRequest,MockResponse> handler = req -> {
			if ( calls.get() + 1 != stopAt ) { return success(req); }
			entered.countDown(); await(release);
			return completed(Map.of("at_goal", true, "padding", "x".repeat(65536)));
		};
		server.on("POST", MOVE, handler).on("POST", GRIPPER, handler);
		Place place = place();
		AtomicReference<Thread> worker = new AtomicReference<>();
		place.setExecutor(task -> { Thread thread = new Thread(task); worker.set(thread); thread.start(); });
		place.start();
		try {
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			worker.get().interrupt(); release.countDown();
			assertThrows(CancellationException.class, () -> place.get(3, TimeUnit.SECONDS));
			assertTrue(place.getProgress().releaseAttempted());
			assertEquals(stopAt == 4, place.getProgress().releaseConfirmed());
			assertFalse(place.getProgress().retreatReached());
			assertInstanceOf(InterruptedException.class, place.getProgress().steps().get(stopAt - 1).failure());
			assertEquals(stopAt, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); worker.get().join(3000); }
	}

	@Test void cancelWhileRetreatSettlesPreservesOpeningAndRemoteRetreatCompletion() throws Exception {
		CountDownLatch observing = new CountDownLatch(1), release = new CountDownLatch(1);
		server.on("GET", "/variables/ee_pose", req -> {
			observing.countDown(); await(release);
			return FakeTwinServer.json(200, Map.of("quality", "STALE"));
		});
		Place.Options options = new Place.Options(FAST.approach(), FAST.descent(),
				FAST.retreat().withSettleTimeout(Duration.ofSeconds(3)), "open");
		Place place = new Place(server.client(), ABOVE, TARGET, RETREAT, options);
		place.start();
		try {
			assertTrue(observing.await(3, TimeUnit.SECONDS));
			assertTrue(place.cancel(true)); release.countDown();
			assertThrows(CancellationException.class, () -> place.get(3, TimeUnit.SECONDS));
			assertTrue(place.getProgress().releaseConfirmed());
			assertFalse(place.getProgress().retreatReached());
			assertTrue(place.getProgress().steps().get(3).remoteResult().orElseThrow().isCompleted());
			assertEquals(4, server.requests("POST").size());
			assertTrue(server.requests("DELETE").isEmpty());
		}
		finally { release.countDown(); }
	}

	@Test void validatesRetreatAndAllOptionsBeforeAnyMovement() {
		assertEquals(Duration.ofSeconds(10), Place.Options.DEFAULT.retreat().settleTimeout());
		assertThrows(IllegalArgumentException.class, () -> new Place(null, ABOVE, TARGET, RETREAT));
		assertThrows(IllegalArgumentException.class, () -> new Place(server.client(), ABOVE, TARGET, null));
		assertThrows(IllegalArgumentException.class, () -> new Place(server.client(), ABOVE, TARGET,
				RETREAT.withPosition(new Position(Double.NaN, 0, 0))));
		assertThrows(IllegalArgumentException.class, () -> new Place(server.client(), ABOVE, TARGET, RETREAT, null));
		assertThrows(IllegalArgumentException.class, () -> new Place(server.client(), ABOVE, TARGET, RETREAT, FAST, Duration.ZERO));
		for ( double speed : new double[] { 0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY } ) {
			assertThrows(IllegalArgumentException.class, () -> new Place.Options(FAST.approach(), FAST.descent(),
					FAST.retreat().withVelocityScaling(speed), "open"));
		}
		assertThrows(IllegalArgumentException.class, () -> new Place.Options(FAST.approach(), FAST.descent(), FAST.retreat(), " open"));
		assertThrows(IllegalArgumentException.class, () -> new Place.Options(FAST.approach(), FAST.descent(),
				FAST.retreat().withSettleTimeout(Duration.ofSeconds(Long.MAX_VALUE)), "open"));
		assertTrue(server.requests.isEmpty());
	}

	@Test void builderNamesEachPoseAndDefaultsRetreatToApproach() throws Exception {
		var twin = server.client();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Place.builder(twin).approachPose(ABOVE).build());
		assertEquals("placePose is not set", e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> Place.builder(twin).placePose(TARGET).build());
		assertThrows(IllegalArgumentException.class,
				() -> Place.builder(twin).approachPose(ABOVE).placePose(TARGET).timeout(Duration.ZERO).build());
		assertTrue(server.requests.isEmpty());

		Place.Check check = Place.builder(twin).approachPose(ABOVE).placePose(TARGET).options(FAST).build().run();
		assertTrue(check.goalReached(), check.failureReason());
		assertTrue(check.retreatReached());
	}

	private Place place() { return new Place(server.client(), ABOVE, TARGET, RETREAT, FAST); }
	private MockResponse success(RecordedRequest req) {
		calls.incrementAndGet();
		JsonNode inputs = inputs(req); bodies.add(inputs);
		return inputs.has("pose") ? completed(Map.of("final_pose", inputs.get("pose"))) : completed(Map.of("at_goal", true));
	}
	private void installFailure(int at, String kind) {
		java.util.function.Function<RecordedRequest,MockResponse> handler = req -> {
			if ( calls.get() + 1 != at ) { return success(req); }
			calls.incrementAndGet();
			JsonNode inputs = inputs(req);
			if ( kind.equals("goal") ) {
				return inputs.has("pose") ? completed(Map.of("final_pose", TARGET.withPosition(new Position(9, 9, 9))))
						: completed(Map.of("at_goal", false));
			}
			if ( kind.equals("rejected") ) { return FakeTwinServer.json(409, Map.of("error", Map.of("code", "RESOURCE_BUSY", "message", "busy"))); }
			return FakeTwinServer.json(200, Map.of("status", kind, "error", Map.of("code", "FAILED_STEP", "message", "failed")));
		};
		server.on("POST", MOVE, handler).on("POST", GRIPPER, handler);
	}
	private void asyncMove(int stage, AtomicBoolean done, CountDownLatch polled) {
		server.operation("move_linear", "async");
		server.on("POST", MOVE, req -> {
			int current = calls.incrementAndGet();
			if ( current != stage ) {
				JsonNode pose = inputs(req).get("pose");
				String path = "/operations/sessions/earlier" + current;
				server.on("GET", path, poll -> completed(Map.of("final_pose", pose)));
				return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + path));
			}
			return FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + SESSION));
		});
		server.on("GET", SESSION, req -> {
			polled.countDown();
			return done.get() ? completed(Map.of("final_pose", stage == 1 ? ABOVE : RETREAT))
					: FakeTwinServer.json(200, Map.of("status", "RUNNING"));
		});
	}
	private static JsonNode inputs(RecordedRequest req) {
		try { return JsonMapper.builder().build().readTree(req.getBody().readUtf8()).path("inputs"); }
		catch ( java.io.IOException e ) { throw new IllegalStateException(e); }
	}
	private static MockResponse completed(Map<String,?> outputs) { return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs)); }
	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(5, TimeUnit.SECONDS) ) { throw new AssertionError("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
