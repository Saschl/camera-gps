package com.saschl.cameragps.service

import android.content.Intent
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.coordinator.RemoteCommand
import java.util.Locale

class ServiceCommandRouter {
    companion object {
        const val EXTRA_REMOTE_CONTROL_ENABLED = "remoteControlEnabled"

        /** Extra holding a [RemoteCommand] enum name for [SonyBluetoothConstants.ACTION_SEND_REMOTE_COMMAND]. */
        const val EXTRA_REMOTE_COMMAND = "remoteCommand"
    }

    fun route(intent: Intent?): ServiceCommand {
        val action = intent?.action
        val address = intent?.getStringExtra("address")?.uppercase(Locale.getDefault())

        if (action == SonyBluetoothConstants.ACTION_SET_REMOTE_CONTROL_MONITORING) {
            val enabled = intent.getBooleanExtra(EXTRA_REMOTE_CONTROL_ENABLED, false)
            return if (address == null) {
                ServiceCommand.Ignore("Remote control monitoring toggle ignored because address is missing")
            } else {
                ServiceCommand.SetRemoteControlMonitoring(address, enabled)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_SEND_REMOTE_COMMAND) {
            val command = RemoteCommand.fromName(intent.getStringExtra(EXTRA_REMOTE_COMMAND))
            return when {
                address == null ->
                    ServiceCommand.Ignore("Remote command ignored because address is missing")

                command == null ->
                    ServiceCommand.Ignore("Remote command ignored because command is missing or unknown")

                else -> ServiceCommand.SendRemoteCommand(address, command)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_TRIGGER_SHUTTER_SEQUENCE) {
            return if (address == null) {
                ServiceCommand.Ignore("Shutter sequence ignored because address is missing")
            } else {
                ServiceCommand.TriggerShutterSequence(address)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_TRIGGER_REMOTE_SHUTTER) {
            return if (address == null) {
                ServiceCommand.Ignore("Remote shutter trigger ignored because address is missing")
            } else {
                ServiceCommand.TriggerRemoteShutter(address)
            }
        }

        if (action == SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN) {
            return if (address == null) {
                ServiceCommand.Ignore("Shutdown request ignored because address is missing")
            } else {
                ServiceCommand.Shutdown(address)
            }
        }

        return if (address == null) {
            ServiceCommand.ReconnectAlwaysOn
        } else {
            ServiceCommand.Connect(address)
        }
    }
}

