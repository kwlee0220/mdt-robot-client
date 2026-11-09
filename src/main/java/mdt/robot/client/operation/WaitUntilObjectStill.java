package mdt.robot.client.operation;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import com.fasterxml.jackson.databind.JsonNode;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * scene_objects의 이름으로 선택한 물체의 위치·회전 정착을 관측한다. 생성자는 통신하지 않는다.
 * run() 또는 start()/get()으로 한 번 실행한다. POST·DELETE·로봇 명령은 보내지 않는다.
 * <p>
 * quality=OK, 시간대가 있는 received_at, 유효한 위치·단위 쿼터니언이 필요하다.
 * 새 표본의 위치와 회전이 구간 시작 자세의 허용 범위에 있고, 로컬 단조 시간과 received_at의
 * 경과가 모두 quiet 이상일 때만 정착을 확인한다. timestamp 중복은 근거에 추가하지 않는다.
 * 무효 표본, 시각 역행, stamp 유무·좌표 프레임 변경, 위치·회전 범위 초과는 구간을 재시작한다.
 * 서버의 quality 판정을 신뢰하며 클라이언트와 서버의 벽시계 동기화를 가정하지 않는다.
 * <p>
 * 정착 상한은 정상 결과(NO_VALID_SAMPLE/TIMEOUT)다. 시간 초과에도 마지막 유효 응답을 보존하지만
 * 그것을 정착의 증거로 사용하지 않는다. timeout=0이면 조회하지 않는다. 물체 정착은 관측 구간의
 * pose 변화에 대한 판정이며 물리적 정지, 파지·착석·쌓기 성공이나 물체 끝점의 위치 오차를 보장하지 않는다.
 * 중심~끝점 거리 r, 회전 허용값 theta(rad), 각 축 위치 허용값 t에 대해 관측된 끝점 변위는
 * 최대 sqrt(3)*t + 2*r*sin(theta/2)로 평가한다. 끝점 정밀도가 필요하면 두 허용값을 함께 조정한다.
 * STALE은 정숙 구간을 초기화한다. 발행 중단이 있는 배포에서는 중단 시간과 재개 후 quiet,
 * 폴링·통신 여유를 포함해 timeout을 정한다. 백엔드 이름만으로 중단 주기를 가정하지 않는다.
 * <p>
 * <b>Isaac의 한계:</b> Isaac scene 노드는 주기마다 TF 버퍼의 최신 변환을 읽어 현재 시각으로 다시 발행하며
 * 변환 자체의 시각은 보지 않는다. tf2는 새 변환이 들어올 때만 오래된 값을 지우므로, Isaac 쪽 TF가 멈추면
 * 마지막 값이 새 received_at과 quality=OK로 계속 온다.
 * 그러면 멈춘 값만으로 SETTLED가 된다. 클라이언트는 이를 실제로 멈춘 물체와 구분할 수 없다.
 * <p>
 * 실행 중 cancel(true)는 관측 스레드를 인터럽트한다. STARTING 중 cancel(true)는 기록되어 관측 전에 끝난다.
 * 실행 후 cancel(false)는 거부한다. 외부 인터럽트도 CANCELLED이며 run()은 플래그를 해제한 InterruptedException을 전달한다.
 * 통신 오류는 FAILED/ExecutionException이다. HTTP 시간 초과는 관측 시간 초과와 구분한다.
 * 관측 상한·취소는 블로킹 HTTP의 즉시 종료를 보장하지 않는다. 종료 후에도 getProgress()로 기록을 읽는다.
 */
