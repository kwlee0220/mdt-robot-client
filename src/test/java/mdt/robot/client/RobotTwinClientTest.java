package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RobotTwinClientTest {
	private static final String SESSION = FakeTwinServer.BASE + "/operations/sessions/s1";

	private FakeTwinServer m_twin;

	@BeforeEach
	void setUp() throws Exception {
		m_twin = new FakeTwinServer().operation("move_gripper_to_target", "sync")
										.operation("move_linear", "async");
	}

	@AfterEach
	void tearDown() throws Exception {
		m_twin.close();
	}

	@Test
	void defaultTargetIsTheSingleTwin() {
		RobotTwinClient twin = RobotTwinClient.create();
		assertEquals("http://127.0.0.1:8801", twin.getBaseUrl());
		assertEquals("http://127.0.0.1:8801/api/v1/robot_twins/panda01", twin.getTwinUrl());
	}

	@Test
	void baseUrlKeepsHttpsAndPathPrefixAndDropsTrailingSlash() {
		RobotTwinClient twin = RobotTwinClient.builder()
												.baseUrl("https://gw.example.com/robot-twin/")
												.twinId("ur01")
												.build();
		assertEquals("https://gw.example.com/robot-twin", twin.getBaseUrl());
		assertEquals("https://gw.example.com/robot-twin/api/v1/robot_twins/ur01", twin.getTwinUrl());
	}

	@Test
	void baseUrlRejectsWhatCannotBeARoot() {
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().baseUrl("ftp://host:8801"));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().baseUrl("127.0.0.1:8801"));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().baseUrl("http://host:8801?x=1"));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().baseUrl("http://host:8801#top"));
	}

	@Test
	void syncOperationReturnsOutputs() {
		m_twin.on("POST", "/operations/move_gripper_to_target", req -> {
			assertTrue(req.getBody().readUtf8().contains("\"inputs\":{\"target\":\"open\"}"));
			return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("at_goal", true)));
		});
		OperationResult result = m_twin.client().run("move_gripper_to_target", Map.of("target", "open"));
		assertTrue(result.isCompleted());
		assertTrue(result.output("at_goal").asBoolean());
	}

	@Test
	void syncFailureIsAResultNotAnException() {
		m_twin.on("POST", "/operations/move_gripper_to_target", req -> FakeTwinServer.json(200, Map.of(
				"status", "FAILED", "error", Map.of("code", "TIMEOUT", "message", "at_goal never set"))));
		OperationResult result = m_twin.client().run("move_gripper_to_target", Map.of("target", "grasp"));
		assertEquals("FAILED", result.status());
		assertEquals("TIMEOUT", result.errorCode());
		assertEquals("at_goal never set", result.errorMessage());
	}

	@Test
	void outputsTurnsAFailureIntoAnException() {
		m_twin.on("POST", "/operations/move_gripper_to_target", req -> FakeTwinServer.json(200, Map.of(
				"status", "FAILED", "error", Map.of("code", "TIMEOUT", "message", "at_goal never set"))));
		OperationFailedException e = assertThrows(OperationFailedException.class,
								() -> m_twin.client().outputs("move_gripper_to_target", Map.of("target", "grasp")));
		assertEquals("TIMEOUT", e.getCode());
	}

	@Test
	void asyncOperationPollsTheSessionUntilItEnds() {
		AtomicInteger polls = new AtomicInteger();
		m_twin.on("POST", "/operations/move_linear",
					req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", SESSION)))
				.on("GET", "/operations/sessions/s1", req -> polls.incrementAndGet() < 3
						? FakeTwinServer.json(200, Map.of("status", "RUNNING"))
						: FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("fraction", 1.0))));
		OperationResult result = m_twin.client().run("move_linear", Map.of());
		assertTrue(result.isCompleted());
		assertEquals(1.0, result.output("fraction").asDouble());
		assertTrue(polls.get() >= 3);
	}

	@Test
	void asyncSessionFailureCarriesTheCode() {
		m_twin.on("POST", "/operations/move_linear",
					req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", SESSION)))
				.on("GET", "/operations/sessions/s1", req -> FakeTwinServer.json(200, Map.of(
						"status", "FAILED", "error", Map.of("code", "PLANNING_FAILED", "message", "no plan"))));
		OperationResult result = m_twin.client().run("move_linear", Map.of());
		assertEquals("PLANNING_FAILED", result.errorCode());
		assertEquals("계획 실패 — 로봇은 움직이지 않았다. 같은 목표로 다시 불러도 결과가 같으니 목표나 시작 자세를 바꾼다",
					Ops.failureWhy(result).orElseThrow());
	}

	@Test
	void rejectionBeforeASessionIsATwinException() {
		m_twin.on("POST", "/operations/move_linear", req -> FakeTwinServer.json(409, Map.of(
				"error", Map.of("code", "RESOURCE_BUSY", "message", "arm is held"))));
		TwinException e = assertThrows(TwinException.class, () -> m_twin.client().run("move_linear", Map.of()));
		assertEquals(409, e.getHttpStatus());
		assertEquals("RESOURCE_BUSY", e.getCode());
	}

	@Test
	void pollingTimeoutStopsWaitingWithoutCancelling() throws Exception {
		m_twin.on("POST", "/operations/move_linear",
					req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", SESSION)))
				.on("GET", "/operations/sessions/s1", req -> FakeTwinServer.json(200, Map.of("status", "RUNNING")))
				.on("DELETE", "/operations/sessions/s1", req -> FakeTwinServer.json(202,
						Map.of("status", "RUNNING", "phase", "CANCELING", "session_endpoint", SESSION)));

		OperationTimeoutException e = assertThrows(OperationTimeoutException.class,
								() -> m_twin.client().run("move_linear", Map.of(), Duration.ofMillis(200)));
		assertTrue(m_twin.requests("DELETE").isEmpty(), "timeout must not cancel the remote operation");

		e.cancel();
		assertFalse(m_twin.requests("DELETE").isEmpty());
	}

	@Test
	void readRefusesAValueThatIsNotOk() {
		m_twin.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(200, Map.of(
				"quality", "STALE", "reason", "SOURCE_TIMEOUT", "value", Map.of())));
		RobotTwinClient client = m_twin.client();
		VariableNotOkException e = assertThrows(VariableNotOkException.class, () -> client.read("ee_pose"));
		assertEquals("STALE", e.getQuality());
		assertEquals("STALE", client.readRaw("ee_pose").get("quality").asText());
	}

	@Test
	void requireBackendComparesTheMetaBackend() {
		m_twin.on("GET", "", req -> FakeTwinServer.json(200, Map.of("id", "panda01", "backend", "panda_mock")));
		RobotTwinClient client = m_twin.client();
		assertEquals("panda_mock", client.requireBackend("pick", "panda_mock", "panda_isaac"));
		WrongBackendException e = assertThrows(WrongBackendException.class,
												() -> client.requireBackend("stack", "panda_isaac"));
		assertEquals("panda_mock", e.getActual());
	}

	@Test
	void requireBackendRejectsATwinThatDoesNotSay() {
		m_twin.on("GET", "", req -> FakeTwinServer.json(200, Map.of("id", "panda01")));
		WrongBackendException e = assertThrows(WrongBackendException.class,
												() -> m_twin.client().requireBackend("stack", "panda_isaac"));
		assertNull(e.getActual());
	}
}
