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

package com.android.systemui.qs.tiles.base.logging

import androidx.annotation.GuardedBy
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.QSTilesLogBuffers
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.StatusBarState
import javax.inject.Inject

@SysUISingleton
class QSTileLogger
@Inject
constructor(
    @QSTilesLogBuffers logBuffers: Map<TileSpec, LogBuffer>,
    private val factory: LogBufferFactory,
    private val mStatusBarStateController: StatusBarStateController,
) {
    @GuardedBy("logBufferCache") private val logBufferCache = logBuffers.toMutableMap()

    /**
     * Tracks user action when it's first received by the ViewModel and before it reaches the
     * pipeline
     */
    fun logUserAction(
        userAction: QSTileUserAction,
        tileSpec: TileSpec,
        hasData: Boolean,
        hasTileState: Boolean,
    ) {
        tileSpec
            .getLogBuffer()
            .log(
                tileSpec.getLogTag(),
                LogLevel.DEBUG,
                {
                    str1 = userAction.toLogString()
                    int1 = mStatusBarStateController.state
                    bool1 = hasTileState
                    bool2 = hasData
                },
                {
                    "tile $str1: " +
                        "statusBarState=${StatusBarState.toString(int1)}, " +
                        "hasState=$bool1, " +
                        "hasData=$bool2"
                }
            )
    }

    /** Tracks user action when it's rejected by false gestures */
    fun logUserActionRejectedByFalsing(
        userAction: QSTileUserAction,
        tileSpec: TileSpec,
    ) {
        tileSpec
            .getLogBuffer()
            .log(
                tileSpec.getLogTag(),
                LogLevel.DEBUG,
                { str1 = userAction.toLogString() },
                { "tile $str1: rejected by falsing" }
            )
    }

    /** Tracks user action when it's rejected according to the policy */
    fun logUserActionRejectedByPolicy(
        userAction: QSTileUserAction,
        tileSpec: TileSpec,
    ) {
        tileSpec
            .getLogBuffer()
            .log(
                tileSpec.getLogTag(),
                LogLevel.DEBUG,
                { str1 = userAction.toLogString() },
                { "tile $str1: rejected by policy" }
            )
    }

    /**
     * Tracks user actions when it reaches the pipeline and mixes with the last tile state and data
     */
    fun <T> logUserActionPipeline(
        tileSpec: TileSpec,
        userAction: QSTileUserAction,
        tileState: QSTileState,
        data: T,
    ) {
        tileSpec
            .getLogBuffer()
            .log(
                tileSpec.getLogTag(),
                LogLevel.DEBUG,
                {
                    str1 = userAction.toLogString()
                    str2 = tileState.toLogString()
                    str3 = data.toString().take(DATA_MAX_LENGTH)
                },
                {
                    "tile $str1 pipeline: " +
                        "statusBarState=${StatusBarState.toString(int1)}, " +
                        "state=$str2, " +
                        "data=$str3"
                }
            )
    }

    fun logForceUpdate(tileSpec: TileSpec) {
        tileSpec
            .getLogBuffer()
            .log(tileSpec.getLogTag(), LogLevel.DEBUG, {}, { "tile data force update" })
    }

    fun logInitialRequest(tileSpec: TileSpec) {
        tileSpec
            .getLogBuffer()
            .log(tileSpec.getLogTag(), LogLevel.DEBUG, {}, { "tile data initial update" })
    }

    /** Tracks state changes based on the data and trigger event. */
    fun <T> logStateUpdate(
        tileSpec: TileSpec,
        tileState: QSTileState,
        data: T,
    ) {
        tileSpec
            .getLogBuffer()
            .log(
                tileSpec.getLogTag(),
                LogLevel.DEBUG,
                {
                    str1 = tileState.toLogString()
                    str2 = data.toString().take(DATA_MAX_LENGTH)
                },
                { "tile state update: state=$str1, data=$str2" }
            )
    }

    private fun TileSpec.getLogTag(): String = "${TAG_FORMAT_PREFIX}_${this.spec}"

    private fun TileSpec.getLogBuffer(): LogBuffer =
        synchronized(logBufferCache) {
            logBufferCache.getOrPut(this) {
                factory.create(
                    this.getLogTag(),
                    BUFFER_MAX_SIZE /* maxSize */,
                    false /* systrace */
                )
            }
        }

    private fun QSTileUserAction.toLogString(): String =
        when (this) {
            is QSTileUserAction.Click -> "click"
            is QSTileUserAction.LongClick -> "long click"
        }

    /* Shortened version of a data class toString() */
    private fun QSTileState.toLogString(): String =
        "[label=$label, " +
            "state=$activationState, " +
            "s_label=$secondaryLabel, " +
            "cd=$contentDescription, " +
            "sd=$stateDescription, " +
            "svi=$sideViewIcon, " +
            "enabled=$enabledState, " +
            "a11y=$expandedAccessibilityClassName" +
            "]"

    private companion object {
        const val TAG_FORMAT_PREFIX = "QSLog_tile_"
        const val DATA_MAX_LENGTH = 50
        const val BUFFER_MAX_SIZE = 25
    }
}
