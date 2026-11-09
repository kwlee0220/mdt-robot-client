package mdt.robot.client.operation;

import static mdt.robot.client.operation.OperationInputs.defaultTimeout;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 이름으로 지정한 그리퍼 목표를 실행하고 서버 출력에 따른 판정 결과를 반환한다.
 * <p>
 * run()은 호출 스레드에서, start()/get()은 실행기를 통해 실행한다. 생성자는 통신하지 않으며
 * 한 번만 실행한다. 원격 연산 종류는 카탈로그를 따른다. start()도 서버의 동기 연산을
 * 비동기 세션으로 바꾸지 않으므로 동기 HTTP 호출 중 cancel(true)는 false다.
 * <p>
 * 서버 COMPLETED와 JSON Boolean at_goal=true인 경우만 목표 달성으로 인정한다.
 * stalled는 관측값이며 성공 판정에 사용하지 않는다. 서버 FAILED/CANCELLED와 목표 달성 확인 불가는
 * goalReached=false 결과로 정상 완료한다. 접수 거부·통신 오류는 ExecutionException의 원인으로 전달한다.
 * 직접 요청한 취소가 접수된 경우의 로컬 CANCELLED 계약은 공통 실행부를 따른다.
 */
public final class MoveGripperToNamedTarget extends AbstractRobotTwinOperation<GripperCheck> {
	private final String m_target;

	/** 클라이언트의 원격 비동기 결과 대기 상한을 사용한다. */
	public MoveGripperToNamedTarget(RobotTwinClient twin, String target) {
		this(twin, target, defaultTimeout(twin));
	}

	/**
	 * 목표 이름(예: open, close, grasp)의 지원 여부는 실행 시 서버가 검증한다.
	 * timeout은 카탈로그상 비동기 연산의 결과 대기 상한이다. 동기 연산에는 HTTP 시간 제한이
	 * 적용된다. httpTimeout(기본 30초)은 연결·읽기·쓰기에 각각 적용되며 전체 호출 시간 상한이 아니다.
	 *
	 * @throws IllegalArgumentException 목표 이름이 null/공백 문자열이거나 앞뒤 공백을 포함한 경우,
	 *         또는 timeout이 null/0 이하인 경우.
	 */
	public MoveGripperToNamedTarget(RobotTwinClient twin, String target, Duration timeout) {
		super(twin, "move_gripper_to_target", timeout);
		Preconditions.checkNotNullArgument(target, "target is null");
		Preconditions.checkArgument(!target.isBlank(), "target must not be blank");
		Preconditions.checkArgument(target.equals(target.strip()), "target must not have surrounding whitespace");
		m_target = target;
	}

	@Override
	protected Map<String,?> buildInputs() {
		return Map.of("target", m_target);
	}

	@Override
	protected GripperCheck judgeResult(OperationResult result) {
		JsonNode stalledNode = result.output("stalled");
		boolean stalled = stalledNode != null && stalledNode.isBoolean() && stalledNode.booleanValue();
		if ( !result.isCompleted() ) {
			String why = OperationChecks.failureWhy(result);
			return new GripperCheck(false, stalled, why, result);
		}
		JsonNode atGoal = result.output("at_goal");
		if ( atGoal == null || atGoal.isNull() ) {
			return new GripperCheck(false, stalled, "at_goal 정보 없음 — 목표 달성을 확인할 수 없다", result);
		}
		if ( !atGoal.isBoolean() ) {
			return new GripperCheck(false, stalled,
					"at_goal 형식 오류 — Boolean이 필요하다. 받은 값: " + atGoal, result);
		}
		return new GripperCheck(atGoal.booleanValue(), stalled,
				atGoal.booleanValue() ? null : "at_goal=false — 서버가 목표 달성을 보고하지 않았다", result);
	}
}
