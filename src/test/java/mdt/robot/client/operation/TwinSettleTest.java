package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;

/**
 * 트윈이 이동 뒤 정착을 기다려 주는 경우({@code settle_timeout_sec}, 트윈 설계서 6.7 "정착 대기").
 * <p>
 * 트윈이 그 입력을 받으면 클라이언트는 상한을 넘기고 결과의 {@code settled}·{@code goal_error}로 판정하며 ee_pose를
 * 읽지 않는다. 받지 않는 옛 트윈에는 입력을 보내지 않는다(옛 트윈은 모르는 입력을 400 으로 거부한다).
 */
@Timeout(10)
class TwinSettleTest {
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final Pose TARGET = new Pose(new Position(0.4, 0.0, 0.1), Geometry.DOWN);
	private static final String SETTLE = "settle_timeout_sec";

	private FakeTwinServer m_server;
	private final List<String> m_bodies = new CopyOnWriteArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		if ( m_server != null ) { m_server.close(); }
	}

	private RobotTwinClient twin(String operation, boolean supportsSettle, Map<String,Object> outputs)
		throws Exception {
		m_server = supportsSettle ? new FakeTwinServer().operation(operation, "sync", SETTLE)
									: new FakeTwinServer().operation(operation, "sync");
		m_server.on("POST", "/operations/" + operation, req -> {
			m_bodies.add(req.getBody().readUtf8());
			return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs));
		});
		return m_server.client();
	}

	private JsonNode sentInputs() throws Exception {
		assertEquals(1, m_bodies.size());
		return MAPPER.readTree(m_bodies.get(0)).get("inputs");
	}

	private static Map<String,Object> linearOutputs(Boolean settled, double x) {
		Map<String,Object> outputs = new LinkedHashMap<>();
		outputs.put("closed_loop", false);
		outputs.put("final_pose", Map.of("pose", new Pose(new Position(x, 0.0, 0.1), Geometry.DOWN)));
		if ( settled != null ) { outputs.put("settled", settled); }
		return outputs;
	}

	private static Map<String,Object> jointOutputs(boolean settled, Double jointError) {
		Map<String,Object> outputs = new LinkedHashMap<>();
		outputs.put("closed_loop", false);
		outputs.put("settled", settled);
		if ( jointError != null ) { outputs.put("goal_error", Map.of("joint_max_rad", jointError)); }
		return outputs;
	}

	// ---------- move_linear ----------

	@Test
	void linearHandsTheWaitToATwinThatSettles() throws Exception {
		RobotTwinClient twin = twin("move_linear", true, linearOutputs(true, 0.401));
		MoveCheck check = new MoveLinear(twin, TARGET,
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofMillis(2500))).run();

		assertEquals(2.5, sentInputs().get(SETTLE).asDouble(), 1e-9);
		assertTrue(check.goalReached());
		assertEquals(0.001, check.errorM(), 1e-9);
		assertEquals(0.401, Pose.fromJson(check.settledPose()).position().x(), 1e-9);
		assertTrue(m_server.requests("GET /variables").isEmpty(), "the twin already waited");
	}

	@Test
	void linearReportsSettleTimeoutFromTheTwin() throws Exception {
		RobotTwinClient twin = twin("move_linear", true, linearOutputs(false, 0.4));
		MoveCheck check = new MoveLinear(twin, TARGET,
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(3))).run();

		assertFalse(check.goalReached());
		assertEquals(MoveCheck.SETTLE_TIMEOUT, check.failureCode());
		assertNull(check.errorM());
		assertNull(check.settledPose());
		assertTrue(m_server.requests("GET /variables").isEmpty());
	}

	@Test
	void linearNeverSendsTheInputToAnOldTwin() throws Exception {
		// closed_loop=true 라 옛 경로에서도 관측 없이 끝난다.
		Map<String,Object> outputs = linearOutputs(null, 0.4);
		outputs.put("closed_loop", true);
		RobotTwinClient twin = twin("move_linear", false, outputs);
		MoveCheck check = new MoveLinear(twin, TARGET,
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(1))).run();

		assertFalse(sentInputs().has(SETTLE));
		assertTrue(check.goalReached());
	}

	@Test
	void linearWithoutSettleTimeoutDoesNotAskTheCatalogForIt() throws Exception {
		RobotTwinClient twin = twin("move_linear", true, linearOutputs(null, 0.4));
		new MoveLinear(twin, TARGET).run();
		assertFalse(sentInputs().has(SETTLE));
	}

	// ---------- move_to_named_target · move_to_joints ----------

	@ParameterizedTest
	@CsvSource({ "0.002, true", "0.05, false" })
	void namedTargetJudgesOpenLoopByJointErrorOnceSettled(double error, boolean reached) throws Exception {
		RobotTwinClient twin = twin("move_to_named_target", true, jointOutputs(true, error));
		MotionLimits limits = MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(5)).withJointToleranceRad(0.01);
		ClosedLoopCheck check = new MoveToNamedTarget(twin, "ready", limits).run();

		assertEquals(5.0, sentInputs().get(SETTLE).asDouble(), 1e-9);
		assertEquals(reached, check.goalReached());
		assertEquals(error, check.jointErrorRad().getAsDouble(), 1e-12);
		assertEquals(true, check.settled().orElseThrow());
		if ( !reached ) {
			assertTrue(check.failureReason().contains("허용값"));
		}
	}

	@Test
	void namedTargetSettleTimeout() throws Exception {
		RobotTwinClient twin = twin("move_to_named_target", true, jointOutputs(false, null));
		ClosedLoopCheck check = new MoveToNamedTarget(twin, "ready",
				MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(5))).run();
		assertFalse(check.goalReached());
		assertTrue(check.failureReason().contains(MoveCheck.SETTLE_TIMEOUT));
	}

	@Test
	void settledWithoutJointErrorIsNotReached() throws Exception {
		RobotTwinClient twin = twin("move_to_named_target", true, jointOutputs(true, null));
		ClosedLoopCheck check = new MoveToNamedTarget(twin, "ready",
				MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(5))).run();
		assertFalse(check.goalReached());
		assertTrue(check.failureReason().contains("goal_error"));
	}

	@Test
	void namedTargetKeepsTheClosedLoopRuleOnAnOldTwin() throws Exception {
		RobotTwinClient twin = twin("move_to_named_target", false, Map.of("closed_loop", false));
		ClosedLoopCheck check = new MoveToNamedTarget(twin, "ready",
				MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(5))).run();
		assertFalse(sentInputs().has(SETTLE));
		assertFalse(check.goalReached());
		assertTrue(check.failureReason().contains("open loop"));
		assertTrue(check.settled().isEmpty());
	}

	@Test
	void jointsUseTheSameRule() throws Exception {
		RobotTwinClient twin = twin("move_to_joints", true, jointOutputs(true, 0.003));
		ClosedLoopCheck check = new MoveToJoints(twin, Map.of("panda_joint1", 0.0),
				MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(4))).run();
		assertEquals(4.0, sentInputs().get(SETTLE).asDouble(), 1e-9);
		assertTrue(check.goalReached());
	}

	// ---------- 설정 · 카탈로그 ----------

	@Test
	void motionLimitsDefaultsDoNotWait() {
		assertEquals(Duration.ZERO, MotionLimits.DEFAULT.settleTimeout());
		assertEquals(MotionLimits.DEFAULT_JOINT_TOLERANCE_RAD, MotionLimits.DEFAULT.jointToleranceRad());
		MotionLimits legacy = new MotionLimits(0.2, null, null);
		assertEquals(Duration.ZERO, legacy.settleTimeout());
		// with* 는 정착 설정을 잃지 않는다.
		MotionLimits limits = MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(3)).withVelocityScaling(0.5)
													.withMaxDuration(Duration.ofSeconds(30)).withTimeout(Duration.ofSeconds(60));
		assertEquals(Duration.ofSeconds(3), limits.settleTimeout());
	}

	@Test
	void motionLimitsRejectBadSettleValues() {
		assertThrows(IllegalArgumentException.class, () -> MotionLimits.DEFAULT.withSettleTimeout(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> MotionLimits.DEFAULT.withSettleTimeout(null));
		assertThrows(IllegalArgumentException.class, () -> MotionLimits.DEFAULT.withJointToleranceRad(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> MotionLimits.DEFAULT.withJointToleranceRad(-0.1));
	}

	@Test
	void acceptsInputReadsTheCatalogOnceAndRereadsForUnknownOperations() throws Exception {
		RobotTwinClient twin = twin("move_linear", true, linearOutputs(null, 0.4));
		assertTrue(twin.acceptsInput("move_linear", SETTLE));
		assertFalse(twin.acceptsInput("move_linear", "frame"));
		assertEquals(1, m_server.requests("GET /operations").size());
		assertFalse(twin.acceptsInput("move_to_pose", SETTLE));
		assertEquals(2, m_server.requests("GET /operations").size(), "unknown operation triggers one reload");
	}
}
