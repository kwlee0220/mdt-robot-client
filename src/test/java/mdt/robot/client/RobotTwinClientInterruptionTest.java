package mdt.robot.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import utils.RuntimeInterruptedException;
import utils.http.RESTfulIOException;

@Timeout(10)
class RobotTwinClientInterruptionTest {
	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void getPreservesInterruptFlagAndTransportCause(boolean catalog) throws Exception {
		try ( FakeTwinServer server = new FakeTwinServer() ) {
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			server.on("GET", catalog ? "/operations" : "/variables/ee_pose", req -> {
				entered.countDown();
				try {
					if ( !release.await(3, TimeUnit.SECONDS) ) { throw new IllegalStateException("response not released"); }
				}
				catch ( InterruptedException e ) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
				// Ensure a subsequent socket read after the interrupted blocking read returns.
				return FakeTwinServer.json(200, Map.of("padding", "x".repeat(65536)));
			});
			AtomicReference<Throwable> thrown = new AtomicReference<>();
			AtomicBoolean interrupted = new AtomicBoolean();
			Thread worker = new Thread(() -> {
				try { read(server.client(), catalog); }
				catch ( Throwable e ) { thrown.set(e); }
				finally { interrupted.set(Thread.currentThread().isInterrupted()); }
			}, "client-get-interruption");
			worker.start();
			try {
				assertTrue(entered.await(3, TimeUnit.SECONDS));
				worker.interrupt();
				release.countDown();
				worker.join(3000);
				assertFalse(worker.isAlive());
				RuntimeInterruptedException error = assertInstanceOf(RuntimeInterruptedException.class, thrown.get());
				RESTfulIOException transport = assertInstanceOf(RESTfulIOException.class, error.getCause().getCause());
				assertInstanceOf(InterruptedIOException.class, transport.getCause());
				assertTrue(interrupted.get(), "runtime interruption must leave the flag set for direct callers");
			}
			finally { release.countDown(); worker.interrupt(); worker.join(3000); }
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void getTimeoutWithoutInterruptRemainsTransportFailure(boolean catalog) throws Exception {
		try ( FakeTwinServer server = new FakeTwinServer() ) {
			server.on("GET", catalog ? "/operations" : "/variables/ee_pose", req ->
					FakeTwinServer.json(200, Map.of()).setHeadersDelay(400, TimeUnit.MILLISECONDS));
			RobotTwinClient twin = RobotTwinClient.builder().baseUrl(server.client().getBaseUrl())
					.httpTimeout(Duration.ofMillis(100)).build();
			RESTfulIOException error = assertThrows(RESTfulIOException.class, () -> read(twin, catalog));
			assertInstanceOf(SocketTimeoutException.class, error.getCause());
			assertFalse(Thread.currentThread().isInterrupted());
		}
	}

	private static void read(RobotTwinClient twin, boolean catalog) {
		if ( catalog ) { twin.operations(); }
		else { twin.readRaw("ee_pose"); }
	}
}
