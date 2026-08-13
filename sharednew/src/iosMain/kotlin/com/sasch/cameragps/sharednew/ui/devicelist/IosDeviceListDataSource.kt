package com.sasch.cameragps.sharednew.ui.devicelist

import com.sasch.cameragps.sharednew.bluetooth.IosBluetoothController
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

object IosDeviceListDataSource : DeviceListDataSource {
    override val sessions: StateFlow<Map<String, CameraSession>>
        get() = IosBluetoothController.sessions
    override val transmissionActive: StateFlow<Boolean>
        get() = IosBluetoothController.transmissionActive
    override val deviceSettings: Flow<List<CameraDevice>>
        get() = IosBluetoothController.deviceDao.observeAllDevices()
}
