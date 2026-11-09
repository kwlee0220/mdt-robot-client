# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`mdt-robot-client` is a Java 21 library (`etri:mdt-robot-client`, version `0.1.0`) for the robot twin REST gateway (`robot_twin` in `~/development/ros/rdfp_ws`). It reads twin state variables and runs twin operations (arm moves, gripper, scene reset, observations, composite pick/place/align). It is a port of the Python [`robot-twin-client`](../../mdtpy/robot-twin-client). There is no `main` apart from the examples.

The project is a git repository. Source comments, Javadoc and the user guide [README.md](README.md) are in Korean. Match that when editing near them. The README is the single user guide: update it whenever behavior or a public name changes.

## Build and run

Composite build: `settings.gradle` includes `../../common/utils` as `:utils`. Library versions come from `../mdt-client/gradle/libs.versions.toml`, so `mdt-client` must be checked out, although it is not part of the build. There is no Gradle wrapper: use the system `gradle`.

```bash
gradle :test                                     # this project's tests only
gradle :test --tests '*PickTest'                 # one class (use ':test': a bare 'test --tests' also filters :utils and fails there)
gradle :compileExamplesJava                      # examples compile without a twin
gradle runExample -Pexample=MoveLinearExample --args='--url http://127.0.0.1:8801 --twin-id panda01'
gradle eclipse
```

- **Examples** live in a separate source set (`src/examples/java`, package `mdt.robot.client.examples`) and are not in the jar. Most of them move the robot. `ExampleSupport.connect(args)` reads `--url` / `--twin-id`.
- **Tests never contact a real twin.** "Tests pass" says nothing about grasping or placing on a real backend.
- `api` dependencies are `:utils`, `jackson-databind` and `okhttp`, because the public API exposes `utils.async` types, `JsonNode` and `OkHttpClient` (`Builder.httpClient`). utils itself declares okhttp as `implementation`, so it is not transitive from there.

## Downstream: update mdt-instance with every public change

`../mdt-instance` includes this project (`include ':mdt-robot-client'`). Its AAS operation providers in `mdt-instance/src/main/java/mdt/assetconnection/operation/robot/` (`PickProvider`, `GraspProvider`, `AlignHeldObjectProvider`, …, base `RobotTwinOperationProvider`) call the operation classes directly. After a public rename or signature change:

```bash
cd ../mdt-instance && gradle compileJava compileTestJava
gradle :test --tests 'mdt.assetconnection.operation.robot.*'
```

Its robot README (`.../operation/robot/README.md`) documents the AAS inputs and outputs. AAS variable names (`TravelZ`, `HoldTolerance`, …) are model contracts in the deployed `model.json` files under `~/mdt/models/` and are not renamed together with Java names.

## The twin server, as this client relies on it

- **Addressing.** Base URL default is `http://127.0.0.1:8801`. Twin URL: `<base>/api/v1/robot_twins/<id>`. Twin ids are per environment: Isaac `panda01`, FunctionBay `rdfp01`, mock `mock01` (backends `panda_isaac`, `panda_functionbay`, `panda_mock`). The id pattern is `[A-Za-z0-9_-]+`. An unknown twin path returns FastAPI's code-less `{"detail": ...}` 404. `RobotTwinClient.translate` adds a twin-id hint to it. Use `requireBackend(...)` / `meta().backend` rather than inferring the environment from the id.
- **The mock backend is being removed.** Ignore it in reviews and new work. Only Isaac and FunctionBay matter. Existing mock branches (e.g. in `PickAndPlaceExample`) are marked as removal candidates.
- **Operation kind.** `GET /operations` lists each operation's `kind` (`sync`: POST → 200 terminal; `async`: POST → 202 + `session_endpoint`, then poll; `DELETE` session = cancel). The client caches the catalog. If a restarted twin changed a known operation's kind, the call response shows it. A sync call that gets `RUNNING` attaches to the session (`UnexpectedRpcStateException` from utils). An async call that finishes at start uses that result. Either way the cache is dropped.
- **Error codes.** 409 `RESOURCE_BUSY` carries `error.details` (`occupied_by`, `resource`) and `Retry-After` (`TwinException.getDetails()` / `getRetryAfter()`). Session 404 is `SESSION_NOT_FOUND` (→ `SessionLostException` once a session existed). Cancel: 409 `NOT_CANCELLABLE`, 501 `CANCEL_UNSUPPORTED`. A 202 `RUNNING` + `phase: CANCELING` DELETE response means "accepted, still stopping".
- **Frames.**
  - FunctionBay: both `ee_pose` and `move_linear` use `grasp_center`.
  - Isaac: `ee_pose` is `panda_hand`, while `move_linear` commands are `panda_link8`. The two have the same position but differ by 45° about z. Re-commanding an observed pose therefore needs `PandaHand.armCommandFromArmPose`; otherwise the wrist turns 45° per move.
  - A fingertip (TCP) target needs `PandaHand.toArmCommand` (TCP offset 0.1034 m). Never apply it on FunctionBay.
- **Isaac object freshness (known limitation).** `robot_control/isaac/scene_state_node.py` republishes the latest TF with the current time and never checks the transform's own stamp. If Isaac's TF stops, `scene_objects` keeps arriving as `quality=OK` with fresh `received_at`. `WaitUntilObjectStill`, `MeasureObjectOffset` and `Pick`'s hold verdict cannot detect this. The fix belongs on the server.

