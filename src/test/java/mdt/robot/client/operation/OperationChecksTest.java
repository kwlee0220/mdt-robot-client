package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.OperationResult;
import mdt.robot.client.Ops;
import mdt.robot.client.RobotTwinClient;

class OperationChecksTest {
	private final RobotTwinClient m_twin = RobotTwinClient.create();

	@ParameterizedTest
	@ValueSource(strings = { "true", "false", "null", "missing", "\"true\"", "1", "{}", "[]" })
	void namedAndJointOperationsShareClosedLoopJudgment(String value) throws Exception {
		Map<String,JsonNode> outputs = value.equals("missing") ? Map.of()
				: Map.of("closed_loop", JsonMapper.builder().build().readTree(value));
		OperationResult result = new OperationResult("test", "COMPLETED", outputs, null, null);
		MoveToNamedTarget.Check named = new MoveToNamedTarget(m_twin, "ready").convertResult(result);
		MoveToJoints.Check joints = new MoveToJoints(m_twin, Map.of("joint_a", 0.0)).convertResult(result);
		assertEquals(value.equals("true"), joints.reached());
		assertEquals(named.reached(), joints.reached());
		assertEquals(named.why(), joints.why());
		assertSame(result, named.result());
		assertSame(result, joints.result());
	}

	@ParameterizedTest
	@MethodSource("failures")
	void failureGuidanceUsesCodeThenMessageThenStatus(String status, String code, String message, String expected) {
		OperationResult result = new OperationResult("test", status, Map.of(), code, message);
		MoveToNamedTarget.Check named = new MoveToNamedTarget(m_twin, "ready").convertResult(result);
		MoveToJoints.Check joints = new MoveToJoints(m_twin, Map.of("joint_a", 0.0)).convertResult(result);
		GripperCheck gripper = new MoveGripperToNamedTarget(m_twin, "open").convertResult(result);
		assertFalse(named.reached());
		assertFalse(joints.reached());
		assertFalse(gripper.atGoal());
		assertEquals(expected, named.why());
		assertEquals(expected, joints.why());
		assertEquals(expected, gripper.why());
	}

	private static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of("FAILED", "TIMEOUT", "server message", Ops.FAILURE_HINTS.get("TIMEOUT")),
				Arguments.of("FAILED", "CUSTOM_ERROR", "server message", "server message"),
				Arguments.of("FAILED", "CUSTOM_ERROR", null, "원격 연산 상태: FAILED"),
				Arguments.of("FAILED", null, "", "원격 연산 상태: FAILED"),
				Arguments.of("FAILED", null, "  ", "원격 연산 상태: FAILED"),
				Arguments.of("CANCELLED", null, null, "원격 연산 상태: CANCELLED"));
	}

	@Test
	void jointGroupHintIsSpecificToJointPlanningFailure() {
		OperationResult result = new OperationResult("test", "FAILED", Map.of(), "PLANNING_FAILED", "no plan");
		String named = new MoveToNamedTarget(m_twin, "ready").convertResult(result).why();
		String joints = new MoveToJoints(m_twin, Map.of("joint_a", 0.0)).convertResult(result).why();
		assertEquals(Ops.FAILURE_HINTS.get("PLANNING_FAILED"), named);
		assertTrue(joints.startsWith(named));
		assertTrue(joints.contains("이동 그룹"));
		assertTrue(joints.contains("Panda에서는 손가락 관절"));
		assertFalse(named.contains("손가락"));
	}
}
