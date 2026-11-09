package mdt.robot.client.operation;

import java.time.Duration;
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
 * ee_pose의 새 유효 표본으로 팔(도구 기준점)의 위치 정착을 확인하는 관측 연산.
 * <p>
 * 생성자는 통신하지 않는다. run() 또는 start()/get()으로 한 번 실행한다.
 * quality=OK인 표본의 위치가 구간 시작점 기준 허용 범위 안에 머무르고, 로컬 경과 시간과
 * received_at 차이가 모두 quiet 이상이면 SETTLED 결과를 반환한다. 결과의 sample은 마지막 유효 응답 전체이고
 * pose()는 그 관측값(PoseStamped)이다.
 * 같은 캐시 표본은 건너뛰며, 무효 표본·시각 역행·stamp 유무 변경·허용 범위 초과는 구간을 재시작한다.
 * 자세·목표 도달 여부나 물리적 정지 완료는 검증하지 않는다.
 * <p>
 * 정착 상한 초과는 TIMEOUT/NO_VALID_SAMPLE 결과(goalReached=false)로 정상 완료한다. timeout=0이면 조회하지 않는다.
 * 통신 오류는 FAILED이며 run()/get()의 ExecutionException에 원인을 담는다.
 * 실행 전 취소는 요청 없이 CANCELLED다. 시작 중(STARTING) cancel(true)는 기록되어 관측 전에 CANCELLED로 끝난다.
 * 실행 후 cancel(false)는 false다.
 * 실행 중 cancel(true)는 관측 스레드를 인터럽트하며 종료까지 CANCELLING이다.
 * 블로킹 HTTP는 즉시 중단을 보장하지 않으며 관측 상한도 진행 중인 HTTP를 강제로 끊지 않는다.
 * 원격 연산 POST·DELETE나 비상정지는 보내지 않는다.
 * <p>
 * 외부 인터럽트는 인식 시 CANCELLED가 된다. run()은 플래그를 해제하고 InterruptedException으로,
 * start()/get()은 CancellationException으로 전달한다.
 */
public final class WaitUntilArmStill extends AbstractObservationOperation<WaitUntilArmStill.Result, WaitUntilArmStill.Result> {
	/**
	 * 정착 확인의 매개변수.
	 *
	 * @param timeout	정착을 기다릴 상한. 0 이상.
	 * @param positionToleranceM	구간 시작 위치에서 각 축이 머물러야 하는 범위(m). 0 이상.
	 * @param quiet		정착으로 볼 최소 구간 길이. 양수.
	 * @param poll		조회 주기. 양수.
	 * 값이 null이거나 범위를 벗어난 경우, 기간이 나노초 long 범위를 넘는 경우 IllegalArgumentException이다.
	 */
	public record Options(Duration timeout, double positionToleranceM, Duration quiet, Duration poll) {
		public static final Options DEFAULT = new Options(Duration.ofSeconds(10), 0.0002,
																		Duration.ofMillis(600), Duration.ofMillis(300));

		public Options {
			Preconditions.checkNotNullArgument(timeout, "timeout is null");
			Preconditions.checkNotNullArgument(quiet, "quiet is null");
			Preconditions.checkNotNullArgument(poll, "poll is null");
			Preconditions.checkArgument(!timeout.isNegative(), "timeout must not be negative");
			Preconditions.checkArgument(Double.isFinite(positionToleranceM) && positionToleranceM >= 0,
					"positionToleranceM must be finite and nonnegative");
			Preconditions.checkArgument(!quiet.isNegative() && !quiet.isZero(), "quiet must be positive");
			Preconditions.checkArgument(!poll.isNegative() && !poll.isZero(), "poll must be positive");
			nanos(timeout); nanos(quiet); nanos(poll);
		}

		/** 상한만 바꾼다. */
		public Options withTimeout(Duration value) {
			return new Options(value, positionToleranceM, quiet, poll);
		}
	}

	/** 관측 결과 분류. TIMEOUT은 계속 움직였다는 확정 판정이 아니다. */
	public enum Outcome { NOT_STARTED, OBSERVING, SETTLED, NO_VALID_SAMPLE, TIMEOUT, CANCELLED, FAILED }

