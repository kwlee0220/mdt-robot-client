package mdt.robot.client.examples;

import java.util.List;
import java.util.Map;

import mdt.robot.client.operation.ClosedLoopCheck;
import mdt.robot.client.operation.MoveToNamedTarget;
import mdt.robot.client.OperationFailedException;
import mdt.robot.client.Ops;
import mdt.robot.client.RobotTwinClient;

/**
 * <b>세션 · 에피소드 경계 — 학습 데이터로 남기기.</b> 로봇을 움직인다.
 * <p>
 * {@code start_session} → {@code start_episode} → (작업) → {@code stop_episode(outcome, metadata)} →
 * {@code stop_session}. 수집 계층({@code rdfp_collect})이 떠 있어야 하고, 실제로 담으려면 레코더와
 * rosbag 녹화도 따로 떠 있어야 한다 — 이 경계는 시간 구간을 찍을 뿐이다.
 * <ul>
 *   <li>경계 연산은 동기이며 상태가 맞지 않으면 {@code FAILED}/{@code PRECONDITION_FAILED} 로 끝난다.
 *       <b>{@code outputs()} 로 불러</b> 실패를 예외로 받는다 — {@code run()} 으로 부르고 상태를 안 보면
 *       에피소드가 안 열렸는데 작업이 기록된 것으로 착각한다.</li>
 *   <li>시작 전에 {@code session_state} 가 {@code IDLE} 인지 본다 — 아니면 이전 실행이 남긴 경계다.</li>
 *   <li>작업이 실패해도 <b>{@code stop_episode} 를 반드시 부른다</b>(outcome=failure 도 유효한 데이터다).
 *       세션은 {@code finally} 에서 닫는다.</li>
 * </ul>
 *
 * <pre>
 *   gradle runExample -Pexample=SessionEpisodeExample --args='demo_ready_move'
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class SessionEpisodeExample {
	public static void main(String[] args) {
		RobotTwinClient twin = ExampleSupport.connect(args);
		List<String> rest = ExampleSupport.positional(args);
		String taskLabel = rest.isEmpty() ? "demo_ready_move" : rest.get(0);

		String state = twin.read("session_state").path("state").asText();
		if ( !"IDLE".equals(state) ) {
			System.out.println("session_state=" + state + " — 이전 실행이 남긴 경계가 있다. 정리한 뒤 다시 실행한다");
			return;
		}

		// task_label 은 이 세션의 모든 에피소드에 박히며 나중에 고칠 수 없다.
		twin.runForOutputs("start_session", Map.of("task_label", taskLabel));
		try {
			twin.runForOutputs("start_episode", Map.of());
			boolean success = false;
			String error = null;
			try {
				// 에피소드 안의 작업 — 여기서는 ready 로 이동 하나.
				ClosedLoopCheck check = Ops.moveToNamedTargetVerified(twin, "ready");
				success = check.result().isCompleted();
				error = success ? null : check.result().errorCode();
			}
			catch ( RuntimeException e ) {
				error = e.getClass().getSimpleName() + ": " + e.getMessage();
			}
			// 실패해도 닫는다. metadata 는 이 에피소드의 재현 정보다.
			twin.runForOutputs("stop_episode", Map.of("outcome", success ? "success" : "failure",
												"metadata", error == null ? Map.of("note", "example")
																		: Map.of("note", "example", "error", error)));
			System.out.println("에피소드 기록: outcome=" + (success ? "success" : "failure"));
		}
		catch ( OperationFailedException e ) {
			System.out.println("경계 연산 실패: " + e.getMessage());
		}
		finally {
			// 닫기 실패를 예외로 덮지 않으려고 run() 으로 부르고 상태만 알린다.
			System.out.println("stop_session: " + twin.run("stop_session", Map.of()).status());
		}
	}
}
