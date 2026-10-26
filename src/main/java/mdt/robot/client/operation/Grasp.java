package mdt.robot.client.operation;

import static mdt.robot.client.operation.OperationInputs.defaultTimeout;
import static mdt.robot.client.operation.OperationInputs.validateName;
import static mdt.robot.client.operation.OperationInputs.validatePose;

import java.time.Duration;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 열기 → 접근 → 하강 → 파지 목표 확인을 순서대로 수행하는 조합 연산. 상승은 포함하지 않는다.
 * 생성자는 통신하지 않으며 run() 또는 start()/get()으로 한 번 실행한다.
 * 목표 자세는 서버 기본 EE 프레임 기준의 절대 pose다. 자동 좌표 변환이나 높이 계산은 하지 않는다.
 * 이동은 MoveLinear의 위치 판정이며 자세 도달, 접촉, 특정 물체 파지 여부는 검증하지 않는다.
 * <p>
 * 단계별 목표 확인 실패는 goalsReached=false 결과로 정상 완료한다. 접수 거부·통신 오류는
 * FAILED/ExecutionException, 외부 인터럽트는 CANCELLED다. run()의 인터럽트는 InterruptedException으로
 * 전달된다. 실행 중 결과·취소·실패 기록은 getProgress()로 조회한다.
 * graspAttempted는 GRASP 하위 실행 진입 직전에 기록한다. true이면 파지 실행 가능성을 고려해
 * 복구 정책을 정한다. 전송·파지 성공의 증거는 아니며 false도 실행 전부터 빈손이었음을 보장하지 않는다.
 * <p>
 * cancel(true)는 이후 단계 중단을 접수하고 현재 하위 연산에도 취소를 시도한다.
 * 동기 HTTP/세션 확보 전 등 하위 취소가 불가능하면 현재 단계가 끝날 때까지 CANCELLING이다.
 * 이때도 이후 단계는 실행하지 않는다. 하위 취소 오류는 progress에 남기고 현재 단계 종료를 기다린다.
 * 시작 중 취소와 실행 후 cancel(false)는 false다. 취소는 원격 정지·실제 파지 상태를 보장하지 않는다.
 * 자동 개방·복귀·재시도·비상정지는 하지 않는다. 작업 전체의 서버 자원을 점유하지 않으므로
 * 다른 호출자의 명령이 단계 사이에 끼어들지 않게 하는 것은 호출자의 책임이다.
 */
public final class Grasp extends AbstractStepwiseOperation<Grasp.Stage, Grasp.Check> {
	public enum Stage { OPEN, APPROACH, DESCEND, GRASP }

	/** 두 이동에 다른 속도·허용 오차·정착 상한을 지정할 수 있다. */
	public record Options(MoveLinear.Options approach, MoveLinear.Options descent, String openTarget, String graspTarget) {
		/** 기본은 각 이동 뒤 최대 10초 정착 관측. 개루프 환경에서도 종료 스냅샷만으로 진행하지 않는다. */
		public static final Options DEFAULT = new Options(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)), "open", "grasp");

		public Options {
			Preconditions.checkNotNullArgument(approach, "approach options is null");
			Preconditions.checkNotNullArgument(descent, "descent options is null");
			validateName(openTarget, "openTarget");
			validateName(graspTarget, "graspTarget");
		}
	}

	/** goalsReached는 목표 확인 여부, graspAttempted는 GRASP 실행 시도 여부다. 실제 파지를 보장하지 않는다. */
	public record Check(boolean goalsReached, @Nullable Stage failedStage, @Nullable String why, List<StepResult<Stage>> steps,
			boolean graspAttempted) {
		public Check { steps = List.copyOf(steps); }
	}

	/** 공통 스냅샷에서 도메인 판정을 읽는다. 별도의 가변 상태를 갖지 않는다. */
	public static final class Progress extends StepwiseProgress<Stage> {
		private Progress(StepwiseProgress<Stage> source) { super(source); }
		public boolean graspAttempted() { return enteredStages().contains(Stage.GRASP); }
	}

	public Grasp(RobotTwinClient twin, Pose approachPose, Pose graspPose) {
		this(twin, approachPose, graspPose, Options.DEFAULT);
	}

	public Grasp(RobotTwinClient twin, Pose approachPose, Pose graspPose, Options options) {
		this(twin, approachPose, graspPose, options, defaultTimeout(twin));
	}

	/**
	 * timeout은 각 하위 원격 비동기 연산의 대기 상한이며 전체 Grasp 실행 상한이 아니다.
	 * 동기 그리퍼에는 클라이언트 httpTimeout이 적용되고 정착 상한은 각 MoveLinear.Options에 따른다.
	 * 입력은 첫 개방 전에 모두 검사한다. 배포별 프레임·목표 지원 여부와 도달 가능성은 서버가 검사한다.
	 */
	public Grasp(RobotTwinClient twin, Pose approachPose, Pose graspPose, Options options, Duration timeout) {
		super(sequence(twin, approachPose, graspPose, options, timeout));
	}

	private static List<Step<Stage>> sequence(RobotTwinClient twin, Pose approachPose, Pose graspPose, Options options, Duration timeout) {
		Preconditions.checkNotNullArgument(options, "options is null");
		validatePose(approachPose);
		validatePose(graspPose);
		return List.of(
				new Step<>(Stage.OPEN, new MoveGripperToNamedTarget(twin, options.openTarget(), timeout)),
				new Step<>(Stage.APPROACH, new MoveLinear(twin, approachPose, options.approach(), timeout)),
				new Step<>(Stage.DESCEND, new MoveLinear(twin, graspPose, options.descent(), timeout)),
				new Step<>(Stage.GRASP, new MoveGripperToNamedTarget(twin, options.graspTarget(), timeout)));
	}

	@Override public Progress getProgress() { return new Progress(snapshot()); }

	@Override protected Check createResult(@Nullable StepResult<Stage> failedStep, StepwiseProgress<Stage> progress) {
		return new Check(failedStep == null, failedStep == null ? null : failedStep.stage(),
				failedStep == null ? null : failedStep.why(), progress.steps(),
				progress.enteredStages().contains(Stage.GRASP));
	}
}
