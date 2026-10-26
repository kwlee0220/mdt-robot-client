package mdt.robot.client.examples;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.Map;

import mdt.robot.client.operation.MoveCheck;
import mdt.robot.client.OperationResult;
import mdt.robot.client.OperationTimeoutException;
import mdt.robot.client.operation.MoveLinear;
import mdt.robot.client.Pose;
import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.TwinException;

/**
 * <b>시간 초과 · 취소 · 자원 경합.</b> 로봇을 움직인다.
 * <p>
 * 느린 속도로 위로 10 cm 이동을 보내 놓고 0.5 초만 기다린다.
 * <ol>
 *   <li><b>시간 초과는 기다리기만 끝낸다</b> — 원격 연산은 계속 돈다. 그 사이 다른 팔 연산을 보내면 자원이
 *       점유돼 있어 {@code 409 RESOURCE_BUSY} 로 거부되는 것을 보인다.</li>
 *   <li>{@code OperationTimeoutException.cancel()} 로 세션에 취소를 요청한다. 접수가 정지 완료를 뜻하지는
 *       않으므로 자원이 풀릴 때까지 {@code resources()} 를 본다.</li>
 * </ol>
 * 실제 비상정지는 {@code twin.estop()} / {@code twin.releaseEstop()} 이다(해제는 사람이 현장을 확인한 뒤).
 *
 * <pre>
 *   gradle runExample -Pexample=TimeoutAndCancelExample
 * </pre>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public final class TimeoutAndCancelExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		RobotTwinClient twin = ExampleSupport.connect(args);
		Pose start = Pose.fromJson(twin.read("ee_pose"));
		Pose up = start.withPosition(start.position().plus(0.0, 0.0, 0.10));

		Map<String,Object> inputs = Map.of("pose", up, "velocity_scaling", 0.05);
		try {
			OperationResult result = twin.run("move_linear", inputs, Duration.ofMillis(500));
			System.out.println("0.5 초 안에 끝났다: " + result.status());
			return;
		}
		catch ( OperationTimeoutException timeout ) {
			System.out.println("시간 초과 — 원격 이동은 계속된다: " + timeout.getMessage());

			try {
				twin.run("move_to_named_target", Map.of("target", "ready"));
			}
			catch ( TwinException busy ) {
				System.out.printf("다른 팔 연산은 거부된다: HTTP %d %s%n", busy.getHttpStatus(), busy.getCode());
			}

			timeout.cancel();
			System.out.println("취소를 요청했다 — 자원이 풀리기를 기다린다");
			// /resources 는 {자원: {"state": "FREE" | "BUSY", "operation": …}} 이다.
			for ( int i = 0; i < 50 && "BUSY".equals(twin.resources().path("arm").path("state").asText()); ++i ) {
				Thread.sleep(200);
			}
			System.out.println("자원: " + twin.resources());
		}

		// 멈춘 자리에서 시작 자세로 되돌린다 — 목표는 처음 읽은 값에서 만든다.
		MoveCheck back = new MoveLinear(twin, start,
				MoveLinear.Options.DEFAULT.withSettleTimeout(Duration.ofSeconds(10))).run();
		ExampleSupport.report("시작 자세 복귀", back);
	}
}