public final class WaitUntilObjectStill extends AbstractObservationOperation<WaitUntilObjectStill.Result, WaitUntilObjectStill.Result> {
	public record Options(Duration timeout, double positionToleranceM, double angleToleranceRad,
			Duration quiet, Duration poll) {
		public static final Options DEFAULT = new Options(Duration.ofSeconds(10), 0.0002,
				Math.toRadians(0.5), Duration.ofMillis(600), Duration.ofMillis(100));
		public Options {
			Preconditions.checkNotNullArgument(timeout, "timeout is null");
			Preconditions.checkNotNullArgument(quiet, "quiet is null");
			Preconditions.checkNotNullArgument(poll, "poll is null");
			Preconditions.checkArgument(!timeout.isNegative(), "timeout must not be negative");
			Preconditions.checkArgument(!quiet.isNegative() && !quiet.isZero(), "quiet must be positive");
			Preconditions.checkArgument(!poll.isNegative() && !poll.isZero(), "poll must be positive");
			Preconditions.checkArgument(Double.isFinite(positionToleranceM) && positionToleranceM >= 0,
					"positionToleranceM must be finite and nonnegative");
			Preconditions.checkArgument(Double.isFinite(angleToleranceRad) && angleToleranceRad >= 0 && angleToleranceRad <= Math.PI,
					"angleToleranceRad must be finite and in [0, pi]");
			nanos(timeout); nanos(quiet); nanos(poll);
		}
		public Options withTimeout(Duration value) { return new Options(value, positionToleranceM, angleToleranceRad, quiet, poll); }
	}

	public enum Outcome { NOT_STARTED, OBSERVING, SETTLED, NO_VALID_SAMPLE, TIMEOUT, CANCELLED, FAILED }
	public enum Rejection {
		QUALITY_NOT_OK, RECEIVED_AT_MISSING, RECEIVED_AT_INVALID, OBJECT_MISSING, POSITION_INVALID,
		ORIENTATION_INVALID, STAMP_INVALID, STAMP_OUT_OF_RANGE, FRAME_INVALID
	}
	public enum ResetReason { INVALID_SAMPLE, TIME_REWOUND, STAMP_KIND_CHANGED, FRAME_CHANGED, POSITION_CHANGED, ORIENTATION_CHANGED }

	/** reads = validSamples + rejectedSamples + lateSamples. 새 표본 수에는 시각 역행 후의 기준도 포함한다. */
	public record Diagnostics(long reads, long validSamples, long freshSamples, long duplicates, long rejectedSamples,
			long lateSamples, long resets, Map<Rejection,Long> rejections,
			@Nullable Rejection lastRejection, @Nullable ResetReason lastReset) implements ObservationDiagnostics<Rejection> {
		public Diagnostics { rejections = Map.copyOf(rejections); }
		@Override public long newSamples() { return freshSamples; }
		@Override public long duplicateSamples() { return duplicates; }
		@Override public long resetCount() { return resets; }
		@Override public Map<Rejection,Long> rejectionCounts() { return rejections; }
	}

	/** sample은 마지막 유효 응답 전체이며 읽기 전용이다. 취소·실패 시에도 getProgress()로 조회한다. */
	public record Result(String objectName, Outcome outcome, Optional<JsonNode> sample, Duration elapsed, Diagnostics diagnostics) implements StepVerdict {
		@Override public boolean goalReached() { return outcome == Outcome.SETTLED; }
		@Override public @Nullable String failureReason() { return goalReached() ? null : "물체 정착 확인 불가: " + summary(); }
		public Optional<JsonNode> object() { return sample.map(body -> body.path("value").path("objects").get(objectName)); }
		public String summary() {
			return outcome + ": " + objectName + ", 유효 표본 " + diagnostics.validSamples() + "건, 새 표본 "
					+ diagnostics.freshSamples() + "건, 중복 " + diagnostics.duplicates() + "건, 거부 "
					+ diagnostics.rejectedSamples() + "건, 상한 이후 " + diagnostics.lateSamples() + "건, 구간 재시작 "
					+ diagnostics.resets() + "회, 마지막 거부=" + diagnostics.lastRejection() + ", 마지막 재시작=" + diagnostics.lastReset();
		}
	}

	private final RobotTwinClient m_twin;
	private final String m_objectName;
	private final Options m_options;
	private final Stats m_stats = new Stats();
	private long m_started;
	private boolean m_observationStarted;

