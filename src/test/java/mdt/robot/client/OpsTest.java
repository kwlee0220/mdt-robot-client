package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mdt.robot.client.operation.MoveToNamedTarget;

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
		MoveToNamedTarget.Check check = Ops.moveToNamedTargetVerified(m_twin.client(), "ready");
		assertFalse(check.reached());
		assertTrue(check.why().contains("open loop"));
	}

}
