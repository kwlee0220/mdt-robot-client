package mdt.robot.client.operation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.Geometry;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 쥔 원기둥의 <b>아래쪽 끝면 중심</b>을 입구(entry) 바로 위로 맞추는 되먹임 정렬. 하강은 포함하지 않는다.
 * 생성자는 통신하지 않으며 run() 또는 start()/get()으로 한 번 실행한다.
 * <p>
 * 한 회차는 측정 → 보정 이동 → 정착 확인이다. 첫 회차만 측정 전에 물체 정착을 먼저 기다린다
 * (이후 회차는 직전 회차의 정착 확인이 그 역할을 한다).
 * <ol>
 *   <li>SETTLE — 물체(원기둥)가 그리퍼 안에서 멈출 때까지 기다린다(첫 회차만).</li>
 *   <li>MEASURE — 물체와 도구 기준점(ee_pose)을 가까운 시각에 함께 잰다. 바닥점은 물체 자세를 반영해 계산한다.</li>
 *   <li>CORRECT — 도구 기준점을 (입구 − 바닥점)만큼 옮긴 위치로 이동한다. 높이는 {@code travelZ},
 *       자세는 생성자에서 받은 <b>절대 자세</b>로 고정한다(현재 자세를 이어 쓰면 기울기가 쌓인다).</li>
 *   <li>VERIFY — 다시 멈출 때까지 기다린 뒤 바닥점과 입구의 수평 거리(오차)를 잰다.</li>
 * </ol>
 * 오차가 {@code acceptRadiusM} 미만이면 ALIGNED로 멈춘다. 그렇지 않고 직전 회차보다 줄지 않았으면
 * NOT_IMPROVING, 최대 회차에 닿으면 MAX_PASSES로 멈춘다 — 흔들수록 물체가 그리퍼 안에서 돌아앉아
 * 오히려 나빠지므로 무한정 반복하지 않는다. 판정은 <b>마지막으로 잰 실제 오차</b>로 한다. 이전 회차의 더 작은
 * 오차로 성공을 판정하지 않는다(그 자리에 있지 않기 때문이다).
 * <p>
 * 하위 단계가 목표를 확인하지 못하면(정착·측정 실패, 보정 이동 미도달) 그 자리에서 STEP_FAILED로 멈춘다.
 * robot-twin-client의 Python 정렬은 정착을 확인하지 못해도 마지막 관측으로 계속하지만, 이 연산은
 * 확인되지 않은 관측으로 다음 목표를 만들지 않는다. 정렬 실패는 예외가 아니라 {@code aligned=false} 결과다.
 * 접수 거부·통신 오류는 FAILED, 취소·인터럽트는 공통 순차 연산의 계약을 따른다.
 * <p>
 * 이동 명령의 기준점과 ee_pose가 같은 점이어야 한다(펑션베이 grasp_center). 측정은 ee_pose를 그대로 쓰며
 * 손끝 변환을 하지 않는다. 입구 좌표는 ee_pose와 scene_objects의 공통 좌표계(로봇 base)로 준다.
 * 착석·삽입은 판정하지 않으며 물체가 원기둥({@code type=cylinder})이어야 한다.
 */
public final class AlignHeldObject extends AbstractStepwiseOperation<AlignHeldObject.Stage, AlignHeldObject.Check> {
	public enum Stage { SETTLE, MEASURE, CORRECT, VERIFY }

	public enum StopReason {
		/** 오차가 허용 반경 안으로 들어왔다. */
		ALIGNED,
		/** 오차가 직전 회차보다 줄지 않았다. */
		NOT_IMPROVING,
		/** 최대 회차까지 보정했지만 허용 반경 밖이다. */
		MAX_PASSES,
		/** 하위 단계가 목표를 확인하지 못했다. */
		STEP_FAILED
	}

	/**
	 * @param maxPasses		최대 보정 회차. 1 이상.
	 * @param correction	보정 이동의 옵션. 개루프 배포에서는 정착 대기 상한이 양수여야 한다.
	 * @param still			정착 관측의 옵션. 발행이 멈추는 배포는 그 시간을 timeout에 포함한다.
	 * @param measure		물체·도구 기준점 측정의 옵션.
	 */
	public record Options(int maxPasses, MoveLinear.Options correction, WaitUntilObjectStill.Options still,
			MeasureObjectOffset.Options measure) {
		/** robot-twin-client peg_in_hole.py와 같은 값: 3회, 속도 0.15, 위치 허용 5 mm, 이동 정착 15초. */
		public static final Options DEFAULT = new Options(3, new MoveLinear.Options(0.005, 0.15, Duration.ofSeconds(15)),
				WaitUntilObjectStill.Options.DEFAULT, MeasureObjectOffset.Options.DEFAULT);

		public Options {
			Preconditions.checkArgument(maxPasses >= 1, "maxPasses must be positive: " + maxPasses);
			Preconditions.checkNotNullArgument(correction, "correction options is null");
			Preconditions.checkNotNullArgument(still, "still options is null");
			Preconditions.checkNotNullArgument(measure, "measure options is null");
		}

		public Options withMaxPasses(int value) { return new Options(value, correction, still, measure); }
		public Options withCorrection(MoveLinear.Options value) { return new Options(maxPasses, value, still, measure); }
		public Options withStill(WaitUntilObjectStill.Options value) { return new Options(maxPasses, correction, value, measure); }
		public Options withMeasure(MeasureObjectOffset.Options value) { return new Options(maxPasses, correction, still, value); }
	}

