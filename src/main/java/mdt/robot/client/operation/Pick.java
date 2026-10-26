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
 * 물체 이름을 주면 파지 직후(MEASURE_GRIP)와 들어 올린 뒤(MEASURE_HELD)에 물린 위치(물체 중심 - 도구 기준점)를 잰다.
 * 두 값을 각 측정의 도구 자세로 도구 좌표계에 옮겨 비교하고, 차이가 {@code holdToleranceM} 이하이면 물체가
 * 그리퍼와 함께 올라온 것으로 본다. 도구 좌표계에서 비교하므로 들어 올리는 동안 손목이 돌아도 단단히 쥔 물체는
 * 차이가 생기지 않는다. 그리퍼의 파지 판정({@code at_goal} = 물체에 막혀 멈춤)은 원하는 물체를 들었는지까지
 * 판정하지 않으므로 장면 관측으로 확인하는 것이다. 물체가 탁자에 남았거나(마찰 설정 누락 등) 다른 물체를 물었으면
 * 차이가 들어 올린 거리만큼 벌어진다.
 * <p>
 * 그래서 두 측정 사이에 도구 기준점이 실제로 {@code 2 × holdToleranceM} 이상 움직여야 판정할 수 있다. 덜 움직였으면
 * (상승 거리가 짧거나, 상승 뒤 관측이 갱신되지 않아 상승 전 값이 다시 왔으면) 판정 불가로 보고 실패 처리한다.
 * 생성자도 파지·상승 명령 위치가 이 거리보다 가까우면 거부한다.
 * 이 판정은 물체 형상을 쓰지 않으며 미끄러짐의 원인이나 놓기 시점의 유지 여부는 판정하지 않는다.
 * <p>
 * 단계별 목표 확인 실패와 유지 판정 실패는 goalsReached=false 결과로 정상 완료한다. 접수 거부·통신 오류는
 * FAILED/ExecutionException, 외부 인터럽트는 CANCELLED이며 run()은 InterruptedException을 전달한다.
 * graspAttempted는 GRASP 하위 실행 진입 직전에 기록한다. 들기 전에 판정이 필요한 작업(peg 삽입의 기하 확인 등)은
 * Pick 대신 Grasp를 쓰고 판정 뒤에 따로 들어 올린다. 취소·자원 점유 계약은 Grasp와 같다.
 */
public final class Pick extends AbstractStepwiseOperation<Pick.Stage, Pick.Check> {
	public enum Stage { OPEN, APPROACH, DESCEND, GRASP, MEASURE_GRIP, LIFT, MEASURE_HELD }

	/** 유지 판정에 필요한 최소 상승 거리 = 이 값 × holdToleranceM. */
	static final double MIN_LIFT_FACTOR = 2.0;

