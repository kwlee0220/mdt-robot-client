package mdt.robot.client.examples;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.WaitUntilObjectStill;

/**
 * 지정한 물체의 위치·회전 정착을 관측한다. 이동·그리퍼 명령을 보내지 않는다.
 * <pre>
 * gradle runExample -Pexample=WaitUntilObjectStillExample --args='peg --url http://127.0.0.1:8801'
 * </pre>
 */
public final class WaitUntilObjectStillExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		List<String> positional = ExampleSupport.positional(args);
		if ( positional.size() != 1 ) {
			System.out.println("사용법: <scene_objects의 물체 이름> [--url <서버 URL>] [--twin-id <ID>]");
			return;
		}
		RobotTwinClient twin = ExampleSupport.connect(args);
		WaitUntilObjectStill operation = new WaitUntilObjectStill(twin, positional.get(0));
		operation.start();
		try {
			WaitUntilObjectStill.Result result = operation.get();
			System.out.println(result.summary());
			System.out.println("관측 경과: " + result.elapsed());
			result.object().ifPresent(object -> System.out.println(
					(result.goalReached() ? "정착 판정에 사용한 물체: " : "마지막 유효 물체 (정착 확인 안 됨): ") + object));
		}
		catch ( InterruptedException | ExecutionException | CancellationException e ) {
			System.out.println("관측 진행 기록: " + operation.getProgress().summary());
			throw e;
		}
	}
}
