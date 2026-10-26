package mdt.robot.client.operation;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

import mdt.robot.client.OperationResult;

/**
 * 순차 연산의 단계 판정과 수신한 원격 결과. 내부 JSON은 읽기 전용으로 취급한다.
 * <p>
 * 판정은 하위 연산 종류에 따라 하나만 채워진다. 이동은 {@code movement}, 그리퍼는 {@code gripper},
 * 물체 정착 관측은 {@code objectStill}, 물체·도구 기준점 측정은 {@code measurement}다.
 * 관측·측정 단계는 원격 연산이 아니므로 {@code remoteResult}가 비어 있다.
 */
public record StepResult<S extends Enum<S>>(S stage, @Nullable MoveCheck movement, @Nullable GripperCheck gripper,
		Optional<OperationResult> remoteResult, @Nullable Throwable failure,
		WaitUntilObjectStill.@Nullable Result objectStill, MeasureObjectOffset.@Nullable Result measurement) {
	/** 이동·그리퍼 단계의 결과. 관측·측정 판정은 없다. */
	public StepResult(S stage, @Nullable MoveCheck movement, @Nullable GripperCheck gripper,
			Optional<OperationResult> remoteResult, @Nullable Throwable failure) {
		this(stage, movement, gripper, remoteResult, failure, null, null);
	}

	public boolean goalReached() {
		if ( failure != null ) { return false; }
		if ( movement != null ) { return movement.reached(); }
		if ( gripper != null ) { return gripper.atGoal(); }
		if ( objectStill != null ) { return objectStill.settled(); }
		return measurement != null && measurement.measured();
	}

	public @Nullable String why() {
		if ( failure != null ) { return failure.toString(); }
		if ( goalReached() ) { return null; }
		if ( movement != null ) {
			if ( movement.why() != null ) { return movement.why(); }
			return "위치 도달 확인 불가: reason=" + movement.reason() + ", errorM=" + movement.errorM();
		}
		if ( gripper != null ) { return gripper.why(); }
		if ( objectStill != null ) { return "물체 정착 확인 불가: " + objectStill.summary(); }
		return measurement != null ? "물체·도구 기준점 측정 불가: " + measurement.summary() : "단계 판정 없음";
	}
}
