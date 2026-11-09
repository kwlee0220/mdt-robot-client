/**
 * 로봇 트윈의 단위 명령·관측·순차 조합 연산과 실행/진단 계약.
 * <p>
 * 생성자는 통신하지 않으며 한 객체를 한 번 실행한다. 로컬 완료와 도메인 목표 달성은 구분하고,
 * 취소의 효과는 원격 명령·관측·조합별 계약을 따른다.
 * <p>
 * REST 클라이언트, 원격 결과, 기하 타입과 정적 호환 API인
 * {@link mdt.robot.client.Ops}는 {@code mdt.robot.client}에 둔다. 옵션과 판정 결과 타입은 이 패키지에 있다.
 */
package mdt.robot.client.operation;