	public enum Rejection {
		QUALITY_NOT_OK, RECEIVED_AT_MISSING, RECEIVED_AT_INVALID, POSITION_MISSING, POSITION_INVALID,
		STAMP_INVALID, STAMP_OUT_OF_RANGE
	}

	public enum ResetReason { INVALID_SAMPLE, TIME_REWOUND, STAMP_KIND_CHANGED, POSITION_OUT_OF_RANGE }

	/**
	 * 일관된 불변 진단 스냅샷. reads = validSamples + rejectedSamples + lateSamples다.
	 * freshSamples는 유효 표본 중 중복이 아닌 표본 수이며 시각 역행 표본도 포함한다.
	 * 구간 재시작(resets)은 처음 구간을 잡는 경우를 제외한다. 마지막 거부/재시작 이유는 이후에도 유지한다.
	 */
	public record Diagnostics(long reads, long validSamples, long freshSamples, long duplicates, long rejectedSamples,
			long lateSamples, long resets, Map<Rejection,Long> rejections,
			@Nullable Rejection lastRejection, @Nullable ResetReason lastReset) implements ObservationDiagnostics<Rejection> {
		public Diagnostics { rejections = Map.copyOf(rejections); }
		@Override public long newSamples() { return freshSamples; }
		@Override public long duplicateSamples() { return duplicates; }
		@Override public long resetCount() { return resets; }
		@Override public Map<Rejection,Long> rejectionCounts() { return rejections; }
	}

	/**
	 * 관측 결과. sample은 정착을 확인한 마지막 유효 응답 전체(SETTLED일 때만)이며 읽기 전용이다.
	 * TIMEOUT은 유효 표본이 있었지만 정착을 확인하지 못한 경우, NO_VALID_SAMPLE은 유효 표본 0건이다.
	 * 취소·실패 시에도 getProgress()로 조회한다.
	 */
	public record Result(Outcome outcome, Optional<JsonNode> sample, Duration elapsed, Diagnostics diagnostics)
			implements StepVerdict {
		@Override public boolean goalReached() { return outcome == Outcome.SETTLED; }
		@Override public @Nullable String failureReason() { return goalReached() ? null : "위치 정착 확인 불가: " + summary(); }
		/** 정착을 확인한 관측값(PoseStamped). */
		public Optional<JsonNode> pose() { return sample.map(body -> body.path("value")); }
		public String summary() {
			return String.format("%s: 유효 표본 %d건, 새 표본 %d건, 중복 %d건, 거부 %d건, 상한 이후 %d건, 구간 재시작 %d회%s%s",
					outcome, diagnostics.validSamples(), diagnostics.freshSamples(), diagnostics.duplicates(),
					diagnostics.rejectedSamples(), diagnostics.lateSamples(), diagnostics.resets(),
					diagnostics.lastRejection() == null ? "" : ", 마지막 거부=" + diagnostics.lastRejection(),
					diagnostics.lastReset() == null ? "" : ", 마지막 재시작=" + diagnostics.lastReset());
		}
	}

	// 작업 스레드만 Stats를 변경하고, volatile로 불변 스냅샷을 게시한다.
	private final Stats m_stats = new Stats();

	/**
	 * 실행 중에도 안전하게 읽을 수 있는 최신 게시 스냅샷이다. 이후 호출에서는 값이 달라질 수 있다.
	 * 실행이 종료된 뒤에는 최종 값이다. 진행 중인 HTTP 응답은 처리되기 전까지 집계하지 않는다.
	 * 관측 단계의 상태(outcome)는 getProgress()에 있다.
	 */
	@Override public Diagnostics getDiagnostics() { return getProgress().diagnostics(); }

	private final RobotTwinClient m_twin;
	private final Options m_options;
	private final long m_timeoutNanos;
	private final long m_quietNanos;
	private final long m_pollNanos;
	private long m_started;
	private boolean m_observationStarted;

	public WaitUntilArmStill(RobotTwinClient twin) {
		this(twin, Options.DEFAULT);
	}

