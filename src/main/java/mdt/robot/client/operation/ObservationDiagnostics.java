package mdt.robot.client.operation;

import java.util.Map;

/**
 * 관측 연산이 게시하는 불변 진단의 공통 조회 계약. 전용 거부 사유는 연산별 enum을 유지한다.
 * <p>
 * reads = validSamples + rejectedSamples + lateSamples,
 * validSamples = newSamples + duplicateSamples다. newSamples는 누적 비중복 유효 표본 수이며,
 * 최초 기준 표본과 시각 역행 후 기준도 포함한다. 현재 구간의 갱신 횟수(updates)와 다르다.
 * 진행 중이거나 실패한 HTTP는 집계하지 않는다. resetCount는 최초 구간 설정을 제외한다.
 * 스냅샷은 이후 실행으로 바뀌지 않으며, 상태·전용 판정은 각 연산의 결과와 실행 상태로 확인한다.
 *
 * @param <E> 연산별 표본 거부 사유.
 */
public interface ObservationDiagnostics<E extends Enum<E>> {
	long reads();
	long validSamples();
	long newSamples();
	long duplicateSamples();
	long rejectedSamples();
	long lateSamples();
	long resetCount();
	Map<E,Long> rejectionCounts();
}
