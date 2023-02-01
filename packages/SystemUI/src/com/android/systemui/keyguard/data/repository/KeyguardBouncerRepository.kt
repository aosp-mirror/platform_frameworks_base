/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.BouncerCallbackActionsModel
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.keyguard.shared.model.KeyguardBouncerModel
import com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_HIDDEN
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates app state for the lock screen bouncer. */
@SysUISingleton
class KeyguardBouncerRepository
@Inject
constructor(
    private val viewMediatorCallback: ViewMediatorCallback,
    keyguardUpdateMonitor: KeyguardUpdateMonitor,
) {
    var bouncerPromptReason: Int? = null
    /** Determines if we want to instantaneously show the bouncer instead of translating. */
    private val _isScrimmed = MutableStateFlow(false)
    val isScrimmed = _isScrimmed.asStateFlow()
    /** Set amount of how much of the bouncer is showing on the screen */
    private val _expansionAmount = MutableStateFlow(EXPANSION_HIDDEN)
    val expansionAmount = _expansionAmount.asStateFlow()
    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()
    private val _show = MutableStateFlow<KeyguardBouncerModel?>(null)
    val show = _show.asStateFlow()
    private val _showingSoon = MutableStateFlow(false)
    val showingSoon = _showingSoon.asStateFlow()
    private val _hide = MutableStateFlow(false)
    val hide = _hide.asStateFlow()
    private val _startingToHide = MutableStateFlow(false)
    val startingToHide = _startingToHide.asStateFlow()
    private val _onDismissAction = MutableStateFlow<BouncerCallbackActionsModel?>(null)
    val onDismissAction = _onDismissAction.asStateFlow()
    private val _disappearAnimation = MutableStateFlow<Runnable?>(null)
    val startingDisappearAnimation = _disappearAnimation.asStateFlow()
    private val _keyguardPosition = MutableStateFlow(0f)
    val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    val showMessage = _showMessage.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    /** Determines if user is already unlocked */
    val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _onScreenTurnedOff = MutableStateFlow(false)
    val onScreenTurnedOff = _onScreenTurnedOff.asStateFlow()

    val bouncerErrorMessage: CharSequence?
        get() = viewMediatorCallback.consumeCustomMessage()

    init {
        val callback =
            object : KeyguardUpdateMonitorCallback() {
                override fun onStrongAuthStateChanged(userId: Int) {
                    bouncerPromptReason = viewMediatorCallback.bouncerPromptReason
                }

                override fun onLockedOutStateChanged(type: BiometricSourceType) {
                    if (type == BiometricSourceType.FINGERPRINT) {
                        bouncerPromptReason = viewMediatorCallback.bouncerPromptReason
                    }
                }
            }

        keyguardUpdateMonitor.registerCallback(callback)
    }

    fun setScrimmed(isScrimmed: Boolean) {
        _isScrimmed.value = isScrimmed
    }

    fun setExpansion(expansion: Float) {
        _expansionAmount.value = expansion
    }

    fun setVisible(isVisible: Boolean) {
        _isVisible.value = isVisible
    }

    fun setShow(keyguardBouncerModel: KeyguardBouncerModel?) {
        _show.value = keyguardBouncerModel
    }

    fun setShowingSoon(showingSoon: Boolean) {
        _showingSoon.value = showingSoon
    }

    fun setHide(hide: Boolean) {
        _hide.value = hide
    }

    fun setStartingToHide(startingToHide: Boolean) {
        _startingToHide.value = startingToHide
    }

    fun setOnDismissAction(bouncerCallbackActionsModel: BouncerCallbackActionsModel?) {
        _onDismissAction.value = bouncerCallbackActionsModel
    }

    fun setStartDisappearAnimation(runnable: Runnable?) {
        _disappearAnimation.value = runnable
    }

    fun setKeyguardPosition(keyguardPosition: Float) {
        _keyguardPosition.value = keyguardPosition
    }

    fun setResourceUpdateRequests(willUpdateResources: Boolean) {
        _resourceUpdateRequests.value = willUpdateResources
    }

    fun setShowMessage(bouncerShowMessageModel: BouncerShowMessageModel?) {
        _showMessage.value = bouncerShowMessageModel
    }

    fun setKeyguardAuthenticated(keyguardAuthenticated: Boolean?) {
        _keyguardAuthenticated.value = keyguardAuthenticated
    }

    fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean) {
        _isBackButtonEnabled.value = isBackButtonEnabled
    }

    fun setOnScreenTurnedOff(onScreenTurnedOff: Boolean) {
        _onScreenTurnedOff.value = onScreenTurnedOff
    }
}
