package mdt.robot.client.operation;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import mdt.robot.client.OperationResult;
import mdt.robot.client.RobotTwinClient;

import utils.Preconditions;

/**
 * 서버 레시피로 장면을 재배치하고 적용 결과를 확인한다. 에피소드 시작 전에 실행한다.
 * <p>
 * 생성자는 통신하지 않으며 run() 또는 start()/get()으로 한 번 실행한다.
 * 레시피·백엔드 지원 여부는 서버가 검증한다. seed 생략 시 서버 기본값을 사용한다.
 * 현재 서버 기본 seed는 0이므로 같은 레시피·추출 방식에서는 같은 요청 배치가 반복된다.
 * 다양한 배치가 필요하면 호출자가 seed를 바꿔야 한다. 고정 위치 레시피는 seed와 무관하다.
 * 제공 서버는 scene과 arm 자원을 함께 잡으므로 팔 이동 중에는 409 RESOURCE_BUSY로 거부될 수 있다.
 * 원격 FAILED/CANCELLED나 출력 확인 불가는 applied=false 결과로 정상 완료하며,
 * 접수 거부·통신 오류는 ExecutionException의 원인으로 전달한다.
 * <p>
 * 현재 제공 서버에서는 동기 연산이다. start()도 원격 비동기 세션을 만들지 않으므로
 * 동기 HTTP 호출 중 cancel(true)는 false다. 시간 초과·외부 인터럽트는 원격 재배치를
 * 되돌리지 않으며 자동 재시도하지 않는다. 실행·취소의 상세 계약은 공통 기반을 따른다.
 * <p>
 * objects는 서버가 생성해 적용을 요청한 배치다. 물리적 안착 위치나 파지 가능성은 검증하지 않는다.
 * 리셋 직후 scene_objects에는 이전 배치가 남아 있을 수 있다. 선택적 관측 옵션으로 새 발행을
 * 기다리고 판정에 사용한 표본을 받을 수 있다. 새 발행도 리셋 반영·물리적 정착을 증명하지 않는다.
 */
public final class ResetScene extends AbstractRobotTwinOperation<ResetScene.Check> {
	/**
	 * applied는 원격 COMPLETED, 배치의 기본 형식 유효, applied_count와 배열 크기의 일치를 뜻한다.
	 * 빈 objects와 applied_count=0도 유효하다. false의 이유는 why, 원래 출력은 result에서 확인한다.
	 * 배치의 기본 검사는 고유한 비어 있지 않은 name과 유한한 position.x/y/z에 한정한다.
	 * type·dimensions·orientation 등 작업별 요구사항은 호출자가 검사한다. JsonNode 출력은 읽기 전용으로 취급한다.
	 * observation은 선택적 관측 결과이며 관측 미설정/적용 확인 실패 시 empty다.
	 * 관측 미확인은 applied와 why를 바꾸지 않는다. 관측 사유는 observation에서 확인한다.
	 */
	public record Check(boolean applied, @Nullable String why, OperationResult result,
			Optional<WaitForFreshVariable.Result> observation) {
		/** 관측을 수행하지 않은 기존 결과 생성 계약. */
		public Check(boolean applied, @Nullable String why, OperationResult result) {
			this(applied, why, result, Optional.empty());
		}
	}

	private final String m_scene;
	private final @Nullable Long m_seed;
	private final @Nullable WaitForFreshVariable.Options m_observationOptions;

	/** seed를 보내지 않는다. 현재 서버 기본값은 0이지만 이 클래스에서 고정하지 않는다. */
	public ResetScene(RobotTwinClient twin, String scene) {
		this(twin, scene, null, defaultTimeout(twin));
	}

	/** seed는 ROS 서비스의 int64에 맞는 long이며 음수도 허용한다. */
	public ResetScene(RobotTwinClient twin, String scene, long seed) {
		this(twin, scene, seed, defaultTimeout(twin));
	}

