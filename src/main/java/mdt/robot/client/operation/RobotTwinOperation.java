package mdt.robot.client.operation;

import utils.async.StartableExecution;

/**
 * 로봇 트윈을 이용하는 한 번의 실행. 객체 생성은 원격 요청을 보내지 않으며 {@link #start()}로 시작한다.
 * <p>
 * 실행의 완료와 로봇 동작의 성공은 구분한다. 완료 결과에 도달 여부 등의 판정이 있으면 호출자가 확인한다.
 * 실행 중 취소 지원 여부와 원격 연산에 대한 취소 효과는 각 구현의 계약을 따른다.
 *
 * @param <T> 실행 결과 타입.
 * @author Kang-Woo Lee (ETRI)
 */
public interface RobotTwinOperation<T> extends StartableExecution<T> {
}
