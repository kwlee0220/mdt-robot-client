package mdt.robot.client.operation;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import com.fasterxml.jackson.databind.JsonNode;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 실행 후 읽은 기준 표본보다 발행 시각이 증가한 유효 표본을 기다린다. 생성자는 통신하지 않는다.
 * quality=OK, value 존재, 유효한 시각이 필요하며 기준 표본은 갱신 수에 포함하지 않는다.
 * received_at을 우선하며 없을 때만 stamp를 사용한다. 무효 표본·시각 역행·시각 종류 변경은
 * 갱신 구간을 다시 시작한다. 값 자체가 같아도 새 시각이면 갱신이다.
 * <p>
 * FRESH는 캐시 갱신의 근거이며 특정 명령의 반영·물리적 정착을 보장하지 않는다.
 * 시각 누락이나 상한 초과는 실패 사유를 담은 Result로 정상 완료한다. 통신 오류는 FAILED다.
 * 실행 전 취소는 요청을 보내지 않는다. 실행 중 cancel(true)는 관측 스레드를 인터럽트하고,
 * 시작 중(STARTING) cancel(true)는 기록되어 관측 전에 끝나고, 실행 중 cancel(false)는 false다. 외부 인터럽트도 CANCELLED로 처리한다.
 * run()은 플래그를 해제한 InterruptedException, start()/get()은 CancellationException으로 전달한다.
 * 관측 상한과 취소는 진행 중인 블로킹 HTTP를 즉시 끊지 않는다. POST·DELETE는 보내지 않는다.
 */
public final class WaitForFreshVariable extends AbstractObservationOperation<WaitForFreshVariable.Result, WaitForFreshVariable.Diagnostics> {
	public record Options(int samples, Duration timeout, Duration poll) {
		public static final Options DEFAULT = new Options(2, Duration.ofSeconds(5), Duration.ofMillis(100));
		public Options {
			Preconditions.checkArgument(samples > 0, "samples must be positive");
			Preconditions.checkNotNullArgument(timeout, "timeout is null");
			Preconditions.checkNotNullArgument(poll, "poll is null");
			Preconditions.checkArgument(!timeout.isNegative(), "timeout must not be negative");
			Preconditions.checkArgument(!poll.isNegative() && !poll.isZero(), "poll must be positive");
			nanos(timeout);
			nanos(poll);
		}
	}

	public enum Outcome { FRESH, NO_VALID_SAMPLE, NO_VALID_TIMESTAMP, TIMEOUT }
	public enum Rejection { QUALITY_NOT_OK, VALUE_MISSING, TIMESTAMP_MISSING, TIMESTAMP_INVALID }

	/** validSamples는 시각까지 유효한 표본 수다. updates는 현재 구간의 갱신 수다. */
	public record Diagnostics(long reads, long validSamples, int updates, long duplicates, long resets,
							  long lateSamples, Map<Rejection,Long> rejections) implements ObservationDiagnostics<Rejection> {
		public Diagnostics { rejections = Map.copyOf(rejections); }
		@Override public long newSamples() { return validSamples - duplicates; }
		@Override public long duplicateSamples() { return duplicates; }
		@Override public long rejectedSamples() { return rejections.values().stream().mapToLong(Long::longValue).sum(); }
		@Override public long resetCount() { return resets; }
		@Override public Map<Rejection,Long> rejectionCounts() { return rejections; }
	}

	/** sample은 FRESH 판정에 사용한 응답 전체다. value·quality·시각을 보존한다. 읽기 전용으로 사용한다. */
	public record Result(Outcome outcome, Optional<JsonNode> sample, Diagnostics diagnostics) implements StepVerdict {
		@Override public boolean goalReached() { return outcome == Outcome.FRESH; }
		@Override public @Nullable String failureReason() { return goalReached() ? null : "새 표본 확인 불가: " + summary(); }
		public String summary() {
			return outcome + ": 갱신 " + diagnostics.updates() + "회, 유효 표본 " + diagnostics.validSamples()
					+ "건, 중복 " + diagnostics.duplicates() + "건, 거부 " + diagnostics.rejections();
		}
	}

	private final RobotTwinClient m_twin;
	private final String m_variable;
	private final Options m_options;
	private final Stats m_stats = new Stats();

	public WaitForFreshVariable(RobotTwinClient twin, String variable) { this(twin, variable, Options.DEFAULT); }