	/**
	 * 정렬 결과.
	 *
	 * @param aligned		마지막으로 잰 오차가 허용 반경 미만인지.
	 * @param stopReason	멈춘 이유.
	 * @param errorM		마지막으로 잰 바닥점-입구 수평 거리(m). 한 번도 재지 못했으면 {@code null}.
	 * @param passErrorsM	회차별 오차(m), 잰 순서대로.
	 * @param lastTarget	마지막으로 명령한(또는 하려던) 보정 목표. 측정 전에 멈췄으면 비어 있다.
	 * @param failedStage	STEP_FAILED일 때 목표를 확인하지 못한 단계. 그 밖에는 {@code null}.
	 * @param why			정렬하지 못한 이유. aligned이면 {@code null}.
	 * @param steps			단계 기록.
	 */
	public record Check(boolean aligned, StopReason stopReason, @Nullable Double errorM, List<Double> passErrorsM,
			Optional<Pose> lastTarget, @Nullable Stage failedStage, @Nullable String why, List<StepResult<Stage>> steps) {
		public Check {
			passErrorsM = List.copyOf(passErrorsM);
			steps = List.copyOf(steps);
		}
		public int passes() { return passErrorsM.size(); }
	}

	private final RobotTwinClient m_twin;
	private final String m_objectName;
	private final Position m_entry;
	private final double m_travelZ;
	private final Quaternion m_orientation;
	private final double m_acceptRadius;
	private final Options m_options;
	private final Duration m_timeout;

	public AlignHeldObject(RobotTwinClient twin, String objectName, Position entry, double travelZ,
							Quaternion orientation, double acceptRadiusM) {
		this(twin, objectName, entry, travelZ, orientation, acceptRadiusM, Options.DEFAULT);
	}

	public AlignHeldObject(RobotTwinClient twin, String objectName, Position entry, double travelZ,
							Quaternion orientation, double acceptRadiusM, Options options) {
		this(twin, objectName, entry, travelZ, orientation, acceptRadiusM, options, OperationInputs.defaultTimeout(twin));
	}

	/**
	 * @param twin			연결 대상.
	 * @param objectName	쥔 원기둥의 scene_objects 이름.
	 * @param entry			바닥점을 맞출 입구 위치. x, y만 쓴다.
	 * @param travelZ		보정 이동의 도구 기준점 높이(m).
	 * @param orientation	보정 이동의 절대 자세.
	 * @param acceptRadiusM	정렬로 인정하는 수평 오차 상한(m). 예: 구멍 반경 여유 × 0.4.
	 * @param options		회차·하위 연산 옵션.
	 * @param timeout		보정 이동의 원격 결과 대기 상한. 정착·측정 상한은 options에 따른다.
	 * @throws IllegalArgumentException	입력이 유효하지 않은 경우. 통신 전에 검사한다.
	 */
	public AlignHeldObject(RobotTwinClient twin, String objectName, Position entry, double travelZ,
							Quaternion orientation, double acceptRadiusM, Options options, Duration timeout) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		OperationInputs.validateName(objectName, "objectName");
		Preconditions.checkNotNullArgument(entry, "entry is null");
		Preconditions.checkNotNullArgument(orientation, "orientation is null");
		OperationInputs.validatePose(new Pose(entry, orientation));
		Preconditions.checkArgument(Double.isFinite(travelZ), "travelZ must be finite");
		Preconditions.checkArgument(Double.isFinite(acceptRadiusM) && acceptRadiusM > 0,
									"acceptRadiusM must be finite and positive");
		Preconditions.checkNotNullArgument(options, "options is null");
		Preconditions.checkNotNullArgument(timeout, "timeout is null");
		Preconditions.checkArgument(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive");

		m_twin = twin;
		m_objectName = objectName;
		m_entry = entry;
		m_travelZ = travelZ;
		m_orientation = orientation;
		m_acceptRadius = acceptRadiusM;
		m_options = options;
		m_timeout = timeout;
	}

