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
 * limitations under the License
 */

package com.android.systemui.common.ui.data.repository

import android.content.Context
import android.content.res.Configuration
import android.view.DisplayInfo
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.wrapper.DisplayUtilsWrapper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

interface ConfigurationRepository {
    /** Called whenever ui mode, theme or configuration has changed. */
    val onAnyConfigurationChange: Flow<Unit>
    val scaleForResolution: Flow<Float>

    fun getResolutionScale(): Float
}

@ExperimentalCoroutinesApi
@SysUISingleton
class ConfigurationRepositoryImpl
@Inject
constructor(
    private val configurationController: ConfigurationController,
    private val context: Context,
    @Application private val scope: CoroutineScope,
    private val displayUtils: DisplayUtilsWrapper,
) : ConfigurationRepository {
    private val displayInfo = MutableStateFlow(DisplayInfo())

    override val onAnyConfigurationChange: Flow<Unit> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val callback =
                object : ConfigurationController.ConfigurationListener {
                    override fun onUiModeChanged() {
                        sendUpdate("ConfigurationRepository#onUiModeChanged")
                    }

                    override fun onThemeChanged() {
                        sendUpdate("ConfigurationRepository#onThemeChanged")
                    }

                    override fun onConfigChanged(newConfig: Configuration) {
                        sendUpdate("ConfigurationRepository#onConfigChanged")
                    }

                    fun sendUpdate(reason: String) {
                        trySendWithFailureLogging(Unit, reason)
                    }
                }
            configurationController.addCallback(callback)
            awaitClose { configurationController.removeCallback(callback) }
        }

    private val configurationChange: Flow<Unit> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
            val callback =
                object : ConfigurationController.ConfigurationListener {
                    override fun onConfigChanged(newConfig: Configuration) {
                        trySendWithFailureLogging(Unit, "ConfigurationRepository#onConfigChanged")
                    }
                }
            configurationController.addCallback(callback)
            awaitClose { configurationController.removeCallback(callback) }
        }

    override val scaleForResolution: StateFlow<Float> =
        configurationChange
            .mapLatest { getResolutionScale() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), getResolutionScale())

    override fun getResolutionScale(): Float {
        context.display?.getDisplayInfo(displayInfo.value)
        val maxDisplayMode =
            displayUtils.getMaximumResolutionDisplayMode(displayInfo.value.supportedModes)
        maxDisplayMode?.let {
            val scaleFactor =
                displayUtils.getPhysicalPixelDisplaySizeRatio(
                    maxDisplayMode.physicalWidth,
                    maxDisplayMode.physicalHeight,
                    displayInfo.value.naturalWidth,
                    displayInfo.value.naturalHeight
                )
            return if (scaleFactor == Float.POSITIVE_INFINITY) 1f else scaleFactor
        }
        return 1f
    }
}