	/**
	 * @param approach		접근 이동 옵션.
	 * @param descent		하강 이동 옵션.
	 * @param lift			들어 올리기 이동 옵션.
	 * @param openTarget	개방 목표 이름.
	 * @param graspTarget	파지 목표 이름.
	 * @param measure		물린 위치 측정 옵션. 물체 이름을 줬을 때만 쓴다.
	 * @param holdToleranceM	들기 전후 물린 위치 차이(도구 좌표계)의 허용값(m). 유한한 양수.
	 * 						물체 이름을 주면 이 값의 2배 이상 들어 올려야 한다.
	 */
	public record Options(MoveLinear.Options approach, MoveLinear.Options descent, MoveLinear.Options lift,
			String openTarget, String graspTarget, MeasureObjectOffset.Options measure, double holdToleranceM) {
		/** 각 이동 뒤 최대 10초 정착 관측, 유지 허용 10 mm. */
		public static final Options DEFAULT = new Options(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)), "open", "grasp",
				MeasureObjectOffset.Options.DEFAULT, 0.01);

		public Options {
			Preconditions.checkNotNullArgument(approach, "approach options is null");
			Preconditions.checkNotNullArgument(descent, "descent options is null");
			Preconditions.checkNotNullArgument(lift, "lift options is null");
			validateName(openTarget, "openTarget");
			validateName(graspTarget, "graspTarget");
			Preconditions.checkNotNullArgument(measure, "measure options is null");
			Preconditions.checkArgument(Double.isFinite(holdToleranceM) && holdToleranceM > 0,
										"holdToleranceM must be finite and positive");
		}

		public Options withLift(MoveLinear.Options value) {
			return new Options(approach, descent, value, openTarget, graspTarget, measure, holdToleranceM);
		}
		public Options withMeasure(MeasureObjectOffset.Options value) {
			return new Options(approach, descent, lift, openTarget, graspTarget, value, holdToleranceM);
		}
		public Options withHoldTolerance(double value) {
			return new Options(approach, descent, lift, openTarget, graspTarget, measure, value);
		}
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
	 * goalsReached는 모든 단계의 목표와 (측정했다면) 유지 판정을 확인했는지다. graspAttempted는 GRASP 실행 시도 여부다.
	 * hold는 두 측정을 모두 마쳤을 때만 있다. 실제 물체의 안정적인 유지를 보장하지 않는다.
	 */
	public record Check(boolean goalsReached, @Nullable Stage failedStage, @Nullable String why, List<StepResult<Stage>> steps,
			boolean graspAttempted, Optional<Hold> hold) {
		public Check { steps = List.copyOf(steps); }
	}

	/** 공통 스냅샷에서 도메인 판정을 읽는다. 별도의 가변 상태를 갖지 않는다. */
	public static final class Progress extends StepwiseProgress<Stage> {
		private Progress(StepwiseProgress<Stage> source) { super(source); }
		public boolean graspAttempted() { return enteredStages().contains(Stage.GRASP); }
	}

	private final double m_holdTolerance;

	/** 물린 위치를 재지 않는다. 들어 올린 뒤의 유지는 판정하지 않는다. */
	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose) {
		this(twin, approachPose, graspPose, liftPose, null, MeasureObjectOffset.TcpReference.AS_REPORTED, Options.DEFAULT);
	}

	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose, @Nullable String objectName,
				MeasureObjectOffset.TcpReference tcpReference, Options options) {
		this(twin, approachPose, graspPose, liftPose, objectName, tcpReference, options, defaultTimeout(twin));
	}

	/**
	 * @param liftPose		들어 올릴 자세. 흔히 접근 자세와 같다.
	 * @param objectName	물린 위치를 잴 물체의 scene_objects 이름. {@code null}이면 재지 않는다.
	 * @param tcpReference	측정의 도구 기준점. 펑션베이는 AS_REPORTED, Isaac Panda Hand는 PANDA_HAND_TCP.
	 * @param timeout		각 하위 원격 비동기 연산의 대기 상한이며 전체 Pick 실행 상한이 아니다.
	 * @throws IllegalArgumentException	입력이 유효하지 않은 경우. 첫 개방 전에 모두 검사한다.
	 */
	public Pick(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose, @Nullable String objectName,
				MeasureObjectOffset.TcpReference tcpReference, Options options, Duration timeout) {
		super(sequence(twin, approachPose, graspPose, liftPose, objectName, tcpReference, options, timeout));
		m_holdTolerance = options.holdToleranceM();
	}

	private static List<Step<Stage>> sequence(RobotTwinClient twin, Pose approachPose, Pose graspPose, Pose liftPose,
			@Nullable String objectName, MeasureObjectOffset.TcpReference tcpReference, Options options, Duration timeout) {
		Preconditions.checkNotNullArgument(options, "options is null");
		Preconditions.checkNotNullArgument(tcpReference, "tcpReference is null");
		validatePose(approachPose);
		validatePose(graspPose);
		validatePose(liftPose);
		if ( objectName != null ) {
			validateName(objectName, "objectName");
			double lift = distance(graspPose.position(), liftPose.position());
			double minLift = MIN_LIFT_FACTOR * options.holdToleranceM();
			Preconditions.checkArgument(lift >= minLift, String.format(
					"liftPose must be at least %.1f mm away from graspPose to check the hold: %.1f mm", minLift * 1000, lift * 1000));
		}

		List<Step<Stage>> steps = new ArrayList<>(List.of(
				new Step<>(Stage.OPEN, new MoveGripperToNamedTarget(twin, options.openTarget(), timeout)),
				new Step<>(Stage.APPROACH, new MoveLinear(twin, approachPose, options.approach(), timeout)),
				new Step<>(Stage.DESCEND, new MoveLinear(twin, graspPose, options.descent(), timeout)),
				new Step<>(Stage.GRASP, new MoveGripperToNamedTarget(twin, options.graspTarget(), timeout))));
		if ( objectName != null ) {
			steps.add(new Step<>(Stage.MEASURE_GRIP, new MeasureObjectOffset(twin, objectName, tcpReference, options.measure())));
		}
		steps.add(new Step<>(Stage.LIFT, new MoveLinear(twin, liftPose, options.lift(), timeout)));
		if ( objectName != null ) {
			steps.add(new Step<>(Stage.MEASURE_HELD, new MeasureObjectOffset(twin, objectName, tcpReference, options.measure())));
		}
		return steps;
	}

	@Override public Progress getProgress() { return new Progress(snapshot()); }

	@Override protected Check createResult(@Nullable StepResult<Stage> failedStep, StepwiseProgress<Stage> progress) {
		List<StepResult<Stage>> steps = progress.steps();
		boolean attempted = progress.enteredStages().contains(Stage.GRASP);
		if ( failedStep != null ) {
			return new Check(false, failedStep.stage(), failedStep.why(), steps, attempted, Optional.empty());
		}
		Optional<MeasureObjectOffset.Measurement> before = measurementOf(steps, Stage.MEASURE_GRIP);
		Optional<MeasureObjectOffset.Measurement> after = measurementOf(steps, Stage.MEASURE_HELD);
		if ( before.isEmpty() || after.isEmpty() ) {
			// 물체 이름 없이 만들어 재지 않았다.
			return new Check(true, null, null, steps, attempted, Optional.empty());
		}
		MeasureObjectOffset.Measurement b = before.get(), a = after.get();
		double lift = distance(b.tcpPose().position(), a.tcpPose().position());
		double drift = distance(toolFrameOffset(b), toolFrameOffset(a));
		Hold hold = new Hold(b, a, lift, drift);
		double minLift = MIN_LIFT_FACTOR * m_holdTolerance;
		if ( lift < minLift ) {
			String why = String.format("유지 판정 불가: 두 측정 사이 도구 기준점 이동 %.1f mm (필요 %.1f mm 이상). "
										+ "상승이 짧거나 상승 뒤 관측이 갱신되지 않았다", lift * 1000, minLift * 1000);
			return new Check(false, Stage.MEASURE_HELD, why, steps, attempted, Optional.of(hold));
		}
		if ( drift > m_holdTolerance ) {
			String why = String.format("물체가 그리퍼와 함께 움직이지 않았다: 들기 전후 물린 위치 차이 %.1f mm (허용 %.1f mm)",
										drift * 1000, m_holdTolerance * 1000);
			return new Check(false, Stage.MEASURE_HELD, why, steps, attempted, Optional.of(hold));
		}
		return new Check(true, null, null, steps, attempted, Optional.of(hold));
	}

	private static Optional<MeasureObjectOffset.Measurement> measurementOf(List<StepResult<Stage>> steps, Stage stage) {
		return steps.stream().filter(s -> s.stage() == stage && s.measurement() != null)
					.findFirst().flatMap(s -> s.measurement().measurement());
	}

	/** 물린 위치를 측정 시점의 도구 자세로 역회전한다. 단단히 쥔 물체는 손목이 돌아도 이 값이 같다. */
	private static Position toolFrameOffset(MeasureObjectOffset.Measurement m) {
		Quaternion q = m.tcpPose().orientation();
		double[] v = new Quaternion(-q.x(), -q.y(), -q.z(), q.w()).rotate(m.offset().x(), m.offset().y(), m.offset().z());
		return new Position(v[0], v[1], v[2]);
	}

	private static double distance(Position a, Position b) {
		return Math.sqrt(square(a.x() - b.x()) + square(a.y() - b.y()) + square(a.z() - b.z()));
	}

	private static double square(double v) { return v * v; }
}