## Architecture (`src/main/java/mdt/robot/client`)

- **`mdt.robot.client`**
  - `RobotTwinClient`: reads (`read`, `readRaw`, `readMany`), runs operations (`run(op, inputs[, timeout[, onStarted]])`, `runForOutputs`, `startAsync`) and cancels (`cancel(sessionEndpoint)`).
  - `OperationResult` (`Status` enum).
  - Geometry: `Pose`/`Position`/`Quaternion`, `Geometry`, `PandaHand`.
  - Exceptions: `TwinException`, `SessionLostException`, `OperationTimeoutException`, `OperationFailedException`, `WrongBackendException`, `VariableNotOkException`.
  - `Ops`: static helpers kept for older callers (`failureHint`, `FAILURE_HINTS`, `moveToNamedTargetVerified`).
- **`mdt.robot.client.operation`**: one-shot operation objects (constructors never communicate; run once with `run()` or `start()`/`get()`), on three bases:
  - `AbstractRobotTwinOperation<T>`: one remote operation. Subclasses implement `buildInputs()` and `judgeResult(OperationResult)`. Override `observesAfterRemote` when `judgeResult` waits on an observation. Covers `MoveLinear`, `MoveToNamedTarget`, `MoveToJoints`, `MoveGripperToNamedTarget`, `ResetScene`.
  - `AbstractObservationOperation<R,P>`: local GET polling with immutable progress/diagnostic snapshots (`ObservationDiagnostics`). Covers `WaitUntilArmStill`, `WaitUntilObjectStill`, `WaitForFreshVariable`, `MeasureObjectOffset`.
  - `AbstractStepwiseOperation<S,C>`: sequential composites. A step's child must return a `StepVerdict` (`goalReached()`, `failureReason()`), which is enforced by `Step`'s type. `AbstractStepSequenceOperation` takes a fixed step list (`Grasp`, `Pick`, `Place`). `AlignHeldObject` overrides `nextStep()` to decide steps at run time. Progress is `StepwiseProgress`/`StepResult` (`verdictAs(Class)`, `failureReason()`). `Pick`, `Place` and `AlignHeldObject` also have role-named builders (`Pick.builder(twin).approachPose(..).graspPose(..)…build()`); the positional constructors remain.
  - Shared types: `MotionLimits` (velocity scaling, server `max_duration_sec`, client wait limit = `max(default, maxDuration + 30s)`), `ClosedLoopCheck`, `MoveCheck` (`failureCode` such as `SETTLE_TIMEOUT`), `GripperCheck`. Every result's explanation component is `failureReason` (null when the goal was reached).

## Contracts to preserve

- **A target not reached is a normal result, not an exception.** Every result implements `StepVerdict`. Remote `FAILED`/`CANCELLED` and failed judgements (open loop, settle timeout, hold not confirmed) complete normally with `goalReached=false`. Exceptions mean the request was rejected, communication failed, the session was lost, or the caller cancelled or interrupted. `COMPLETED` never means "reached".
- **Cancellation.**
  - A cancel during `STARTING` is latched and delivered once the session exists. Utils does the same before the session URL is known.
  - The `DELETE` is sent outside the control lock.
  - Once a cancel is accepted, later failures become `CancellationException`.
  - A wait timeout does **not** cancel the remote operation. `OperationTimeoutException.cancel()` / `getSessionUrl()` let the caller do that.
- **Composite results.** `Pick`'s hold verdict lives in `Check.holdVerdict()` / `Progress.holdVerdict()`. The `MEASURE_HELD` step record only says the measurement itself succeeded. The constructor requires that the lift still measures at least 2 × `HoldCheck.toleranceM` even if descent and lift each stop short within their tolerance. `MoveLinear` reaches a target when the **per-axis maximum** error is within `positionToleranceM`, so the check is the length of the vector `max(0, |dᵢ| − both tolerances)` per axis, not the Euclidean distance minus the tolerances (`Pick.shortestReachedDistance`). A vertical lift needs 30 mm by default; a diagonal one needs more.
- **Validation** uses `utils.Preconditions` (`IllegalArgumentException` before any communication). Name inputs go through `OperationInputs.validateName`, poses through `validatePose`.
- **Renames are done outright, without aliases.**
- **Docs and comments describe the current behavior only.** Javadoc, comments, the README and this file carry no change history: no old names, "이전에는/2026-… 이전" notes, migration tables or review item ids.
- **Ops** passes `RuntimeException`/`Error` through and wraps checked causes in `RuntimeExecutionException`.

## Tests (`src/test/java/mdt/robot/client`)

`FakeTwinServer` wraps OkHttp `MockWebServer`.
- `.operation(name, kind)` populates the catalog.
- `.on(method, pathAfterTwinBase, handler)` stubs a route; the last registration wins.
- `requests(prefix)` lists what was sent.

Cancellation and race tests block a handler on a `CountDownLatch`, call `cancel(true)`, release, and then assert that no further `POST`/`GET`/`DELETE` went out (see `PickTest`, `GraspTest`, `AlignHeldObjectTest`). Use `@Timeout` on such classes.

## Code reviews

Code reviews are tracked in `docs/reviews/<name>-review.md` while in progress and deleted when every item is done.
