/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRouter
import android.media.projection.MediaProjectionInfo
import android.text.TextUtils
import android.util.Log
import com.android.systemui.res.R
import com.android.systemui.util.Utils

/** Represents a specific cast session. */
data class CastDevice(
    val id: String,
    /** A human-readable name of what is receiving the cast (e.g. "Home Speaker", "Abc App"). */
    val name: String?,
    /** An optional description with more information about the cast. */
    val description: String? = null,
    val state: CastState,
    val origin: CastOrigin,
    /** Optional tag to use as a comparison value between cast sessions. */
    val tag: Any? = null,
) {
    val isCasting = state == CastState.Connecting || state == CastState.Connected

    companion object {
        /** Creates a [CastDevice] based on the provided information from MediaRouter. */
        fun MediaRouter.RouteInfo.toCastDevice(context: Context): CastDevice {
            val state =
                when {
                    statusCode == MediaRouter.RouteInfo.STATUS_CONNECTING -> CastState.Connecting
                    this.isSelected || statusCode == MediaRouter.RouteInfo.STATUS_CONNECTED ->
                        CastState.Connected
                    else -> CastState.Disconnected
                }
            return CastDevice(
                id = this.tag.toString(),
                name = this.getName(context)?.toString(),
                description = this.description?.toString(),
                state = state,
                tag = this,
                origin = CastOrigin.MediaRouter,
            )
        }

        /** Creates a [CastDevice] based on the provided information from MediaProjection. */
        fun MediaProjectionInfo.toCastDevice(
            context: Context,
            packageManager: PackageManager
        ): CastDevice {
            return CastDevice(
                id = this.packageName,
                name = getAppName(this.packageName, packageManager),
                description = context.getString(R.string.quick_settings_casting),
                state = CastState.Connected,
                tag = this,
                origin = CastOrigin.MediaProjection,
            )
        }

        private fun getAppName(packageName: String, packageManager: PackageManager): String {
            if (Utils.isHeadlessRemoteDisplayProvider(packageManager, packageName)) {
                return ""
            }
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val label = appInfo.loadLabel(packageManager)
                if (!TextUtils.isEmpty(label)) {
                    return label.toString()
                }
                Log.w(CastControllerImpl.TAG, "No label found for package: $packageName")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(CastControllerImpl.TAG, "Error getting appName for package: $packageName", e)
            }
            return packageName
        }
    }

    enum class CastState {
        Disconnected,
        Connecting,
        Connected,
    }

    enum class CastOrigin {
        /** SysUI found out about this cast device from MediaRouter APIs. */
        MediaRouter,
        /** SysUI found out about this cast device from MediaProjection APIs. */
        MediaProjection,
    }
}
