// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.utils.XLog

/**
 * Boot broadcast receiver: schedules the keep-alive watchdog.
 * The watchdog job is persisted across reboots, this re-arms it and
 * re-syncs the foreground service in case monitoring was active before shutdown.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "Boot broadcast received, scheduling keep-alive watchdog")
            KeepAliveJobService.schedule(context)
        }
    }
}
