package mdt.robot.client.examples;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.WaitUntilArmStill;

/**
 * ee_pose의 위치 정착만 관측한다. 이동·정지 명령은 보내지 않는다.
 * <pre>
 * gradle runExample -Pexample=WaitUntilArmStillExample
 * </pre>
 */
public final class WaitUntilArmStillExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		WaitUntilArmStill operation = new WaitUntilArmStill(twin,
				WaitUntilArmStill.Options.DEFAULT.withTimeout(Duration.ofSeconds(10)));
		operation.start();
		WaitUntilArmStill.Result result = operation.get();
		if ( result.goalReached() ) {
			System.out.printf("위치 정착을 확인한 관측값(%d ms): %s%n", result.elapsed().toMillis(), result.pose().orElseThrow());
		}
		else {
			System.out.println("위치 정착 확인 실패: " + result.summary());
		}
	}
}
