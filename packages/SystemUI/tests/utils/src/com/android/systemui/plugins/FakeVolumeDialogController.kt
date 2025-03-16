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

package com.android.systemui.plugins

import android.media.AudioManager
import android.media.AudioManager.CsdWarning
import android.os.Handler
import android.os.VibrationEffect
import androidx.core.util.getOrElse
import java.util.concurrent.CopyOnWriteArraySet

class FakeVolumeDialogController(private val audioManager: AudioManager) : VolumeDialogController {

    var isVisible: Boolean = false
        private set

    var hasScheduledTouchFeedback: Boolean = false
        private set

    var vibrationEffect: VibrationEffect? = null
        private set

    var hasUserActivity: Boolean = false
        private set

    private val callbacks = CopyOnWriteArraySet<VolumeDialogController.Callbacks>()

    private var hasVibrator: Boolean = true
    private var state = VolumeDialogController.State()

    override fun setActiveStream(stream: Int) {
        updateState {
            // ensure streamState existence for the active stream`
            states.getOrElse(stream) {
                VolumeDialogController.StreamState().also { streamState ->
                    state.states.put(stream, streamState)
                }
            }
            activeStream = stream
        }
    }

    override fun setStreamVolume(stream: Int, userLevel: Int) {
        updateState {
            val streamState =
                states.getOrElse(stream) {
                    VolumeDialogController.StreamState().also { streamState ->
                        states.put(stream, streamState)
                    }
                }
            streamState.level = userLevel.coerceIn(streamState.levelMin, streamState.levelMax)
        }
    }

    override fun setRingerMode(ringerModeNormal: Int, external: Boolean) {
        updateState {
            if (external) {
                ringerModeExternal = ringerModeNormal
            } else {
                ringerModeInternal = ringerModeNormal
            }
        }
    }

    fun setHasVibrator(hasVibrator: Boolean) {
        this.hasVibrator = hasVibrator
    }

    override fun hasVibrator(): Boolean = hasVibrator

    override fun vibrate(effect: VibrationEffect) {
        vibrationEffect = effect
    }

    override fun scheduleTouchFeedback() {
        hasScheduledTouchFeedback = true
    }

    fun resetScheduledTouchFeedback() {
        hasScheduledTouchFeedback = false
    }

    override fun getAudioManager(): AudioManager = audioManager

    override fun notifyVisible(visible: Boolean) {
        isVisible = visible
    }

    override fun addCallback(callbacks: VolumeDialogController.Callbacks?, handler: Handler?) {
        this.callbacks.add(callbacks)
    }

    override fun removeCallback(callbacks: VolumeDialogController.Callbacks?) {
        this.callbacks.remove(callbacks)
    }

    override fun userActivity() {
        hasUserActivity = true
    }

    fun resetUserActivity() {
        hasUserActivity = false
    }

    fun updateState(update: VolumeDialogController.State.() -> Unit) {
        state = state.copy().apply(update)
        getState()
    }

    override fun getState() {
        callbacks.sendEvent { it.onStateChanged(state) }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onShowRequested */
    fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
        callbacks.sendEvent { it.onShowRequested(reason, keyguardLocked, lockTaskModeState) }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onDismissRequested */
    fun onDismissRequested(reason: Int) {
        callbacks.sendEvent { it.onDismissRequested(reason) }
    }

    /**
     * @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onLayoutDirectionChanged
     */
    fun onLayoutDirectionChanged(layoutDirection: Int) {
        callbacks.sendEvent { it.onLayoutDirectionChanged(layoutDirection) }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onConfigurationChanged */
    fun onConfigurationChanged() {
        callbacks.sendEvent { it.onConfigurationChanged() }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onShowVibrateHint */
    fun onShowVibrateHint() {
        callbacks.sendEvent { it.onShowVibrateHint() }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onShowSilentHint */
    fun onShowSilentHint() {
        callbacks.sendEvent { it.onShowSilentHint() }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onScreenOff */
    fun onScreenOff() {
        callbacks.sendEvent { it.onScreenOff() }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onShowSafetyWarning */
    fun onShowSafetyWarning(flags: Int) {
        callbacks.sendEvent { it.onShowSafetyWarning(flags) }
    }

    /**
     * @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onAccessibilityModeChanged
     */
    fun onAccessibilityModeChanged(showA11yStream: Boolean?) {
        callbacks.sendEvent { it.onAccessibilityModeChanged(showA11yStream) }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onShowCsdWarning */
    fun onShowCsdWarning(@CsdWarning csdWarning: Int, durationMs: Int) {
        callbacks.sendEvent { it.onShowCsdWarning(csdWarning, durationMs) }
    }

    /** @see com.android.systemui.plugins.VolumeDialogController.Callbacks.onVolumeChangedFromKey */
    fun onVolumeChangedFromKey() {
        callbacks.sendEvent { it.onVolumeChangedFromKey() }
    }

    override fun getCaptionsEnabledState(checkForSwitchState: Boolean) {
        error("Unsupported for the new Volume Dialog")
    }

    override fun setCaptionsEnabledState(enabled: Boolean) {
        error("Unsupported for the new Volume Dialog")
    }

    override fun getCaptionsComponentState(fromTooltip: Boolean) {
        error("Unsupported for the new Volume Dialog")
    }
}

private inline fun CopyOnWriteArraySet<VolumeDialogController.Callbacks>.sendEvent(
    event: (callback: VolumeDialogController.Callbacks) -> Unit
) {
    for (callback in this) {
        event(callback)
    }
}
