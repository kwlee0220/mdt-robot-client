package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.operation.MoveToNamedTarget;

/**
 * MoveIt 원본 오류({@code error.details.moveit_error_name}, 트윈 설계서 6.8)가 결과 · 안내 · 예외까지 오는지.
 * <p>
 * {@code PLANNING_FAILED} 하나로는 시작 자세 문제와 목표 문제가 같아 보이는데 대응이 반대다 — 원인이 대응을 가른다.
 */
@Timeout(10)
class MoveItErrorTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final Pose TARGET = new Pose(new Position(0.4, 0.0, 0.1), Geometry.DOWN);
	private FakeTwinServer m_server;

	@AfterEach
	void tearDown() throws Exception {
		if ( m_server != null ) { m_server.close(); }
	}

	private static OperationResult failed(String code, String details) throws Exception {
		JsonNode node = (details != null) ? MAPPER.readTree(details) : null;
		return new OperationResult("move_linear", OperationResult.Status.FAILED, Map.of(), code, "x", node);
	}

	// ---------- 단위 ----------

	@Test
	void moveitCauseRefinesTheCodeHint() throws Exception {
		String start = Ops.failureHint(failed("PLANNING_FAILED",
				"{\"moveit_error_code\":-10,\"moveit_error_name\":\"START_STATE_IN_COLLISION\"}")).orElseThrow();
		String goal = Ops.failureHint(failed("PLANNING_FAILED",
				"{\"moveit_error_code\":-12,\"moveit_error_name\":\"GOAL_IN_COLLISION\"}")).orElseThrow();
		assertTrue(start.startsWith(Ops.FAILURE_HINTS.get("PLANNING_FAILED")));
		assertTrue(start.contains("MoveIt 원인 START_STATE_IN_COLLISION") && start.contains("목표를 바꿔도"));
		assertTrue(goal.contains("MoveIt 원인 GOAL_IN_COLLISION") && goal.contains("목표를 물체"));
	}

	@Test
	void unknownNameIsReportedWithoutAHintAndCodeOnlyAsANumber() throws Exception {
		assertTrue(Ops.failureHint(failed("PLANNING_FAILED", "{\"moveit_error_name\":\"CRASH\"}"))
						.orElseThrow().endsWith("MoveIt 원인 CRASH"));
		assertEquals("error_code=-9999",
				failed("PLANNING_FAILED", "{\"moveit_error_code\":-9999}").moveitErrorName().orElseThrow());
	}

	@Test
	void noDetailsKeepsTheCodeHintOnly() throws Exception {
		assertEquals(Ops.FAILURE_HINTS.get("PLANNING_FAILED"), Ops.failureHint(failed("PLANNING_FAILED", null)).orElseThrow());
		// 400 의 details 는 목록이다 — MoveIt 원인으로 읽지 않는다.
		assertTrue(failed("INVALID_INPUT", "[{\"loc\":[\"body\"]}]").moveitErrorName().isEmpty());
		assertTrue(Ops.failureHint(failed("SOMETHING_NEW", null)).isEmpty());
	}

	@Test
	void moveitCauseAloneStillGivesAHint() throws Exception {
		assertTrue(Ops.failureHint(failed("SOMETHING_NEW", "{\"moveit_error_name\":\"NO_IK_SOLUTION\"}"))
						.orElseThrow().startsWith("MoveIt 원인 NO_IK_SOLUTION: "));
	}

	@Test
	void legacyConstructorHasNoDetails() {
		OperationResult result = new OperationResult("x", OperationResult.Status.FAILED, Map.of(), "PLANNING_FAILED", "m");
		assertNull(result.errorDetails());
		assertTrue(result.moveitErrorName().isEmpty());
	}

	@Test
	void operationFailedMessageNamesTheCause() throws Exception {
		OperationFailedException exc = new OperationFailedException(failed("PLANNING_FAILED",
				"{\"moveit_error_code\":-12,\"moveit_error_name\":\"GOAL_IN_COLLISION\"}"));
		assertTrue(exc.getMessage().contains("PLANNING_FAILED(GOAL_IN_COLLISION): x"), exc.getMessage());
		assertTrue(new OperationFailedException(failed("EXECUTION_ABORTED", null)).getMessage()
						.contains("EXECUTION_ABORTED: x"));
	}

	// ---------- 트윈 응답 → 연산 결과 ----------

	private RobotTwinClient twinFailing(String operation) throws Exception {
		m_server = new FakeTwinServer().operation(operation, "sync");
		m_server.on("POST", "/operations/" + operation, req -> FakeTwinServer.json(200, Map.of(
				"status", "FAILED",
				"error", Map.of("code", "PLANNING_FAILED", "message", "Path planning failed with error code: -31",
								"details", Map.of("moveit_error_code", -31, "moveit_error_name", "NO_IK_SOLUTION")))));
		return m_server.client();
	}

	@Test
	void moveLinearFailureCarriesTheMoveItCause() throws Exception {
		MoveCheck check = new MoveLinear(twinFailing("move_linear"), TARGET).run();

		assertFalse(check.goalReached());
		assertEquals("NO_IK_SOLUTION", check.result().moveitErrorName().orElseThrow());
		assertEquals(-31, check.result().errorDetails().get("moveit_error_code").asInt());
		assertTrue(check.failureReason().contains("IK 해가 없다"), check.failureReason());
	}

	@Test
	void namedTargetFailureCarriesTheMoveItCause() throws Exception {
		ClosedLoopCheck check = new MoveToNamedTarget(twinFailing("move_to_named_target"), "ready").run();

		assertFalse(check.goalReached());
		assertTrue(check.failureReason().contains("MoveIt 원인 NO_IK_SOLUTION"), check.failureReason());
	}

	@Test
	void runForOutputsThrowsWithTheCause() throws Exception {
		RobotTwinClient twin = twinFailing("move_linear");
		OperationFailedException exc = assertThrows(OperationFailedException.class,
				() -> twin.runForOutputs("move_linear", Map.of("pose", TARGET)));
		assertTrue(exc.getMessage().contains("PLANNING_FAILED(NO_IK_SOLUTION)"), exc.getMessage());
	}
}
