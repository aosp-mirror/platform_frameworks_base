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

import android.media.MediaRouter.RouteInfo
import android.media.projection.MediaProjectionInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.MessageInitializer
import com.android.systemui.log.core.MessagePrinter
import com.android.systemui.statusbar.policy.dagger.CastControllerLog
import javax.inject.Inject

/** Helper class for logging events to [CastControllerLog] from Java. */
@SysUISingleton
class CastControllerLogger
@Inject
constructor(
    @CastControllerLog val logger: LogBuffer,
) {
    /** Passthrough to [logger]. */
    inline fun log(
        tag: String,
        level: LogLevel,
        messageInitializer: MessageInitializer,
        noinline messagePrinter: MessagePrinter,
        exception: Throwable? = null,
    ) {
        logger.log(tag, level, messageInitializer, messagePrinter, exception)
    }

    fun logDiscovering(isDiscovering: Boolean) =
        logger.log(TAG, LogLevel.DEBUG, { bool1 = isDiscovering }, { "setDiscovering: $bool1" })

    fun logStartCasting(route: RouteInfo) =
        logger.log(TAG, LogLevel.DEBUG, { str1 = route.toLogString() }, { "startCasting: $str1" })

    fun logStopCasting(isProjection: Boolean) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = isProjection },
            { "stopCasting. isProjection=$bool1" },
        )

    fun logStopCastingNoProjection(projection: MediaProjectionInfo) =
        logger.log(
            TAG,
            LogLevel.WARNING,
            { str1 = projection.toString() },
            { "stopCasting failed because projection is no longer active: $str1" },
        )

    fun logStopCastingMediaRouter() =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            {},
            { "stopCasting is selecting fallback route in MediaRouter" },
        )

    fun logSetProjection(oldInfo: MediaProjectionInfo?, newInfo: MediaProjectionInfo?) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = oldInfo.toString()
                str2 = newInfo.toString()
            },
            { "setProjection: $str1 -> $str2" },
        )

    fun logRouteAdded(route: RouteInfo) =
        logger.log(TAG, LogLevel.DEBUG, { str1 = route.toLogString() }, { "onRouteAdded: $str1" })

    fun logRouteChanged(route: RouteInfo) =
        logger.log(TAG, LogLevel.DEBUG, { str1 = route.toLogString() }, { "onRouteChanged: $str1" })

    fun logRouteRemoved(route: RouteInfo) =
        logger.log(TAG, LogLevel.DEBUG, { str1 = route.toLogString() }, { "onRouteRemoved: $str1" })

    fun logRouteSelected(route: RouteInfo, type: Int) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = route.toLogString()
                int1 = type
            },
            { "onRouteSelected($int1): $str1" },
        )

    fun logRouteUnselected(route: RouteInfo, type: Int) =
        logger.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = route.toLogString()
                int1 = type
            },
            { "onRouteUnselected($int1): $str1" },
        )

    companion object {
        @JvmStatic
        fun RouteInfo?.toLogString(): String? {
            if (this == null) return null
            val sb =
                StringBuilder()
                    .append(name)
                    .append('/')
                    .append(description)
                    .append('@')
                    .append(deviceAddress)
                    .append(",status=")
                    .append(status)
            if (isDefault) sb.append(",default")
            if (isEnabled) sb.append(",enabled")
            if (isConnecting) sb.append(",connecting")
            if (isSelected) sb.append(",selected")
            return sb.append(",id=").append(this.tag).toString()
        }

        private const val TAG = "CastController"
    }
}
