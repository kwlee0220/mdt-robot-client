package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.RobotTwinClient;

class MoveLinearBuilderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pose TARGET = new Pose(new Position(0.4, 0, 0.1), Geometry.DOWN);

    @Test
    void forwardsOptionalInputsAndSnapshotsBuilderWithoutImplicitCoordinateConversion() throws Exception {
        try (FakeTwinServer server = new FakeTwinServer().operation("move_linear", "sync")) {
            AtomicReference<String> request = new AtomicReference<>();
            server.on("POST", "/operations/move_linear", r -> {
                request.set(r.getBody().readUtf8());
                return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of("final_pose", TARGET)));
            });
            MoveLinear.Builder builder = MoveLinear.builder(server.client(), TARGET)
                    .velocityScaling(null).maxDuration(Duration.ofMillis(1250));
            MoveLinear operation = builder.build();
            builder.maxDuration(Duration.ofSeconds(2)).velocityScaling(0.5);
            assertTrue(server.requests.isEmpty());
            assertTrue(operation.run().goalReached());
            JsonNode inputs = JSON.readTree(request.get()).path("inputs");
            assertEquals(TARGET, Pose.fromJson(inputs.path("pose")));
            assertFalse(inputs.has("velocity_scaling"));
            for (String name : List.of("frame", "max_step", "jump_threshold")) {
                assertFalse(inputs.has(name), name);
            }
            assertEquals(1.25, inputs.path("max_duration_sec").asDouble());

            MoveLinear.builder(server.client(), TARGET).build().run();
            inputs = JSON.readTree(request.get()).path("inputs");
            assertEquals(MoveLinear.Options.DEFAULT.velocityScaling(), inputs.path("velocity_scaling").asDouble());
            for (String name : List.of("frame", "max_step", "jump_threshold", "max_duration_sec")) {
                assertFalse(inputs.has(name), name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "null", "\"true\"", "1", "missing"})
    void onlyBooleanClosedLoopTrueSkipsObservation(String closedLoop) throws Exception {
        try (FakeTwinServer server = new FakeTwinServer().operation("move_linear", "sync")) {
            var outputs = JSON.createObjectNode();
            outputs.set("final_pose", JSON.valueToTree(TARGET));
            if (!closedLoop.equals("missing")) { outputs.set("closed_loop", JSON.readTree(closedLoop)); }
            server.on("POST", "/operations/move_linear", r ->
                    FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", outputs)));
            server.on("GET", "/variables/ee_pose", r -> FakeTwinServer.json(200, Map.of("quality", "NO_DATA")));
            MoveCheck result = MoveLinear.builder(server.client(), TARGET)
                    .settleTimeout(Duration.ofMillis(100)).build().run();
            boolean closed = closedLoop.equals("true");
            assertEquals(closed, result.goalReached());
            assertEquals(closed, server.requests("GET /variables/ee_pose").isEmpty());
            if (!closed) { assertEquals(MoveCheck.SETTLE_TIMEOUT, result.failureCode()); }
        }
    }

    @Test
    void existingConstructorAlsoSkipsClosedLoopObservation() throws Exception {
        try (FakeTwinServer server = new FakeTwinServer().operation("move_linear", "sync")) {
            server.on("POST", "/operations/move_linear", r -> FakeTwinServer.json(200,
                    Map.of("status", "COMPLETED", "outputs", Map.of("closed_loop", true, "final_pose", TARGET))));
            server.on("GET", "/variables/ee_pose", r -> FakeTwinServer.json(200, Map.of("quality", "NO_DATA")));
            MoveCheck check = new MoveLinear(server.client(), TARGET,
                    MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofMillis(100))).run();
            assertTrue(check.goalReached());
            assertNull(check.settledPose());
            assertTrue(server.requests("GET /variables/ee_pose").isEmpty());
        }
    }

    @Test
    void invalidBuilderSettingsAreRejectedBeforeAnyRequest() throws Exception {
        try (FakeTwinServer server = new FakeTwinServer()) {
            List<Consumer<MoveLinear.Builder>> invalid = List.of(
                    b -> b.positionToleranceM(Double.NaN), b -> b.positionToleranceM(-1),
                    b -> b.velocityScaling(Double.POSITIVE_INFINITY), b -> b.velocityScaling(0.0),
                    b -> b.velocityScaling(1.1),
                    b -> b.maxDuration(Duration.ZERO), b -> b.timeout(Duration.ZERO),
                    b -> b.timeout(null), b -> b.settleTimeout(Duration.ofSeconds(-1)),
                    b -> b.settleTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
            for (var setting : invalid) {
                assertThrows(IllegalArgumentException.class, () -> setting.accept(MoveLinear.builder(server.client(), TARGET)));
            }
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void explicitClientTimeoutIsIndependentOfServerMaxDurationAndDoesNotCancel() throws Exception {
        try (FakeTwinServer server = new FakeTwinServer().operation("move_linear", "async")) {
            String session = "/operations/sessions/slow";
            server.on("POST", "/operations/move_linear", r -> FakeTwinServer.json(202,
                    Map.of("status", "RUNNING", "session_endpoint", FakeTwinServer.BASE + session)));
            server.on("GET", session, r -> FakeTwinServer.json(200, Map.of("status", "RUNNING")));
            RobotTwinClient twin = server.client();
            ExecutionException failure = assertThrows(ExecutionException.class, () ->
                    MoveLinear.builder(twin, TARGET).maxDuration(Duration.ofSeconds(60))
                            .timeout(Duration.ofMillis(100)).build().run());
            assertInstanceOf(OperationTimeoutException.class, failure.getCause());
            assertTrue(server.requests("DELETE").isEmpty());
            // 데몬 폴러도 시험 종료 전에 끝날 수 있도록 원격 결과를 종료 상태로 바꾼다.
            server.on("GET", session, r -> FakeTwinServer.json(200, Map.of("status", "CANCELLED")));
        }
    }
}
