package mdt.robot.client.operation;

/**
 * 순차 연산의 하위 취소 전달 상태. ACCEPTED는 실행 전 로컬 취소도 포함하며 원격 정지를 보장하지 않는다.
 * IN_PROGRESS인 결과는 부모 실행이 끝난 뒤에도 갱신될 수 있다.
 */
public enum ChildCancellationState {
	NOT_REQUESTED, NOT_NEEDED, IN_PROGRESS, ACCEPTED, REJECTED, FAILED
}
