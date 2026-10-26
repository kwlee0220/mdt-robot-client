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
 * ee_pose의 새 유효 표본으로 위치 정착을 확인하는 관측 연산.
 * <p>
 * 생성자는 통신하지 않는다. run() 또는 start()/get()으로 한 번 실행한다.
 * quality=OK인 표본의 위치가 구간 시작점 기준 허용 범위 안에 머무르고, 로컬 경과 시간과
 * received_at 차이가 모두 quietPeriod 이상이면 마지막 관측값(PoseStamped)을 반환한다.
 * 같은 캐시 표본은 건너뛰며, 무효 표본·시각 역행·stamp 유무 변경·허용 범위 초과는 구간을 재시작한다.
 * 자세·목표 도달 여부나 물리적 정지 완료는 검증하지 않는다.
 * <p>
 * 정착 상한 초과는 Optional.empty()로 정상 완료한다. timeout=0이면 조회하지 않는다.
 * 통신 오류는 FAILED이며 run()/get()의 ExecutionException에 원인을 담는다.
 * 실행 전 취소는 요청 없이 CANCELLED, 시작 중 취소는 false, 실행 후 cancel(false)는 false다.
 * 실행 중 cancel(true)는 관측 스레드를 인터럽트하며 종료까지 CANCELLING이다.
 * 블로킹 HTTP는 즉시 중단을 보장하지 않으며 관측 상한도 진행 중인 HTTP를 강제로 끊지 않는다.
 * 원격 연산 POST·DELETE나 비상정지는 보내지 않는다.
 * <p>
 * 외부 인터럽트는 인식 시 CANCELLED가 된다. run()은 플래그를 해제하고 InterruptedException으로,
 * start()/get()은 CancellationException으로 전달한다.
 */
public final class WaitUntilStill extends AbstractObservationOperation<Optional<JsonNode>, WaitUntilStill.Diagnostics> {
	/**
	 * 정착 확인의 매개변수.
	 *
	 * @param timeout	정착을 기다릴 상한. 0 이상.
	 * @param positionTolerance		구간 시작 위치에서 각 축이 머물러야 하는 범위(m). 0 이상.
	 * @param quietPeriod		정착으로 볼 최소 구간 길이. 양수.
	 * @param pollInterval		조회 주기. 양수.
	 */
	public record Options(Duration timeout, double positionTolerance, Duration quietPeriod, Duration pollInterval) {
		public static final Options DEFAULT = new Options(Duration.ofSeconds(10), 0.0002,
																		Duration.ofMillis(600), Duration.ofMillis(300));

		public Options {
			if ( timeout.isNegative() ) {
				throw new IllegalArgumentException("timeout must be nonnegative");
			}
			if ( !Double.isFinite(positionTolerance) || positionTolerance < 0 ) {
				throw new IllegalArgumentException("positionTolerance must be finite and nonnegative");
			}
			if ( quietPeriod.isNegative() || quietPeriod.isZero() ) {
				throw new IllegalArgumentException("quietPeriod must be positive");
			}
			if ( pollInterval.isNegative() || pollInterval.isZero() ) {
				throw new IllegalArgumentException("pollInterval must be positive");
			}
		}

		/** 상한만 바꾼다. */
		public Options withTimeout(Duration newTimeout) {
			return new Options(newTimeout, positionTolerance, quietPeriod, pollInterval);
		}
	}

	/** 관측 결과 분류. TIMEOUT은 계속 움직였다는 확정 판정이 아니다. */
	public enum Outcome { NOT_STARTED, OBSERVING, SETTLED, NO_VALID_SAMPLE, TIMEOUT, CANCELLED, FAILED }

	public enum RejectionReason {
		QUALITY_NOT_OK, RECEIVED_AT_MISSING, RECEIVED_AT_INVALID, POSITION_MISSING, POSITION_INVALID,
		STAMP_INVALID, STAMP_OUT_OF_RANGE
	}

	public enum ResetReason { INVALID_SAMPLE, TIME_REWOUND, STAMP_KIND_CHANGED, POSITION_OUT_OF_RANGE }

