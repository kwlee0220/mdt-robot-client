package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;

class MoveLinearOptionsTest {
	private static final Pose TARGET = new Pose(new Position(0.4, 0, 0.1), Geometry.DOWN);

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void constructorAndBuilderUseSameOptionsWithoutMutatingEarlierExecutions(boolean serverVelocity) throws Exception {
		try (FakeTwinServer server = new FakeTwinServer().operation("move_linear", "sync")) {
			var requests = new CopyOnWriteArrayList<JsonNode>();
			var json = JsonMapper.builder().build();
			server.on("POST", "/operations/move_linear", r -> {
				try { requests.add(json.readTree(r.getBody().readUtf8()).path("inputs")); }
				catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
				return FakeTwinServer.json(200, Map.of("status", "COMPLETED", "outputs", Map.of(
						"closed_loop", true, "final_pose", TARGET.withPosition(new Position(0.408, 0, 0.1)))));
			});
			Double velocity = serverVelocity ? null : 0.3;
			MoveLinear.Options options = MoveLinear.Options.DEFAULT.withTolerance(0.01)
					.withVelocityScaling(velocity).withSettleTimeout(Duration.ofSeconds(1));
			MoveLinear fromOptions = new MoveLinear(server.client(), TARGET, options);
			MoveLinear.Builder builder = MoveLinear.builder(server.client(), TARGET).tolerance(0.01)
					.velocityScaling(velocity).settleTimeout(Duration.ofSeconds(1));
			MoveLinear fromBuilder = builder.build();
			builder.tolerance(0).velocityScaling(0.9);
			assertTrue(server.requests.isEmpty());
			assertTrue(fromOptions.run().reached());
			assertTrue(fromBuilder.run().reached());
			assertEquals(requests.get(0), requests.get(1));
			assertEquals(!serverVelocity, requests.get(0).has("velocity_scaling"));
			assertFalse(new MoveLinear(server.client(), TARGET).run().reached()); // 기본 허용 오차 5mm
			assertFalse(MoveLinear.builder(server.client(), TARGET).build().run().reached());
			assertEquals(requests.get(2), requests.get(3));
			assertEquals(0.2, requests.get(2).path("velocity_scaling").asDouble());
			assertTrue(server.requests("GET /variables/ee_pose").isEmpty());
		}
	}

	@Test
	void optionsAndBuilderRejectInvalidToleranceBeforeRequests() throws Exception {
		try (FakeTwinServer server = new FakeTwinServer()) {
			for (double value : new double[] { -1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY }) {
				assertThrows(IllegalArgumentException.class, () -> new MoveLinear.Options(value, 0.2, Duration.ZERO));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.Options.DEFAULT.withTolerance(value));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.builder(server.client(), TARGET).tolerance(value));
			}
			assertTrue(server.requests.isEmpty());
		}
	}

	@Test
	void optionsAndBuilderRejectInvalidVelocityBeforeRequests() throws Exception {
		try (FakeTwinServer server = new FakeTwinServer()) {
			for (double value : new double[] { -1, 0, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY }) {
				assertThrows(IllegalArgumentException.class, () -> new MoveLinear.Options(0.005, value, Duration.ZERO));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.Options.DEFAULT.withVelocityScaling(value));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.builder(server.client(), TARGET).velocityScaling(value));
			}
			assertTrue(server.requests.isEmpty());
		}
	}

	@Test
	void optionsAndBuilderRejectInvalidSettlingBeforeRequests() throws Exception {
		try (FakeTwinServer server = new FakeTwinServer()) {
			for (Duration value : Arrays.asList(null, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
				assertThrows(IllegalArgumentException.class, () -> new MoveLinear.Options(0.005, 0.2, value));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.Options.DEFAULT.withSettleTimeout(value));
				assertThrows(IllegalArgumentException.class, () -> MoveLinear.builder(server.client(), TARGET).settleTimeout(value));
			}
			assertTrue(server.requests.isEmpty());
		}
	}
}
