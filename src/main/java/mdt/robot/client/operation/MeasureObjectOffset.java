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

import mdt.robot.client.PandaHand;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * ee_pose와 scene_objects를 배치 조회하여 물체 중심 - 도구 기준점 오프셋(m)을 측정한다.
 * 생성자는 통신하지 않는다. 한 번 실행하며 로봇 명령이나 원격 취소를 보내지 않는다.
 * <p>
 * 두 응답 모두 quality=OK, 시간대가 있는 received_at, 명시적이고 동일한 header.frame_id가
 * 필요하다. 수신 시각 차이가 maxTimeSkew 이하일 때만 측정한다. stamp는 서로 다른 시계일 수 있어
 * 비교에 사용하지 않는다. 서버의 quality/staleness 판정을 신뢰하며, 같은 배치 요청이나 가까운
 * 수신 시각이 센서의 동시 측정·물체 정착·파지 성공을 증명하지는 않는다.
 * <p>
 * <b>Isaac의 한계:</b> Isaac scene 노드는 주기마다 TF 버퍼의 최신 변환을 읽어 현재 시각으로 다시 발행하며
 * 변환 자체의 시각은 보지 않는다. tf2는 새 변환이 들어올 때만 오래된 값을 지우므로, Isaac 쪽 TF가 멈추면
 * 마지막 값이 새 received_at과 quality=OK로 계속 온다.
 * 그러면 수신 시각 차이 검사를 통과해도 물체 위치는 멈춘 시점의 값이다.
 * <p>
 * 도구 기준점은 호출자가 명시한다. AS_REPORTED는 펑션베이 grasp_center처럼 ee_pose를 그대로
 * 쓰며 손끝이라고 가정하지 않는다. PANDA_HAND_TCP는 PandaHand.tcpFromArmPose를 사용한다.
 * 표현 좌표계의 변환은 하지 않는다. 오프셋은 공통 header 좌표계의 벡터이며, 손목 회전·미끄러짐
 * 후에는 다시 측정한다. 측정 전 상승이나 정착 대기는 태스크가 수행한다.
 * <p>
 * 사용할 수 없는 응답은 timeout까지 재조회한다. 상한은 TIMEOUT 결과, 통신 오류는 FAILED다.
 * 늦은 응답은 측정에 쓰지 않는다. 취소·인터럽트 계약은 AbstractObservationOperation을 따른다.
 * 실패·취소 후에도 진단은 남지만, 완료되지 않은 측정 후보는 공개하지 않는다.
 */
