/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui

import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.util.Supplier
import com.android.internal.R
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import java.io.File
import javax.inject.Inject

/**
 * Performs a migration of pinned targets to the unbundled chooser if legacy data exists.
 *
 * Sends an explicit broadcast with the contents of the legacy pin preferences. The broadcast is
 * protected by the RECEIVE_CHOOSER_PIN_MIGRATION permission. This class requires the
 * ADD_CHOOSER_PINS permission in order to be able to send this broadcast.
 */
class ChooserPinMigration
@Inject
constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags,
    private val broadcastSender: BroadcastSender,
    legacyPinPrefsFileSupplier: LegacyPinPrefsFileSupplier,
) : CoreStartable {

    private val legacyPinPrefsFile = legacyPinPrefsFileSupplier.get()
    private val chooserComponent =
        ComponentName.unflattenFromString(
            context.resources.getString(R.string.config_chooserActivity)
        )

    override fun start() {
        if (migrationIsRequired()) {
            doMigration()
        }
    }

    private fun migrationIsRequired(): Boolean {
        return featureFlags.isEnabled(Flags.CHOOSER_MIGRATION_ENABLED) &&
            legacyPinPrefsFile.exists() &&
            chooserComponent?.packageName != null
    }

    private fun doMigration() {
        Log.i(TAG, "Beginning migration")

        val legacyPinPrefs = context.getSharedPreferences(legacyPinPrefsFile, MODE_PRIVATE)

        if (legacyPinPrefs.all.isEmpty()) {
            Log.i(TAG, "No data to migrate, deleting legacy file")
        } else {
            sendSharedPreferences(legacyPinPrefs)
            Log.i(TAG, "Legacy data sent, deleting legacy preferences")

            val legacyPinPrefsEditor = legacyPinPrefs.edit()
            legacyPinPrefsEditor.clear()
            if (!legacyPinPrefsEditor.commit()) {
                Log.e(TAG, "Failed to delete legacy preferences")
                return
            }
        }

        if (!legacyPinPrefsFile.delete()) {
            Log.e(TAG, "Legacy preferences deleted, but failed to delete legacy preferences file")
            return
        }

        Log.i(TAG, "Legacy preference deletion complete")
    }

    private fun sendSharedPreferences(sharedPreferences: SharedPreferences) {
        val bundle = Bundle()

        sharedPreferences.all.entries.forEach { (key, value) ->
            when (value) {
                is Boolean -> bundle.putBoolean(key, value)
                else -> Log.e(TAG, "Unsupported preference type for $key: ${value?.javaClass}")
            }
        }

        sendBundle(bundle)
    }

    private fun sendBundle(bundle: Bundle) {
        val intent =
            Intent().apply {
                `package` = chooserComponent?.packageName!!
                action = BROADCAST_ACTION
                putExtras(bundle)
            }
        broadcastSender.sendBroadcast(intent, BROADCAST_PERMISSION)
    }

    companion object {
        private const val TAG = "PinnedShareTargetMigration"
        private const val BROADCAST_ACTION = "android.intent.action.CHOOSER_PIN_MIGRATION"
        private const val BROADCAST_PERMISSION = "android.permission.RECEIVE_CHOOSER_PIN_MIGRATION"

        class LegacyPinPrefsFileSupplier @Inject constructor(private val context: Context) :
            Supplier<File> {

            override fun get(): File {
                val packageDirectory =
                    Environment.getDataUserCePackageDirectory(
                        StorageManager.UUID_PRIVATE_INTERNAL,
                        context.userId,
                        context.packageName,
                    )
                val sharedPrefsDirectory = File(packageDirectory, "shared_prefs")
                return File(sharedPrefsDirectory, "chooser_pin_settings.xml")
            }
        }
    }
}