	/**
	 * 일관된 불변 진단 스냅샷. samples = validSamples + rejectedSamples + lateSamples다.
	 * newSamples는 유효 표본 중 중복이 아닌 표본 수이며 시각 역행 표본도 포함한다.
	 * 구간 재시작은 처음 구간을 잡는 경우를 제외한다. 마지막 거부/재시작 이유는 이후에도 유지한다.
	 * TIMEOUT은 유효 표본이 있었지만 정착을 확인하지 못한 경우, NO_VALID_SAMPLE은 유효 표본 0건이다.
	 */
	public record Diagnostics(Outcome outcome, long samples, long validSamples, long newSamples,
			long duplicateSamples, long rejectedSamples, long lateSamples, long resetCount,
			Map<RejectionReason,Long> rejectionCounts, @Nullable RejectionReason lastRejection,
			@Nullable ResetReason lastReset) implements ObservationDiagnostics<RejectionReason> {
		public Diagnostics { rejectionCounts = Map.copyOf(rejectionCounts); }
		@Override public long reads() { return samples; }

		public String summary() {
			return String.format("%s: 유효 표본 %d건, 새 표본 %d건, 중복 %d건, 거부 %d건, 상한 이후 %d건, 구간 재시작 %d회%s%s",
					outcome, validSamples, newSamples, duplicateSamples, rejectedSamples, lateSamples, resetCount,
					lastRejection == null ? "" : ", 마지막 거부=" + lastRejection,
					lastReset == null ? "" : ", 마지막 재시작=" + lastReset);
		}
	}

	// 작업 스레드만 Stats를 변경하고, volatile로 불변 스냅샷을 게시한다.
	private final Stats m_stats = new Stats();

	/**
	 * 실행 중에도 안전하게 읽을 수 있는 최신 게시 스냅샷이다. 이후 호출에서는 값이 달라질 수 있다.
	 * 실행이 종료된 뒤에는 최종 값이다. get() 호출자만 시간 초과/인터럽트된 경우는 해당하지 않는다.
	 * 진행 중인 HTTP 응답은 처리되기 전까지 집계하지 않는다. outcome은 관측 단계의 상태이므로
	 * 실행기 제출 실패 등 관측 시작 전 실패에서는 NOT_STARTED일 수 있다.
	 */
	@Override public Diagnostics getDiagnostics() { return getProgress(); }

	private final RobotTwinClient m_twin;
	private final Options m_options;
	private final long m_timeoutNanos;
	private final long m_quietNanos;
	private final long m_pollNanos;

	public WaitUntilStill(RobotTwinClient twin) {
		this(twin, Options.DEFAULT);
	}