	public WaitForFreshVariable(RobotTwinClient twin, String variable, Options options) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		Preconditions.checkNotNullArgument(variable, "variable is null");
		Preconditions.checkArgument(!variable.isBlank() && variable.equals(variable.strip()), "invalid variable name");
		Preconditions.checkNotNullArgument(options, "options is null");
		m_twin = twin;
		m_variable = variable;
		m_options = options;
		publishProgress(m_stats.snapshot());
	}

	/** 실행 중의 최신 불변 집계. 실패·취소 후에도 보존하며 실행 상태는 getState()로 확인한다. */
	@Override public Diagnostics getDiagnostics() { return getProgress(); }
	@Override protected void onObservationStarted() { publishProgress(m_stats.snapshot()); }
	@Override protected void onObservationCompleted(Result result) { publishProgress(result.diagnostics()); }
	@Override protected void onObservationStopped(boolean cancelled) { publishProgress(m_stats.snapshot()); }

	@Override protected Result observe() throws InterruptedException {
		long started = System.nanoTime(), timeout = nanos(m_options.timeout());
		Stats stats = m_stats;
		Mark previous = null;
		while ( System.nanoTime() - started < timeout ) {
			checkStopped();
			JsonNode envelope = m_twin.readRaw(m_variable);
			checkStopped();
			++stats.reads;
			if ( System.nanoTime() - started >= timeout ) { ++stats.late; break; }
			Parsed parsed = parse(envelope);
			if ( parsed.rejection() != null ) {
				stats.rejections.merge(parsed.rejection(), 1L, Long::sum);
				if ( previous != null ) { ++stats.resets; }
				previous = null;
				stats.updates = 0;
			}
			else {
				++stats.valid;
				Mark current = parsed.mark();
				if ( previous == null ) { previous = current; }
				else if ( current.received() != previous.received() || current.time().isBefore(previous.time()) ) {
					++stats.resets;
					stats.updates = 0;
					previous = current;
				}
				else if ( current.time().equals(previous.time()) ) { ++stats.duplicates; }
				else {
					previous = current;
					if ( ++stats.updates >= m_options.samples() ) {
						return new Result(Outcome.FRESH, Optional.of(envelope.deepCopy()),
								stats.snapshot());
					}
				}
			}
			publishProgress(stats.snapshot());
			long remaining = timeout - (System.nanoTime() - started);
			long delay = Math.min(nanos(m_options.poll()), Math.max(0, remaining));
			if ( delay > 0 ) { Thread.sleep(delay / 1_000_000, (int)(delay % 1_000_000)); }
		}
		// 한 번도 읽지 못한 채 시간이 다 됐으면(timeout 0 포함) 표본이 나빴던 것이 아니라 시간이 부족했던 것이다.
		Outcome outcome = (stats.valid > 0 || stats.reads == 0) ? Outcome.TIMEOUT
				: stats.rejections.containsKey(Rejection.TIMESTAMP_MISSING) || stats.rejections.containsKey(Rejection.TIMESTAMP_INVALID)
				? Outcome.NO_VALID_TIMESTAMP : Outcome.NO_VALID_SAMPLE;
		return new Result(outcome, Optional.empty(),
				stats.snapshot());
	}

	private static final class Stats {
		long reads, valid, duplicates, resets, late;
		int updates;
		final Map<Rejection,Long> rejections = new EnumMap<>(Rejection.class);
		Diagnostics snapshot() { return new Diagnostics(reads, valid, updates, duplicates, resets, late, rejections); }
	}

	private record Mark(boolean received, Instant time) { }
	private record Parsed(@Nullable Mark mark, @Nullable Rejection rejection) { }

	private static Parsed parse(JsonNode body) {
		if ( body == null || !"OK".equals(body.path("quality").asText()) ) {
			return new Parsed(null, Rejection.QUALITY_NOT_OK);
		}
		if ( !body.hasNonNull("value") ) { return new Parsed(null, Rejection.VALUE_MISSING); }
		JsonNode received = body.get("received_at");
		if ( received != null && !received.isNull() ) {
			if ( !received.isTextual() ) { return new Parsed(null, Rejection.TIMESTAMP_INVALID); }
			try { return new Parsed(new Mark(true, OffsetDateTime.parse(received.textValue()).toInstant()), null); }
			catch ( DateTimeParseException e ) { return new Parsed(null, Rejection.TIMESTAMP_INVALID); }
		}
		JsonNode stamp = body.get("stamp");
		if ( stamp == null || stamp.isNull() ) { return new Parsed(null, Rejection.TIMESTAMP_MISSING); }
		JsonNode sec = stamp.path("sec"), ns = stamp.path("nanosec");
		if ( !sec.isIntegralNumber() || !ns.isIntegralNumber() || !sec.canConvertToLong() || !ns.canConvertToLong()
				|| ns.longValue() < 0 || ns.longValue() >= 1_000_000_000 ) {
			return new Parsed(null, Rejection.TIMESTAMP_INVALID);
		}
		try { return new Parsed(new Mark(false, Instant.ofEpochSecond(sec.longValue(), ns.longValue())), null); }
		catch ( java.time.DateTimeException | ArithmeticException e ) { return new Parsed(null, Rejection.TIMESTAMP_INVALID); }
	}

	private static long nanos(Duration duration) {
		try { return duration.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException("duration is too large: " + duration, e); }
	}
}
