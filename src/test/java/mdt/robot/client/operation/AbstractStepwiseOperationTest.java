package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;

@Timeout(10)
class AbstractStepwiseOperationTest {
	private FakeTwinServer server;
	private enum Phase { FIRST, SECOND, THIRD }

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "sync").operation("move_gripper_to_target", "sync");
		server.on("POST", "/operations/move_linear", req -> {
			try {
				var inputs = JsonMapper.builder().build().readTree(req.getBody().readUtf8()).path("inputs");
				return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("final_pose", inputs.get("pose"))));
			}
			catch ( java.io.IOException e ) { throw new AssertionError(e); }
		});
		server.on("POST", "/operations/move_gripper_to_target", req ->
				FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true))));
	}
	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void workflowsReadBothDomainOperationsThroughSharedProgressAndResultTypes() throws Exception {
		Pose pose = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
		var move = MoveLinear.Options.DEFAULT;
		Grasp grasp = new Grasp(server.client(), pose, pose, new Grasp.Options(move, move, "open", "grasp"));
		Place place = new Place(server.client(), pose, pose, pose, new Place.Options(move, move, move, "open"));
		List<AbstractStepwiseOperation<?,?>> operations = List.of(grasp, place);
		List<StepwiseProgress<?>> before = new ArrayList<>();
		for ( AbstractStepwiseOperation<?,?> operation : operations ) {
			before.add(operation.getProgress());
			assertEquals("idle:0", display(operation.getProgress()));
			operation.run();
			StepwiseProgress<?> progress = operation.getProgress();
			assertEquals("idle:4", display(progress));
			assertEquals(4, progress.enteredStages().size());
			assertEquals(ChildCancellationState.NOT_REQUESTED, progress.childCancellationState());
			for ( StepResult<?> step : progress.steps() ) { assertTrue(step.goalReached()); }
			assertThrows(UnsupportedOperationException.class, () -> progress.enteredStages().clear());
			assertThrows(UnsupportedOperationException.class, () -> progress.steps().clear());
		}
		assertTrue(before.stream().allMatch(progress -> progress.enteredStages().isEmpty() && progress.steps().isEmpty()));
		assertTrue(grasp.getProgress().graspAttempted());
		assertTrue(place.getProgress().releaseAttempted());
		assertTrue(place.getProgress().releaseConfirmed());
		assertTrue(place.getProgress().retreatReached());
	}

	@Test void activeStageAndDomainAttemptComeFromSameSnapshot() throws Exception {
		CountDownLatch releasing = new CountDownLatch(1), resume = new CountDownLatch(1);
		server.on("POST", "/operations/move_gripper_to_target", req -> {
			releasing.countDown(); await(resume);
			return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true)));
		});
		Pose pose = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
		var move = MoveLinear.Options.DEFAULT;
		Place place = new Place(server.client(), pose, pose, pose, new Place.Options(move, move, move, "open"));
		place.start();
		try {
			assertTrue(releasing.await(3, TimeUnit.SECONDS));
			Place.Progress during = place.getProgress();
			assertEquals("RELEASE:2", display(during));
			assertTrue(during.releaseAttempted());
			assertEquals(Set.of(Place.Stage.APPROACH, Place.Stage.DESCEND, Place.Stage.RELEASE), during.enteredStages());
			assertFalse(during.releaseConfirmed());
			assertTrue(place.cancel(true));
			resume.countDown();
			assertThrows(java.util.concurrent.CancellationException.class, () -> place.get(3, TimeUnit.SECONDS));
			assertEquals(during.enteredStages(), place.getProgress().enteredStages());
			assertTrue(place.getProgress().releaseConfirmed());
			assertFalse(during.releaseConfirmed());
		}
		finally { resume.countDown(); }
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void sequenceIsCopiedAndStopsAtFirstUnconfirmedStep(boolean failSecond) throws Exception {
		if ( failSecond ) {
			server.on("POST", "/operations/move_gripper_to_target", req ->
					FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs",
							Map.of("at_goal", !req.getBody().readUtf8().contains("bad")))));
		}
		List<AbstractStepwiseOperation.Step<Phase>> plan = new ArrayList<>(List.of(
				step(Phase.SECOND, "open"), step(Phase.FIRST, "bad"), step(Phase.THIRD, "open")));
		TestSequence sequence = new TestSequence(plan);
		plan.clear(); // 생성 후 호출자의 목록 변경은 실행에 영향을 주지 않는다.
		StepwiseProgress<Phase> result = sequence.run();
		assertTrue(sequence.isCompleted());
		assertEquals(failSecond ? Phase.FIRST : null, sequence.failedStage);
		assertEquals(failSecond ? List.of(Phase.SECOND, Phase.FIRST) : List.of(Phase.SECOND, Phase.FIRST, Phase.THIRD),
				result.steps().stream().map(StepResult::stage).toList());
		assertEquals(failSecond ? 2 : 3, server.requests("POST").size());
		assertEquals(failSecond ? Set.of(Phase.SECOND, Phase.FIRST) : Set.of(Phase.values()), result.enteredStages());
	}

	@Test void singleStageCompletionIsCommittedBeforeResultConversion() throws Exception {
		TestSequence sequence = new TestSequence(List.of(step(Phase.FIRST, "open")));
		sequence.converting = new CountDownLatch(1);
		sequence.finish = new CountDownLatch(1);
		sequence.start();
		try {
			assertTrue(sequence.converting.await(3, TimeUnit.SECONDS));
			assertFalse(sequence.cancel(true), "confirmed terminal result cannot be cancelled during conversion");
			sequence.finish.countDown();
			assertTrue(sequence.get(3, TimeUnit.SECONDS).confirmed(Phase.FIRST));
		}
		finally { sequence.finish.countDown(); }
	}

	@Test void rejectsInvalidPlansBeforeCommunication() {
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(null));
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(List.of()));
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(java.util.Arrays.asList((AbstractStepwiseOperation.Step<Phase>)null)));
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(List.of(step(Phase.FIRST, "open"), step(Phase.FIRST, "close"))));
		var child = new MoveGripperToNamedTarget(server.client(), "open");
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(List.of(
				new AbstractStepwiseOperation.Step<>(Phase.FIRST, child), new AbstractStepwiseOperation.Step<>(Phase.SECOND, child))));
		assertTrue(child.cancel(false));
		assertThrows(IllegalArgumentException.class, () -> new TestSequence(List.of(new AbstractStepwiseOperation.Step<>(Phase.FIRST, child))));
		assertTrue(server.requests.isEmpty());
	}

	private AbstractStepwiseOperation.Step<Phase> step(Phase phase, String target) {
		return new AbstractStepwiseOperation.Step<>(phase, new MoveGripperToNamedTarget(server.client(), target));
	}

	private static String display(StepwiseProgress<?> progress) {
		return progress.activeStage().map(Enum::name).orElse("idle") + ":" + progress.steps().size();
	}

	private static class TestSequence extends AbstractStepwiseOperation<Phase, StepwiseProgress<Phase>> {
		Phase failedStage;
		CountDownLatch converting;
		CountDownLatch finish;
		TestSequence(List<Step<Phase>> steps) { super(steps); }
		@Override protected StepwiseProgress<Phase> createResult(StepResult<Phase> failedStep, StepwiseProgress<Phase> progress) {
			failedStage = failedStep == null ? null : failedStep.stage();
			if ( converting != null ) { converting.countDown(); await(finish); }
			return progress;
		}
	}

	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(3, TimeUnit.SECONDS) ) { throw new AssertionError("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
