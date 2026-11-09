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
		assertEquals("FAILED", result.status().name());
		assertEquals("TIMEOUT", result.errorCode());
		assertEquals("at_goal never set", result.errorMessage());
	}

	@Test
	void outputsTurnsAFailureIntoAnException() {
		m_twin.on("POST", "/operations/move_gripper_to_target", req -> FakeTwinServer.json(200, Map.of(
				"status", "FAILED", "error", Map.of("code", "TIMEOUT", "message", "at_goal never set"))));
		OperationFailedException e = assertThrows(OperationFailedException.class,
								() -> m_twin.client().runForOutputs("move_gripper_to_target", Map.of("target", "grasp")));
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
					Ops.failureHint(result).orElseThrow());
	}

	@Test
	void lostSessionDuringPollingIsNotAnAcceptanceRejection() {
		// 접수된 뒤 세션이 사라지면(만료·트윈 재시작) 결과를 알 수 없다. 접수 거부(TwinException)와 구분한다.
		m_twin.on("POST", "/operations/move_linear",
					req -> FakeTwinServer.json(202, Map.of("status", "RUNNING", "session_endpoint", SESSION)))
				.on("GET", "/operations/sessions/s1", req -> FakeTwinServer.json(404, Map.of(
						"error", Map.of("code", "SESSION_NOT_FOUND", "message", "no such session"))));
		SessionLostException e = assertThrows(SessionLostException.class,
												() -> m_twin.client().run("move_linear", Map.of()));
		assertEquals("move_linear", e.getOperationName());
		assertTrue(e.getSessionUrl().endsWith(SESSION), e.getSessionUrl());
		assertEquals(404, e.getHttpStatus());
		assertEquals("SESSION_NOT_FOUND", e.getCode());
		assertTrue(e.getMessage().contains("outcome is unknown"), e.getMessage());
	}

	@Test
	void busyRejectionCarriesDetailsAndRetryAfter() {
		// 409 RESOURCE_BUSY의 error.details와 Retry-After를 버리지 않는다.
		m_twin.on("POST", "/operations/move_linear", req -> FakeTwinServer.json(409, Map.of(
				"error", Map.of("code", "RESOURCE_BUSY", "message", "arm is held",
								"details", Map.of("resource", "arm", "occupied_by", SESSION))))
				.setHeader("Retry-After", "2.5"));
		TwinException e = assertThrows(TwinException.class, () -> m_twin.client().run("move_linear", Map.of()));
		assertEquals(SESSION, e.getDetails().path("occupied_by").asText());
		assertEquals(Duration.ofMillis(2500), e.getRetryAfter().orElseThrow());
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
	void builderRejectsInvalidIdsAndNonPositiveDurations() {
		// id는 URL 경로에 들어가므로 트윈 id 규칙([A-Za-z0-9_-]+)만 받는다. 시간은 양수여야 한다.
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().twinId("panda 01"));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().twinId("../x"));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().twinId(""));
		assertEquals("rdfp01", RobotTwinClient.builder().twinId("rdfp01").build().getTwinId());
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().httpTimeout(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().pollInterval(Duration.ofMillis(-1)));
		assertThrows(IllegalArgumentException.class, () -> RobotTwinClient.builder().operationTimeout(Duration.ZERO));
	}

	@Test
	void injectedHttpClientIsUsed() {
		// 주입한 OkHttpClient의 연결 풀·디스패처를 쓴다.
		AtomicInteger intercepted = new AtomicInteger();
		okhttp3.OkHttpClient shared = new okhttp3.OkHttpClient.Builder()
				.addInterceptor(chain -> { intercepted.incrementAndGet(); return chain.proceed(chain.request()); })
				.build();
		m_twin.on("GET", "", req -> FakeTwinServer.json(200, Map.of("id", "panda01", "backend", "panda_isaac")));
		RobotTwinClient client = RobotTwinClient.builder().baseUrl(m_twin.client().getBaseUrl()).httpClient(shared).build();
		assertEquals("panda_isaac", client.meta().path("backend").asText());
		assertTrue(intercepted.get() > 0);
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
	void requireBackendSuggestsTheTwinOption() {
		// 실행 스크립트는 --twin <id>를 받는다. 안내도 그 옵션을 가리킨다.
		m_twin.on("GET", "", req -> FakeTwinServer.json(200, Map.of("id", "rdfp01", "backend", "panda_functionbay")));
		WrongBackendException e = assertThrows(WrongBackendException.class,
												() -> m_twin.client().requireBackend("stack", "panda_isaac"));
		assertTrue(e.getMessage().contains("run_robot_twin.sh --twin <id>"), e.getMessage());
		assertFalse(e.getMessage().contains("--backend"), e.getMessage());
	}

	@Test
	void unknownTwinPathHintsAtTheTwinId() {
		// 트윈 id가 틀리면 서버 프레임워크가 오류 코드 없는 404를 준다. id 확인 안내를 붙인다.
		m_twin.on("GET", "/variables/ee_pose", req -> FakeTwinServer.json(404, Map.of("detail", "Not Found")));
		TwinException e = assertThrows(TwinException.class, () -> m_twin.client().readRaw("ee_pose"));
		assertEquals(404, e.getHttpStatus());
		assertNull(e.getCode());
		assertTrue(e.getMessage().contains("check the twin id 'panda01'"), e.getMessage());
		assertTrue(e.getMessage().contains("/api/v1/robot_twins"), e.getMessage());
	}

	@Test
	void structuredNotFoundHasNoTwinIdHint() {
		// 연산·변수·세션의 404는 오류 코드가 있으므로 id 안내를 붙이지 않는다.
		TwinException e = assertThrows(TwinException.class, () -> m_twin.client().readRaw("no_such_variable"));
		assertEquals("NOT_FOUND", e.getCode());
		assertFalse(e.getMessage().contains("check the twin id"), e.getMessage());
	}

	@Test
	void requireBackendRejectsATwinThatDoesNotSay() {
		m_twin.on("GET", "", req -> FakeTwinServer.json(200, Map.of("id", "panda01")));
		WrongBackendException e = assertThrows(WrongBackendException.class,
												() -> m_twin.client().requireBackend("stack", "panda_isaac"));
		assertNull(e.getActual());
	}

	@Test
	void syncOperationThatBecameAsyncIsFollowedThroughItsSession() {
		// 카탈로그 캐시는 sync인데 재기동한 트윈이 202 세션을 돌려준다. 세션에 붙어 결과를 받고 캐시를 비운다.
		RobotTwinClient twin = m_twin.client();
		assertFalse(twin.isAsyncOperation("move_gripper_to_target"));
		m_twin.operation("move_gripper_to_target", "async");
		m_twin.on("POST", "/operations/move_gripper_to_target", req -> FakeTwinServer.json(202,
				Map.of("status", "RUNNING", "session_endpoint", SESSION, "outputs", Map.of())));
		m_twin.on("GET", "/operations/sessions/s1", req -> FakeTwinServer.json(200,
				Map.of("status", "COMPLETED", "session_endpoint", SESSION, "outputs", Map.of("at_goal", true))));
		OperationResult result = twin.run("move_gripper_to_target", Map.of("target", "open"), Duration.ofSeconds(3));
		assertTrue(result.isCompleted());
		assertTrue(result.output("at_goal").asBoolean());
		assertTrue(twin.isAsyncOperation("move_gripper_to_target"), "the catalog is read again");
		assertEquals(2, m_twin.requests("GET /operations").stream().filter(r -> r.equals("GET /operations")).count());
	}

	@Test
	void asyncOperationThatBecameSyncReturnsTheStartResult() {
		RobotTwinClient twin = m_twin.client();
		assertTrue(twin.isAsyncOperation("move_linear"));
		m_twin.operation("move_linear", "sync");
		m_twin.on("POST", "/operations/move_linear", req -> FakeTwinServer.json(200,
				Map.of("status", "COMPLETED", "outputs", Map.of("closed_loop", true))));
		OperationResult result = twin.run("move_linear", Map.of(), Duration.ofSeconds(3));
		assertTrue(result.isCompleted());
		assertTrue(result.output("closed_loop").asBoolean());
		assertFalse(twin.isAsyncOperation("move_linear"), "the catalog is read again");
		assertTrue(m_twin.requests("GET /operations/").isEmpty(), "no session to poll");
	}
}
