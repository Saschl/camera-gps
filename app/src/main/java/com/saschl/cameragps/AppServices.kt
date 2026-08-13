package com.saschl.cameragps

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.core.content.getSystemService
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.sasch.cameragps.sharednew.bluetooth.session.PairingRetryPolicy
import com.sasch.cameragps.sharednew.database.LogDatabase
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import com.sasch.cameragps.sharednew.database.getDatabaseBuilder
import com.saschl.cameragps.service.location.createLocationSource
import com.saschl.cameragps.service.transport.AndroidBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped BLE/location graph (manual wiring — no DI framework). Owns the
 * shared orchestrator so the UI can observe it directly; [LocationSenderService]
 * borrows it for the duration of a foreground session and still tears BLE down
 * in its onDestroy. Android counterpart of iOS's `IosBluetoothController` object.
 *
 * First access must happen on the main thread (all current call sites do):
 * the orchestrator event subscription has to exist before the first connect.
 */
class AppServices(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The platform's single confined dispatcher (threading rule) — deliberately
     * never cancelled; the orchestrator survives service restarts.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val deviceDao: CameraDeviceDAO =
        LogDatabase.getRoomDatabase(getDatabaseBuilder(appContext)).cameraDeviceDao()

    val bluetoothManager: BluetoothManager = appContext.getSystemService()!!

    val transport = AndroidBleTransport(appContext, bluetoothManager)

    val orchestrator = CameraSessionOrchestrator(
        transport = transport,
        locationSource = createLocationSource(appContext),
        deviceDao = deviceDao,
        scope = scope,
        // First auth-error retry immediately: on Android the error only means
        // encryption is still re-establishing, and the retry queues behind it.
        // A 3s first delay would stall every reconnect handshake.
        pairingPolicy = PairingRetryPolicy(firstRetryDelayMs = 0),
    )

    /**
     * Address of the last device whose pairing was rejected by the camera (auth
     * retries exhausted). The UI shows a troubleshooting dialog while non-null;
     * dialog dismissal is local UI state — only a later successful handshake
     * with the same device clears the source and re-arms the dialog.
     */
    private val _pairingFailedDevice = MutableStateFlow<String?>(null)
    val pairingFailedDevice: StateFlow<String?> = _pairingFailedDevice

    init {
        orchestrator.start()
        scope.launch {
            orchestrator.events.collect { event ->
                when (event) {
                    is OrchestratorEvent.PairingFailed ->
                        _pairingFailedDevice.value = event.identifier

                    is OrchestratorEvent.HandshakeCompleted ->
                        if (_pairingFailedDevice.value.equals(
                                event.identifier,
                                ignoreCase = true
                            )
                        ) {
                            _pairingFailedDevice.value = null
                        }

                    else -> Unit
                }
            }
        }
    }

    companion object {
        fun from(context: Context): AppServices =
            (context.applicationContext as CameraGpsApplication).services
    }
}
