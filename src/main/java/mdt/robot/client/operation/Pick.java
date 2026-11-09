package mdt.robot.client.operation;

import static mdt.robot.client.operation.OperationInputs.defaultTimeout;
import static mdt.robot.client.operation.OperationInputs.validateName;
import static mdt.robot.client.operation.OperationInputs.validatePose;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;
import utils.Preconditions;

/**
 * 열기 → 접근 → 하강 → 파지 → 들어 올리기를 순서대로 수행하는 조합 연산. Grasp에 들어 올리기를 더한 것이다.
 * 생성자는 통신하지 않으며 run() 또는 start()/get()으로 한 번 실행한다.
 * 목표 자세는 서버 기본 EE 프레임 기준의 절대 pose다. 자동 좌표 변환이나 높이 계산은 하지 않는다.
 * <p>
 * {@link HoldCheck}를 주면 파지 직후(MEASURE_GRIP)와 들어 올린 뒤(MEASURE_HELD)에 물린 위치(물체 중심 - 도구 기준점)를 잰다.
 * 두 값을 각 측정의 도구 자세로 도구 좌표계에 옮겨 비교하고, 차이가 {@code HoldCheck.toleranceM} 이하이면 물체가
 * 그리퍼와 함께 올라온 것으로 본다. 도구 좌표계에서 비교하므로 들어 올리는 동안 손목이 돌아도 단단히 쥔 물체는
 * 차이가 생기지 않는다. 그리퍼의 파지 판정({@code at_goal} = 물체에 막혀 멈춤)은 원하는 물체를 들었는지까지
 * 판정하지 않으므로 장면 관측으로 확인하는 것이다. 물체가 탁자에 남았거나(마찰 설정 누락 등) 다른 물체를 물었으면
 * 차이가 들어 올린 거리만큼 벌어진다.
 * <p>
 * 그래서 두 측정 사이에 도구 기준점이 실제로 {@code 2 × toleranceM} 이상 움직여야 판정할 수 있다. 덜 움직였으면
 * (상승 거리가 짧거나, 상승 뒤 관측이 갱신되지 않아 상승 전 값이 다시 왔으면) 판정 불가(UNDETERMINED)로 보고 실패 처리한다.
 * 생성자도 descent·lift가 축별 허용 오차 안에서 덜 갔을 때의 최소 상승이 이 거리보다 짧으면 거부한다
 * (수직 상승이면 명령 거리 ≥ 2 × toleranceM + 두 허용 오차, 기본 30 mm. 대각선이면 더 길어야 한다).
 * 판정 결과는 {@link Check#holdVerdict()}와 {@link Progress#holdVerdict()}로 읽는다. MEASURE_HELD 단계 기록의
 * goalReached는 측정 자체의 성공 여부이며 유지 판정이 아니다.
 * 이 판정은 물체 형상을 쓰지 않으며 미끄러짐의 원인이나 놓기 시점의 유지 여부는 판정하지 않는다.
 * Isaac에서 물체 TF가 멈추면 상승 뒤에도 물체가 멈춘 자리로 보고되어, 실제로 들었어도 NOT_HELD(ee_pose도
 * 멈췄으면 UNDETERMINED)로 실패할 수 있다({@link MeasureObjectOffset}의 Isaac 한계 참고).
 * <p>
 * 단계별 목표 확인 실패와 유지 판정 실패는 goalReached=false 결과로 정상 완료한다. 접수 거부·통신 오류는
 * FAILED/ExecutionException, 외부 인터럽트는 CANCELLED이며 run()은 InterruptedException을 전달한다.
 * graspAttempted는 GRASP 하위 실행 진입 직전에 기록한다. 들기 전에 판정이 필요한 작업(peg 삽입의 기하 확인 등)은
 * Pick 대신 Grasp를 쓰고 판정 뒤에 따로 들어 올린다. 취소·자원 점유 계약은 Grasp와 같다.
 */
public final class Pick extends AbstractStepSequenceOperation<Pick.Stage, Pick.Check> {
	public enum Stage { OPEN, APPROACH, DESCEND, GRASP, MEASURE_GRIP, LIFT, MEASURE_HELD }

	/** 유지 판정에 필요한 최소 상승 거리 = 이 값 × HoldCheck.toleranceM. */
	static final double MIN_LIFT_FACTOR = 2.0;

