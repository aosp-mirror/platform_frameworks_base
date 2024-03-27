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

package com.android.systemui.brightness.data.repository

import android.annotation.SuppressLint
import android.hardware.display.BrightnessInfo
import android.hardware.display.DisplayManager
import com.android.systemui.brightness.shared.model.BrightnessLog
import com.android.systemui.brightness.shared.model.LinearBrightness
import com.android.systemui.brightness.shared.model.formatBrightness
import com.android.systemui.brightness.shared.model.logDiffForTable
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableLogBuffer
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for tracking brightness in the current display.
 *
 * Values are in a linear space, as used by [DisplayManager].
 */
interface ScreenBrightnessRepository {
    /** Current brightness as a value between [minLinearBrightness] and [maxLinearBrightness] */
    val linearBrightness: Flow<LinearBrightness>

    /** Current minimum value for the brightness */
    val minLinearBrightness: Flow<LinearBrightness>

    /** Current maximum value for the brightness */
    val maxLinearBrightness: Flow<LinearBrightness>

    /** Gets the current values for min and max brightness */
    suspend fun getMinMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness>

    /**
     * Sets the temporary value for the brightness. This should change the display brightness but
     * not trigger any updates.
     */
    fun setTemporaryBrightness(value: LinearBrightness)

    /** Sets the brightness definitively. */
    fun setBrightness(value: LinearBrightness)
}

@SuppressLint("MissingPermission")
@SysUISingleton
class ScreenBrightnessDisplayManagerRepository
@Inject
constructor(
    @DisplayId private val displayId: Int,
    private val displayManager: DisplayManager,
    @BrightnessLog private val logBuffer: LogBuffer,
    @BrightnessLog private val tableBuffer: TableLogBuffer,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundContext: CoroutineContext,
) : ScreenBrightnessRepository {

    private val apiQueue =
        Channel<SetBrightnessMethod>(
            capacity = UNLIMITED,
        )

    init {
        applicationScope.launch(backgroundContext) {
            for (call in apiQueue) {
                val bounds = getMinMaxLinearBrightness()
                val value = call.value.clamp(bounds.first, bounds.second).floatValue
                when (call) {
                    is SetBrightnessMethod.Temporary -> {
                        displayManager.setTemporaryBrightness(displayId, value)
                    }
                    is SetBrightnessMethod.Permanent -> {
                        displayManager.setBrightness(displayId, value)
                    }
                }
                logBrightnessChange(call is SetBrightnessMethod.Permanent, value)
            }
        }
    }

    private val brightnessInfo: StateFlow<BrightnessInfo?> =
        conflatedCallbackFlow {
                val listener =
                    object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}

                        override fun onDisplayRemoved(displayId: Int) {}

                        override fun onDisplayChanged(displayId: Int) {
                            if (
                                displayId == this@ScreenBrightnessDisplayManagerRepository.displayId
                            ) {
                                trySend(Unit)
                            }
                        }
                    }
                displayManager.registerDisplayListener(
                    listener,
                    null,
                    DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS,
                )

                awaitClose { displayManager.unregisterDisplayListener(listener) }
            }
            .onStart { emit(Unit) }
            .map { brightnessInfoValue() }
            .flowOn(backgroundContext)
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0L),
                null,
            )

    private suspend fun brightnessInfoValue(): BrightnessInfo? {
        return withContext(backgroundContext) {
            displayManager.getDisplay(displayId).brightnessInfo
        }
    }

    override val minLinearBrightness =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightnessMinimum) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_MIN, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(0f))

    override val maxLinearBrightness: SharedFlow<LinearBrightness> =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightnessMaximum) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_MAX, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(1f))

    override suspend fun getMinMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness> {
        val brightnessInfo = brightnessInfo.value ?: brightnessInfoValue()
        val min = brightnessInfo?.brightnessMinimum ?: 0f
        val max = brightnessInfo?.brightnessMaximum ?: 1f
        return LinearBrightness(min) to LinearBrightness(max)
    }

    override val linearBrightness =
        brightnessInfo
            .filterNotNull()
            .map { LinearBrightness(it.brightness) }
            .logDiffForTable(tableBuffer, TABLE_PREFIX_LINEAR, TABLE_COLUMN_BRIGHTNESS, null)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), LinearBrightness(0f))

    override fun setTemporaryBrightness(value: LinearBrightness) {
        apiQueue.trySend(SetBrightnessMethod.Temporary(value))
    }

    override fun setBrightness(value: LinearBrightness) {
        apiQueue.trySend(SetBrightnessMethod.Permanent(value))
    }

    private sealed interface SetBrightnessMethod {
        val value: LinearBrightness
        @JvmInline
        value class Temporary(override val value: LinearBrightness) : SetBrightnessMethod
        @JvmInline
        value class Permanent(override val value: LinearBrightness) : SetBrightnessMethod
    }

    private fun logBrightnessChange(permanent: Boolean, value: Float) {
        logBuffer.log(
            LOG_BUFFER_BRIGHTNESS_CHANGE_TAG,
            if (permanent) LogLevel.DEBUG else LogLevel.VERBOSE,
            { str1 = value.formatBrightness() },
            { "Change requested: $str1" }
        )
    }

    private companion object {
        const val TABLE_COLUMN_BRIGHTNESS = "brightness"
        const val TABLE_COLUMN_MIN = "min"
        const val TABLE_COLUMN_MAX = "max"
        const val TABLE_PREFIX_LINEAR = "linear"
        const val LOG_BUFFER_BRIGHTNESS_CHANGE_TAG = "BrightnessChange"
    }
}
