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
 * 물체를 쥔 상태에서 접근 → 하강 → 개방 목표 확인 → 후퇴를 순서대로 수행하는 조합 연산.
 * 생성자는 통신하지 않는다. 각 pose는 호출자가 계산한 서버 기본 EE 프레임 기준의 절대 자세이며 자동 변환하지 않는다.
 * 이동은 위치만 판정한다. 개방 목표 확인은 물체의 실제 분리·착석·안정 배치를 보장하지 않는다.
 * 하강 목표는 물체 형상·파지 오프셋·여유 간격을 고려한 접촉 전 위치로 계산한다.
 * 기본 도달 허용 오차 5mm는 Python 쌓기의 기본 여유 4mm보다 크므로 둘만으로 비접촉을 보장하지 않는다.
 * 허용 오차는 도달 판정 기준이며 충돌을 막는 이동 제한이 아니다. 개방 확인 후 고정 대기 없이 후퇴한다.
 * peg 삽입처럼 개방 전 착석 확인이 필요한 작업에는 별도 태스크의 검증이 필요하다.
 * <p>
 * run() 또는 start()/get()으로 한 번 실행한다. 목표 확인 불가는 goalReached=false로 정상 완료하고
 * 이후 단계는 실행하지 않는다. 접수 거부·통신 오류·비동기 대기 시간 초과는 FAILED/ExecutionException,
 * 외부 인터럽트는 CANCELLED다. run()은 외부 인터럽트를 InterruptedException으로 전달한다.
 * <p>
 * releaseAttempted는 RELEASE 하위 실행 직전에 기록한다. 전송·실제 해제의 증거는 아니다.
 * false도 물체를 계속 쥐고 있음을 보장하지 않는다. Check와 getProgress()의 releaseConfirmed 및
 * retreatReached로 개방 목표 확인과 후퇴 도달을 구별한다. 실패·취소 후에도 단계 기록을 보존한다.
 * <p>
 * cancel(true)는 후속 단계 중단을 접수하고 현재 하위 연산에 취소를 시도한다. 하위 연산이 세션 확보 전이면 취소가
 * 기록되어 세션을 받는 즉시 전달되고, 취소 불가인 동기 HTTP면 현재 단계 종료를 기다린다. 취소는 실제 정지를 보장하지 않는다. 개방 뒤 취소하면
 * 후퇴하지 않을 수 있다. 자동 복구·재파지·재시도·비상정지는 수행하지 않는다.
 * STARTING 중 cancel(true)는 기록해 첫 단계 전에 끝내고, 실행 중 cancel(false)는 거부한다. 전체 작업의 서버 자원을 예약하지 않으므로
 * 다른 호출자의 명령과의 조율은 태스크가 맡는다.
 */
public final class Place extends AbstractStepSequenceOperation<Place.Stage, Place.Check> {
	public enum Stage { APPROACH, DESCEND, RELEASE, RETREAT }

