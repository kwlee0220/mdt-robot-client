package mdt.robot.client.operation;

import java.time.Duration;
import mdt.robot.client.Pose;
import mdt.robot.client.Position;
import mdt.robot.client.Quaternion;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/** 조합 연산이 원격 요청 전에 수행하는 공통 입력 검사. */
final class OperationInputs {
	private OperationInputs() { }
	static Duration defaultTimeout(RobotTwinClient twin) {
		Preconditions.checkNotNullArgument(twin, "twin is null");
		return twin.getOperationTimeout();
	}

	static void validateName(String name, String label) {
		Preconditions.checkNotNullArgument(name, label + " is null");
		Preconditions.checkArgument(!name.isBlank() && name.equals(name.strip()), label + " must be nonblank without surrounding whitespace");
	}

	static void validatePose(Pose pose) {
		Preconditions.checkNotNullArgument(pose, "pose is null");
		Position p = pose.position();
		Quaternion q = pose.orientation();
		for ( double value : new double[] { p.x(), p.y(), p.z(), q.x(), q.y(), q.z(), q.w() } ) {
			Preconditions.checkArgument(Double.isFinite(value), "pose components must be finite");
		}
		Preconditions.checkArgument(q.x() != 0 || q.y() != 0 || q.z() != 0 || q.w() != 0, "quaternion must be nonzero");
	}
}
