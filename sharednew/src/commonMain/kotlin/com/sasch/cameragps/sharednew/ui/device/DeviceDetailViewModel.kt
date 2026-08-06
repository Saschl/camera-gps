package com.sasch.cameragps.sharednew.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class DeviceDetailViewModel(
    dataSource: DeviceDetailDataSource,
    private val serviceActions: DeviceDetailServiceActions,
) : ViewModel() {

    private val stateStore = DeviceDetailStateStore(dataSource)
    val uiState: StateFlow<DeviceDetailToggleState> = stateStore.uiState

    fun load(address: String, deviceName: String? = null) {
        viewModelScope.launch {
            stateStore.load(address, deviceName)
        }
    }

    fun setDeviceEnabled(
        isEnabled: Boolean,
        device: String,
        onCompleted: ((Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            stateStore.setDeviceEnabled(device, isEnabled)
            // Only notify once the write is committed: platform reactions
            // (reconnect, service shutdown) read the flag back from the database
            onCompleted?.invoke(isEnabled)
        }
    }

    fun setRemoteControlStatus(enabled: Boolean, device: String) {
        val normalizedDevice = device.uppercase()
        viewModelScope.launch {
            stateStore.setRemoteControlEnabled(normalizedDevice, enabled)
            serviceActions.setRemoteControlMonitoring(normalizedDevice, enabled)
        }
    }

    fun setAlwaysOnEnabled(enabled: Boolean, deviceAddress: String) {
        viewModelScope.launch {
            stateStore.setAlwaysOnEnabled(deviceAddress, enabled)
            if (enabled) {
                serviceActions.startAlwaysOn(deviceAddress)
            } else {
                requestShutdownWithDelay(deviceAddress)
            }
        }
    }

    private suspend fun requestShutdownWithDelay(deviceAddress: String) {
        stateStore.setButtonEnabled(false)
        serviceActions.requestShutdown(deviceAddress)
        delay(2.seconds)
        stateStore.setButtonEnabled(true)
    }
}

