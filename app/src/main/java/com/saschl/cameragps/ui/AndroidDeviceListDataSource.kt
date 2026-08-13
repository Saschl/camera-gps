package com.saschl.cameragps.ui

import com.sasch.cameragps.sharednew.bluetooth.session.CameraSession
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.ui.devicelist.DeviceListDataSource
import com.saschl.cameragps.AppServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class AndroidDeviceListDataSource(services: AppServices) : DeviceListDataSource {
    override val sessions: StateFlow<Map<String, CameraSession>> =
        services.orchestrator.sessions
    override val transmissionActive: StateFlow<Boolean> =
        services.orchestrator.locationManager.isActive
    override val deviceSettings: Flow<List<CameraDevice>> =
        services.deviceDao.observeAllDevices()
}
