package mdt.robot.client.operation;

import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;

/**
 * 그리퍼 판정 결과. 원격 실패나 정보 누락·형식 오류도 atGoal=false이며 why로 구분한다.
 * stalled는 JSON Boolean true만 인정하는 관측값이다. false에는 누락·형식 오류도 포함되며,
 * 원래 값과 다른 출력은 result에서 조회한다. stalled만으로 성공을 판단하지 않는다.
 */
public record GripperCheck(boolean atGoal, boolean stalled, @Nullable String why, OperationResult result) { }
