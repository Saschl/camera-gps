package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.annotation.RequiresPermission
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants.locationTransmissionNotificationId
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionOrchestrator
import com.sasch.cameragps.sharednew.bluetooth.session.OrchestratorEvent
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.R
import com.saschl.cameragps.notification.NotificationsHelper
import com.saschl.cameragps.service.coordinator.ServiceShutdownCoordinator
import com.saschl.cameragps.service.transport.AndroidBleTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Thin Android lifecycle shell. Owns the foreground service, notifications,
 * sounds, intent routing and stopSelf(). All BLE/location/session logic lives
 * in the shared [CameraSessionOrchestrator]; the raw GATT/location plumbing in
 * [AndroidBleTransport] and the flavor-provided
 * [com.sasch.cameragps.sharednew.bluetooth.location.LocationSource].
 */
class LocationSenderService : LifecycleService() {

    private var isInitialized = true
    private lateinit var eventSoundPlayer: EventSoundPlayer
    private lateinit var bluetoothStateReceiver: BluetoothStateBroadcastReceiver
    private val commandMutex = Mutex()
    private val commandRouter = ServiceCommandRouter()

    // The BLE/location graph is app-scoped (see [AppServices]); the service
    // borrows it for the duration of a foreground session.
    private val services get() = AppServices.from(this)
    private val orchestrator get() = services.orchestrator
    private val transport get() = services.transport
    private val bluetoothManager get() = services.bluetoothManager

    private val shutdownCoordinator by lazy {
        ServiceShutdownCoordinator(services.deviceDao, services.transport) { startId ->
            requestShutdown(startId)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    // ==================== Lifecycle ====================

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        eventSoundPlayer = EventSoundPlayer(this)
        NotificationsHelper.createNotificationChannel(this)

        if (!startAsForegroundService()) return

        lifecycleScope.launch {
            orchestrator.events.collect { event -> handleEvent(event) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        runCatching {
            if (::bluetoothStateReceiver.isInitialized) unregisterReceiver(bluetoothStateReceiver)
        }.onFailure { e ->
            Timber.e(e, "Failed to unregister Bluetooth state receiver")
        }
        orchestrator.shutdownAll()
        transport.disconnectAll()
        if (::eventSoundPlayer.isInitialized) eventSoundPlayer.release()
        Timber.i("Destroyed service")
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!bluetoothManager.adapter.isEnabled) {
            Timber.w("Bluetooth is disabled, will shutdown service")
            startAsForegroundService()
            requestShutdown(startId)
            return START_NOT_STICKY
        }

        lifecycleScope.launch {
            commandMutex.withLock {
                handleStartCommand(intent, startId)
                Timber.i(
                    "processed start command $startId with intent action ${intent?.action} and address ${
                        intent?.getStringExtra(
                            "address"
                        )
                    }"
                )
            }
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission")
    private fun handleEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.DeviceConnected -> {
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_CONNECTED)
                val notification = NotificationsHelper.buildNotification(
                    this,
                    orchestrator.connectedDeviceCount()
                )
                NotificationsHelper.showNotification(
                    this,
                    locationTransmissionNotificationId,
                    notification
                )
            }

            is OrchestratorEvent.DeviceDisconnected -> {
                eventSoundPlayer.play(TransmissionSoundEvent.CAMERA_DISCONNECTED)
                updateNotificationAfterDisconnect()
            }

            is OrchestratorEvent.HandshakeCompleted -> {
                // Setup is done — the periodic location writes don't need the
                // short connection interval requested at connect
                transport.relaxConnection(event.identifier)
            }

            is OrchestratorEvent.PairingFailed -> {
                // Pairing-failure UI state is tracked in AppServices
                Timber.e("Pairing failed for ${event.identifier}")
            }

            is OrchestratorEvent.FirstLocationAcquired -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_ACQUIRED)
            }

            is OrchestratorEvent.LocationUnavailable -> {
                eventSoundPlayer.play(TransmissionSoundEvent.LOCATION_INVALID)
            }
        }
    }

    // ==================== Command handling ====================

    @SuppressLint("MissingPermission")
    private suspend fun handleStartCommand(intent: Intent?, startId: Int) {
        when (val command = commandRouter.route(intent)) {
            is ServiceCommand.Ignore -> {
                Timber.w(command.reason)
            }

            is ServiceCommand.ReconnectAlwaysOn -> {
                shutdownCoordinator.handleNoAddress(startId)
            }

            is ServiceCommand.Shutdown -> {
                shutdownCoordinator.handleShutdownRequest(command.address, startId)
            }

            is ServiceCommand.TriggerRemoteShutter -> {
                val success = orchestrator.triggerRemoteShutter(command.address)
                if (!success) {
                    Timber.w("Remote shutter request failed for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.SendRemoteCommand -> {
                val success = orchestrator.sendRemoteCommand(command.address, command.command)
                if (!success) {
                    Timber.w("Remote command ${command.command} failed for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.TriggerShutterSequence -> {
                val success = orchestrator.triggerShutterSequence(command.address)
                if (!success) {
                    Timber.w("Shutter sequence failed to start for ${command.address.uppercase()}")
                }
            }

            is ServiceCommand.SetRemoteControlMonitoring -> {
                orchestrator.setRemoteMonitoring(command.address, command.enabled)
            }

            is ServiceCommand.Connect -> {
                ensureBluetoothStateReceiver()
                if (!transport.hasConnection(command.address)) {
                    Timber.i("Service initialized")
                    orchestrator.onConnectRequested(command.address)
                    runCatching {
                        transport.connect(command.address)
                    }.onFailure {
                        Timber.e("Failed to connect to device, bluetooth is likely turned off")
                        orchestrator.onConnectFailed(command.address)
                    }
                }
            }
        }
    }

    private fun ensureBluetoothStateReceiver() {
        if (!::bluetoothStateReceiver.isInitialized) {
            bluetoothStateReceiver = BluetoothStateBroadcastReceiver { enabled ->
                if (!enabled) {
                    Timber.w("Bluetooth turned off, will shutdown service")
                    requestShutdown()
                }
            }
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    // ==================== Notification helpers ====================

    private fun updateNotificationAfterDisconnect() {
        val connectedCount = orchestrator.connectedDeviceCount()
        if (connectedCount == 0) {
            val notification = NotificationsHelper.buildNotification(
                this,
                getString(R.string.app_standby_title),
                getString(R.string.app_standby_content)
            )
            NotificationsHelper.showNotification(
                this,
                locationTransmissionNotificationId,
                notification
            )
            Timber.d("No active cameras remaining")
        } else {
            Timber.d("Active cameras remaining, updating notification")
            val notification = NotificationsHelper.buildNotification(
                this,
                connectedCount,
                channelId = NotificationsHelper.DISCONNECT_NOTIFICATION_CHANNEL
            )
            NotificationsHelper.showNotification(
                this,
                locationTransmissionNotificationId,
                notification
            )
        }
    }

    private fun startAsForegroundService(): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                locationTransmissionNotificationId,
                NotificationsHelper.buildNotification(
                    this,
                    getString(R.string.app_standby_title),
                    getString(R.string.app_standby_content),
                    NotificationsHelper.NOTIFICATION_CHANNEL_ID
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: SecurityException) {
            Timber.e("Failed to start foreground service due to missing permissions: ${e.message}")
            isInitialized = false
            stopSelf()
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun requestShutdown(startId: Int? = null) {
        isInitialized = false
        if (startId != null) stopSelf(startId) else stopSelf()
    }
}
