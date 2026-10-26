package mdt.robot.client;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 트윈 REST API 를 흉내 내는 시험용 서버. 경로는 {@code /api/v1/robot_twins/panda01} 아래만 본다.
 * client와 operation 패키지의 테스트가 공유한다. 테스트 소스에만 있으며 라이브러리 jar에는 포함하지 않는다.
 */
public final class FakeTwinServer implements AutoCloseable {
	public static final String BASE = "/api/v1/robot_twins/panda01";
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final MockWebServer m_server = new MockWebServer();
	private final Map<String,String> m_kinds = new ConcurrentHashMap<>();
	private final Map<String,Function<RecordedRequest,MockResponse>> m_handlers = new ConcurrentHashMap<>();
	public final List<String> requests = new CopyOnWriteArrayList<>();

	public FakeTwinServer() throws IOException {
		m_server.setDispatcher(new Dispatcher() {
			@Override
			public @NotNull MockResponse dispatch(@NotNull RecordedRequest req) {
				String path = req.getPath().substring(BASE.length());
				requests.add(req.getMethod() + " " + path);
				Function<RecordedRequest,MockResponse> handler = m_handlers.get(req.getMethod() + " " + path);
				if ( handler != null ) {
					return handler.apply(req);
				}
				if ( req.getMethod().equals("GET") && path.equals("/operations") ) {
					List<Map<String,String>> ops = new ArrayList<>();
					m_kinds.forEach((name, kind) -> ops.add(Map.of("name", name, "kind", kind)));
					return json(200, Map.of("operations", ops));
				}
				return json(404, Map.of("error", Map.of("code", "NOT_FOUND", "message", "unknown: " + path)));
			}
		});
		m_server.start();
	}

	public FakeTwinServer operation(String name, String kind) {
		m_kinds.put(name, kind);
		return this;
	}

	public FakeTwinServer on(String method, String path, Function<RecordedRequest,MockResponse> handler) {
		m_handlers.put(method + " " + path, handler);
		return this;
	}

	public RobotTwinClient client() {
		return RobotTwinClient.builder()
								.baseUrl(m_server.url("/").toString())
								.pollInterval(Duration.ofMillis(20))
								.build();
	}

	public List<String> requests(String prefix) {
		return requests.stream().filter(r -> r.startsWith(prefix)).toList();
	}

	public static MockResponse json(int status, Object body) {
		try {
			return new MockResponse().setResponseCode(status)
										.setHeader("Content-Type", "application/json")
										.setBody(MAPPER.writeValueAsString(body));
		}
		catch ( IOException e ) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void close() throws IOException {
		m_server.shutdown();
	}
}