	@Override
	protected Optional<Step<Stage>> nextStep(StepwiseProgress<Stage> progress) {
		List<StepResult<Stage>> steps = progress.steps();
		if ( steps.isEmpty() ) {
			return Optional.of(still(Stage.SETTLE));
		}
		StepResult<Stage> last = steps.get(steps.size() - 1);
		return switch ( last.stage() ) {
			case SETTLE -> Optional.of(measure());
			case MEASURE -> Optional.of(new Step<>(Stage.CORRECT,
										new MoveLinear(m_twin, targetFor(last), m_options.correction(), m_timeout)));
			case CORRECT -> Optional.of(still(Stage.VERIFY));
			case VERIFY -> (stopReasonAfter(passErrors(steps)) == null) ? Optional.of(measure()) : Optional.empty();
		};
	}

	@Override
	protected Check createResult(@Nullable StepResult<Stage> failedStep, StepwiseProgress<Stage> progress) {
		List<StepResult<Stage>> steps = progress.steps();
		List<Double> errors = passErrors(steps);
		Double error = errors.isEmpty() ? null : errors.get(errors.size() - 1);
		Optional<Pose> target = lastTarget(steps);
		if ( failedStep != null ) {
			return new Check(false, StopReason.STEP_FAILED, error, errors, target, failedStep.stage(),
								failedStep.why(), steps);
		}
		// 실패 없이 끝났으면 마지막 단계가 정착 확인이므로 오차가 하나 이상 있다.
		double current = errors.get(errors.size() - 1);
		StopReason reason = stopReasonAfter(errors);
		String why = switch ( reason ) {
			case ALIGNED -> null;
			case NOT_IMPROVING -> String.format("오차가 더 줄지 않는다: %.3f mm (직전 %.3f mm), 허용 %.3f mm",
										current * 1000, errors.get(errors.size() - 2) * 1000, m_acceptRadius * 1000);
			case MAX_PASSES -> String.format("%d회 보정 후 오차 %.3f mm가 허용 %.3f mm 이상이다",
										errors.size(), current * 1000, m_acceptRadius * 1000);
			case STEP_FAILED -> throw new IllegalStateException("unexpected stop reason without a failed step");
		};
		return new Check(reason == StopReason.ALIGNED, reason, error, errors, target, null, why, steps);
	}

	/** 회차를 이어 가면 {@code null}. 마지막 정착 확인 뒤에만 의미가 있다. */
	private @Nullable StopReason stopReasonAfter(List<Double> errors) {
		double current = errors.get(errors.size() - 1);
		if ( current < m_acceptRadius ) { return StopReason.ALIGNED; }
		if ( errors.size() >= 2 && current >= errors.get(errors.size() - 2) ) { return StopReason.NOT_IMPROVING; }
		if ( errors.size() >= m_options.maxPasses() ) { return StopReason.MAX_PASSES; }
		return null;
	}

	private List<Double> passErrors(List<StepResult<Stage>> steps) {
		List<Double> errors = new ArrayList<>();
		for ( StepResult<Stage> step : steps ) {
			if ( step.stage() == Stage.VERIFY && step.goalReached() ) {
				JsonNode object = step.objectStill().object()
										.orElseThrow(() -> new IllegalStateException("settled sample has no object"));
				Position bottom = Geometry.bottomOf(object);
				errors.add(Math.hypot(bottom.x() - m_entry.x(), bottom.y() - m_entry.y()));
			}
		}
		return errors;
	}

	private Optional<Pose> lastTarget(List<StepResult<Stage>> steps) {
		for ( int i = steps.size() - 1; i >= 0; --i ) {
			if ( steps.get(i).stage() == Stage.MEASURE && steps.get(i).goalReached() ) {
				return Optional.of(targetFor(steps.get(i)));
			}
		}
		return Optional.empty();
	}

	/** 측정한 도구 기준점을 (입구 − 바닥점)만큼 수평으로 옮긴 보정 목표. */
	private Pose targetFor(StepResult<Stage> measured) {
		MeasureObjectOffset.Measurement m = measured.measurement().measurement()
										.orElseThrow(() -> new IllegalStateException("no measurement"));
		JsonNode object = m.sceneSample().path("value").path("objects").get(m_objectName);
		Preconditions.checkState(object != null, "measured scene has no object: %s", m_objectName);
		Position bottom = Geometry.bottomOf(object);
		Position tcp = m.tcpPose().position();
		return new Pose(new Position(tcp.x() + (m_entry.x() - bottom.x()), tcp.y() + (m_entry.y() - bottom.y()), m_travelZ),
						m_orientation);
	}

	private Step<Stage> still(Stage stage) {
		return new Step<>(stage, new WaitUntilObjectStill(m_twin, m_objectName, m_options.still()));
	}

	private Step<Stage> measure() {
		return new Step<>(Stage.MEASURE, new MeasureObjectOffset(m_twin, m_objectName,
											MeasureObjectOffset.TcpReference.AS_REPORTED, m_options.measure()));
	}
}
