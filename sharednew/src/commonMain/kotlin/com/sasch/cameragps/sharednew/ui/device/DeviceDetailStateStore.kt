package com.sasch.cameragps.sharednew.ui.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface DeviceDetailDataSource {
    suspend fun ensureDeviceExists(deviceId: String, deviceName: String? = null)
    suspend fun isDeviceEnabled(deviceId: String): Boolean
    suspend fun isAlwaysOnEnabled(deviceId: String): Boolean
    suspend fun isRemoteControlEnabled(deviceId: String): Boolean
    suspend fun getHandshakeDelayMs(deviceId: String): Long
    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean)
    suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean)
    suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean)
    suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long)
}

interface DeviceDetailServiceActions {
    fun startAlwaysOn(deviceAddress: String)
    fun requestShutdown(deviceAddress: String)
    fun setRemoteControlMonitoring(deviceAddress: String, enabled: Boolean)
}

data class DeviceDetailToggleState(
    val buttonEnabled: Boolean = true,
    val isDeviceEnabled: Boolean = true,
    val isAlwaysOnEnabled: Boolean = false,
    val isRemoteControlEnabled: Boolean = false,
    val handshakeDelayMs: Long = 0,
)

class DeviceDetailStateStore(
    private val dataSource: DeviceDetailDataSource,
) {
    private val _uiState = MutableStateFlow(DeviceDetailToggleState())
    val uiState: StateFlow<DeviceDetailToggleState> = _uiState.asStateFlow()

    suspend fun load(deviceId: String, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        _uiState.update {
            it.copy(
                isAlwaysOnEnabled = dataSource.isAlwaysOnEnabled(normalized),
                isDeviceEnabled = dataSource.isDeviceEnabled(normalized),
                isRemoteControlEnabled = dataSource.isRemoteControlEnabled(normalized),
                handshakeDelayMs = dataSource.getHandshakeDelayMs(normalized),
            )
        }
    }

    suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setDeviceEnabled(normalized, enabled)
        _uiState.update { it.copy(isDeviceEnabled = enabled) }
    }

    suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setAlwaysOnEnabled(normalized, enabled)
        _uiState.update { it.copy(isAlwaysOnEnabled = enabled) }
    }

    suspend fun setRemoteControlEnabled(
        deviceId: String,
        enabled: Boolean,
        deviceName: String? = null
    ) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setRemoteControlEnabled(normalized, enabled)
        _uiState.update { it.copy(isRemoteControlEnabled = enabled) }
    }

    suspend fun setHandshakeDelayMs(deviceId: String, delayMs: Long, deviceName: String? = null) {
        val normalized = deviceId.uppercase()
        dataSource.ensureDeviceExists(normalized, deviceName)
        dataSource.setHandshakeDelayMs(normalized, delayMs)
        _uiState.update { it.copy(handshakeDelayMs = delayMs) }
    }

    fun setButtonEnabled(enabled: Boolean) {
        _uiState.update { it.copy(buttonEnabled = enabled) }
    }
}