	/**
	 * 이동·그리퍼 단계의 옵션이다. 유지 판정의 옵션은 {@link HoldCheck}에 있다.
	 *
	 * @param approach		접근 이동 옵션.
	 * @param descent		하강 이동 옵션.
	 * @param lift			들어 올리기 이동 옵션.
	 * @param openTarget	개방 목표 이름.
	 * @param graspTarget	파지 목표 이름.
	 */
	public record Options(MoveLinear.Options approach, MoveLinear.Options descent, MoveLinear.Options lift,
			String openTarget, String graspTarget) {
		/** 각 이동 뒤 최대 10초 정착 관측. */
		public static final Options DEFAULT = new Options(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)), "open", "grasp");

		public Options {
			Preconditions.checkNotNullArgument(approach, "approach options is null");
			Preconditions.checkNotNullArgument(descent, "descent options is null");
			Preconditions.checkNotNullArgument(lift, "lift options is null");
			validateName(openTarget, "openTarget");
			validateName(graspTarget, "graspTarget");
		}

		public Options withLift(MoveLinear.Options value) {
			return new Options(approach, descent, value, openTarget, graspTarget);
		}
	}

	/**
	 * 들기 전후 물린 위치를 비교하는 유지 판정의 설정이다. 이 값들은 함께일 때만 뜻이 있다.
	 * <p>
	 * 유지 판정은 tcpReference와 무관하다. 두 물린 위치의 차이에서 도구 기준점의 고정 오프셋이 상쇄되기 때문이다.
	 * tcpReference는 {@link Hold}가 보고하는 물린 위치 값(놓기 자세 계산에 쓰는 값)에만 영향을 준다.
	 * 펑션베이는 AS_REPORTED, Isaac Panda Hand는 PANDA_HAND_TCP를 쓴다.
	 *
	 * @param objectName	물린 위치를 잴 물체의 scene_objects 이름.
	 * @param tcpReference	측정의 도구 기준점.
	 * @param measure		물린 위치 측정 옵션.
	 * @param toleranceM	들기 전후 물린 위치 차이(도구 좌표계)의 허용값(m). 유한한 양수.
	 * 						descent·lift가 축별 허용 오차 안에서 덜 가도 실제 상승이 이 값의 2배 이상이어야 한다.
	 * 						수직 상승이면 명령 거리 ≥ 2 × toleranceM + 두 허용 오차(기본 30 mm)다.
	 */
	public record HoldCheck(String objectName, MeasureObjectOffset.TcpReference tcpReference,
			MeasureObjectOffset.Options measure, double toleranceM) {
		/** 기본 허용값 10 mm. */
		public static final double DEFAULT_TOLERANCE_M = 0.01;

		public HoldCheck {
			validateName(objectName, "objectName");
			Preconditions.checkNotNullArgument(tcpReference, "tcpReference is null");
			Preconditions.checkNotNullArgument(measure, "measure options is null");
			Preconditions.checkArgument(Double.isFinite(toleranceM) && toleranceM > 0, "toleranceM must be finite and positive");
		}

		/** 기본 측정 옵션과 허용값 10 mm로 물체를 잰다. */
		public static HoldCheck of(String objectName, MeasureObjectOffset.TcpReference tcpReference) {
			return new HoldCheck(objectName, tcpReference, MeasureObjectOffset.Options.DEFAULT, DEFAULT_TOLERANCE_M);
		}

		public HoldCheck withMeasure(MeasureObjectOffset.Options value) {
			return new HoldCheck(objectName, tcpReference, value, toleranceM);
		}
		public HoldCheck withTolerance(double value) {
			return new HoldCheck(objectName, tcpReference, measure, value);
		}
	}

	/** 유지 판정의 결과. */
	public enum HoldVerdict {
		/** 물체가 그리퍼와 함께 올라왔다(차이가 허용값 이하). */
		HELD,
		/** 물체가 따라오지 않았다(차이가 허용값 초과). 탁자에 남았거나 다른 물체를 물었다. */
		NOT_HELD,
		/** 두 측정 사이 도구 기준점 이동이 판정 하한보다 짧았다. 상승이 짧거나 상승 뒤 관측이 갱신되지 않았다. */
		UNDETERMINED,
		/** 판정하지 않았다. HoldCheck를 주지 않았거나 두 측정을 마치기 전에 끝났다(또는 아직 진행 중). */
		NOT_CHECKED
	}

	/**
	 * 들기 전후의 물린 위치 측정과 그 차이.
	 *
	 * @param beforeLift	파지 직후의 측정.
	 * @param afterLift		들어 올린 뒤의 측정. 놓기 자세 계산에 쓰는 물린 위치는 이 값이다.
	 * @param liftM			두 측정 사이 도구 기준점이 실제로 움직인 거리(m).
	 * @param driftM		두 물린 위치(물체 중심 - 도구 기준점)를 도구 좌표계에서 비교한 차이의 크기(m).
	 */
	public record Hold(MeasureObjectOffset.Measurement beforeLift, MeasureObjectOffset.Measurement afterLift,
						double liftM, double driftM) { }

	/**
	 * goalReached는 모든 단계의 목표와 (HoldCheck를 줬다면) 유지 판정을 확인했는지다. graspAttempted는 GRASP 실행 시도 여부다.
	 * holdVerdict는 유지 판정 결과이며, HoldCheck를 줬을 때 goalReached=true는 holdVerdict=HELD와 같다.
	 * hold는 두 측정을 모두 마쳤을 때만 있다. 실제 물체의 안정적인 유지를 보장하지 않는다.
	 */
	public record Check(boolean goalReached, @Nullable Stage failedStage, @Nullable String failureReason, List<StepResult<Stage>> steps,
			boolean graspAttempted, HoldVerdict holdVerdict, Optional<Hold> hold) implements StepVerdict {
		@Override public @Nullable String failureReason() { return goalReached ? null : failureReason; }
		public Check {
			steps = List.copyOf(steps);
			Preconditions.checkNotNullArgument(holdVerdict, "holdVerdict is null");
		}
	}

	/**
	 * 공통 스냅샷에서 도메인 판정을 읽는다. 별도의 가변 상태를 갖지 않는다.
	 * holdVerdict는 두 측정을 마친 뒤부터 결과의 holdVerdict와 같고, 그 전에는 NOT_CHECKED다.
	 */
	public static final class Progress extends StepwiseProgress<Stage> {
		private final HoldVerdict m_holdVerdict;

		private Progress(StepwiseProgress<Stage> source, HoldVerdict holdVerdict) {
			super(source);
			m_holdVerdict = holdVerdict;
		}
		public boolean graspAttempted() { return enteredStages().contains(Stage.GRASP); }
		public HoldVerdict holdVerdict() { return m_holdVerdict; }
	}

	private final @Nullable HoldCheck m_holdCheck;

	/** 물린 위치를 재지 않는다. 들어 올린 뒤의 유지는 판정하지 않는다. */
	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose) {
		this(twin, approachPose, graspPose, liftPose, null, Options.DEFAULT);
	}

	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose, @Nullable HoldCheck holdCheck,
				Options options) {
		this(twin, approachPose, graspPose, liftPose, holdCheck, options, defaultTimeout(twin));
	}

	/**
	 * @param liftPose		들어 올릴 자세. 흔히 접근 자세와 같다.
	 * @param holdCheck		유지 판정 설정. {@code null}이면 물린 위치를 재지 않는다.
	 * @param timeout		각 하위 원격 비동기 연산의 대기 상한이며 전체 Pick 실행 상한이 아니다.
	 * @throws IllegalArgumentException	입력이 유효하지 않은 경우. 첫 개방 전에 모두 검사한다.
	 */
	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose, @Nullable HoldCheck holdCheck,
				Options options, Duration timeout) {
		super(sequence(twin, approachPose, graspPose, liftPose, holdCheck, options, timeout));
		m_holdCheck = holdCheck;
	}

	/**
	 * 인자를 역할 이름으로 지정하는 빌더. 여러 Pose를 위치 인자로 넘길 때 접근·파지·상승을 뒤바꾸는 실수를 막는다.
	 * approachPose·graspPose는 필수다. liftPose를 생략하면 approachPose로 들어 올리고, holdCheck를 생략하면 재지 않는다.
	 * options 기본값은 {@link Options#DEFAULT}, timeout 기본값은 클라이언트의 operationTimeout이다.
	 * 검증 규칙은 생성자와 같고 build()에서 통신 전에 한다.
	 *
	 * <pre>{@code
	 * Pick pick = Pick.builder(twin).approachPose(above).graspPose(grasp)
	 *                 .holdCheck(Pick.HoldCheck.of("box", TcpReference.PANDA_HAND_TCP)).build();
	 * }</pre>
	 */
	public static Builder builder(RobotTwinClient twin) {
		return new Builder(twin);
	}

	public static final class Builder {
		private final RobotTwinClient m_twin;
		private @Nullable Pose m_approachPose;
		private @Nullable Pose m_graspPose;
		private @Nullable Pose m_liftPose;
		private @Nullable HoldCheck m_holdCheck;
		private Options m_options = Options.DEFAULT;
		private @Nullable Duration m_timeout;

		private Builder(RobotTwinClient twin) {
			Preconditions.checkNotNullArgument(twin, "twin is null");
			m_twin = twin;
		}

		public Builder approachPose(Pose value) { m_approachPose = value; return this; }
		public Builder graspPose(Pose value) { m_graspPose = value; return this; }
		/** 생략하면 approachPose로 들어 올린다. */
		public Builder liftPose(Pose value) { m_liftPose = value; return this; }
		/** null이면 물린 위치를 재지 않는다. */
		public Builder holdCheck(@Nullable HoldCheck value) { m_holdCheck = value; return this; }
		public Builder options(Options value) { m_options = value; return this; }
		/** 각 하위 원격 비동기 연산의 대기 상한. 전체 Pick 실행 상한이 아니다. */
		public Builder timeout(Duration value) { m_timeout = value; return this; }

		/** @throws IllegalArgumentException 필수 자세가 없거나 입력이 유효하지 않은 경우. */
		public Pick build() {
			Preconditions.checkArgument(m_approachPose != null, "approachPose is not set");
			Preconditions.checkArgument(m_graspPose != null, "graspPose is not set");
			Pose lift = m_liftPose != null ? m_liftPose : m_approachPose;
			return new Pick(m_twin, m_approachPose, m_graspPose, lift, m_holdCheck, m_options,
							m_timeout != null ? m_timeout : defaultTimeout(m_twin));
		}
	}

	private static List<Step<Stage>> sequence(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose,
			@Nullable HoldCheck holdCheck, Options options, Duration timeout) {
		Preconditions.checkNotNullArgument(options, "options is null");
		validatePose(approachPose);
		validatePose(graspPose);
		validatePose(liftPose);
		if ( holdCheck != null ) {
			// 판정은 측정한 도구 기준점의 이동(유클리드 거리)으로 하는데, DESCEND·LIFT는 각각 축별 허용 오차
			// (MoveLinear의 maxAxisDistance) 안이면 도달로 인정된다. 두 끝점이 그 상자 안에서 서로 가장 가까워졌을 때의
			// 거리가 판정 하한(2 × toleranceM) 이상이어야 한다. 축마다 max(0, |dᵢ| − 두 허용 오차)를 구한 벡터의 크기가
			// 그 최소 거리다. 수직 상승이면 '명령 거리 ≥ 하한 + 두 허용 오차'와 같다.
			double lift = distance(graspPose.position(), liftPose.position());
			double slack = options.descent().positionToleranceM() + options.lift().positionToleranceM();
			double shortest = shortestReachedDistance(graspPose.position(), liftPose.position(), slack);
			double needed = MIN_LIFT_FACTOR * holdCheck.toleranceM();
			// 경계값(예: 수직 30 mm)이 부동소수점 오차로 거부되지 않게 1e-9 m의 여유를 둔다.
			Preconditions.checkArgument(shortest + 1e-9 >= needed, String.format(
					"liftPose is too close to graspPose to check the hold: commanded %.1f mm, but within the "
					+ "descent and lift tolerances (%.1f mm per axis in total) the measured lift can be as short as "
					+ "%.1f mm, below the %.1f mm (%.0f × hold tolerance) the hold check needs",
					lift * 1000, slack * 1000, shortest * 1000, needed * 1000, MIN_LIFT_FACTOR));
		}

		List<Step<Stage>> steps = new ArrayList<>(List.of(
				new Step<>(Stage.OPEN, new MoveGripperToNamedTarget(twin, options.openTarget(), timeout)),
				new Step<>(Stage.APPROACH, new MoveLinear(twin, approachPose, options.approach(), timeout)),
				new Step<>(Stage.DESCEND, new MoveLinear(twin, graspPose, options.descent(), timeout)),
				new Step<>(Stage.GRASP, new MoveGripperToNamedTarget(twin, options.graspTarget(), timeout))));
		if ( holdCheck != null ) {
			steps.add(new Step<>(Stage.MEASURE_GRIP, measure(twin, holdCheck)));
		}
		steps.add(new Step<>(Stage.LIFT, new MoveLinear(twin, liftPose, options.lift(), timeout)));
		if ( holdCheck != null ) {
			steps.add(new Step<>(Stage.MEASURE_HELD, measure(twin, holdCheck)));
		}
		return steps;
	}

	private static MeasureObjectOffset measure(RobotTwinClient twin, HoldCheck holdCheck) {
		return new MeasureObjectOffset(twin, holdCheck.objectName(), holdCheck.tcpReference(), holdCheck.measure());
	}

	@Override public Progress getProgress() {
		StepwiseProgress<Stage> snapshot = snapshot();
		return new Progress(snapshot, judgeHold(snapshot.steps()).verdict());
	}

	@Override protected Check createResult(@Nullable StepResult<Stage> failedStep, StepwiseProgress<Stage> progress) {
		List<StepResult<Stage>> steps = progress.steps();
		boolean attempted = progress.enteredStages().contains(Stage.GRASP);
		if ( failedStep != null ) {
			return new Check(false, failedStep.stage(), failedStep.failureReason(), steps, attempted, HoldVerdict.NOT_CHECKED,
							Optional.empty());
		}
		HoldJudgement judged = judgeHold(steps);
		return switch ( judged.verdict() ) {
			// HoldCheck 없이 만들어 재지 않았다.
			case NOT_CHECKED -> new Check(true, null, null, steps, attempted, HoldVerdict.NOT_CHECKED, Optional.empty());
			case HELD -> new Check(true, null, null, steps, attempted, HoldVerdict.HELD, judged.hold());
			case NOT_HELD, UNDETERMINED -> new Check(false, Stage.MEASURE_HELD, judged.failureReason(), steps, attempted,
													judged.verdict(), judged.hold());
		};
	}

	private record HoldJudgement(HoldVerdict verdict, @Nullable String failureReason, Optional<Hold> hold) { }

	/** 두 측정이 모두 있을 때만 판정한다. getProgress()와 createResult()가 같은 판정을 쓴다. */
	private HoldJudgement judgeHold(List<StepResult<Stage>> steps) {
		Optional<MeasureObjectOffset.Measurement> before = measurementOf(steps, Stage.MEASURE_GRIP);
		Optional<MeasureObjectOffset.Measurement> after = measurementOf(steps, Stage.MEASURE_HELD);
		if ( m_holdCheck == null || before.isEmpty() || after.isEmpty() ) {
			return new HoldJudgement(HoldVerdict.NOT_CHECKED, null, Optional.empty());
		}
		double tolerance = m_holdCheck.toleranceM();
		MeasureObjectOffset.Measurement b = before.get(), a = after.get();
		double lift = distance(b.tcpPose().position(), a.tcpPose().position());
		double drift = distance(toolFrameOffset(b), toolFrameOffset(a));
		Optional<Hold> hold = Optional.of(new Hold(b, a, lift, drift));
		double minLift = MIN_LIFT_FACTOR * tolerance;
		if ( lift < minLift ) {
			String why = String.format("유지 판정 불가: 두 측정 사이 도구 기준점 이동 %.1f mm (필요 %.1f mm 이상). "
										+ "상승이 짧거나 상승 뒤 관측이 갱신되지 않았다", lift * 1000, minLift * 1000);
			return new HoldJudgement(HoldVerdict.UNDETERMINED, why, hold);
		}
		if ( drift > tolerance ) {
			String why = String.format("물체가 그리퍼와 함께 움직이지 않았다: 들기 전후 물린 위치 차이 %.1f mm (허용 %.1f mm)",
										drift * 1000, tolerance * 1000);
			return new HoldJudgement(HoldVerdict.NOT_HELD, why, hold);
		}
		return new HoldJudgement(HoldVerdict.HELD, null, hold);
	}

	private static Optional<MeasureObjectOffset.Measurement> measurementOf(List<StepResult<Stage>> steps, Stage stage) {
		return steps.stream().filter(s -> s.stage() == stage)
					.flatMap(s -> s.verdictAs(MeasureObjectOffset.Result.class).stream())
					.findFirst().flatMap(MeasureObjectOffset.Result::measurement);
	}

	/** 물린 위치를 측정 시점의 도구 자세로 역회전한다. 단단히 쥔 물체는 손목이 돌아도 이 값이 같다. */
	private static Position toolFrameOffset(MeasureObjectOffset.Measurement m) {
		Quaternion q = m.tcpPose().orientation();
		double[] v = new Quaternion(-q.x(), -q.y(), -q.z(), q.w()).rotate(m.offset().x(), m.offset().y(), m.offset().z());
		return new Position(v[0], v[1], v[2]);
	}

	/** 두 위치가 각 축으로 최대 slack만큼 서로 가까워질 수 있을 때의 최소 거리. */
	static double shortestReachedDistance(Position a, Position b, double slack) {
		double gx = Math.max(0, Math.abs(a.x() - b.x()) - slack);
		double gy = Math.max(0, Math.abs(a.y() - b.y()) - slack);
		double gz = Math.max(0, Math.abs(a.z() - b.z()) - slack);
		return Math.sqrt(gx * gx + gy * gy + gz * gz);
	}

	private static double distance(Position a, Position b) {
		return Math.sqrt(square(a.x() - b.x()) + square(a.y() - b.y()) + square(a.z() - b.z()));
	}

	private static double square(double v) { return v * v; }
}
