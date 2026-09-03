# Restructure: Common BLE/Location Core for Alpha GPS

> **Status: implemented (July 2026).** All groups landed in one pass. Deviations from the
> plan below: no tests were written or run (maintainer's call); verification was
> compile-only (`:sharednew` for iosArm64/iosSimulatorArm64/android, `:app:compileDebugKotlin`,
> and an arm64 simulator `xcodebuild` of the iOS app — all green). Two pre-existing,
> unrelated issues surfaced during verification:
> `:app:assembleDebug` fails at `checkDebugAarMetadata` (lifecycle 2.11.0 requires
> compileSdk 37, app is on 36.1), and a generic-destination `xcodebuild` fails on x86_64
> because the `iosX64` target is disabled — build with `ARCHS=arm64`.
> Resulting sizes: `IosBluetoothController` 994 → 588 lines, `LocationSenderService`
> 491 → 346 lines, new common core ~900 lines. Real-camera validation is tracked in
> the Verification section.

## Context

The app transmits phone GPS to Sony cameras over BLE from two platforms. The Sony handshake state machine (`BleSessionCoordinator`), remote-shutter logic (`RemoteControlCoordinator`), and packet encoding are already shared in `sharednew/commonMain` — but everything around them is duplicated and divergent:

- **Android** wraps the shared coordinators in same-named adapter classes (`app/.../service/coordinator/BleSessionCoordinator.kt`, `RemoteControlCoordinator.kt`) plus a hand-bridged `ServiceEvent` copy of the shared `BleSessionEvent`; session state lives in `CameraConnectionManager`; location transmission is a 379-line Android-only `LocationTransmissionCoordinator` that even bypasses the shared `BleGattPort` when writing packets.
- **iOS** puts all orchestration (session map, phases, pairing retry, event handling) inside the 994-line `IosBluetoothController` singleton, with its own 246-line `IosLocationTransmissionManager` re-implementing the same gating/periodic-send logic.
- **No sequential BLE queue exists on either platform.** On Android ≥4 concurrent writers hit the same GATT connection (handshake writes from binder threads, 5s location loop, 3s remote probe, shutter commands); write collisions surface as GATT status 201 "device busy", silently swallowed in `BluetoothGattUtils`. The staged fix (`SequentialGattOperationExecutor.kt`) is entirely commented out and unwired.

Goal: one common orchestration layer in `sharednew/commonMain` that owns session state, event flow, location transmission, and a per-device **sequential BLE operation queue**; platform code shrinks to thin transports and lifecycle shells.

## User-confirmed decisions

1. Keep **native BLE transports** (CDM/`BluetoothGatt` on Android, CoreBluetooth + state restoration on iOS); commonize everything above them.
2. **Big-bang** delivery: one comprehensive restructure, verified at the end.
3. **Do not touch** the uncommitted values: `OLD_LOCATION_THRESHOLD_MS = 30` (use as-is), `distanceFilter = 2.0`, `hasPreciseAccuracyAuthorization` — copy verbatim into new code.
4. **UI unification out of scope.** Android keeps its screens; zero Android UI file changes (companion-map mirror, see below). iOS public controller surface unchanged so no iOS UI changes.
5. **No tests are to be written.** No new dependencies, no DI framework. New common code in `com.sasch.cameragps.sharednew.bluetooth.*`.

## Target architecture

```
     Android UI (unchanged)                     iOS Compose UI (unchanged)
             │                                            │
 LocationSenderService (thin shell)         IosBluetoothController (thin shell)
 FGS/notifications/sounds/intents           CBCentralManager/scan/restore/reconnect
             │                                            │
             └───────────────┬────────────────────────────┘
                             │  (manual wiring per platform)
             CameraSessionOrchestrator            [commonMain, NEW]
               ├─ BleSessionCoordinator / RemoteControlCoordinator  [existing, UNTOUCHED]
               │        │ BleGattPort (existing interface, UNTOUCHED)
               ├─ QueuedBleGattPort               [commonMain, NEW]
               ├─ LocationTransmissionManager     [commonMain, NEW]
               │        │ LocationSource (NEW interface)
               │   AndroidLocationSource (:app) / IosLocationSource (iosMain)
               └─ BleOperationQueue               [commonMain, NEW]
                        │ BlePeripheralTransport (NEW interface)
                  AndroidBleTransport (:app) / IosBleTransport (iosMain)
```

Key decisions:
- **`BleGattPort` stays exactly as-is** as the coordinator-facing API (its KDoc already promises "successfully enqueued" — the queue-backed impl finally makes that true). `BleSessionCoordinator`, `RemoteControlCoordinator`, `LocationPacketBuilder`, `BleSessionEvent/Phase`, `SonyBluetoothConstants` are reused unchanged (except adding two ATT error constants).
- **Threading: single-dispatcher confinement on `Dispatchers.Main.immediate`** on both platforms. All common mutable state is touched only from one orchestrator scope injected by the platform shell. Platform callbacks (Android binder threads, iOS main queue) only `trySend` into a `Channel(UNLIMITED)`-backed event flow. Matches the CoreBluetooth main-queue contract; Android BLE APIs are thread-agnostic; zero locks.

## New commonMain files

All under `sharednew/src/commonMain/kotlin/com/sasch/cameragps/sharednew/bluetooth/`.

### `transport/BlePeripheralTransport.kt` — the only per-platform BLE contract

```kotlin
enum class BleOperationStatus { Success, Failure, AuthError }

sealed interface BleTransportEvent {
    val identifier: String  // uppercased MAC (Android) / peripheral UUID string (iOS)
    data class Connected(...) ; data class Disconnected(..., val statusCode: Int?)
    data class ServicesDiscovered(..., val success: Boolean)   // Android: onServicesDiscovered; iOS: 2-phase discovery + pairing gate done
    data class CharacteristicRead(..., val characteristicUuid: String, val value: ByteArray, val status: BleOperationStatus)
    data class CharacteristicWritten(..., val characteristicUuid: String, val status: BleOperationStatus)
    data class SubscriptionChanged(..., val characteristicUuid: String, val enabled: Boolean, val status: BleOperationStatus)
    data class CharacteristicChanged(..., val characteristicUuid: String, val value: ByteArray)  // camera-initiated notification
}

interface BlePeripheralTransport {
    val events: Flow<BleTransportEvent>   // Channel(UNLIMITED)-backed; single collector: the orchestrator
    fun isConnected(identifier: String): Boolean
    fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean
    // Initiation only; completion arrives via events. false = could not initiate.
    fun initiateWrite(identifier: String, characteristicUuid: String, value: ByteArray): Boolean
    fun initiateRead(identifier: String, characteristicUuid: String): Boolean
    fun initiateSubscribe(identifier: String, characteristicUuid: String, enable: Boolean): Boolean
    fun initiateDiscoverServices(identifier: String): Boolean
}
```

### `transport/BleOperationQueue.kt` — the sequential executor

Resurrects the commented-out `SequentialGattOperationExecutor` design (Channel + worker + `CompletableDeferred` + `withTimeout` + UUID completion matching) but in commonMain, multi-device, against `BlePeripheralTransport`:

```kotlin
sealed interface BleOperation { Write(uuid, value) / Read(uuid) / Subscribe(uuid, enable) / DiscoverServices }
sealed interface BleOperationResult { Success(value: ByteArray?) / Failure(status) / Timeout / NotInitiated / Cancelled }

class BleOperationQueue(
    private val transport: BlePeripheralTransport,
    private val scope: CoroutineScope,             // orchestrator's Main-confined scope
    private val operationTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 30_000, // iOS pairing gate can take 3×3s retries
) {
    suspend fun execute(identifier: String, op: BleOperation): BleOperationResult  // orchestrator use (discovery, retries)
    fun enqueue(identifier: String, op: BleOperation): Boolean                     // fire-and-forget (QueuedBleGattPort)
    fun onTransportEvent(event: BleTransportEvent)  // completion matching; called for EVERY transport event, first
    fun cancelOperations(identifier: String, reason: String)  // fail pending + drain lane; on disconnect
    fun shutdown(reason: String)
}
```

Internals: `Map<String, DeviceLane>`, lane = `Channel<QueuedOp>(UNLIMITED)` + worker Job + `pending`. Worker: take op → `transport.initiateX()` (false → `NotInitiated`, continue) → `withTimeout { deferred.await() }`. Completion matched by (op kind, uuid case-insensitive); `ServicesDiscovered` completes `DiscoverServices`; `Disconnected` auto-cancels the lane. Single dispatcher → no locks.

### `session/CameraSessionRegistry.kt`

```kotlin
data class CameraSession(
    val identifier: String,                    // uppercased
    val phase: BleSessionPhase = BleSessionPhase.Connecting,
    val remoteFeatureActive: Boolean = false,
    val pairingRetryCount: Int = 0,
    val hasRetriedConfigRead: Boolean = false, // preserves Android GATT-133 one-shot retry
)
class CameraSessionRegistry {
    val sessions: StateFlow<Map<String, CameraSession>>
    fun upsert(id, transform) / remove(id) / clear() / get(id)
    fun readyIdentifiers(): Set<String>  // phase == Transmitting
    fun activeCount(): Int
}
```

Replaces `CameraConnectionManager`'s state/remoteFeatureActive fields and iOS's `PeripheralSession`/`PeripheralPhase`. (`locationDataConfig` already lives in shared `BleSessionCoordinator.locationConfigs`.)

### `session/QueuedBleGattPort.kt` — the linchpin

`internal class QueuedBleGattPort(queue, transport, registry) : BleGattPort` — write/read/subscribe → `queue.enqueue(...)`; isConnected/hasCharacteristic → transport; remote-feature get/set → registry. **Every** write (handshake, 3s probe, shutter, location packets) now flows through one per-device lane — fixing the ≥4-writer collision and closing the Android location-write bypass.

### `session/PairingRetryPolicy.kt`

`data class PairingRetryPolicy(maxRetries = 3, retryDelayMs = 3_000)` — today's iOS constants. Add `ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 5` / `ATT_ERROR_INSUFFICIENT_ENCRYPTION = 15` to `SonyBluetoothConstants` (same values on Android GATT).

### `session/OrchestratorEvent.kt`

```kotlin
sealed interface OrchestratorEvent {
    DeviceConnected(id) / DeviceDisconnected(id) / HandshakeCompleted(id) / PairingFailed(id)
    FirstLocationAcquired / LocationUnavailable
}
```

Replaces app-side `ServiceEvent`/`ServiceEventBus`. Phase/remote state is no longer event-shaped — it's the registry `sessions` StateFlow.

### `location/LocationSource.kt`

```kotlin
data class GeoLocation(latitude: Double, longitude: Double,
    horizontalAccuracyMeters: Double,  // < 0 invalid (iOS convention, preserved)
    timestampMillis: Long)

interface LocationSource {
    val locations: Flow<GeoLocation>   // hot while started; may emit from any thread
    fun start()   // idempotent; Android also fires one-shot getCurrentLocation/lastKnown seed
    fun stop()
    fun hasPreciseAuthorization(): Boolean  // iOS: CLAccuracyAuthorizationFullAccuracy; Android: true
}
```

### `location/LocationTransmissionManager.kt`

```kotlin
class LocationTransmissionManager(
    source: LocationSource,
    readySessions: () -> Set<String>,
    configFor: (String) -> LocationDataConfig?,   // sessionCoordinator::getLocationDataConfig
    port: BleGattPort,                            // the QueuedBleGattPort — all sends queued
    scope: CoroutineScope,
) {
    val events: Flow<LocationEvent>   // FirstFixAcquired / NoLocationAvailable
    val isActive: StateFlow<Boolean>  // feeds iOS isTransmissionActive
    fun onDeviceReady(identifier: String)  // start if needed + immediate cached send to that device
    fun updateTracking()                   // stop when no ready sessions (iOS parity)
    fun shutdown()
}
```

Unifies `LocationTransmissionCoordinator` + `IosLocationTransmissionManager`, lifted verbatim:
- `shouldUpdateLocation`: accuracy-delta ≤ `ACCURACY_THRESHOLD_METERS` accepts, else time-delta > `OLD_LOCATION_THRESHOLD_MS` accepts (identical today on both platforms; keep the iOS `accuracy < 0` guard). **`OLD_LOCATION_THRESHOLD_MS = 30` used as-is.**
- Session-start staleness reset (from `LocationTransmissionCoordinator.kt:160-172`); immediate-send-after-handshake with `MAX_IMMEDIATE_FIX_AGE_SECONDS = 300` (iOS `sendImmediateIfCached`); first accepted fix → send to all ready + emit `FirstFixAcquired`.
- One periodic coroutine loop (`delay(LOCATION_UPDATE_INTERVAL_MS)`; latest fix → send to all ready, else `NoLocationAvailable`) replaces the Android `Handler` loop and iOS `transmissionJob`.
- `sendToDevice`: `LocationPacketBuilder.buildLocationDataPacket(configFor(id) ?: LocationDataConfig(false), lat, lng, PlatformTimeZoneInfo())` → `port.writeCharacteristic(id, CHARACTERISTIC_UUID, packet)`.

### `session/CameraSessionOrchestrator.kt`

```kotlin
class CameraSessionOrchestrator(
    transport: BlePeripheralTransport,
    locationSource: LocationSource,
    deviceDao: CameraDeviceDAO,
    scope: CoroutineScope,                                   // Main-confined, platform-created
    pairingPolicy: PairingRetryPolicy = PairingRetryPolicy(),
    shouldRemainConnected: suspend (String) -> Boolean = { true },  // iOS: isDeviceEnabled check
) {
    val registry = CameraSessionRegistry()
    // internally: queue, QueuedBleGattPort, RemoteControlCoordinator(port, scope),
    //             BleSessionCoordinator(port, remoteControl), LocationTransmissionManager
    val sessions: StateFlow<Map<String, CameraSession>>
    val events: Flow<OrchestratorEvent>
    fun start()
    fun onConnectRequested(id) / triggerRemoteShutter(id): Boolean / setRemoteMonitoring(id, enabled)
    fun clearDevice(id)      // cancel queue ops + clearSession + registry.remove + locationManager.updateTracking()
    fun shutdownAll()        // clearAllSessions + cancelAllProbes + queue.shutdown + locationManager.shutdown + registry.clear
    fun connectedDeviceCount(): Int
}
```

`start()` launches collectors on `scope`:

1. **`transport.events`** — always `queue.onTransportEvent(event)` FIRST, then:
   - `Connected` → phase `Connected`, emit `DeviceConnected`, launch discovery: phase `DiscoveringServices`; `queue.execute(id, DiscoverServices)`; Success → `sessionCoordinator.beginHandshake(id)`; failure/timeout → phase `Error`.
   - `Disconnected` → `clearDevice(id)`, emit `DeviceDisconnected`.
   - `CharacteristicWritten` — `AuthError`: pairing retry (increment count; exhausted → `PairingFailed`, shell disconnects; else `delay(retryDelayMs)` then `beginHandshake` — today's iOS `retryAfterPairing`). Otherwise reset count, `sessionCoordinator.onCharacteristicWrite(id, uuid, status == Success)`.
   - `CharacteristicRead` — `AuthError`: retry read after delay (iOS parity). `Failure` on `CHARACTERISTIC_READ_UUID`: one-shot retry via `hasRetriedConfigRead` (preserves Android GATT-133 retry from `app/.../coordinator/BleSessionCoordinator.kt:122-139`), else forward `success=false` → phase `Error`. `Success` → forward.
   - `SubscriptionChanged` — `AuthError`: retry per policy; else log-only (today's behavior).
   - `CharacteristicChanged` → `sessionCoordinator.onCharacteristicChanged(id, uuid, value)`.
2. **`sessionCoordinator.events`** — `PhaseChanged` → registry upsert. `HandshakeComplete` → if `!shouldRemainConnected(id)` emit nothing further (iOS shell disconnects the disabled device); else phase `Transmitting`, `locationManager.onDeviceReady(id)`, `if (deviceDao.isRemoteControlEnabled(id)) remoteControl.startRemoteStatusMonitoring(id)`, emit `HandshakeCompleted`. (Unifies `LocationSenderService.handleEvent`'s HandshakeComplete branch + `IosBluetoothController`'s init-block — near-identical today.)
3. **`remoteControl.events`** — `RemoteFeatureActivated/Deactivated` → registry upsert `remoteFeatureActive`.
4. **`locationManager.events`** — re-emit as `FirstLocationAcquired` / `LocationUnavailable`.

## Tricky seams

**(a) Callback → completion, race-free.** Platform delegates/callbacks only `trySend(BleTransportEvent)` (never touch shared state). Orchestrator is the single collector; it calls `queue.onTransportEvent` before coordinator routing, all on one dispatcher — deterministic ordering, no torn state. iOS quirk: `didUpdateValueForCharacteristic` serves both read responses and notifications — keep the current UUID split (`CHARACTERISTIC_READ_UUID` → `CharacteristicRead`; anything else, e.g. `REMOTE_STATUS_UUID` → `CharacteristicChanged`); safe because the config read is the protocol's only read.

**(b) Discovery asymmetry.** `DiscoverServices` = "whatever this platform needs before `beginHandshake`". Android: `gatt.discoverServices()` → `onServicesDiscovered` emits the event. iOS: `discoverServices([location, control, remote])` → per-service `discoverCharacteristics` → when all cached, run the **pairing gate** (current logic from `IosBluetoothController.kt:247-300`: subscribe to first notifiable characteristic to force pairing; `didUpdateNotificationState` auth error → retry per policy, exhausted → `ServicesDiscovered(success=false)` → shell cancels connection; success → emit `ServicesDiscovered(true)`). 30s discovery timeout accommodates 3×3s retries. Characteristic lookup: Android searches `gatt.services` live; iOS transport builds a per-peripheral `Map<String, CBCharacteristic>` at discovery (kills `IosSonyBleConstants` CBUUID table and the hand-maintained CBUUID↔string mappings).

**(c) Pairing/bonding asymmetry.** Android: bonding stays pre-connection (`BOND_BONDED` check + `connectGatt(autoConnect=true)`); GATT 5/15 still map to `AuthError` for uniformity (effectively never fire when bonded). iOS: pairing gate in transport (seam b) + mid-handshake ATT 5/15 mapped to `AuthError` op results handled by the orchestrator's policy (read → retry read; write → restart handshake; subscribe → retry). Retry count per session, reset on success, `PairingFailed` on exhaustion → shell cancels connection.

**(d) Connect/disconnect discovery.** Transports own sockets, orchestrator owns sessions. Android: transport's internal `BluetoothGattCallback` (replaces `BluetoothGattCallbackHandler`) emits `Connected`/`Disconnected(status)`; `pauseDevice(id)` emits synthetic `Disconnected(null)` **without closing the gatt** (preserves autoConnect resume; mirrors `CameraConnectionManager.pauseDevice`); `disconnectAll()` is the only close path. iOS: central delegate stays in the controller; `didConnect`/`willRestoreState` → `transport.attachPeripheral(peripheral)` (+ emits `Connected` → orchestrator drives discovery same as fresh connect); `didDisconnect` → `detachPeripheral` → `Disconnected`.

## Platform changes

### Android (`app/src/main/java/com/saschl/cameragps/`)

**CREATE `service/transport/AndroidBleTransport.kt`** (~250 lines) — implements `BlePeripheralTransport`. Absorbs `CameraConnectionManager` (gatt registry, bond check, `autoConnect=true`, disconnectAll/pause), `BluetoothGattCallbackHandler`, and `BluetoothGattUtils` (API-33 write branching; **status-201-as-success kept verbatim**; CCCD descriptor write for subscribe, completed on `onDescriptorWrite`). Extra platform methods: `connect(mac)`, `disconnectAll()`, `pauseDevice(mac)`, `connectedCount()`.

**CREATE `service/location/AndroidLocationSource.kt`** (~180 lines) — implements `LocationSource`. Lifts provider plumbing from `LocationTransmissionCoordinator`: `PreferencesManager.getLocationProvider` switch, fused client (`PRIORITY_HIGH_ACCURACY`, 5s interval, 10m min distance, `getCurrentLocation` seed), `LocationManager` FUSED/GPS/NETWORK fallback, provider-enabled guard. Stays in `:app` (play-services-location is app-only).

**MODIFY `service/LocationSenderService.kt`** (rewrite ~491 → ~250 lines). Keeps: companion maps + `isRunning`, foreground start, `NotificationsHelper`, `EventSoundPlayer`, BT-state receiver + BT-off shutdown, `commandMutex` + `ServiceCommandRouter`, logging init, `START_REDELIVER_INTENT`. New `onCreate` wiring:
```kotlin
transport = AndroidBleTransport(applicationContext, bluetoothManager)
orchestrator = CameraSessionOrchestrator(transport, AndroidLocationSource(this), deviceDao,
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
orchestrator.start()
lifecycleScope.launch { orchestrator.events.collect(::handleEvent) }      // sounds/notifications/shutdown
lifecycleScope.launch { orchestrator.sessions.collect(::mirrorToCompanionMaps) }
```
**UI bridge: mirror, don't re-point** — `mirrorToCompanionMaps` diff-applies the registry snapshot into the three `mutableStateMapOf`s (only `ui/AssociatedDevicesList.kt:70-73` reads them; zero UI files change). Commands: `Connect` → `orchestrator.onConnectRequested` + `transport.connect`; shutter/monitoring → orchestrator; `Shutdown`/always-on → `ServiceShutdownCoordinator`. `DeviceConnected/Disconnected` events drive connect/disconnect sounds + notification-count logic (via `orchestrator.connectedDeviceCount()`). `onDestroy`: `orchestrator.shutdownAll()` + `transport.disconnectAll()` + clear maps.

**MODIFY `service/coordinator/ServiceShutdownCoordinator.kt`** — swap `CameraConnectionManager`/`ServiceEventBus` deps for `AndroidBleTransport` + a shutdown-request callback; semantics unchanged (always-on checks, `pauseDevice` for disappeared non-always-on devices, `disconnectAll` for "all").

**KEEP unchanged:** `ServiceCommand(Router).kt`, `CameraDeviceCompanionService.kt` (CDM presence → intents), `BluetoothStateBroadcastReceiver.kt`, `EventSoundPlayer.kt`, `NotificationsHelper`, reboot/restart receivers, all `ui/**`.

### iOS (`sharednew/src/iosMain/kotlin/com/sasch/cameragps/sharednew/bluetooth/`)

**CREATE `IosBleTransport.kt`** (~350 lines) — implements `BlePeripheralTransport`. Owns the peripheral delegate (moved from controller, now emitting transport events), per-peripheral handles + `characteristicsByUuid` cache, two-phase discovery + pairing gate (seam b), ATT-5/15 → `AuthError` mapping, NSData↔ByteArray helpers, write `WithResponse`/read/`setNotifyValue` (idempotent-success when already subscribed). Internal API: `attachPeripheral(peripheral)`, `detachPeripheral(identifier)`.

**CREATE `IosLocationSource.kt`** (~120 lines) — implements `LocationSource`. Lifts from `IosLocationTransmissionManager`: CLLocationManager config (**`distanceFilter = 2.0`**, `kCLLocationAccuracyBest`, background updates on, no auto-pause — byte-for-byte), delegate (fix emission; WhenInUse→Always escalation), `hasPreciseAuthorization()` = `CLAccuracyAuthorizationFullAccuracy` (**preserved**).

**MODIFY `IosBluetoothController.kt`** (shrinks ~994 → ~450 lines). Keeps: `object` + `ensureInitialized()` (AppDelegate contract), restore identifier, central delegate (0x012D scan filter, `willRestoreState`, connect/fail/disconnect + auto-reconnect via `IosAutoReconnectStore` + `retrievePeripheralsWithIdentifiers`), connect/disconnect callback plumbing, app/device-enabled logic, `deviceDao`, device-record helpers. New wiring: constructs `IosBleTransport`, `IosLocationSource`, `CameraSessionOrchestrator(..., shouldRemainConnected = ::isDeviceEnabled)`; `refreshDeviceList` becomes a `combine(orchestrator.sessions, orchestrator.locationManager.isActive, local device state)` → `_devices`. `PairingFailed` event → `central.cancelPeripheralConnection`. **Public surface unchanged** (`devices`, connect/disconnect/forgetDevice, `triggerRemoteShutter`, `setRemoteStatusMonitoringEnabled`, `applyDeviceEnabledState`, `applyAppEnabledState`, `ensureDeviceRecord`, `deviceDao`, `hasPreciseAccuracyAuthorization`) → no iOS UI changes. Deleted from it: peripheral delegate, `PeripheralSession`/`PeripheralPhase`, pairing-retry methods, both init-block collectors, `IosSonyBleConstants`, NSData helpers, direct coordinator/port instances.

## Deletions

| File | Replaced by |
|---|---|
| `app/.../service/CameraConnectionManager.kt` | `AndroidBleTransport` + `CameraSessionRegistry` |
| `app/.../service/coordinator/BleSessionCoordinator.kt` (adapter) | orchestrator + queue (incl. 133-retry) |
| `app/.../service/coordinator/RemoteControlCoordinator.kt` (adapter) | orchestrator |
| `app/.../service/coordinator/AndroidBleGattPort.kt` | `QueuedBleGattPort` + `AndroidBleTransport` |
| `app/.../service/coordinator/LocationTransmissionCoordinator.kt` | common manager + `AndroidLocationSource` |
| `app/.../service/LocationDataConverter.kt` | manager calls `LocationPacketBuilder` directly |
| `app/.../service/BluetoothGattUtils.kt` | absorbed into `AndroidBleTransport` |
| `app/.../service/ServiceEvent.kt` + `ServiceEventBus.kt` | `OrchestratorEvent` + registry StateFlow |
| `app/.../service/SequentialGattOperationExecutor.kt` (untracked, commented) | design lives on in `BleOperationQueue` |
| `sharednew/iosMain/.../IosBleGattPort.kt` | `QueuedBleGattPort` + `IosBleTransport` |
| `sharednew/iosMain/.../IosLocationTransmissionManager.kt` | common manager + `IosLocationSource` |
| `sharednew/iosMain/.../SonyLocationTransmissionUtils.kt` | unreferenced (KDoc-only mention) |

Survivors: `IosAutoReconnectStore.kt`, `ServiceCommand(Router).kt`, `ServiceShutdownCoordinator.kt` (modified), `CameraDeviceCompanionService.kt`, `BluetoothStateBroadcastReceiver.kt`, `EventSoundPlayer.kt`, all shared coordinators/constants/models, all UI.

## Implementation order (big-bang branch, no tests)

**Group 1 — common core** (compiles standalone; nothing consumes it yet)
1. `transport/BlePeripheralTransport.kt` (interface + events + status enum)
2. `transport/BleOperationQueue.kt`
3. `session/CameraSessionRegistry.kt`, `PairingRetryPolicy.kt`, `OrchestratorEvent.kt`, `QueuedBleGattPort.kt`; add `ATT_ERROR_*` to `SonyBluetoothConstants.kt`
4. `location/LocationSource.kt`, `location/LocationTransmissionManager.kt`
5. `session/CameraSessionOrchestrator.kt`
6. Checkpoint: `./gradlew :sharednew:compileKotlinIosArm64` + `:sharednew` android compile

**Group 2 — iOS rewiring**
7. `iosMain/IosBleTransport.kt` (move peripheral delegate, pairing gate, characteristic cache, NSData helpers)
8. `iosMain/IosLocationSource.kt`
9. Rewrite `IosBluetoothController.kt` (public surface unchanged)
10. Delete `IosBleGattPort.kt`, `IosLocationTransmissionManager.kt`, `SonyLocationTransmissionUtils.kt`
11. Checkpoint: `./gradlew :sharednew:compileKotlinIosArm64 :sharednew:compileKotlinIosSimulatorArm64`

**Group 3 — Android rewiring**
12. `app/service/transport/AndroidBleTransport.kt`
13. `app/service/location/AndroidLocationSource.kt`
14. Rewrite `LocationSenderService.kt`; modify `ServiceShutdownCoordinator.kt`
15. Delete the 9 Android files listed above
16. Checkpoint: `./gradlew :app:assembleDebug`

**Group 4 — final verification** (below) + `git status` sanity (only intended files; leave `tools/` untouched)

## Risks & mitigations

1. **Handshake order drift** (Sony cameras are sequence-sensitive): shared `BleSessionCoordinator` is not modified at all; all queue traffic preserves enqueue order per device. Real-camera validation by user at the end.
2. **Queue changes timing** (a packet now waits behind a stuck op, up to 15s): per-op timeout, lane continues after timeout, handshake-op timeout surfaces phase `Error`. Probe/location packets complete in ~tens of ms on healthy links.
3. **iOS pairing gate regression** (most fragile code): gate logic moved verbatim with same constants (3 retries/3s/ATT 5/15); restoration path emits the same effective call sequence; `willRestoreState`/auto-reconnect untouched in controller.
4. **Android autoConnect pause/resume**: `pauseDevice` must not close the gatt — explicit transport method with synthetic `Disconnected(null)`, gatt handle retained; `disconnectAll` is the only close path (mirrors `CameraConnectionManager` exactly).
5. **Main-confinement + Room**: DAO calls from Main are `suspend` (Room KMP moves them off-thread); current code already collects on `lifecycleScope` (Main). No ANR risk.
6. **Additive resilience note**: unification gives iOS the one-shot config-read retry it never had, and Android the auth-error retry it never had — strictly additive, but flag for the real-camera pass.
7. **Companion-map mirror recompositions**: diff-apply (set changed keys, remove missing) instead of clear+rebuild.
8. **Uncommitted-value preservation**: final grep for `OLD_LOCATION_THRESHOLD_MS` (=30), `distanceFilter = 2.0`, `CLAccuracyAuthorizationFullAccuracy` confirming verbatim copies.

## Verification

```bash
# Existing common tests must stay green (RemoteControlCoordinatorTest — untouched code)
./gradlew :sharednew:allTests

# iOS compilation, both targets
./gradlew :sharednew:compileKotlinIosArm64 :sharednew:compileKotlinIosSimulatorArm64

# Android app
./gradlew :app:assembleDebug

# iOS app shell (resolve scheme first; Xcode runs :sharednew:embedAndSignAppleFrameworkForXcode)
xcodebuild -list -project iosApp/alphagps.xcodeproj
xcodebuild -project iosApp/alphagps.xcodeproj -scheme <scheme> \
  -destination 'generic/platform=iOS Simulator' build
```

Real-camera validation (user): Android connect → handshake sounds → GPS icon on camera → remote shutter; iOS cold-launch restoration → auto-reconnect → pairing with a factory-reset camera (gate + retry path) → background location continuity.
