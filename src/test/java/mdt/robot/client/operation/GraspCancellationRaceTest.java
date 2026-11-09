package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.OperationResult;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;

import utils.async.AbstractThreadedExecution;

/** 시작 경합을 강제로 배치하기 위해 단계 실행부에 제어 가능한 하위 연산을 전달한다. */
@Timeout(10)
class GraspCancellationRaceTest {
	private FakeTwinServer server;
	private Grasp grasp;

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_gripper_to_target", "sync");
		Pose pose = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
		grasp = new Grasp(server.client(), pose, pose);
		// perform의 진입 조건. 전체 실행의 최종 상태 전이는 GraspTest에서 별도로 검증한다.
		assertTrue(grasp.notifyStarting());
		assertTrue(grasp.notifyStarted());
	}

	@AfterEach void tearDown() throws Exception { server.close(); }

	@ParameterizedTest @CsvSource({ "false,OPEN", "true,OPEN", "false,GRASP", "true,GRASP" })
	void preStartCancellationIsRecordedWithoutAlreadyStarted(boolean afterCheck, Grasp.Stage stage) throws Exception {
		CountDownLatch checking = new CountDownLatch(1), resume = new CountDownLatch(1);
		AtomicBoolean first = new AtomicBoolean(true);
		AtomicInteger starts = new AtomicInteger();
		TestOperation child = new TestOperation() {
			@Override public boolean isCancelled() {
				if ( first.compareAndSet(true, false) ) {
					boolean before = super.isCancelled();
					checking.countDown(); await(resume);
					// true: 확인은 false였지만 run()이 호출되기 전에 취소된 순서를 재현한다.
					return afterCheck ? before : super.isCancelled();
				}
				return super.isCancelled();
			}
			@Override protected void initializeThread() { starts.incrementAndGet(); }
		};
		var worker = Executors.newSingleThreadExecutor();
		try {
			var execution = worker.submit(() -> perform(stage, child));
			assertTrue(checking.await(3, TimeUnit.SECONDS));
			assertTrue(grasp.cancel(true));
			assertTrue(child.isCancelled());
			resume.countDown();
			ExecutionException error = assertThrows(ExecutionException.class, () -> execution.get(3, TimeUnit.SECONDS));
			CancellationException cancelled = assertInstanceOf(CancellationException.class, error.getCause());
			Grasp.Progress progress = grasp.getProgress();
			assertEquals(1, progress.steps().size());
			StepResult<Grasp.Stage> step = progress.steps().get(0);
			CancellationException reason = assertInstanceOf(CancellationException.class, step.failure());
			assertEquals("하위 연산 실행 전 취소: " + stage, reason.getMessage());
			assertSame(reason, cancelled.getCause());
			assertNull(reason.getCause(), "misleading already-started error is not retained as a cause");
			assertTrue(step.remoteResult().isEmpty());
			assertFalse(step.goalReached());
			assertTrue(progress.activeStage().isEmpty());
			assertEquals(stage == Grasp.Stage.GRASP, progress.graspAttempted());
			assertEquals(ChildCancellationState.ACCEPTED, progress.childCancellationState());
			assertEquals(0, starts.get());
			assertTrue(server.requests.isEmpty(), "pre-start cancellation sends no remote request");
		}
		finally { resume.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void unrelatedStartRejectionIsNotRelabelled(boolean parentCancels) throws Exception {
		TestOperation child = new TestOperation();
		assertTrue(child.notifyStarting());
		assertTrue(child.notifyStarted()); // 이미 실행 중인 객체를 잘못 전달한 경우.
		if ( parentCancels ) {
			// 첫 상태 확인 중 취소를 요청하되, 하위 연산은 RUNNING/원격 세션 없음이라 취소를 거부한다.
			child.onCheck = () -> assertTrue(grasp.cancel(true));
		}
		Exception error = assertThrows(Exception.class, () -> perform(Grasp.Stage.OPEN, child));
		StepResult<Grasp.Stage> step = grasp.getProgress().steps().get(0);
		assertInstanceOf(IllegalStateException.class, step.failure());
		assertFalse(step.failure() instanceof CancellationException);
		assertTrue(step.failure().getMessage().startsWith("already started:"));
		if ( parentCancels ) { assertSame(step.failure(), assertInstanceOf(CancellationException.class, error).getCause()); }
		else { assertSame(step.failure(), error); }
		assertTrue(server.requests.isEmpty());
	}

	@ParameterizedTest @ValueSource(booleans = { false, true })
	void executionBodyExceptionsKeepTheirOriginalDiagnosis(boolean cancelled) {
		RuntimeException original = cancelled ? new CancellationException("cancelled inside child")
				: new IllegalStateException("invalid child input state");
		TestOperation child = new TestOperation() {
			@Override protected Map<String,?> buildInputs() { throw original; }
		};
		Exception error = assertThrows(Exception.class, () -> perform(Grasp.Stage.GRASP, child));
		assertSame(original, grasp.getProgress().steps().get(0).failure());
		if ( cancelled ) { assertSame(original, error); }
		else { assertSame(original, assertInstanceOf(ExecutionException.class, error).getCause()); }
		assertTrue(grasp.getProgress().graspAttempted());
		assertTrue(server.requests.isEmpty());
	}

	private class TestOperation extends AbstractRobotTwinOperation<GripperCheck> {
		Runnable onCheck;
		TestOperation() { super(server.client(), "move_gripper_to_target", Duration.ofSeconds(1)); }
		@Override public boolean isCancelled() {
			Runnable callback = onCheck;
			onCheck = null;
			if ( callback != null ) { callback.run(); }
			return super.isCancelled();
		}
		@Override protected Map<String,?> buildInputs() { return Map.of("target", "grasp"); }
		@Override protected GripperCheck judgeResult(OperationResult result) {
			throw new AssertionError("no remote operation should complete in these tests");
		}
	}

	@SuppressWarnings("unchecked")
	private StepResult<Grasp.Stage> perform(Grasp.Stage stage, AbstractRobotTwinOperation<?> child) throws Exception {
		// 공개 API에 테스트용 훅을 추가하지 않고 실제 단계 기록 처리를 검증한다.
		var method = AbstractStepwiseOperation.class.getDeclaredMethod("perform", Enum.class, AbstractThreadedExecution.class);
		method.setAccessible(true);
		try { return (StepResult<Grasp.Stage>)method.invoke(grasp, stage, child); }
		catch ( InvocationTargetException e ) {
			if ( e.getCause() instanceof Exception error ) { throw error; }
			throw (Error)e.getCause();
		}
	}

	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(3, TimeUnit.SECONDS) ) { throw new AssertionError("not released"); } }
		catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}
