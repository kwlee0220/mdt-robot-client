package mdt.robot.client.examples;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import mdt.robot.client.RobotTwinClient;
import mdt.robot.client.operation.MeasureObjectOffset;

/**
 * 물체 중심 - 도구 기준점 오프셋만 관측한다. 자동 상승·정착 대기·이동을 하지 않는다.
 * <pre>
 * gradle runExample -Pexample=MeasureObjectOffsetExample --args='peg AS_REPORTED --url http://127.0.0.1:8801'
 * </pre>
 */
public final class MeasureObjectOffsetExample {
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		List<String> positional = ExampleSupport.positional(args);
		if ( positional.size() != 2 ) {
			System.out.println("사용법: <물체 이름> <AS_REPORTED|PANDA_HAND_TCP> [--url <URL>] [--twin-id <ID>]");
			return;
		}
		MeasureObjectOffset.TcpReference reference = MeasureObjectOffset.TcpReference.valueOf(positional.get(1));
		RobotTwinClient twin = ExampleSupport.connect(args);
		MeasureObjectOffset operation = new MeasureObjectOffset(twin, positional.get(0), reference);
		operation.start();
		try {
			MeasureObjectOffset.Result result = operation.get();
			System.out.println(result.summary());
			result.measurement().ifPresent(m -> {
				System.out.println("도구 기준점 정책: " + m.tcpReference() + ", 표현 좌표계: " + m.frame());
				System.out.println("물체 중심 - 도구 기준점 (m): " + m.offset());
				System.out.println("수신 시각 차이: " + m.timeSkew());
			});
		}
		catch ( InterruptedException | ExecutionException | CancellationException e ) {
			System.out.println("관측 진행 기록: " + operation.getProgress().summary());
			throw e;
		}
	}
}
