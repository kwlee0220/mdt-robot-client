package mdt.robot.client.examples;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.WaitUntilStill;

/**
 * ee_pose의 위치 정착만 관측한다. 이동·정지 명령은 보내지 않는다.
 * <pre>
 * gradle runExample -Pexample=WaitUntilStillExample
 * </pre>
 */
public final class WaitUntilStillExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		WaitUntilStill operation = new WaitUntilStill(twin,
				WaitUntilStill.Options.DEFAULT.withTimeout(Duration.ofSeconds(10)));
		operation.start();
		Optional<JsonNode> settled = operation.get();
		if ( settled.isPresent() ) {
			System.out.println("위치 정착을 확인한 관측값: " + settled.orElseThrow());
		}
		else {
			System.out.println("위치 정착 확인 실패: " + operation.getDiagnostics().summary());
		}
	}
}
