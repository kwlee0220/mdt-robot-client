package mdt.robot.client.examples;

import java.util.List;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.ResetScene;
import mdt.robot.client.operation.WaitForFreshVariable;

/**
 * 장면을 서버 레시피로 재배치한다. 실행 중인 에피소드 밖에서 사용한다.
 * <pre>
 * gradle runExample -Pexample=ResetSceneExample --args='one_cube 7'      # mock
 * gradle runExample -Pexample=ResetSceneExample --args='two_objects 7'   # Isaac
 * </pre>
 * 레시피는 배포마다 다르다. seed 생략 시 현재 서버는 0을 사용하므로 같은 요청 배치를 반복한다.
 */
public final class ResetSceneExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		List<String> rest = ExampleSupport.positional(args);
		if ( rest.isEmpty() || rest.size() > 2 ) {
			throw new IllegalArgumentException("usage: ResetSceneExample <scene> [seed] [--url <url>] [--twin-id <id>]");
		}
		String scene = rest.get(0);
		Long seed = rest.size() == 2 ? Long.valueOf(rest.get(1)) : null;
		RobotTwinClient twin = ExampleSupport.connect(args);
		ResetScene operation = new ResetScene(twin, scene, seed, twin.getOperationTimeout(),
				WaitForFreshVariable.Options.DEFAULT);
		operation.start(); // 호출 스레드에서 실행하려면 대신 run()을 사용한다.
		ResetScene.Check check = operation.get();
		if ( !check.goalReached() ) {
			System.out.println("장면 재배치 확인 실패: " + check.failureReason());
			return;
		}
		System.out.println("적용 물체 수: " + check.result().output("applied_count"));
		System.out.println("적용 요청 배치: " + check.result().output("objects"));
		WaitForFreshVariable.Result observed = check.observation().orElseThrow();
		System.out.println("관측 갱신: " + observed.summary());
		if ( observed.goalReached() ) {
			System.out.println("갱신 확인에 사용한 관측값: " + observed.sample().orElseThrow().get("value"));
		}
		// 갱신 미확인은 리셋 미적용이 아니다. 자동 재시도하지 않는다. 새 발행도 물리적 정착의 증명은 아니다.
	}
}
