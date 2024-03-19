/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.util.Log

/**
 * Proxy to launch in user 0
 */
class ControlsRequestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ControlsRequestReceiver"

        fun isPackageInForeground(context: Context, packageName: String): Boolean {
            val uid = try {
                context.packageManager.getPackageUid(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package $packageName not found")
                return false
            }

            val am = context.getSystemService(ActivityManager::class.java)
            if ((am?.getUidImportance(uid) ?: IMPORTANCE_GONE) != IMPORTANCE_FOREGROUND) {
                Log.w(TAG, "Uid $uid not in foreground")
                return false
            }
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONTROLS)) {
            return
        }

        val targetComponent = try {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed intent extra ComponentName", e)
            return
        } ?: run {
            Log.e(TAG, "Null target component")
            return
        }

        val control = try {
            intent.getParcelableExtra(ControlsProviderService.EXTRA_CONTROL, Control::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed intent extra Control", e)
            return
        } ?: run {
            Log.e(TAG, "Null control")
            return
        }

        val packageName = targetComponent.packageName

        if (!isPackageInForeground(context, packageName)) {
            return
        }

        val activityIntent = Intent(context, ControlsRequestDialog::class.java).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, targetComponent)
            putExtra(ControlsProviderService.EXTRA_CONTROL, control)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        activityIntent.putExtra(Intent.EXTRA_USER_ID, context.userId)

        context.startActivityAsUser(activityIntent, UserHandle.SYSTEM)
    }
}