	/**
	 * seed=null이면 입력에서 생략한다. timeout은 카탈로그상 비동기 연산의 결과 대기에만 적용된다.
	 * 동기 연산에는 클라이언트 httpTimeout(기본 30초)이 연결·읽기·쓰기에 각각 적용되며,
	 * 전체 호출 시간의 상한은 아니다. 서버 실행 상한은 서버 배포 설정을 따른다.
	 *
	 * @throws IllegalArgumentException scene이 null/공백 문자열이거나 앞뒤 공백을 포함한 경우,
	 *         twin이 null이거나 timeout이 null/0 이하인 경우.
	 */
	public ResetScene(RobotTwinClient twin, String scene, @Nullable Long seed, Duration timeout) {
		this(twin, scene, seed, timeout, null);
	}

	/**
	 * observationOptions가 있으면 적용 확인 후 scene_objects의 새 발행을 기다린다.
	 * 관측 시간 초과/시각 누락은 observation에 담으며 applied를 바꾸지 않는다.
	 * 관측 통신 오류는 FAILED, 관측 중 취소/인터럽트는 CANCELLED이며 원격 결과는 유지한다.
	 * null이면 기존처럼 관측하지 않는다. 관측은 원격 자원 잠금을 연장하지 않는다.
	 */
	public ResetScene(RobotTwinClient twin, String scene, @Nullable Long seed, Duration timeout,
			@Nullable WaitForFreshVariable.Options observationOptions) {
		super(twin, "reset_scene", timeout);
		Preconditions.checkNotNullArgument(scene, "scene is null");
		Preconditions.checkArgument(!scene.isBlank(), "scene must not be blank");
		Preconditions.checkArgument(scene.equals(scene.strip()), "scene must not have surrounding whitespace");
		m_scene = scene;
		m_seed = seed;
		m_observationOptions = observationOptions;
	}

	@Override
	protected Map<String,?> buildInputs() {
		return m_seed == null ? Map.of("scene", m_scene) : Map.of("scene", m_scene, "seed", m_seed);
	}

	@Override
	protected boolean requiresObservation(OperationResult result) {
		return m_observationOptions != null && result.isCompleted();
	}

	@Override
	protected Check convertResult(OperationResult result) throws InterruptedException, ExecutionException {
		Check applied = checkApplied(result);
		if ( !applied.applied() || m_observationOptions == null ) { return applied; }
		WaitForFreshVariable.Result observed = new WaitForFreshVariable(m_twin, "scene_objects", m_observationOptions).run();
		return new Check(true, null, result, Optional.of(observed));
	}

	private Check checkApplied(OperationResult result) {
		if ( !result.isCompleted() ) { return new Check(false, OperationChecks.failureWhy(result), result); }
		JsonNode objects = result.output("objects");
		if ( objects == null || objects.isNull() ) {
			return new Check(false, "objects 정보 없음 — 배치를 확인할 수 없다", result);
		}
		if ( !objects.isArray() ) { return invalid("objects는 배열이어야 한다", result); }
		Set<String> names = new HashSet<>();
		for ( int i = 0; i < objects.size(); ++i ) {
			JsonNode object = objects.get(i);
			JsonNode name = object.path("name");
			if ( !object.isObject() || !name.isTextual() || name.textValue().isBlank() ) {
				return invalid("objects[" + i + "].name은 비어 있지 않은 문자열이어야 한다", result);
			}
			if ( !names.add(name.textValue()) ) { return invalid("중복 물체 이름: " + name.textValue(), result); }
			JsonNode position = object.path("position");
			for ( String axis : new String[] { "x", "y", "z" } ) {
				JsonNode value = position.path(axis);
				if ( !value.isNumber() || !Double.isFinite(value.doubleValue()) ) {
					return invalid("objects[" + i + "].position." + axis + "는 유한한 숫자여야 한다", result);
				}
			}
		}
		JsonNode count = result.output("applied_count");
		if ( count == null || count.isNull() ) {
			return new Check(false, "applied_count 정보 없음 — 적용 개수를 확인할 수 없다", result);
		}
		if ( !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0 ) {
			return invalid("applied_count는 음수가 아닌 long 범위 정수여야 한다. 받은 값: " + count, result);
		}
		if ( count.longValue() != objects.size() ) {
			return new Check(false, "적용 개수 불일치: objects=" + objects.size() + ", applied_count=" + count, result);
		}
		return new Check(true, null, result);
	}

	private static Check invalid(String why, OperationResult result) {
		return new Check(false, "배치 출력 형식 오류: " + why, result);
	}
}