	/** 세 이동에 다른 속도·허용 오차·정착 상한을 지정할 수 있다. */
	public record Options(MoveLinear.Options approach, MoveLinear.Options descent, MoveLinear.Options retreat, String releaseTarget) {
		/** 기본은 각 이동 뒤 최대 10초 정착 관측. 개루프 환경에서도 종료 스냅샷만으로 진행하지 않는다. */
		public static final Options DEFAULT = new Options(
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)),
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10)), "open");

		public Options {
			Preconditions.checkNotNullArgument(approach, "approach options is null");
			Preconditions.checkNotNullArgument(descent, "descent options is null");
			Preconditions.checkNotNullArgument(retreat, "retreat options is null");
			validateName(releaseTarget, "releaseTarget");
		}
	}

	/** goalReached는 목표 확인 여부, releaseAttempted는 RELEASE 실행 시도 여부다. 실제 물체 해제를 보장하지 않는다. */
	public record Check(boolean goalReached, @Nullable Stage failedStage, @Nullable String failureReason, List<StepResult<Stage>> steps,
			boolean releaseAttempted) implements StepVerdict {
		@Override public @Nullable String failureReason() { return goalReached ? null : failureReason; }
		public Check { steps = List.copyOf(steps); }
		/** 개방 단계의 goalReached 판정(서버 at_goal). false는 미확인도 포함한다. */
		public boolean releaseConfirmed() { return steps.stream().anyMatch(step -> step.stage() == Stage.RELEASE && step.goalReached()); }
		public boolean retreatReached() { return steps.stream().anyMatch(step -> step.stage() == Stage.RETREAT && step.goalReached()); }
	}

	/** 공통 스냅샷에서 도메인 판정을 읽는다. 별도의 가변 상태를 갖지 않는다. */
	public static final class Progress extends StepwiseProgress<Stage> {
		private Progress(StepwiseProgress<Stage> source) { super(source); }
		public boolean releaseAttempted() { return enteredStages().contains(Stage.RELEASE); }
		public boolean releaseConfirmed() { return confirmed(Stage.RELEASE); }
		public boolean retreatReached() { return confirmed(Stage.RETREAT); }
	}

	public Place(RobotTwinClient twin, Pose approachPose, Pose placePose, Pose retreatPose) {
		this(twin, approachPose, placePose, retreatPose, Options.DEFAULT);
	}

	public Place(RobotTwinClient twin, Pose approachPose, Pose placePose, Pose retreatPose, Options options) {
		this(twin, approachPose, placePose, retreatPose, options, defaultTimeout(twin));
	}

	/**
	 * timeout은 각 하위 원격 비동기 연산의 대기 상한이며 전체 Place 실행 상한이 아니다.
	 * 동기 그리퍼에는 클라이언트 httpTimeout이 적용되고 정착 상한은 각 MoveLinear.Options에 따른다.
	 * 입력은 첫 이동 전에 모두 검사한다. 배포별 프레임·목표 지원 여부와 도달 가능성은 서버가 검사한다.
	 */
	public Place(RobotTwinClient twin, Pose approachPose, Pose placePose, Pose retreatPose, Options options, Duration timeout) {
		super(sequence(twin, approachPose, placePose, retreatPose, options, timeout));
	}

	/**
	 * 인자를 역할 이름으로 지정하는 빌더. approachPose·placePose는 필수다. retreatPose를 생략하면 approachPose로
	 * 후퇴한다. options 기본값은 {@link Options#DEFAULT}, timeout 기본값은 클라이언트의 operationTimeout이다.
	 * 검증 규칙은 생성자와 같고 build()에서 통신 전에 한다.
	 */
	public static Builder builder(RobotTwinClient twin) {
		return new Builder(twin);
	}

	public static final class Builder {
		private final RobotTwinClient m_twin;
		private @Nullable Pose m_approachPose;
		private @Nullable Pose m_placePose;
		private @Nullable Pose m_retreatPose;
		private Options m_options = Options.DEFAULT;
		private @Nullable Duration m_timeout;

		private Builder(RobotTwinClient twin) {
			Preconditions.checkNotNullArgument(twin, "twin is null");
			m_twin = twin;
		}

		public Builder approachPose(Pose value) { m_approachPose = value; return this; }
		public Builder placePose(Pose value) { m_placePose = value; return this; }
		/** 생략하면 approachPose로 후퇴한다. */
		public Builder retreatPose(Pose value) { m_retreatPose = value; return this; }
		public Builder options(Options value) { m_options = value; return this; }
		/** 각 하위 원격 비동기 연산의 대기 상한. 전체 Place 실행 상한이 아니다. */
		public Builder timeout(Duration value) { m_timeout = value; return this; }

		/** @throws IllegalArgumentException 필수 자세가 없거나 입력이 유효하지 않은 경우. */
		public Place build() {
			Preconditions.checkArgument(m_approachPose != null, "approachPose is not set");
			Preconditions.checkArgument(m_placePose != null, "placePose is not set");
			Pose retreat = m_retreatPose != null ? m_retreatPose : m_approachPose;
			return new Place(m_twin, m_approachPose, m_placePose, retreat, m_options,
							m_timeout != null ? m_timeout : defaultTimeout(m_twin));
		}
	}

	private static List<Step<Stage>> sequence(RobotTwinClient twin, Pose approachPose, Pose placePose, Pose retreatPose, Options options, Duration timeout) {
		Preconditions.checkNotNullArgument(options, "options is null");
		validatePose(approachPose);
		validatePose(placePose);
		validatePose(retreatPose);
		return List.of(
				new Step<>(Stage.APPROACH, new MoveLinear(twin, approachPose, options.approach(), timeout)),
				new Step<>(Stage.DESCEND, new MoveLinear(twin, placePose, options.descent(), timeout)),
				new Step<>(Stage.RELEASE, new MoveGripperToNamedTarget(twin, options.releaseTarget(), timeout)),
				new Step<>(Stage.RETREAT, new MoveLinear(twin, retreatPose, options.retreat(), timeout)));
	}

	@Override public Progress getProgress() { return new Progress(snapshot()); }

	@Override protected Check createResult(@Nullable StepResult<Stage> failedStep, StepwiseProgress<Stage> progress) {
		return new Check(failedStep == null, failedStep == null ? null : failedStep.stage(),
				failedStep == null ? null : failedStep.failureReason(), progress.steps(),
				progress.enteredStages().contains(Stage.RELEASE));
	}
}