	/** 기간은 나노초 long으로 표현 가능한 범위여야 한다. */
	public WaitUntilStill(RobotTwinClient twin, Options options) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		Preconditions.checkNotNullArgument(options, "options is null");
		m_twin = twin;
		m_options = options;
		m_timeoutNanos = nanos(options.timeout());
		m_quietNanos = nanos(options.quietPeriod());
		m_pollNanos = nanos(options.pollInterval());
		publishProgress(m_stats.snapshot(Outcome.NOT_STARTED));
	}

	@Override protected void onObservationStarted() {
		publishProgress(m_stats.snapshot(Outcome.OBSERVING));
	}

	@Override protected Optional<JsonNode> observe() throws InterruptedException { return observe(m_stats); }

	@Override protected void onObservationCompleted(Optional<JsonNode> result) {
		publishFinal(result.isPresent() ? Outcome.SETTLED : m_stats.valid == 0 ? Outcome.NO_VALID_SAMPLE : Outcome.TIMEOUT);
	}

	@Override protected void onObservationStopped(boolean cancelled) {
		publishFinal(cancelled ? Outcome.CANCELLED : Outcome.FAILED);
	}

	private void publishFinal(Outcome outcome) {
		publishProgress(m_stats.snapshot(outcome));
	}

	private Optional<JsonNode> observe(Stats stats) throws InterruptedException {
		long started = System.nanoTime();
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
			++stats.samples;
			if ( now - started >= m_timeoutNanos ) {
				++stats.late;
				publishProgress(stats.snapshot(Outcome.OBSERVING));
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
				boolean duplicate = (lastReceived != null && sample.receivedAt == lastReceived)
						|| (sample.stamp != null && sample.stamp.equals(lastStamp));
				if ( duplicate ) { ++stats.duplicates; }
				else {
					++stats.newSamples;
					boolean rewound = (lastReceived != null && sample.receivedAt < lastReceived)
							|| (sample.stamp != null && lastStamp != null && sample.stamp < lastStamp);
					boolean stampKindChanged = lastReceived != null && (sample.stamp == null) != (lastStamp == null);
					boolean moved = anchor != null && maxDiff(sample.xyz, anchor) > m_options.positionTolerance();
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
						return Optional.of(sample.value);
					}
					lastReceived = sample.receivedAt;
					lastStamp = sample.stamp;
				}
			}
			publishProgress(stats.snapshot(Outcome.OBSERVING));
			sleepNanos(Math.min(m_pollNanos, Math.max(0, m_timeoutNanos - (System.nanoTime() - started))));
		}
		return Optional.empty();
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
		long samples, valid, newSamples, duplicates, rejected, late, resets;
		final Map<RejectionReason,Long> rejections = new EnumMap<>(RejectionReason.class);
		RejectionReason lastRejection;
		ResetReason lastReset;

		void reset(ResetReason reason) { ++resets; lastReset = reason; }

		Diagnostics snapshot(Outcome outcome) {
			return new Diagnostics(outcome, samples, valid, newSamples, duplicates, rejected, late, resets,
					rejections, lastRejection, lastReset);
		}
	}

	private record ParsedSample(@Nullable Sample sample, @Nullable RejectionReason rejection) {
		static ParsedSample rejected(RejectionReason reason) { return new ParsedSample(null, reason); }
	}

	/** 거부 이유를 포함하여 표본을 검증한다. 원래 응답/위치는 진단 스냅샷에 넣지 않는다. */
	private record Sample(JsonNode value, double[] xyz, double receivedAt, @Nullable Long stamp) {
		static ParsedSample parse(JsonNode body) {
			if ( body == null || !"OK".equals(body.path("quality").asText()) ) {
				return ParsedSample.rejected(RejectionReason.QUALITY_NOT_OK);
			}
			JsonNode receivedNode = body.get("received_at");
			if ( receivedNode == null || receivedNode.isNull() ) {
				return ParsedSample.rejected(RejectionReason.RECEIVED_AT_MISSING);
			}
			if ( !receivedNode.isTextual() ) { return ParsedSample.rejected(RejectionReason.RECEIVED_AT_INVALID); }
			OffsetDateTime received;
			try {
				// Z와 명시적 오프셋을 모두 허용하되, 시간대가 없는 문자열은 거부한다.
				received = OffsetDateTime.parse(receivedNode.textValue());
			}
			catch ( DateTimeParseException e ) { return ParsedSample.rejected(RejectionReason.RECEIVED_AT_INVALID); }
			double receivedAt = received.toEpochSecond() + received.getNano() / 1e9;
			JsonNode value = body.get("value");
			JsonNode position = value == null ? null : value.path("pose").get("position");
			if ( position == null || position.isNull() ) { return ParsedSample.rejected(RejectionReason.POSITION_MISSING); }
			double[] xyz = new double[3];
			String[] axes = { "x", "y", "z" };
			for ( int i = 0; i < 3; ++i ) {
				JsonNode v = position.get(axes[i]);
				if ( v == null || !v.isNumber() || !Double.isFinite(v.doubleValue()) ) {
					return ParsedSample.rejected(RejectionReason.POSITION_INVALID);
				}
				xyz[i] = v.doubleValue();
			}
			Long stamp = null;
			JsonNode stampNode = body.get("stamp");
			if ( stampNode != null && !stampNode.isNull() ) {
				JsonNode sec = stampNode.get("sec"), nsec = stampNode.get("nanosec");
				if ( sec == null || nsec == null || !sec.isIntegralNumber() || !nsec.isIntegralNumber() ) {
					return ParsedSample.rejected(RejectionReason.STAMP_INVALID);
				}
				if ( !sec.canConvertToLong() || !nsec.canConvertToLong() ) {
					return ParsedSample.rejected(RejectionReason.STAMP_OUT_OF_RANGE);
				}
				long ns = nsec.longValue();
				if ( ns < 0 || ns >= 1_000_000_000L ) { return ParsedSample.rejected(RejectionReason.STAMP_OUT_OF_RANGE); }
				try { stamp = Math.addExact(Math.multiplyExact(sec.longValue(), 1_000_000_000L), ns); }
				catch ( ArithmeticException e ) { return ParsedSample.rejected(RejectionReason.STAMP_OUT_OF_RANGE); }
			}
			return new ParsedSample(new Sample(value, xyz, receivedAt, stamp), null);
		}
	}
}