	public WaitUntilArmStill(RobotTwinClient twin, Options options) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		Preconditions.checkNotNullArgument(options, "options is null");
		m_twin = twin;
		m_options = options;
		m_timeoutNanos = nanos(options.timeout());
		m_quietNanos = nanos(options.quiet());
		m_pollNanos = nanos(options.poll());
		publishProgress(m_stats.result(Outcome.NOT_STARTED, null, Duration.ZERO));
	}

	@Override protected void onObservationStarted() {
		m_started = System.nanoTime();
		m_observationStarted = true;
		publishProgress(m_stats.result(Outcome.OBSERVING, null, Duration.ZERO));
	}

	@Override protected Result observe() throws InterruptedException {
		JsonNode settled = observe(m_stats, m_started);
		Outcome outcome = settled != null ? Outcome.SETTLED : m_stats.valid == 0 ? Outcome.NO_VALID_SAMPLE : Outcome.TIMEOUT;
		return m_stats.result(outcome, settled, elapsed());
	}

	@Override protected void onObservationCompleted(Result result) { publishProgress(result); }

	@Override protected void onObservationStopped(boolean cancelled) {
		publishProgress(m_stats.result(cancelled ? Outcome.CANCELLED : Outcome.FAILED, null, elapsed()));
	}

	private Duration elapsed() {
		return m_observationStarted ? Duration.ofNanos(System.nanoTime() - m_started) : Duration.ZERO;
	}

	private void publishObserving(Stats stats) {
		publishProgress(stats.result(Outcome.OBSERVING, null, elapsed()));
	}

	/** 정착을 확인한 응답 전체. 확인하지 못하면 null. */
	private @Nullable JsonNode observe(Stats stats, long started) throws InterruptedException {
		double quietSec = m_quietNanos / 1e9;
		double[] anchor = null;
		Double lastReceived = null;
		Long lastStamp = null;
		long stillSince = 0;
		double sampleSince = 0;
		while ( System.nanoTime() - started < m_timeoutNanos ) {
			checkStopped();
			JsonNode body = m_twin.readRaw("ee_pose");
			checkStopped();
			long now = System.nanoTime();
			++stats.reads;
			if ( now - started >= m_timeoutNanos ) {
				++stats.late;
				publishObserving(stats);
				break; // 늦게 도착한 표본은 유효/무효 판정에도 쓰지 않는다.
			}
			ParsedSample parsed = Sample.parse(body);
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
				// 시각 역행·stamp 유무 변경을 먼저 본다. 이것들은 같은 시각 값이 하나 겹쳐도 구간을 다시 시작해야 하므로,
				// 둘 다 아닐 때만 중복으로 센다(WaitUntilObjectStill과 같은 순서).
				boolean rewound = (lastReceived != null && sample.receivedAt < lastReceived)
						|| (sample.stamp != null && lastStamp != null && sample.stamp < lastStamp);
				boolean stampKindChanged = lastReceived != null && (sample.stamp == null) != (lastStamp == null);
				boolean duplicate = !rewound && !stampKindChanged
						&& ((lastReceived != null && sample.receivedAt == lastReceived)
							|| (sample.stamp != null && sample.stamp.equals(lastStamp)));
				if ( duplicate ) { ++stats.duplicates; }
				else {
					++stats.fresh;
					boolean moved = anchor != null && maxDiff(sample.xyz, anchor) > m_options.positionToleranceM();
					if ( anchor == null || rewound || stampKindChanged || moved ) {
						if ( anchor != null ) {
							stats.reset(rewound ? ResetReason.TIME_REWOUND : stampKindChanged
									? ResetReason.STAMP_KIND_CHANGED : ResetReason.POSITION_OUT_OF_RANGE);
						}
						anchor = sample.xyz;
						stillSince = now;
						sampleSince = sample.receivedAt;
					}
					else if ( (now - stillSince) / 1e9 >= quietSec && sample.receivedAt - sampleSince >= quietSec ) {
						return sample.body;
					}
					lastReceived = sample.receivedAt;
					lastStamp = sample.stamp;
				}
			}
			publishObserving(stats);
			sleepNanos(Math.min(m_pollNanos, Math.max(0, m_timeoutNanos - (System.nanoTime() - started))));
		}
		return null;
	}

	private static long nanos(Duration value) {
		try { return value.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException("observation duration is too large: " + value, e); }
	}

	private static double maxDiff(double[] a, double[] b) {
		return Math.max(Math.abs(a[0] - b[0]), Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
	}

	private static void sleepNanos(long nanos) throws InterruptedException {
		if ( nanos > 0 ) { Thread.sleep(nanos / 1_000_000, (int)(nanos % 1_000_000)); }
	}

	private static final class Stats {
		long reads, valid, fresh, duplicates, rejected, late, resets;
		final Map<Rejection,Long> rejections = new EnumMap<>(Rejection.class);
		Rejection lastRejection;
		ResetReason lastReset;

		void reset(ResetReason reason) { ++resets; lastReset = reason; }

		Result result(Outcome outcome, @Nullable JsonNode sample, Duration elapsed) {
			return new Result(outcome, Optional.ofNullable(sample), elapsed,
					new Diagnostics(reads, valid, fresh, duplicates, rejected, late, resets, rejections, lastRejection, lastReset));
		}
	}

	private record ParsedSample(@Nullable Sample sample, @Nullable Rejection rejection) {
		static ParsedSample rejected(Rejection reason) { return new ParsedSample(null, reason); }
	}

	/** 거부 이유를 포함하여 표본을 검증한다. 원래 응답/위치는 진단 스냅샷에 넣지 않는다. */
	private record Sample(JsonNode body, double[] xyz, double receivedAt, @Nullable Long stamp) {
		static ParsedSample parse(JsonNode body) {
			if ( body == null || !"OK".equals(body.path("quality").asText()) ) {
				return ParsedSample.rejected(Rejection.QUALITY_NOT_OK);
			}
			JsonNode receivedNode = body.get("received_at");
			if ( receivedNode == null || receivedNode.isNull() ) {
				return ParsedSample.rejected(Rejection.RECEIVED_AT_MISSING);
			}
			if ( !receivedNode.isTextual() ) { return ParsedSample.rejected(Rejection.RECEIVED_AT_INVALID); }
			OffsetDateTime received;
			try {
				// Z와 명시적 오프셋을 모두 허용하되, 시간대가 없는 문자열은 거부한다.
				received = OffsetDateTime.parse(receivedNode.textValue());
			}
			catch ( DateTimeParseException e ) { return ParsedSample.rejected(Rejection.RECEIVED_AT_INVALID); }
			double receivedAt = received.toEpochSecond() + received.getNano() / 1e9;
			JsonNode value = body.get("value");
			JsonNode position = value == null ? null : value.path("pose").get("position");
			if ( position == null || position.isNull() ) { return ParsedSample.rejected(Rejection.POSITION_MISSING); }
			double[] xyz = new double[3];
			String[] axes = { "x", "y", "z" };
			for ( int i = 0; i < 3; ++i ) {
				JsonNode v = position.get(axes[i]);
				if ( v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) ) {
					return ParsedSample.rejected(Rejection.POSITION_INVALID);
				}
				xyz[i] = v.doubleValue();
			}
			Long stamp = null;
			JsonNode stampNode = body.get("stamp");
			if ( stampNode != null && !stampNode.isNull() ) {
				JsonNode sec = stampNode.get("sec"), nsec = stampNode.get("nanosec");
				if ( sec == null || nsec == null || !sec.isIntegralNumber() || !nsec.isIntegralNumber() ) {
					return ParsedSample.rejected(Rejection.STAMP_INVALID);
				}
				if ( !sec.canConvertToLong() || !nsec.canConvertToLong() ) {
					return ParsedSample.rejected(Rejection.STAMP_OUT_OF_RANGE);
				}
				long ns = nsec.longValue();
				if ( ns < 0 || ns >= 1_000_000_000L ) { return ParsedSample.rejected(Rejection.STAMP_OUT_OF_RANGE); }
				try { stamp = Math.addExact(Math.multiplyExact(sec.longValue(), 1_000_000_000L), ns); }
				catch ( ArithmeticException e ) { return ParsedSample.rejected(Rejection.STAMP_OUT_OF_RANGE); }
			}
			return new ParsedSample(new Sample(body, xyz, receivedAt, stamp), null);
		}
	}
}
