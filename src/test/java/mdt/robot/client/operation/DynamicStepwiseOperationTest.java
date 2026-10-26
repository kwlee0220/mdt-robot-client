package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import mdt.robot.client.FakeTwinServer;

/** 단계 목록 없이 {@code nextStep()}으로 다음 단계를 정하는 순차 연산을 검증한다. */
@Timeout(10)
class DynamicStepwiseOperationTest {
	private enum Phase { OBSERVE, ACT }

	private static final WaitUntilObjectStill.Options QUICK_STILL = new WaitUntilObjectStill.Options(
			Duration.ofSeconds(2), 0.0002, Math.toRadians(0.5), Duration.ofMillis(100), Duration.ofMillis(20));

	private FakeTwinServer server;
	private final AtomicInteger sceneReads = new AtomicInteger();
	private volatile String sceneQuality = "OK";
	private volatile CountDownLatch sceneRead;

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_gripper_to_target", "sync");
		server.on("POST", "/operations/move_gripper_to_target", req -> FakeTwinServer.json(200, Map.of(
				"status", "COMPLETED", "outputs", Map.of("at_goal", !req.getBody().readUtf8().contains("bad")))));
		server.on("GET", "/variables/scene_objects", req -> {
			sceneReads.incrementAndGet();
			if ( sceneRead != null ) { sceneRead.countDown(); }
			return FakeTwinServer.json(200, Map.of("quality", sceneQuality, "received_at", Instant.now().toString(),
					"value", Map.of("objects", Map.of("peg", Map.of("type", "cylinder", "dimensions", List.of(0.05, 0.008),
							"pose", Map.of("position", Map.of("x", 0.5, "y", 0.0, "z", 0.028),
											"orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1)))))));
		});
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void nextStepDecidesEachStepAndMayRepeatAStage() throws Exception {
		AtomicInteger decisions = new AtomicInteger();
		Stepwise operation = new Stepwise(progress -> {
			decisions.incrementAndGet();
			// 직전까지 실행한 단계 수를 보고 다음 단계를 정한다. 같은 단계 이름을 반복해서 쓴다.
			return progress.steps().size() < 3 ? Optional.of(gripper(Phase.ACT, "open")) : Optional.empty();
		});
		StepwiseProgress<Phase> result = operation.run();
		assertNull(operation.failed);
		assertEquals(List.of(Phase.ACT, Phase.ACT, Phase.ACT), result.steps().stream().map(StepResult::stage).toList());
		assertEquals(4, decisions.get(), "first step and once after each confirmed step");
		assertEquals(3, server.requests("POST").size());
		assertTrue(operation.isCompleted());
	}

	@Test void unconfirmedStepStopsWithoutAskingForAnother() throws Exception {
		AtomicInteger decisions = new AtomicInteger();
		Stepwise operation = new Stepwise(progress -> {
			decisions.incrementAndGet();
			return Optional.of(gripper(Phase.ACT, progress.steps().isEmpty() ? "open" : "bad"));
		});
		operation.run();
		assertEquals(Phase.ACT, operation.failed.stage());
		assertFalse(operation.failed.goalReached());
		assertEquals(2, decisions.get());
		assertEquals(2, server.requests("POST").size());
	}

	@Test void observationStepRecordsObjectStillResult() throws Exception {
		Stepwise operation = new Stepwise(progress -> switch ( progress.steps().size() ) {
			case 0 -> Optional.of(still());
			case 1 -> Optional.of(gripper(Phase.ACT, "open"));
			default -> Optional.empty();
		});
		StepwiseProgress<Phase> result = operation.run();
		assertNull(operation.failed);
		StepResult<Phase> observed = result.steps().get(0);
		assertEquals(Phase.OBSERVE, observed.stage());
		assertTrue(observed.goalReached());
		assertTrue(observed.objectStill().settled());
		assertTrue(observed.objectStill().object().isPresent());
		assertTrue(observed.remoteResult().isEmpty(), "observation is not a remote operation");
		assertNull(observed.movement());
		assertNull(observed.gripper());
	}

	@Test void unsettledObservationStopsTheSequence() throws Exception {
		sceneQuality = "STALE";
		WaitUntilObjectStill.Options shortStill = QUICK_STILL.withTimeout(Duration.ofMillis(200));
		Stepwise operation = new Stepwise(progress -> progress.steps().isEmpty()
				? Optional.of(new AbstractStepwiseOperation.Step<>(Phase.OBSERVE,
						new WaitUntilObjectStill(server.client(), "peg", shortStill)))
				: Optional.of(gripper(Phase.ACT, "open")));
		operation.run();
		assertEquals(Phase.OBSERVE, operation.failed.stage());
		assertTrue(operation.failed.why().startsWith("물체 정착 확인 불가: NO_VALID_SAMPLE"), operation.failed.why());
		assertTrue(server.requests("POST").isEmpty());
	}

	@Test void reusingAChildOperationFailsTheExecution() {
		var child = new MoveGripperToNamedTarget(server.client(), "open");
		Stepwise operation = new Stepwise(progress -> progress.steps().size() < 2
				? Optional.of(new AbstractStepwiseOperation.Step<>(Phase.ACT, child)) : Optional.empty());
		ExecutionException error = assertThrows(ExecutionException.class, operation::run);
		IllegalStateException cause = assertInstanceOf(IllegalStateException.class, error.getCause());
		assertTrue(cause.getMessage().startsWith("a child operation cannot be reused"), cause.getMessage());
		assertEquals(1, server.requests("POST").size());
	}

	@Test void startedChildOperationIsRejected() {
		var child = new MoveGripperToNamedTarget(server.client(), "open");
		assertTrue(child.cancel(false));
		Stepwise operation = new Stepwise(progress -> Optional.of(new AbstractStepwiseOperation.Step<>(Phase.ACT, child)));
		ExecutionException error = assertThrows(ExecutionException.class, operation::run);
		assertTrue(error.getCause().getMessage().startsWith("child operation has already started"));
		assertTrue(server.requests.isEmpty());
	}

	@Test void dynamicOperationMustOverrideNextStep() {
		var operation = new AbstractStepwiseOperation<Phase, Boolean>() {
			@Override protected Boolean createResult(StepResult<Phase> failedStep, StepwiseProgress<Phase> progress) {
				return failedStep == null;
			}
		};
		ExecutionException error = assertThrows(ExecutionException.class, operation::run);
		assertTrue(error.getCause().getMessage().endsWith("must override nextStep()"), error.getCause().getMessage());
	}

	@Test void noStepCompletesImmediately() throws Exception {
		Stepwise operation = new Stepwise(progress -> Optional.empty());
		assertTrue(operation.run().steps().isEmpty());
		assertNull(operation.failed);
		assertTrue(server.requests.isEmpty());
	}

	@Test void cancellingDuringObservationStopsBeforeTheNextStep() throws Exception {
		sceneQuality = "STALE";	// 정착하지 않아 관측이 계속된다.
		sceneRead = new CountDownLatch(1);
		Stepwise operation = new Stepwise(progress -> progress.steps().isEmpty()
				? Optional.of(still()) : Optional.of(gripper(Phase.ACT, "open")));
		operation.start();
		assertTrue(sceneRead.await(3, TimeUnit.SECONDS));
		assertTrue(operation.cancel(true));
		assertThrows(CancellationException.class, () -> operation.get(3, TimeUnit.SECONDS));
		StepwiseProgress<Phase> progress = operation.getProgress();
		assertEquals(1, progress.steps().size());
		assertEquals(Phase.OBSERVE, progress.steps().get(0).stage());
		assertNotNull(progress.steps().get(0).failure());
		assertEquals(ChildCancellationState.ACCEPTED, progress.childCancellationState());
		assertTrue(server.requests("POST").isEmpty(), "no step runs after cancellation");
	}

	private AbstractStepwiseOperation.Step<Phase> gripper(Phase phase, String target) {
		return new AbstractStepwiseOperation.Step<>(phase, new MoveGripperToNamedTarget(server.client(), target));
	}

	private AbstractStepwiseOperation.Step<Phase> still() {
		return new AbstractStepwiseOperation.Step<>(Phase.OBSERVE, new WaitUntilObjectStill(server.client(), "peg", QUICK_STILL));
	}

	private static class Stepwise extends AbstractStepwiseOperation<Phase, StepwiseProgress<Phase>> {
		private final Function<StepwiseProgress<Phase>, Optional<Step<Phase>>> m_planner;
		StepResult<Phase> failed;
		Stepwise(Function<StepwiseProgress<Phase>, Optional<Step<Phase>>> planner) { m_planner = planner; }
		@Override protected Optional<Step<Phase>> nextStep(StepwiseProgress<Phase> progress) { return m_planner.apply(progress); }
		@Override protected StepwiseProgress<Phase> createResult(StepResult<Phase> failedStep, StepwiseProgress<Phase> progress) {
			failed = failedStep;
			return progress;
		}
	}
}
