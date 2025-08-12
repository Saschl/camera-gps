/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saschl.cameragps.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.saschl.cameragps.R
import timber.log.Timber
import java.util.Locale
import java.util.UUID


@RequiresApi(Build.VERSION_CODES.S)
class CompanionDeviceSampleService : CompanionDeviceService() {

    companion object {

        // Random UUID for our service known between the client and server to allow communication
        val SERVICE_UUID: UUID = UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff")

        // Same as the service but for the characteristic
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000dd11-0000-1000-8000-00805f9b34fb")

    }


    private fun startLocationSenderService(address: String?) {
        val serviceIntent = Intent(this, LocationSenderService::class.java)
        serviceIntent.putExtra("address", address?.uppercase(Locale.getDefault()))
        Timber.i("Starting LocationSenderService for address: $address")
        startForegroundService(serviceIntent)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        super.onDeviceAppeared(address)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
            return
        }

        startLocationSenderService(address)
    }

    /*    @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onDeviceDisappeared(address: String) {
            super.onDeviceDisappeared(address)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
                return
            }

            notificationManager.onDeviceDisappeared(address)
        }*/

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)
        if (missingPermissions()) {
            return
        }

        val address = associationInfo.deviceMacAddress?.toString() ?: return

        startLocationSenderService(address)

    }


    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @SuppressLint("MissingPermission")
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        super.onDevicePresenceEvent(event)
        if (missingPermissions()) {
            Timber.e(CompanionDeviceSampleService::class.java.toString(), "Missing permissions")
            return
        }

        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) {

            Timber.i("Device appeared new API: ${event.getAssociationId()}")
            val associationId = event.getAssociationId()
            val deviceManager = getSystemService<CompanionDeviceManager>()
            val associatedDevices = deviceManager?.getMyAssociations()
            val associationInfo = associatedDevices?.find { it.id == associationId }
            val address = associationInfo?.deviceMacAddress?.toString()

            startLocationSenderService(address)
        }
    }


    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        //   fusedLocationClient.removeLocationUpdates(locationCallback)
        // timerJob.cancel()

        //fusedLocationClient.removeLocationUpdates(locationCallback)

        stopService(Intent(this, LocationSenderService::class.java))
        //  notificationManager.onDeviceDisappeared("Service gone :)")

        /*   gatt?.disconnect()
           gatt?.close()*/
        Timber.e("Destroyed service")
    }

    /*  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
          super.onStartCommand(intent, flags, startId)

          return START_STICKY
      }*/

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()

        /*  Timber.plant(
              Timber.DebugTree(),
              FileTree()
          )*/

    }

    /**
     * Check BLUETOOTH_CONNECT is granted and POST_NOTIFICATIONS is granted for devices running
     * Android 13 and above.
     */
    private fun missingPermissions(): Boolean = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * Utility class to post notification when CDM notifies that a device appears or disappears
     */
    class DeviceNotificationManager(context: Context) {

        companion object {
            private const val CDM_CHANNEL = "cdm_channel"
        }

        private val manager = NotificationManagerCompat.from(context)

        private val notificationBuilder = NotificationCompat.Builder(context, CDM_CHANNEL)
            .setSmallIcon(R.drawable.ic_gps_fixed)
            .setContentTitle("Companion Device Manager Sample")

        init {
            createNotificationChannel()
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun onDeviceAppeared(address: String, status: String) {
            val notification =
                notificationBuilder.setContentText("Device: $address appeared.\nStatus: $status")
            manager.notify(address.hashCode(), notification.build())
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun onDeviceDisappeared(address: String) {
            val notification = notificationBuilder.setContentText("Device: $address disappeared")
            manager.notify(address.hashCode(), notification.build())
        }

        private fun createNotificationChannel() {
            val channel =
                NotificationChannelCompat.Builder(CDM_CHANNEL, NotificationManager.IMPORTANCE_HIGH)
                    .setName("CDM Sample")
                    .setDescription("Channel for the CDM sample")
                    .build()
            manager.createNotificationChannel(channel)
        }
    }
}
