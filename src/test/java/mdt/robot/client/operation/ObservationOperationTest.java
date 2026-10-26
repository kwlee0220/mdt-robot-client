package mdt.robot.client.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import mdt.robot.client.FakeTwinServer;
import mdt.robot.client.RobotTwinClient;

@Timeout(10)
class ObservationOperationTest {
	@ParameterizedTest
	@CsvSource({"ee,false", "ee,true", "object,false", "object,true", "fresh,false", "fresh,true"})
	void commonDiagnosticsKeepPublishedEvidenceAfterFailureOrCancellation(String kind, boolean cancel) throws Exception {
		try (FakeTwinServer server = new FakeTwinServer()) {
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicInteger reads = new AtomicInteger();
			String variable = kind.equals("ee") ? "ee_pose" : "scene_objects";
			server.on("GET", "/variables/" + variable, req -> {
				int n = reads.incrementAndGet();
				if ( n == 5 ) {
					entered.countDown();
					await(release);
					return cancel ? FakeTwinServer.json(200, sample(5))
							: FakeTwinServer.json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "missing")));
				}
				return FakeTwinServer.json(200, n == 3 ? Map.of("quality", "STALE") : sample(n <= 2 ? 1 : n));
			});
			AbstractObservationOperation<?,?> operation = operation(kind, server.client());
			ObservationDiagnostics<?> initial = operation.getDiagnostics();
			assertNotNull(operation.getProgress());
			assertEquals(0, initial.reads());
			operation.start();
			try {
				assertTrue(entered.await(3, TimeUnit.SECONDS));
				ObservationDiagnostics<?> before = operation.getDiagnostics();
				assertEvidence(before);
				if ( cancel ) {
					assertFalse(operation.cancel(false));
					assertTrue(operation.cancel(true));
					assertTrue(operation.cancel(true));
				}
				release.countDown();
				if ( cancel ) {
					assertThrows(CancellationException.class, () -> operation.get(3, TimeUnit.SECONDS));
					assertTrue(operation.isCancelled());
				}
				else {
					assertThrows(ExecutionException.class, () -> operation.get(3, TimeUnit.SECONDS));
					assertTrue(operation.isFailed());
					assertFalse(operation.cancel(true));
				}
				assertEvidence(operation.getDiagnostics());
				assertEvidence(before); // 이미 게시된 스냅샷은 종료 처리에도 바뀌지 않는다.
				assertEquals(0, initial.reads());
				assertTrue(server.requests.stream().allMatch(r -> r.equals("GET /variables/" + variable)));
			}
			finally { release.countDown(); }
		}
	}

	@Test void cumulativeNewSamplesAreNotTheFreshnessWindowUpdates() throws Exception {
		try (FakeTwinServer server = new FakeTwinServer()) {
			AtomicInteger reads = new AtomicInteger();
			server.on("GET", "/variables/scene_objects", req -> {
				int n = reads.incrementAndGet();
				return FakeTwinServer.json(200, n == 4 ? Map.of("quality", "STALE") : sample(n <= 2 ? 1 : n));
			});
			WaitForFreshVariable operation = new WaitForFreshVariable(server.client(), "scene_objects",
					new WaitForFreshVariable.Options(2, Duration.ofSeconds(3), Duration.ofMillis(5)));
			var result = operation.run();
			assertTrue(result.fresh());
			assertSame(result.diagnostics(), operation.getProgress());
			ObservationDiagnostics<?> counts = operation.getDiagnostics();
			assertEquals(7, counts.reads());
			assertEquals(6, counts.validSamples());
			assertEquals(5, counts.newSamples()); // 옛 구간과 새 구간의 기준 표본도 포함한다.
			assertEquals(1, counts.duplicateSamples());
			assertEquals(1, counts.rejectedSamples());
			assertEquals(1, counts.resetCount());
			assertEquals(2, result.diagnostics().updates()); // 마지막 구간의 기준 이후 갱신만 센다.
		}
	}

	private static void assertEvidence(ObservationDiagnostics<?> counts) {
		assertEquals(4, counts.reads());
		assertEquals(3, counts.validSamples());
		assertEquals(2, counts.newSamples());
		assertEquals(1, counts.duplicateSamples());
		assertEquals(1, counts.rejectedSamples());
		assertEquals(0, counts.lateSamples());
		assertEquals(1, counts.resetCount());
		assertEquals(1L, counts.rejectionCounts().values().iterator().next());
		assertThrows(UnsupportedOperationException.class, () -> counts.rejectionCounts().clear());
	}

	private static AbstractObservationOperation<?,?> operation(String kind, RobotTwinClient twin) {
		Duration timeout = Duration.ofSeconds(5), quiet = Duration.ofSeconds(10), poll = Duration.ofMillis(5);
		return switch (kind) {
			case "ee" -> new WaitUntilStill(twin, new WaitUntilStill.Options(timeout, 0.0002, quiet, poll));
			case "object" -> new WaitUntilObjectStill(twin, "peg",
					new WaitUntilObjectStill.Options(timeout, 0.0002, 0.01, quiet, poll));
			default -> new WaitForFreshVariable(twin, "scene_objects", new WaitForFreshVariable.Options(100, timeout, poll));
		};
	}

	private static Map<String,Object> sample(long second) {
		Map<String,Object> pose = Map.of("position", Map.of("x", 0, "y", 0, "z", 0),
				"orientation", Map.of("x", 0, "y", 0, "z", 0, "w", 1));
		return Map.of("quality", "OK", "received_at", Instant.ofEpochSecond(second).toString(),
				"value", Map.of("pose", pose, "objects", Map.of("peg", Map.of("pose", pose))));
	}

	private static void await(CountDownLatch latch) {
		try { if ( !latch.await(5, TimeUnit.SECONDS) ) { throw new IllegalStateException("test gate timeout"); } }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
}
