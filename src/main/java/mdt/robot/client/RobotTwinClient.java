package mdt.robot.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.annotation.concurrent.GuardedBy;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import okhttp3.OkHttpClient;

import utils.Preconditions;
import utils.RuntimeInterruptedException;
import utils.http.HttpRESTfulClient;
import utils.http.JacksonErrorEntityDeserializer;
import utils.http.RESTfulErrorEntity;
import utils.http.RESTfulException;
import utils.http.RESTfulIOException;
import utils.http.RESTfulRemoteException;
import utils.rpc.restful.RESTfulAsyncRpcClient;
import utils.rpc.restful.RESTfulRpcClient;
import utils.rpc.restful.UnexpectedRpcStateException;
import utils.rpc.restful.RpcRequestMessage;

/**
 * 원격 로봇 트윈(rdfp_ws 의 {@code robot_twin} REST 게이트웨이)에 명령을 전달하는 <b>대리자</b>.
 * <p>
 * <b>트윈이 아니다.</b> 상태·자원 락·세션은 모두 원격 쪽이 갖는다. 이 객체는 접속 주소와 시간 제한만
 * 보관하며 생성할 때 연결하지 않는다 — 트윈이 꺼져 있어도 생성은 성공하고 첫 호출에서 드러난다.
 * <p>
 * 연산 호출은 {@code utils.rpc.restful} 위에 얹는다. 트윈의 연산 와이어 형식(요청 {@code inputs}, 응답
 * {@code status} · {@code session_endpoint} · {@code outputs} · {@code error})이 그 패키지의 것과 같다.
 * <ul>
 *   <li>동기 연산({@code kind: sync}) — {@link RESTfulRpcClient}. POST 응답(200)이 곧 종료 상태다.</li>
 *   <li>비동기 연산({@code kind: async}) — {@link RESTfulAsyncRpcClient}. POST(202)로 세션을 얻고
 *       종료될 때까지 세션을 폴링한다.</li>
 * </ul>
 * 연산 종류는 트윈의 연산 카탈로그({@code GET /operations})에서 읽어 캐시한다.
 * <p>
 * 제공 트윈의 포트(8801)는 환경과 무관하게 같지만, <b>트윈 id는 환경마다 다르다</b>: Isaac은
 * {@code panda01}, 펑션베이는 {@code rdfp01}이다(rdfp_ws {@code robot_twin/config/twins/}). 기본 id
 * ({@link #DEFAULT_TWIN_ID})는 Isaac 트윈이므로 펑션베이에서는 {@link Builder#twinId}로 지정한다. id가 틀리면
 * 모든 호출이 오류 코드 없는 404가 되고, {@link TwinException} 메시지에 id 확인 안내가 붙는다.
 * 트윈 id만으로 환경을 단정하지 말고 {@link #meta()} 의 {@code backend} 나 {@link #requireBackend} 로 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RobotTwinClient {
	private static final Logger s_logger = LoggerFactory.getLogger(RobotTwinClient.class);

	public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8801";
	/** 기본 트윈 id. Isaac 트윈이다. 펑션베이 트윈은 {@code rdfp01}이므로 {@link Builder#twinId}로 지정한다. */
	public static final String DEFAULT_TWIN_ID = "panda01";

	private static final String KIND_ASYNC = "async";

	private final String m_baseUrl;		// 서버 루트: scheme://host[:port][/접두어] (끝에 '/' 없음)
	private final String m_twinId;
	private final String m_basePath;	// /api/v1/robot_twins/{id}
	private final OkHttpClient m_httpClient;
	private final HttpRESTfulClient m_restClient;
	private final JsonMapper m_mapper;
	private final Duration m_pollInterval;
	private final Duration m_operationTimeout;

	@GuardedBy("this") private Map<String,String> m_operationKinds = null;

	private RobotTwinClient(Builder builder) {
		m_baseUrl = builder.m_baseUrl;
		m_twinId = builder.m_twinId;
		m_basePath = "/api/v1/robot_twins/" + builder.m_twinId;
		m_mapper = (builder.m_mapper != null) ? builder.m_mapper : JsonMapper.builder().build();
		m_pollInterval = builder.m_pollInterval;
		m_operationTimeout = builder.m_operationTimeout;

		// 동기 연산의 POST 응답은 트윈의 `sync_timeout_sec` 뒤에 올 수 있으므로 읽기 제한이 그보다 길어야
		// 한다 (펑션베이 그리퍼 12초, reset_scene 10초). 짧으면 동작은 서버에서 계속되는데 클라이언트만
		// 소켓 시간 초과로 먼저 끊긴다.
		// 주입한 클라이언트가 있으면 그 연결 풀·디스패처를 함께 쓰고 제한 시간만 이 클라이언트 값으로 바꾼다.
		OkHttpClient.Builder http = (builder.m_httpClient != null) ? builder.m_httpClient.newBuilder()
																	: new OkHttpClient.Builder();
		m_httpClient = http
											.connectTimeout(builder.m_httpTimeout)
											.readTimeout(builder.m_httpTimeout)
											.writeTimeout(builder.m_httpTimeout)
											.build();
		m_restClient = HttpRESTfulClient.builder()
										.httpClient(m_httpClient)
										.errorEntityDeserializer(new JacksonErrorEntityDeserializer(m_mapper))
										.jsonMapper(m_mapper)
										.build();
	}

	/**
	 * 기본 접속값({@value #DEFAULT_BASE_URL} / {@value #DEFAULT_TWIN_ID})으로 만든다.
	 *
	 * @return	클라이언트.
	 */
	public static RobotTwinClient create() {
		return builder().build();
	}

	/**
	 * 트윈 서버의 루트 URL(빌더의 {@link Builder#baseUrl(String)})을 반환한다.
	 *
	 * @return	루트 URL. (예: {@code http://127.0.0.1:8801})
	 */
	public String getBaseUrl() {
		return m_baseUrl;
	}

	/**
	 * 접속할 트윈의 id를 반환한다. (예: Isaac {@code panda01}, 펑션베이 {@code rdfp01})
	 *
	 * @return	트윈 id.
	 */
	public String getTwinId() {
		return m_twinId;
	}

	/**
	 * 이 트윈의 API URL({@code {baseUrl}/api/v1/robot_twins/{twin_id}})을 반환한다.
	 *
	 * @return	트윈 API URL.
	 */
	public String getTwinUrl() {
		return m_baseUrl + m_basePath;
	}

	// ------------------------------------------------------------------
	// 상태
	// ------------------------------------------------------------------

	/**
	 * 트윈 메타 — id · 설명 · {@code backend} · 실행 모드 · 변수/연산 이름을 반환한다.
	 * <p>
	 * <b>id 로는 환경을 모른다.</b> 어느 환경에 붙었는지는 여기의 {@code backend} 로 본다.
	 *
	 * @return	메타 JSON.
	 */
	public JsonNode meta() {
		return getJson("");
	}

	/**
	 * 서버가 보고하는 준비 상태({@code move_group} · {@code estop} · 변수 품질 등)를 반환한다.
	 * <p>
	 * 변수의 {@code quality} 와는 별개다 — 관측값이 {@code OK} 여도 제어기가 준비됐는지는 따로 본다.
	 *
	 * @return	health JSON.
	 */
	public JsonNode health() {
		return getJson("/health");
	}

	/**
	 * 변수 값을 반환한다. <b>품질이 {@code OK} 가 아니면 예외다</b> — 낡은 값을 확인 없이 쓰지 않게 한다.
	 * <p>
	 * {@code ee_pose} 처럼 {@code PoseStamped} 인 변수는 값 안의 {@code pose} 가 실제 pose 다
	 * ({@link Pose#fromJson(JsonNode)} 가 둘 다 받는다).
	 *
	 * @param name	변수 이름.
	 * @return	변수 값({@code value}).
	 * @throws VariableNotOkException	품질이 {@code OK} 가 아닌 경우.
	 */
	public JsonNode read(String name) {
		return read(name, true);
	}

	/**
	 * 변수 값을 반환한다.
	 *
	 * @param name		변수 이름.
	 * @param requireOk	{@code true} 면 품질이 {@code OK} 가 아닐 때 예외를 던진다.
	 * @return	변수 값({@code value}). 값이 없으면 JSON {@code null}.
	 * @throws VariableNotOkException	{@code requireOk} 이고 품질이 {@code OK} 가 아닌 경우.
	 */
	public JsonNode read(String name, boolean requireOk) {
		JsonNode body = readRaw(name);
		String quality = body.path("quality").asText();
		if ( requireOk && !"OK".equals(quality) ) {
			throw new VariableNotOkException(name, quality,
											body.hasNonNull("reason") ? body.get("reason").asText() : null);
		}
		return body.path("value");
	}

	/**
	 * 변수 응답을 그대로 반환한다 — {@code quality} · {@code reason} · {@code received_at} ·
	 * {@code stamp} · {@code value} 를 포함한다. 낮은 품질을 예외로 바꾸지 않는다.
	 *
	 * @param name	변수 이름.
	 * @return	변수 응답 JSON.
	 * @throws RuntimeInterruptedException HTTP에서 작업 스레드 인터럽트를 인식한 경우. 원인과 인터럽트 플래그를 보존한다.
	 */
	public JsonNode readRaw(String name) {
		return getJson("/variables/" + name);
	}

	/**
	 * 여러 변수를 한 요청으로 읽는다. 각 값이 같은 시각에 생성되었다는 뜻은 아니다.
	 * 정의되지 않은 이름이 섞이면 트윈이 404 로 거부한다.
	 *
	 * @param names	변수 이름들.
	 * @return	{@code 변수 이름 → 변수 응답}. 각 응답의 품질은 호출자가 확인한다.
	 */
	public Map<String,JsonNode> readMany(String... names) {
		JsonNode values = getJson("/variables?names=" + String.join(",", names)).path("values");
		Map<String,JsonNode> result = new LinkedHashMap<>();
		values.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
		return result;
	}

	/**
	 * 상태 변수 카탈로그(이름 · 설명 · 단위 …)를 반환한다.
	 *
	 * @return	변수 카탈로그.
	 */
	public List<JsonNode> variables() {
		return toList(getJson("/variables").path("variables"));
	}

	/**
	 * 연산 카탈로그(이름 · 설명 · {@code kind} · {@code inputs_schema} · 점유 자원 …)를 반환한다.
	 *
	 * @return	연산 카탈로그.
	 */
	public List<JsonNode> operations() {
		return toList(getJson("/operations").path("operations"));
	}

	/**
	 * 자원의 점유 상태를 반환한다.
	 *
	 * @return	자원 JSON.
	 */
	public JsonNode resources() {
		return getJson("/resources");
	}

	/**
	 * 연결된 트윈이 {@code expected} 중 하나의 환경인지 확인하고 그 이름을 반환한다.
	 * <p>
	 * 환경은 트윈 메타의 {@code backend} 로 안다 — id 는 환경을 담지 않는다. 환경 이름이 아니라 능력으로
	 * 가를 수 있으면 그쪽이 낫다(예: peg 작업은 {@code fixtures} 변수 유무).
	 *
	 * @param purpose	작업 설명(오류 문장에 쓴다).
	 * @param expected	허용하는 백엔드 이름들.
	 * @return	확인된 백엔드 이름.
	 * @throws WrongBackendException	다른 환경이거나 트윈이 백엔드를 알려 주지 않는 경우.
	 */
	public String requireBackend(String purpose, String... expected) {
		Preconditions.checkArgument(expected.length > 0, "no expected backend");
		JsonNode backend = meta().get("backend");
		String actual = (backend != null && backend.isTextual()) ? backend.asText() : null;
		if ( actual == null || !Arrays.asList(expected).contains(actual) ) {
			throw new WrongBackendException(purpose, Arrays.asList(expected), actual);
		}
		return actual;
	}

	// ------------------------------------------------------------------
	// 연산
	// ------------------------------------------------------------------

	/**
	 * 연산을 실행하고 종료까지 기다린다. 비동기 연산은 기본 제한 시간까지 기다린다.
	 *
	 * @param operation	연산 이름.
	 * @param inputs	입력. 값은 JSON 으로 바뀐다({@link Pose} · {@link JsonNode} · 기본형 · {@code Map}).
	 * @return	종료 상태. <b>실패해도 예외가 아니라 결과로 돌려준다</b> — 결과를 쓸 것이면 {@link #runForOutputs}.
	 * @see #run(String, Map, Duration)
	 */
	public OperationResult run(String operation, Map<String,?> inputs) {
		return run(operation, inputs, m_operationTimeout);
	}

	/**
	 * 연산을 실행하고 종료까지 기다린다.
	 * <p>
	 * 비동기 연산에서 {@code timeout} 을 넘기면 <b>기다리기를 그만둘 뿐 원격 연산을 취소하지 않는다</b>
	 * ({@link OperationTimeoutException#cancel()} 로 취소할 수 있다). 동기 연산에는 HTTP 시간 제한만
	 * 적용된다.
	 *
	 * @param operation	연산 이름.
	 * @param inputs	입력. ({@code null} 이면 빈 입력)
	 * @param timeout	비동기 연산을 기다릴 상한.
	 * @return	종료 상태 — {@code COMPLETED} · {@code FAILED} · {@code CANCELLED}.
	 * @throws TwinException				트윈이 요청을 접수하지 않은 경우(4xx/5xx).
	 * @throws SessionLostException			비동기 연산이 접수된 뒤 세션 조회가 HTTP 오류를 받아 결과를 알 수 없는 경우.
	 * @throws OperationTimeoutException	비동기 연산이 제한 시간 안에 끝나지 않은 경우.
	 * @throws RESTfulException				통신 실패 등 HTTP 응답이 없는 오류.
	 */
	public OperationResult run(String operation, @Nullable Map<String,?> inputs, Duration timeout) {
		Map<String,JsonNode> args = toJsonInputs(inputs);
		return isAsyncOperation(operation) ? runAsync(operation, args, timeout)
											: runSync(operation, args, timeout, ignored -> { });
	}

	/**
	 * 연산을 실행하고 <b>출력을 반환한다.</b> 실패면 {@link OperationFailedException} 이다.
	 * <p>
	 * {@link #run} 은 실패해도 상태를 그대로 돌려주므로, 결과를 쓸 것이라면 이쪽을 써서 실패를 놓치지
	 * 않는다. 정상 종료했으나 출력이 없으면 빈 맵이다.
	 *
	 * @param operation	연산 이름.
	 * @param inputs	입력. ({@code null} 이면 빈 입력)
	 * @return	연산 출력.
	 * @throws OperationFailedException	연산이 {@code COMPLETED} 가 아닌 상태로 끝난 경우.
	 */
	public Map<String,JsonNode> runForOutputs(String operation, @Nullable Map<String,?> inputs) {
		OperationResult result = run(operation, inputs);
		if ( !result.isCompleted() ) {
			throw new OperationFailedException(result);
		}
		return result.outputs();
	}

	/**
	 * 연산 세션에 취소를 요청한다(DELETE). 접수가 정지 완료를 뜻하지는 않는다.
	 *
	 * @param sessionEndpoint	세션 경로({@code /api/v1/robot_twins/{id}/operations/sessions/{sid}}).
	 *							전체 URL 이 아니라 트윈이 준 경로다.
	 * @return	취소 응답.
	 */
	public JsonNode cancel(String sessionEndpoint) {
		try {
			return m_restClient.delete(m_baseUrl + sessionEndpoint, m_restClient.jsonDeserializer());
		}
		catch ( RESTfulException e ) {
			throw translate(e);
		}
	}

	/**
	 * 비상정지를 건다. 세션 id 를 몰라도 부를 수 있다.
	 *
	 * @return	응답.
	 */
	public JsonNode estop() {
		try {
			return m_restClient.post(getTwinUrl() + "/estop", HttpRESTfulClient.newJsonRequestBody("{}"),
									m_restClient.jsonDeserializer());
		}
		catch ( RESTfulException e ) {
			throw translate(e);
		}
	}

	/**
	 * 비상정지를 해제한다. <b>현장이 안전한지 사람이 확인했을 때만</b> 부른다.
	 *
	 * @return	응답.
	 */
	public JsonNode releaseEstop() {
		try {
			return m_restClient.delete(getTwinUrl() + "/estop", m_restClient.jsonDeserializer());
		}
		catch ( RESTfulException e ) {
			throw translate(e);
		}
	}

	// ------------------------------------------------------------------
	// 내부
	// ------------------------------------------------------------------

	/**
	 * @param timeout	트윈이 이 연산을 비동기로 바꿔 세션을 돌려준 경우(D10)에만 쓰는 결과 대기 상한.
	 * @param onStarted	같은 경우에 세션에 붙은 핸들을 받는다.
	 */
	private OperationResult runSync(String operation, Map<String,JsonNode> inputs, Duration timeout,
									Consumer<AsyncOperation> onStarted) {
		RESTfulRpcClient rpc = RESTfulRpcClient.builder()
												.setHttpClient(m_httpClient)
												.setJsonMapper(m_mapper)
												.setOperationUrl(getTwinUrl() + "/operations/" + operation)
												.setInputs(inputs)
												.build();
		try {
			return new OperationResult(operation, OperationResult.Status.COMPLETED, rpc.call(), null, null);
		}
		catch ( ExecutionException e ) {
			return terminalFailure(operation, e, null);
		}
		catch ( CancellationException e ) {
			return new OperationResult(operation, OperationResult.Status.CANCELLED, Map.of(), null, null);
		}
		catch ( UnexpectedRpcStateException e ) {
			// 카탈로그에는 sync인데 트윈이 202 RUNNING으로 세션을 돌려줬다(같은 id의 트윈이 재기동되며 종류가 바뀜).
			// 원격 연산은 이미 시작됐으므로 세션에 붙어 결과를 기다린다.
			invalidateOperationKinds();
			String session = e.getResponse().getSessionEndpoint();
			if ( session == null ) {
				throw e;
			}
			s_logger.warn("operation '{}' is now asynchronous on the twin; attaching to its session {}", operation, session);
			checkOperationTimeout(timeout);
			AsyncOperation execution = attachAsyncOperation(operation, session);
			onStarted.accept(execution);
			return execution.await(timeout);
		}
		catch ( RESTfulIOException e ) {
			throwIfHttpInterrupted(operation, e);
			// 형식을 알 수 없는 HTTP 오류 본문(예: 경로가 없을 때 서버 프레임워크의 {"detail": ...})도 상태 코드가
			// 있으면 TwinException으로 바꾼다. 상태 코드가 없으면(연결 실패 등) translate가 그대로 돌려준다.
			throw translate(e);
		}
		catch ( RESTfulException e ) {
			throw translate(e);
		}
		catch ( IOException e ) {
			throwIfHttpInterrupted(operation, e);
			throw new UncheckedIOException(e);
		}
	}

	private static void throwIfHttpInterrupted(String operation, Throwable error) {
		// 시간 초과도 InterruptedIOException 계열이다. 실제 인터럽트 신호가 있어야 취소로 분류한다.
		if ( !Thread.currentThread().isInterrupted() ) { return; }
		for ( Throwable cause = error; cause != null; cause = cause.getCause() ) {
			if ( cause instanceof InterruptedIOException ) {
				InterruptedException interrupted = new InterruptedException("interrupted during " + operation);
				interrupted.initCause(error); // HTTP 래퍼와 원래 I/O 원인을 모두 보존한다.
				Thread.currentThread().interrupt(); // 런타임 래퍼를 받는 직접 호출자에게 신호를 유지한다.
				throw new RuntimeInterruptedException(interrupted);
			}
		}
	}

	/** 클라이언트의 기본 원격 연산 대기 상한. 정착 대기 시간과는 별개다. */
	public Duration getOperationTimeout() {
		return m_operationTimeout;
	}

	/**
	 * 비동기 카탈로그 연산을 시작하고 결과 대기·취소용 핸들을 반환한다.
	 * 카탈로그 조회는 호출 스레드에서 수행하며, POST와 폴링은 별도 스레드에서 수행한다.
	 * 세션 확보 전의 cancel()은 접수되어, 세션을 받는 즉시 DELETE로 전달된다.
	 */
	public AsyncOperation startAsync(String operation, Map<String,?> inputs) {
		Preconditions.checkArgument(isAsyncOperation(operation), "not an async operation: " + operation);
		return startAsyncOperation(operation, toJsonInputs(inputs));
	}

	/**
	 * 카탈로그에 따라 동기/비동기로 실행하고, 비동기 실행의 취소 핸들을 호출자에게 전달한다.
	 * {@link #run(String, Map, Duration)}과 같은 종료 결과·예외·대기 상한 계약을 따른다.
	 * <p>
	 * 비동기 POST/폴링을 시작한 뒤 결과 대기 전에 호출 스레드에서 onStarted를 한 번 호출한다.
	 * 이 시점은 서버 접수나 세션 확보를 보장하지 않는다. 핸들의 cancel()은 세션 확보 전에도 접수되며,
	 * 세션을 받는 즉시 DELETE로 전달된다.
	 * 콜백은 핸들을 보관하는 짧은 작업에 사용한다. 콜백이 지연되면 await 시작도 지연되며,
	 * 콜백이 예외를 던져도 이미 시작한 원격 실행은 자동 취소하지 않는다.
	 * 동기 연산에서는 콜백을 호출하지 않고 HTTP 결과를 반환하며, timeout 대신 httpTimeout을 사용한다.
	 * 다만 동기로 알던 연산에 트윈이 세션을 돌려주면 그 세션에 붙은 핸들로 콜백을 부르고 timeout을 쓴다.
	 *
	 * @param operation 서버 연산 이름.
	 * @param inputs 연산 입력. null은 빈 입력으로 처리한다.
	 * @param timeout 비동기 결과 대기 상한. 전체 실행 상한이나 원격 동작 상한이 아니다.
	 * @param onStarted 비동기 실행 핸들을 받는 null이 아닌 콜백.
	 * @return 원격 종료 결과. 서버 FAILED/CANCELLED도 OperationResult로 반환한다.
	 */
	public OperationResult run(String operation, Map<String,?> inputs, Duration timeout,
			Consumer<AsyncOperation> onStarted) {
		Preconditions.checkNotNullArgument(onStarted, "onStarted is null");
		Map<String,JsonNode> args = toJsonInputs(inputs);
		return isAsyncOperation(operation) ? runAsync(operation, args, timeout, onStarted)
											: runSync(operation, args, timeout, onStarted);
	}

	private OperationResult runAsync(String operation, Map<String,JsonNode> inputs, Duration timeout) {
		return runAsync(operation, inputs, timeout, ignored -> { });
	}

	private OperationResult runAsync(String operation, Map<String,JsonNode> inputs, Duration timeout,
			Consumer<AsyncOperation> onStarted) {
		checkOperationTimeout(timeout);
		AsyncOperation execution = startAsyncOperation(operation, inputs);
		onStarted.accept(execution);
		return execution.await(timeout);
	}

	private AsyncOperation startAsyncOperation(String operation, Map<String,JsonNode> inputs) {
		RESTfulAsyncRpcClient poller = RESTfulAsyncRpcClient.builder()
											.setHttpClient(m_httpClient)
											.setJsonMapper(m_mapper)
											// 트윈의 session_endpoint 는 절대 경로라 기준 URL 은 서버 루트다.
											.setBaseUrl(m_baseUrl)
											.setOperationEndpoint(m_basePath + "/operations/" + operation)
											.setRequestMessage(new RpcRequestMessage(inputs))
											.setPollInterval(m_pollInterval)
											.build();
		AsyncOperation execution = new AsyncOperation(operation, poller);
		poller.start();
		return execution;
	}

	private AsyncOperation attachAsyncOperation(String operation, String sessionEndpoint) {
		RESTfulAsyncRpcClient poller = RESTfulAsyncRpcClient.builder()
											.setHttpClient(m_httpClient)
											.setJsonMapper(m_mapper)
											.setBaseUrl(m_baseUrl)
											.setSessionEndpoint(sessionEndpoint)
											.setPollInterval(m_pollInterval)
											.build();
		AsyncOperation execution = new AsyncOperation(operation, poller);
		poller.start();
		return execution;
	}

	private synchronized void invalidateOperationKinds() {
		m_operationKinds = null;
	}

	private static void checkOperationTimeout(Duration timeout) {
		Preconditions.checkNotNullArgument(timeout, "timeout is null");
		Preconditions.checkArgument(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");
	}

	/** 원격 연산의 결과 대기와 취소 요청을 위한 핸들. 취소 접수와 정지 완료는 다르다. */
	public final class AsyncOperation {
		private final String m_operation;
		private final RESTfulAsyncRpcClient m_poller;
		private boolean m_cancelAccepted;

		private AsyncOperation(String operation, RESTfulAsyncRpcClient poller) {
			m_operation = operation;
			m_poller = poller;
		}

		public OperationResult await() {
			return await(m_operationTimeout);
		}

		/** 대기 시간 초과와 호출 스레드 인터럽트는 원격 연산을 취소하지 않는다. */
		public OperationResult await(Duration timeout) {
			checkOperationTimeout(timeout);
			try {
				Map<String,JsonNode> outputs = m_poller.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				invalidateIfFinishedAtStart();
				return new OperationResult(m_operation, OperationResult.Status.COMPLETED, outputs, null, null);
			}
			catch ( TimeoutException e ) {
				// **취소하지 않는다** — 기다리기를 그만둘 뿐 로봇은 계속 움직인다. 폴러는 데몬 스레드에서 연산이
				// 끝날 때까지 돈다. 멈추려면 예외의 cancel() 이 세션에 DELETE 를 보낸다.
				s_logger.warn("operation '{}' did not finish in {}; it keeps running remotely", m_operation, timeout);
				throw new OperationTimeoutException(m_operation, timeout, this::cancel, getSessionUrl());
			}
			catch ( CancellationException e ) {
				invalidateIfFinishedAtStart();
				return new OperationResult(m_operation, OperationResult.Status.CANCELLED, Map.of(), null, null);
			}
			catch ( ExecutionException e ) {
				return terminalFailure(m_operation, e, m_poller.getSessionUrl());
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				throw new RuntimeInterruptedException(e);
			}
		}

		/**
		 * 카탈로그에는 async인데 트윈이 200으로 곧바로 끝냈으면(세션 없음) 종류가 바뀐 것이므로 카탈로그를 다시 읽게
		 * 한다. 결과는 그 응답 그대로 쓴다.
		 */
		private void invalidateIfFinishedAtStart() {
			if ( m_poller.getSessionUrl() == null ) {
				s_logger.warn("operation '{}' finished at start; it is now synchronous on the twin", m_operation);
				invalidateOperationKinds();
			}
		}

		/**
		 * 원격 연산 세션의 URL을 반환한다. 연산 시작 응답(세션)을 아직 받지 못했거나 시작이 실패했으면
		 * {@code null}이다. 다른 프로세스에서 취소하거나 상태를 확인할 때 쓴다.
		 *
		 * @return	세션 URL.
		 */
		public @Nullable String getSessionUrl() {
			return m_poller.getSessionUrl();
		}

		/**
		 * 세션에 DELETE를 요청한다. 202 접수 후에도 최종 상태의 폴링은 계속한다.
		 * <b>세션 확보 전</b>(시작 POST의 응답 전)에 부르면 취소를 기록하고 {@code true}를 반환하며, 세션을
		 * 받는 즉시 DELETE가 나간다.
		 * 종료 후·409 거부는 false다. 그 밖의 HTTP 오류는 {@link TwinException}으로 던진다:
		 * 501 {@code CANCEL_UNSUPPORTED}(정지를 전달할 수 없음 — 팔이 계속 움직일 수 있다), 404(세션 없음) 등.
		 * 응답이 없는 통신 오류는 {@code RESTfulIOException}이다.
		 * true는 요청 접수이며 원격 정지 완료를 뜻하지 않는다.
		 */
		public synchronized boolean cancel() {
			if ( m_poller.isDone() ) {
				return false;
			}
			if ( m_cancelAccepted ) {
				return true;
			}
			try {
				m_cancelAccepted = m_poller.cancelWork();
				return m_cancelAccepted;
			}
			catch ( RESTfulException e ) {
				throw translate(e);
			}
		}
	}

	/**
	 * {@code utils.rpc.restful} 이 던진 실패를 가른다.
	 * <ul>
	 *   <li>HTTP 상태가 실린 {@link RESTfulException} — 세션이 없으면 접수 거부(4xx/5xx) → {@link TwinException}.
	 *       세션을 받은 뒤(폴링 중)면 결과를 알 수 없음 → {@link SessionLostException}.</li>
	 *   <li>상태가 없는 {@link RESTfulRemoteException} — 세션의 {@code FAILED} 응답에서 복원된 것 →
	 *       {@code FAILED} 결과.</li>
	 * </ul>
	 */
	private OperationResult terminalFailure(String operation, ExecutionException e, @Nullable String sessionUrl) {
		for ( Throwable cause = e; cause != null; cause = cause.getCause() ) {
			if ( cause instanceof RESTfulException rex ) {
				if ( rex.getHttpStatusCode() != RESTfulException.UNKNOWN_STATUS_CODE ) {
					RuntimeException translated = translate(rex);
					if ( sessionUrl != null && translated instanceof TwinException twinError ) {
						// 접수된 뒤(세션 확보 후)의 HTTP 오류: 로봇이 움직였을 수 있다.
						throw new SessionLostException(operation, sessionUrl, twinError);
					}
					throw translated;
				}
				if ( rex instanceof RESTfulRemoteException rre && rre.getRemoteErrorEntity() != null ) {
					RESTfulErrorEntity error = rre.getRemoteErrorEntity();
					return new OperationResult(operation, OperationResult.Status.FAILED, Map.of(),
												error.getCode(), error.getMessage());
				}
				throw rex;	// 통신 실패
			}
			if ( cause instanceof CancellationException ) {
				return new OperationResult(operation, OperationResult.Status.CANCELLED, Map.of(), null, null);
			}
		}
		throw new IllegalStateException("operation '" + operation + "' failed unexpectedly", e);
	}

	/**
	 * 연산이 비동기({@code kind=async}) 연산인지 반환한다. 연산 카탈로그를 읽어 캐시하며, 처음 보는 이름이면
	 * 한 번 다시 읽는다. 호출 응답이 캐시한 종류와 다르면(동기로 보냈는데 202 세션, 비동기로 보냈는데 200 완료)
	 * 그 응답대로 결과를 받고 캐시를 비운다. 카탈로그에 없는 연산은 {@code false}다(동기 경로로 보내 트윈이 404로 거부한다).
	 *
	 * @param operation	연산 이름.
	 * @return	비동기 연산이면 {@code true}.
	 */
	public boolean isAsyncOperation(String operation) {
		String kind = kindOf(operation, false);
		if ( kind == null ) {
			// 처음 보는 이름이면 카탈로그를 한 번 다시 읽는다 — 같은 id의 트윈이 다른 구성으로 재기동됐을 수 있다.
			// 이미 아는 이름의 종류가 바뀐 경우는 호출 응답(200/202)으로 알아채 캐시를 비운다.
			kind = kindOf(operation, true);
		}
		// 카탈로그에 없는 연산은 동기 경로로 보낸다 — 트윈이 404 로 거부하고 TwinException 이 된다.
		return KIND_ASYNC.equals(kind);
	}

	private synchronized @Nullable String kindOf(String operation, boolean reload) {
		if ( m_operationKinds == null || reload ) {
			Map<String,String> kinds = new HashMap<>();
			for ( JsonNode op : operations() ) {
				kinds.put(op.path("name").asText(), op.path("kind").asText());
			}
			m_operationKinds = kinds;
		}
		return m_operationKinds.get(operation);
	}

	private JsonNode getJson(String path) {
		try {
			return m_restClient.get(getTwinUrl() + path, m_restClient.jsonDeserializer());
		}
		catch ( RESTfulIOException e ) {
			throwIfHttpInterrupted("GET " + path, e);
			// 형식을 알 수 없는 HTTP 오류 본문(예: 경로가 없을 때 서버 프레임워크의 {"detail": ...})도 상태 코드가
			// 있으면 TwinException으로 바꾼다. 상태 코드가 없으면(연결 실패 등) translate가 그대로 돌려준다.
			throw translate(e);
		}
		catch ( RESTfulException e ) {
			throw translate(e);
		}
	}

	private RuntimeException translate(RESTfulException e) {
		int status = e.getHttpStatusCode();
		if ( status == RESTfulException.UNKNOWN_STATUS_CODE ) {
			return e;	// HTTP 응답이 없는 실패(연결 거부 등)
		}
		String code = null;
		String message = e.getMessage();
		if ( e instanceof RESTfulRemoteException rre && rre.getRemoteErrorEntity() != null ) {
			code = rre.getRemoteErrorEntity().getCode();
			message = rre.getRemoteErrorEntity().getMessage();
		}
		JsonNode details = null;
		String body = e.getResponseBody();
		if ( body != null ) {
			try {
				JsonNode d = m_mapper.readTree(body).path("error").path("details");
				details = d.isMissingNode() || d.isNull() ? null : d;
			}
			catch ( IOException ignored ) {
				// 형식을 알 수 없는 본문이다. 상세 정보 없이 진행한다.
			}
		}
		Duration retryAfter = parseRetryAfter(e.getResponseHeader("Retry-After"));
		if ( status == 404 && code == null ) {
			// 오류 코드가 없는 404는 서버 프레임워크의 경로 없음이다. 연산·변수·세션의 404는 코드가 있다.
			// 트윈 id가 틀린 경우(환경마다 id가 다르다)가 대부분이므로 확인 방법을 안내한다.
			message = message + " — the twin path does not exist; check the twin id '" + getTwinId()
					+ "' (twins on this server: GET " + m_baseUrl + "/api/v1/robot_twins)";
		}
		return new TwinException(status, code, message, details, retryAfter, e);
	}

	// Retry-After는 초(정수·소수) 값만 다룬다. HTTP-date 형식은 트윈이 쓰지 않으므로 무시한다.
	private static @Nullable Duration parseRetryAfter(@Nullable String value) {
		if ( value == null ) {
			return null;
		}
		try {
			double sec = Double.parseDouble(value.trim());
			return (Double.isFinite(sec) && sec >= 0) ? Duration.ofMillis(Math.round(sec * 1000)) : null;
		}
		catch ( NumberFormatException ignored ) {
			return null;
		}
	}

	private Map<String,JsonNode> toJsonInputs(@Nullable Map<String,?> inputs) {
		Map<String,JsonNode> args = new LinkedHashMap<>();
		if ( inputs != null ) {
			inputs.forEach((k, v) -> args.put(k, (v instanceof JsonNode n) ? n : m_mapper.valueToTree(v)));
		}
		return args;
	}

	private static List<JsonNode> toList(JsonNode array) {
		List<JsonNode> list = new ArrayList<>();
		array.forEach(list::add);
		return list;
	}

	// ------------------------------------------------------------------
	// 빌더
	// ------------------------------------------------------------------

	/**
	 * {@link RobotTwinClient} 빌더를 반환한다.
	 *
	 * @return	빌더.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@link RobotTwinClient} 빌더. 모든 항목에 기본값이 있다.
	 */
	public static final class Builder {
		private String m_baseUrl = DEFAULT_BASE_URL;
		private String m_twinId = DEFAULT_TWIN_ID;
		private Duration m_httpTimeout = Duration.ofSeconds(30);
		private Duration m_pollInterval = Duration.ofMillis(500);
		private Duration m_operationTimeout = Duration.ofSeconds(120);
		private JsonMapper m_mapper;
		private OkHttpClient m_httpClient;

		public RobotTwinClient build() {
			return new RobotTwinClient(this);
		}

		/**
		 * 트윈 서버의 루트 URL. (기본 {@value RobotTwinClient#DEFAULT_BASE_URL})
		 * <p>
		 * 트윈 API 경로({@code /api/v1/robot_twins/{twin_id}})는 이 URL 뒤에 붙인다. 역프록시의 경로
		 * 접두어(예: {@code http://gw/robot-twin})를 포함할 수 있다. 트윈이 돌려주는 세션 경로
		 * ({@code /api/v1/...})도 이 URL 뒤에 붙이므로, 프록시는 접두어를 떼고 트윈에 전달해야 한다.
		 *
		 * @param baseUrl	{@code http} 또는 {@code https} URL. 끝의 {@code '/'} 는 제거한다.
		 * @return	본 빌더.
		 * @throws IllegalArgumentException	scheme 이 {@code http}/{@code https} 가 아니거나, 호스트가 없거나,
		 * 									쿼리·fragment 가 붙은 경우.
		 */
		public Builder baseUrl(String baseUrl) {
			Preconditions.checkNotNullArgument(baseUrl, "baseUrl is null");
			URI uri;
			try {
				uri = new URI(baseUrl.trim());
			}
			catch ( URISyntaxException e ) {
				throw new IllegalArgumentException("invalid baseUrl: " + baseUrl, e);
			}
			String scheme = uri.getScheme();
			Preconditions.checkArgument("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme),
										"baseUrl must be an http(s) URL: %s", baseUrl);
			Preconditions.checkArgument(uri.getHost() != null, "baseUrl has no host: %s", baseUrl);
			Preconditions.checkArgument(uri.getRawQuery() == null && uri.getRawFragment() == null,
										"baseUrl must not have a query or fragment: %s", baseUrl);

			String url = uri.toString();
			while ( url.endsWith("/") ) {
				url = url.substring(0, url.length() - 1);
			}
			m_baseUrl = url;
			return this;
		}

		/** 트윈 id. (기본 {@value RobotTwinClient#DEFAULT_TWIN_ID}) */
		public Builder twinId(String twinId) {
			Preconditions.checkNotNullArgument(twinId, "twinId is null");
			// id는 URL 경로에 그대로 들어간다. 트윈 id 규칙과 같은 문자만 받는다.
			Preconditions.checkArgument(twinId.matches("[A-Za-z0-9_-]+"),
										"twinId must consist of letters, digits, '_' or '-': '%s'", twinId);
			m_twinId = twinId;
			return this;
		}

		/**
		 * 연결·읽기·쓰기 각각의 시간 제한. (기본 30초) 전체 호출 시간 상한(callTimeout)은 설정하지 않는다.
		 * <b>트윈 동기 연산의 {@code sync_timeout_sec} 보다 길어야
		 * 한다</b> — 동기 연산은 POST 응답이 그 뒤에 온다.
		 */
		public Builder httpTimeout(Duration timeout) {
			Preconditions.checkNotNullArgument(timeout, "httpTimeout is null");
			checkPositive(timeout, "httpTimeout");
			m_httpTimeout = timeout;
			return this;
		}

		/** 비동기 연산 세션의 폴링 주기. (기본 0.5초) */
		public Builder pollInterval(Duration interval) {
			Preconditions.checkNotNullArgument(interval, "pollInterval is null");
			checkPositive(interval, "pollInterval");
			m_pollInterval = interval;
			return this;
		}

		/** 비동기 연산을 기다리는 기본 상한. (기본 120초) 넘겨도 원격 연산은 취소하지 않는다. */
		public Builder operationTimeout(Duration timeout) {
			Preconditions.checkNotNullArgument(timeout, "operationTimeout is null");
			checkPositive(timeout, "operationTimeout");
			m_operationTimeout = timeout;
			return this;
		}

		/** JSON 매퍼. (기본 {@code JsonMapper.builder().build()}) */
		public Builder jsonMapper(JsonMapper mapper) {
			m_mapper = mapper;
			return this;
		}

		/**
		 * HTTP 클라이언트를 주입한다. 여러 트윈 클라이언트가 연결 풀·디스패처를 함께 쓰게 할 때 쓴다.
		 * 제한 시간은 이 빌더의 {@code httpTimeout}으로 덮어쓴다(주입한 객체 자체는 바꾸지 않는다).
		 * 주지 않으면 클라이언트마다 새로 만든다.
		 *
		 * @param client	HTTP 클라이언트.
		 * @return	이 빌더.
		 */
		public Builder httpClient(OkHttpClient client) {
			Preconditions.checkNotNullArgument(client, "httpClient is null");
			m_httpClient = client;
			return this;
		}

		private static void checkPositive(Duration value, String name) {
			Preconditions.checkArgument(!value.isNegative() && !value.isZero(), "%s must be positive: %s", name, value);
		}
	}
}
