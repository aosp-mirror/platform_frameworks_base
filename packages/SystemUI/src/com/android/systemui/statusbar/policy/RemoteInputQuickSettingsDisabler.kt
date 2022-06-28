/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.StatusBarManager
import android.content.Context
import android.content.res.Configuration
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.LargeScreenUtils
import javax.inject.Inject

/**
 * Controls whether the disable flag [StatusBarManager.DISABLE2_QUICK_SETTINGS] should be set.
 * This would happen when a [RemoteInputView] is active, the device is in landscape and not using
 * split shade.
 */
@SysUISingleton
class RemoteInputQuickSettingsDisabler @Inject constructor(
    private val context: Context,
    private val commandQueue: CommandQueue,
    configController: ConfigurationController
) : ConfigurationController.ConfigurationListener {

    private var remoteInputActive = false
    private var isLandscape: Boolean
    private var shouldUseSplitNotificationShade: Boolean

    init {
        isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        shouldUseSplitNotificationShade =
                LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)
        configController.addCallback(this)
    }

    fun adjustDisableFlags(state: Int): Int {
        var mutableState = state
        if (remoteInputActive &&
            isLandscape &&
            !shouldUseSplitNotificationShade
        ) {
            mutableState = state or StatusBarManager.DISABLE2_QUICK_SETTINGS
        }
        return mutableState
    }

    fun setRemoteInputActive(active: Boolean) {
        if (remoteInputActive != active) {
            remoteInputActive = active
            recomputeDisableFlags()
        }
    }

    override fun onConfigChanged(newConfig: Configuration) {
        var needToRecompute = false

        val newIsLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (newIsLandscape != isLandscape) {
            isLandscape = newIsLandscape
            needToRecompute = true
        }

        val newSplitShadeFlag = LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)
        if (newSplitShadeFlag != shouldUseSplitNotificationShade) {
            shouldUseSplitNotificationShade = newSplitShadeFlag
            needToRecompute = true
        }
        if (needToRecompute) {
            recomputeDisableFlags()
        }
    }

    /**
     * Called in order to trigger a refresh of the disable flags after a relevant configuration
     * change or when a [RemoteInputView] has changed its active state. The method
     * [adjustDisableFlags] will be invoked to modify the disable flags according to
     * [remoteInputActive], [isLandscape] and [shouldUseSplitNotificationShade].
     */
    private fun recomputeDisableFlags() {
        commandQueue.recomputeDisableFlags(context.displayId, true)
    }
}