package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToNamedTarget;

import utils.RuntimeExecutionException;
import utils.async.AbstractThreadedExecution;

class OpsTest {
	private FakeTwinServer m_twin;

	@BeforeEach
	void setUp() throws Exception {
		m_twin = new FakeTwinServer().operation("move_to_named_target", "sync");
	}

	@AfterEach
	void tearDown() throws Exception {
		m_twin.close();
	}

	@Test
	void openLoopNamedTargetIsNotReached() {
		m_twin.on("POST", "/operations/move_to_named_target", req -> FakeTwinServer.json(200, Map.of(
				"status", "COMPLETED", "outputs", Map.of("closed_loop", false))));
		ClosedLoopCheck check = Ops.moveToNamedTargetVerified(m_twin.client(), "ready");
		assertFalse(check.goalReached());
		assertTrue(check.failureReason().contains("open loop"));
	}

	// D11: Exception은 런타임 예외로 바꾸고 Error는 그대로 전파한다.
	@Test
	void checkedFailureIsWrappedInRuntimeExecutionException() {
		IOException cause = new IOException("boom");
		RuntimeExecutionException e = assertThrows(RuntimeExecutionException.class, () -> Ops.runOperation(failing(cause)));
		assertSame(cause, e.getCause());
	}

	@Test
	void runtimeFailureIsRethrownAsIs() {
		IllegalArgumentException cause = new IllegalArgumentException("bad");
		assertSame(cause, assertThrows(IllegalArgumentException.class, () -> Ops.runOperation(failing(cause))));
	}

	@Test
	void errorIsPropagatedNotWrapped() {
		AssertionError cause = new AssertionError("broken");
		assertSame(cause, assertThrows(AssertionError.class, () -> Ops.runOperation(failing(cause))));
	}

	private static AbstractThreadedExecution<String> failing(Throwable cause) {
		return new AbstractThreadedExecution<>() {
			@Override protected String executeWork() throws Exception {
				if ( cause instanceof Error error ) { throw error; }
				throw (Exception)cause;
			}
		};
	}
}
