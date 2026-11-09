package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;

/**
 * 시작 직후(STARTING)의 {@code cancel(true)}가 모든 연산 종류에서 기록되고, 작업 스레드가 아무 요청도 보내지
 * 않고 취소로 끝나는지 확인한다. 원격 연산 하나는 {@code MoveLinearTest}에서 다룬다.
 */
class StartingCancellationTest {
	private static final Pose ABOVE = new Pose(new Position(0.4, 0.1, 0.3), Geometry.DOWN);
	private static final Pose TARGET = ABOVE.withPosition(new Position(0.4, 0.1, 0.1));

	private FakeTwinServer server;

	@BeforeEach void setUp() throws Exception {
		server = new FakeTwinServer().operation("move_linear", "async").operation("move_gripper_to_target", "sync");
	}

	@AfterEach void tearDown() throws Exception { server.close(); }

	@Test void stepwiseOperationRecordsCancelWhileStarting() throws Exception {
		Grasp grasp = new Grasp(server.client(), ABOVE, TARGET);
		List<Runnable> queued = new ArrayList<>();
		grasp.setExecutor(queued::add);
		grasp.start();
		assertTrue(grasp.cancel(true));
		queued.get(0).run();
		assertTrue(grasp.isCancelled());
		assertThrows(CancellationException.class, grasp::get);
		assertTrue(grasp.getProgress().steps().isEmpty(), "no stage started");
		assertTrue(server.requests.isEmpty());
	}

	@Test void observationOperationRecordsCancelWhileStarting() throws Exception {
		WaitUntilArmStill still = new WaitUntilArmStill(server.client());
		List<Runnable> queued = new ArrayList<>();
		still.setExecutor(queued::add);
		still.start();
		assertTrue(still.cancel(true));
		queued.get(0).run();
		assertTrue(still.isCancelled());
		assertThrows(CancellationException.class, still::get);
		assertTrue(server.requests.isEmpty());
	}
}