	public WaitUntilObjectStill(RobotTwinClient twin, String objectName) { this(twin, objectName, Options.DEFAULT); }
	public WaitUntilObjectStill(RobotTwinClient twin, String objectName, Options options) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		OperationInputs.validateName(objectName, "objectName");
		Preconditions.checkNotNullArgument(options, "options is null");
		m_twin = twin;
		m_objectName = objectName;
		m_options = options;
		publishProgress(m_stats.result(Outcome.NOT_STARTED, Duration.ZERO));
	}

	@Override public Diagnostics getDiagnostics() { return getProgress().diagnostics(); }

	@Override protected void onObservationStarted() {
		m_started = System.nanoTime();
		m_observationStarted = true;
		publish(m_stats, Outcome.OBSERVING, m_started);
	}

	@Override protected Result observe() throws InterruptedException {
		boolean settled = observe(m_stats, m_started);
		Outcome outcome = settled ? Outcome.SETTLED : m_stats.valid == 0 ? Outcome.NO_VALID_SAMPLE : Outcome.TIMEOUT;
		return m_stats.result(outcome, Duration.ofNanos(System.nanoTime() - m_started));
	}

	@Override protected void onObservationCompleted(Result result) { publishProgress(result); }

	@Override protected void onObservationStopped(boolean cancelled) {
		Duration elapsed = m_observationStarted ? Duration.ofNanos(System.nanoTime() - m_started) : Duration.ZERO;
		publishProgress(m_stats.result(cancelled ? Outcome.CANCELLED : Outcome.FAILED, elapsed));
	}

	private boolean observe(Stats stats, long started) throws InterruptedException {
		long timeout = nanos(m_options.timeout()), quiet = nanos(m_options.quiet());
		Sample anchor = null, previous = null;
		long stillSince = 0;
		while ( System.nanoTime() - started < timeout ) {
			checkStopped();
			JsonNode body = m_twin.readRaw("scene_objects");
			checkStopped();
			long now = System.nanoTime();
			++stats.reads;
			if ( now - started >= timeout ) { ++stats.late; break; }
			Parsed parsed = parse(body);
			Sample sample = parsed.sample();
			if ( sample == null ) {
				++stats.rejected;
				stats.lastRejection = parsed.rejection();
				stats.rejections.merge(parsed.rejection(), 1L, Long::sum);
				if ( anchor != null ) { stats.reset(ResetReason.INVALID_SAMPLE); }
				anchor = null;
			}
			else {
				++stats.valid;
				stats.lastSample = body.deepCopy();
				boolean rewound = previous != null && (sample.received().isBefore(previous.received())
						|| sample.stamp() != null && previous.stamp() != null && sample.stamp() < previous.stamp());
				boolean kindChanged = previous != null && (sample.stamp() == null) != (previous.stamp() == null);
				boolean frameChanged = previous != null && !sample.frame().equals(previous.frame());
				boolean duplicate = previous != null && (sample.received().equals(previous.received())
						|| sample.stamp() != null && sample.stamp().equals(previous.stamp()));
				if ( duplicate && !rewound && !kindChanged && !frameChanged ) { ++stats.duplicates; }
				else {
					++stats.fresh;
					boolean moved = anchor != null && maxDiff(sample.xyz(), anchor.xyz()) > m_options.positionToleranceM();
					boolean rotated = anchor != null && angle(sample.q(), anchor.q()) > m_options.angleToleranceRad();
					if ( anchor == null || rewound || kindChanged || frameChanged || moved || rotated ) {
						if ( anchor != null ) {
							stats.reset(rewound ? ResetReason.TIME_REWOUND : kindChanged ? ResetReason.STAMP_KIND_CHANGED
									: frameChanged ? ResetReason.FRAME_CHANGED : moved ? ResetReason.POSITION_CHANGED : ResetReason.ORIENTATION_CHANGED);
						}
						anchor = sample;
						stillSince = now;
					}
					else if ( now - stillSince >= quiet && Duration.between(anchor.received(), sample.received()).compareTo(m_options.quiet()) >= 0 ) {
						return true;
					}
					previous = sample;
				}
			}
			publish(stats, Outcome.OBSERVING, started);
			long sleep = Math.min(nanos(m_options.poll()), Math.max(0, timeout - (System.nanoTime() - started)));
			if ( sleep > 0 ) { Thread.sleep(sleep / 1_000_000, (int)(sleep % 1_000_000)); }
		}
		return false;
	}

	private void publish(Stats stats, Outcome outcome, long started) {
		publishProgress(stats.result(outcome, Duration.ofNanos(System.nanoTime() - started)));
	}
	private final class Stats {
		long reads, valid, fresh, duplicates, rejected, late, resets;
		JsonNode lastSample;
		Rejection lastRejection;
		ResetReason lastReset;
		final Map<Rejection,Long> rejections = new EnumMap<>(Rejection.class);
		void reset(ResetReason reason) { ++resets; lastReset = reason; }
		Result result(Outcome outcome, Duration elapsed) {
			return new Result(m_objectName, outcome, Optional.ofNullable(lastSample), elapsed,
					new Diagnostics(reads, valid, fresh, duplicates, rejected, late, resets, rejections, lastRejection, lastReset));
		}
	}
	private record Sample(double[] xyz, double[] q, Instant received, @Nullable Long stamp, String frame) { }
	private record Parsed(@Nullable Sample sample, @Nullable Rejection rejection) { }
	private static Parsed rejected(Rejection reason) { return new Parsed(null, reason); }

	private Parsed parse(JsonNode body) {
		if ( body == null || !"OK".equals(body.path("quality").asText()) ) { return rejected(Rejection.QUALITY_NOT_OK); }
		JsonNode receivedNode = body.get("received_at");
		if ( receivedNode == null || receivedNode.isNull() ) { return rejected(Rejection.RECEIVED_AT_MISSING); }
		if ( !receivedNode.isTextual() ) { return rejected(Rejection.RECEIVED_AT_INVALID); }
		Instant received;
		try { received = OffsetDateTime.parse(receivedNode.textValue()).toInstant(); }
		catch ( DateTimeParseException e ) { return rejected(Rejection.RECEIVED_AT_INVALID); }
		JsonNode value = body.path("value");
		JsonNode object = value.path("objects").get(m_objectName);
		if ( object == null || !object.isObject() ) { return rejected(Rejection.OBJECT_MISSING); }
		double[] xyz = vector(object.path("pose").path("position"), "x", "y", "z");
		if ( xyz == null ) { return rejected(Rejection.POSITION_INVALID); }
		double[] q = vector(object.path("pose").path("orientation"), "x", "y", "z", "w");
		if ( q == null ) { return rejected(Rejection.ORIENTATION_INVALID); }
		double norm = Math.hypot(Math.hypot(q[0], q[1]), Math.hypot(q[2], q[3]));
		if ( !Double.isFinite(norm) || Math.abs(norm - 1) > 0.001 ) { return rejected(Rejection.ORIENTATION_INVALID); }
		for ( int i = 0; i < q.length; ++i ) { q[i] /= norm; }
		Long stamp = null;
		JsonNode stampNode = body.get("stamp");
		if ( stampNode != null && !stampNode.isNull() ) {
			JsonNode sec = stampNode.path("sec"), ns = stampNode.path("nanosec");
			if ( !sec.isIntegralNumber() || !ns.isIntegralNumber() ) { return rejected(Rejection.STAMP_INVALID); }
			if ( !sec.canConvertToLong() || !ns.canConvertToLong() || ns.longValue() < 0 || ns.longValue() >= 1_000_000_000 ) {
				return rejected(Rejection.STAMP_OUT_OF_RANGE);
			}
			try { stamp = Math.addExact(Math.multiplyExact(sec.longValue(), 1_000_000_000L), ns.longValue()); }
			catch ( ArithmeticException e ) { return rejected(Rejection.STAMP_OUT_OF_RANGE); }
		}
		JsonNode frame = value.path("header").get("frame_id");
		if ( frame != null && !frame.isTextual() ) { return rejected(Rejection.FRAME_INVALID); }
		return new Parsed(new Sample(xyz, q, received, stamp, frame == null ? "" : frame.textValue()), null);
	}
	private static double @Nullable [] vector(JsonNode node, String... keys) {
		double[] values = new double[keys.length];
		for ( int i = 0; i < keys.length; ++i ) {
			JsonNode v = node.get(keys[i]);
			if ( v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) ) { return null; }
			values[i] = v.doubleValue();
		}
		return values;
	}
	private static double maxDiff(double[] a, double[] b) {
		return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
	}
	private static double angle(double[] a, double[] b) {
		// q와 -q는 같은 회전. 작은 회전에서도 acos(dot)의 정밀도 손실 없이 최단 회전각을 구한다.
		double minus = 0, plus = 0;
		for ( int i = 0; i < 4; ++i ) { minus = Math.hypot(minus, a[i] - b[i]); plus = Math.hypot(plus, a[i] + b[i]); }
		return 4 * Math.asin(Math.min(1, Math.min(minus, plus) / 2));
	}
	private static long nanos(Duration value) {
		try { return value.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException("observation duration is too large: " + value, e); }
	}
}