public final class MeasureObjectOffset
		extends AbstractObservationOperation<MeasureObjectOffset.Result, MeasureObjectOffset.Result> {
	public enum TcpReference {
		/** 보고된 기준점 그대로. 펑션베이는 grasp_center이며 실제 손끝과 다르다. */
		AS_REPORTED,
		/** Isaac Panda Hand 관측 규약 전용. 자동 백엔드 판별은 하지 않는다. */
		PANDA_HAND_TCP
	}

	public record Options(Duration timeout, Duration maxTimeSkew, Duration poll) {
		/**
		 * 100ms는 수신 시각이 가까운 쌍을 선택하는 허용값이며 발행 주기나 실측 동시성을 보장하지 않는다.
		 * 제공 설정은 ee_pose 50Hz, Isaac·펑션베이 scene_objects 2Hz이므로 최신 캐시 쌍도 거부될 수 있다.
		 * 실제 발행 간격·lastTimeSkew·거부 수를 보고 poll/timeout을 조정한다. 허용 시각 차이는 자동 확대하지 않는다.
		 */
		public static final Options DEFAULT = new Options(Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMillis(100));
		public Options {
			Preconditions.checkNotNullArgument(timeout, "timeout is null");
			Preconditions.checkNotNullArgument(maxTimeSkew, "maxTimeSkew is null");
			Preconditions.checkNotNullArgument(poll, "poll is null");
			Preconditions.checkArgument(!timeout.isNegative(), "timeout must not be negative");
			Preconditions.checkArgument(!maxTimeSkew.isNegative(), "maxTimeSkew must not be negative");
			Preconditions.checkArgument(!poll.isNegative() && !poll.isZero(), "poll must be positive");
			nanos(timeout); nanos(maxTimeSkew); nanos(poll);
		}
	}

	public enum Outcome { NOT_STARTED, OBSERVING, MEASURED, TIMEOUT, CANCELLED, FAILED }
	public enum Rejection {
		EE_RESPONSE_MISSING, SCENE_RESPONSE_MISSING, EE_QUALITY_NOT_OK, SCENE_QUALITY_NOT_OK,
		EE_TIMESTAMP_INVALID, SCENE_TIMESTAMP_INVALID, EE_FRAME_INVALID, SCENE_FRAME_INVALID,
		FRAME_MISMATCH, TIME_SKEW_TOO_LARGE, EE_POSITION_INVALID, EE_ORIENTATION_INVALID,
		OBJECT_MISSING, OBJECT_POSITION_INVALID, NON_FINITE_OFFSET
	}

	/**
	 * 집계 단위는 배치 HTTP 응답 하나(두 변수의 쌍)다.
	 * 유효 쌍을 처음 발견하면 종료하므로 중복 표본·구간 재시작은 없고 newSamples=validSamples다.
	 * 거부 응답은 첫 사유 하나로 집계한다. lastTimeSkew는 마지막으로 시각 차이를 계산한 시도의 값이다.
	 */
	public record Diagnostics(long reads, long validSamples, long rejectedSamples, long lateSamples,
			Map<Rejection,Long> rejectionCounts, @Nullable Rejection lastRejection,
			@Nullable Duration lastTimeSkew) implements ObservationDiagnostics<Rejection> {
		public Diagnostics { rejectionCounts = Map.copyOf(rejectionCounts); }
		@Override public long newSamples() { return validSamples; }
		@Override public long duplicateSamples() { return 0; }
		@Override public long resetCount() { return 0; }
	}

	/**
	 * offset = objectCenter - tcpPose.position, 단위 m, frame 좌표계.
	 * Python stack.held_offset()의 TCP - 물체 중심과 부호가 반대이며, Z 성분은 Python peg gap과 같은 부호다.
	 * 두 sample은 이 측정에 사용한 원본 응답의 사본이며 읽기 전용으로 취급한다.
	 * tcpPose의 쿼터니언만 계산용으로 정규화한다(norm 오차 0.001 이하).
	 */
	public record Measurement(TcpReference tcpReference, String frame, Pose tcpPose, Position objectCenter,
			Position offset, Instant eeReceivedAt, Instant sceneReceivedAt, Duration timeSkew,
			JsonNode eeSample, JsonNode sceneSample) {
		/**
		 * 물체 중심을 지정 위치에 두기 위한 도구 기준점 위치(targetObjectCenter - offset)를 계산한다.
		 * 입력과 반환 위치는 이 측정의 frame 좌표계이며, 측정 당시 도구 자세와 파지 관계가 유지돼야 한다.
		 * 손목 회전·미끄러짐 이후에는 다시 측정한다. AS_REPORTED의 기준점은 실제 손끝이 아닐 수 있다.
		 * 좌표계 변환, 이동 명령 기준점 변환, 경로/충돌 검사는 수행하지 않는 순수 계산이다.
		 *
		 * @param targetObjectCenter 목표 물체 중심 위치(m).
		 * @return 측정 때 선택한 도구 기준점의 목표 위치(m). 자세와 이동 명령은 포함하지 않는다.
		 * @throws IllegalArgumentException 목표 또는 offset이 null/비유한 값이거나 계산 결과가 비유한 경우.
		 */
		public Position toolPositionFor(Position targetObjectCenter) {
			requireFinitePosition(targetObjectCenter, "targetObjectCenter");
			requireFinitePosition(offset, "offset");
			Position target = targetObjectCenter.plus(-offset.x(), -offset.y(), -offset.z());
			requireFinitePosition(target, "tool target");
			return target;
		}

		private static void requireFinitePosition(Position position, String name) {
			Preconditions.checkNotNullArgument(position, name + " is null");
			Preconditions.checkArgument(Double.isFinite(position.x()) && Double.isFinite(position.y())
					&& Double.isFinite(position.z()), name + " coordinates must be finite");
		}
	}

	/** MEASURED에서만 measurement가 있다. 물체 파지·정착이나 후속 이동 가능 여부의 판정이 아니다. */
	public record Result(String objectName, Outcome outcome, Optional<Measurement> measurement,
			Duration elapsed, Diagnostics diagnostics) implements StepVerdict {
		@Override public boolean goalReached() { return outcome == Outcome.MEASURED; }
		@Override public @Nullable String failureReason() { return goalReached() ? null : "물체·도구 기준점 측정 불가: " + summary(); }
		public String summary() {
			return outcome + ": " + objectName + ", 유효 쌍 " + diagnostics.validSamples() + "건, 거부 "
					+ diagnostics.rejectedSamples() + "건, 상한 이후 " + diagnostics.lateSamples()
					+ "건, 마지막 거부=" + diagnostics.lastRejection() + ", 마지막 시각 차이=" + diagnostics.lastTimeSkew();
		}
	}

	private final RobotTwinClient m_twin;
	private final String m_objectName;
	private final TcpReference m_tcpReference;
	private final Options m_options;
	private final Stats m_stats = new Stats();
	private long m_started;
	private boolean m_observationStarted;

	public MeasureObjectOffset(RobotTwinClient twin, String objectName, TcpReference tcpReference) {
		this(twin, objectName, tcpReference, Options.DEFAULT);
	}

	public MeasureObjectOffset(RobotTwinClient twin, String objectName, TcpReference tcpReference, Options options) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		OperationInputs.validateName(objectName, "objectName");
		Preconditions.checkNotNullArgument(tcpReference, "tcpReference is null");
		Preconditions.checkNotNullArgument(options, "options is null");
		m_twin = twin;
		m_objectName = objectName;
		m_tcpReference = tcpReference;
		m_options = options;
		publishProgress(result(Outcome.NOT_STARTED, null));
	}

	@Override public Diagnostics getDiagnostics() { return getProgress().diagnostics(); }
	@Override protected void onObservationStarted() {
		m_started = System.nanoTime();
		m_observationStarted = true;
		publishProgress(result(Outcome.OBSERVING, null));
	}
	@Override protected void onObservationCompleted(Result result) { publishProgress(result); }
	@Override protected void onObservationStopped(boolean cancelled) {
		publishProgress(result(cancelled ? Outcome.CANCELLED : Outcome.FAILED, null));
	}

	@Override protected Result observe() throws InterruptedException {
		long timeout = nanos(m_options.timeout());
		while ( System.nanoTime() - m_started < timeout ) {
			checkStopped();
			Map<String,JsonNode> responses = m_twin.readMany("ee_pose", "scene_objects");
			checkStopped();
			++m_stats.reads;
			if ( System.nanoTime() - m_started >= timeout ) { ++m_stats.late; break; }
			Assessment assessment = assess(responses.get("ee_pose"), responses.get("scene_objects"));
			if ( assessment.timeSkew() != null ) { m_stats.lastTimeSkew = assessment.timeSkew(); }
			if ( assessment.measurement() != null ) {
				++m_stats.valid;
				return result(Outcome.MEASURED, assessment.measurement());
			}
			++m_stats.rejected;
			m_stats.lastRejection = assessment.rejection();
			m_stats.rejections.merge(assessment.rejection(), 1L, Long::sum);
			publishProgress(result(Outcome.OBSERVING, null));
			long remaining = timeout - (System.nanoTime() - m_started);
			long delay = Math.min(nanos(m_options.poll()), Math.max(0, remaining));
			if ( delay > 0 ) { Thread.sleep(delay / 1_000_000, (int)(delay % 1_000_000)); }
		}
		return result(Outcome.TIMEOUT, null);
	}

	private Result result(Outcome outcome, @Nullable Measurement measurement) {
		Duration elapsed = m_observationStarted ? Duration.ofNanos(System.nanoTime() - m_started) : Duration.ZERO;
		return new Result(m_objectName, outcome, Optional.ofNullable(measurement), elapsed, m_stats.snapshot());
	}

	private static final class Stats {
		long reads, valid, rejected, late;
		Rejection lastRejection;
		Duration lastTimeSkew;
		final Map<Rejection,Long> rejections = new EnumMap<>(Rejection.class);
		Diagnostics snapshot() { return new Diagnostics(reads, valid, rejected, late, rejections, lastRejection, lastTimeSkew); }
	}

	private record Assessment(@Nullable Measurement measurement, @Nullable Rejection rejection, @Nullable Duration timeSkew) { }
	private static Assessment rejected(Rejection reason) { return new Assessment(null, reason, null); }

	private Assessment assess(@Nullable JsonNode ee, @Nullable JsonNode scene) {
		if ( ee == null || !ee.isObject() ) { return rejected(Rejection.EE_RESPONSE_MISSING); }
		if ( scene == null || !scene.isObject() ) { return rejected(Rejection.SCENE_RESPONSE_MISSING); }
		if ( !"OK".equals(ee.path("quality").asText()) ) { return rejected(Rejection.EE_QUALITY_NOT_OK); }
		if ( !"OK".equals(scene.path("quality").asText()) ) { return rejected(Rejection.SCENE_QUALITY_NOT_OK); }
		Instant eeTime = receivedAt(ee), sceneTime = receivedAt(scene);
		if ( eeTime == null ) { return rejected(Rejection.EE_TIMESTAMP_INVALID); }
		if ( sceneTime == null ) { return rejected(Rejection.SCENE_TIMESTAMP_INVALID); }
		Duration skew = Duration.between(eeTime, sceneTime).abs();
		if ( skew.compareTo(m_options.maxTimeSkew()) > 0 ) {
			return new Assessment(null, Rejection.TIME_SKEW_TOO_LARGE, skew);
		}
		JsonNode eeValue = ee.path("value"), sceneValue = scene.path("value");
		String eeFrame = frame(eeValue), sceneFrame = frame(sceneValue);
		if ( eeFrame == null ) { return new Assessment(null, Rejection.EE_FRAME_INVALID, skew); }
		if ( sceneFrame == null ) { return new Assessment(null, Rejection.SCENE_FRAME_INVALID, skew); }
		if ( !eeFrame.equals(sceneFrame) ) { return new Assessment(null, Rejection.FRAME_MISMATCH, skew); }
		JsonNode eePose = eeValue.path("pose");
		double[] p = vector(eePose.path("position"), "x", "y", "z");
		if ( p == null ) { return new Assessment(null, Rejection.EE_POSITION_INVALID, skew); }
		double[] q = vector(eePose.path("orientation"), "x", "y", "z", "w");
		if ( q == null ) { return new Assessment(null, Rejection.EE_ORIENTATION_INVALID, skew); }
		double norm = Math.hypot(Math.hypot(q[0], q[1]), Math.hypot(q[2], q[3]));
		if ( !Double.isFinite(norm) || Math.abs(norm - 1) > 0.001 ) {
			return new Assessment(null, Rejection.EE_ORIENTATION_INVALID, skew);
		}
		JsonNode object = sceneValue.path("objects").get(m_objectName);
		if ( object == null || !object.isObject() ) { return new Assessment(null, Rejection.OBJECT_MISSING, skew); }
		double[] center = vector(object.path("pose").path("position"), "x", "y", "z");
		if ( center == null ) { return new Assessment(null, Rejection.OBJECT_POSITION_INVALID, skew); }
		Pose reported = new Pose(new Position(p[0], p[1], p[2]), new Quaternion(q[0]/norm, q[1]/norm, q[2]/norm, q[3]/norm));
		Pose tcp = m_tcpReference == TcpReference.AS_REPORTED ? reported : PandaHand.tcpFromArmPose(reported);
		Position offset = new Position(center[0] - tcp.position().x(), center[1] - tcp.position().y(), center[2] - tcp.position().z());
		if ( !Double.isFinite(offset.x()) || !Double.isFinite(offset.y()) || !Double.isFinite(offset.z()) ) {
			return new Assessment(null, Rejection.NON_FINITE_OFFSET, skew);
		}
		return new Assessment(new Measurement(m_tcpReference, eeFrame, tcp, new Position(center[0], center[1], center[2]),
				offset, eeTime, sceneTime, skew, ee.deepCopy(), scene.deepCopy()), null, skew);
	}

	private static @Nullable Instant receivedAt(JsonNode body) {
		JsonNode time = body.get("received_at");
		if ( time == null || !time.isTextual() ) { return null; }
		try { return OffsetDateTime.parse(time.textValue()).toInstant(); }
		catch ( DateTimeParseException e ) { return null; }
	}
	private static @Nullable String frame(JsonNode value) {
		JsonNode frame = value.path("header").get("frame_id");
		if ( frame == null || !frame.isTextual() || frame.textValue().isBlank()
				|| !frame.textValue().equals(frame.textValue().strip()) ) { return null; }
		return frame.textValue();
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
	private static long nanos(Duration duration) {
		try { return duration.toNanos(); }
		catch ( ArithmeticException e ) { throw new IllegalArgumentException("observation duration is too large", e); }
	}
}
