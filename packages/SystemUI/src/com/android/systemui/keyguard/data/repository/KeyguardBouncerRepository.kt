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

import android.os.Build
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.keyguard.shared.model.KeyguardBouncerModel
import com.android.systemui.log.dagger.BouncerLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.phone.KeyguardBouncer
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

/**
 * Encapsulates app state for the lock screen primary and alternate bouncer.
 *
 * Make sure to add newly added flows to the logger.
 */
@SysUISingleton
class KeyguardBouncerRepository
@Inject
constructor(
    private val viewMediatorCallback: ViewMediatorCallback,
    private val clock: SystemClock,
    @Application private val applicationScope: CoroutineScope,
    @BouncerLog private val buffer: TableLogBuffer,
) {
    /** Values associated with the PrimaryBouncer (pin/pattern/password) input. */
    private val _primaryBouncerVisible = MutableStateFlow(false)
    val primaryBouncerVisible = _primaryBouncerVisible.asStateFlow()
    private val _primaryBouncerShow = MutableStateFlow<KeyguardBouncerModel?>(null)
    val primaryBouncerShow = _primaryBouncerShow.asStateFlow()
    private val _primaryBouncerShowingSoon = MutableStateFlow(false)
    val primaryBouncerShowingSoon = _primaryBouncerShowingSoon.asStateFlow()
    private val _primaryBouncerHide = MutableStateFlow(false)
    val primaryBouncerHide = _primaryBouncerHide.asStateFlow()
    private val _primaryBouncerStartingToHide = MutableStateFlow(false)
    val primaryBouncerStartingToHide = _primaryBouncerStartingToHide.asStateFlow()
    private val _primaryBouncerDisappearAnimation = MutableStateFlow<Runnable?>(null)
    val primaryBouncerStartingDisappearAnimation = _primaryBouncerDisappearAnimation.asStateFlow()
    /** Determines if we want to instantaneously show the primary bouncer instead of translating. */
    private val _primaryBouncerScrimmed = MutableStateFlow(false)
    val primaryBouncerScrimmed = _primaryBouncerScrimmed.asStateFlow()
    /**
     * Set how much of the notification panel is showing on the screen.
     * ```
     *      0f = panel fully hidden = bouncer fully showing
     *      1f = panel fully showing = bouncer fully hidden
     * ```
     */
    private val _panelExpansionAmount = MutableStateFlow(EXPANSION_HIDDEN)
    val panelExpansionAmount = _panelExpansionAmount.asStateFlow()
    private val _keyguardPosition = MutableStateFlow(0f)
    val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _onScreenTurnedOff = MutableStateFlow(false)
    val onScreenTurnedOff = _onScreenTurnedOff.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    /** Determines if user is already unlocked */
    val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    val showMessage = _showMessage.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
    val bouncerPromptReason: Int
        get() = viewMediatorCallback.bouncerPromptReason
    val bouncerErrorMessage: CharSequence?
        get() = viewMediatorCallback.consumeCustomMessage()

    /** Values associated with the AlternateBouncer */
    private val _isAlternateBouncerVisible = MutableStateFlow(false)
    val isAlternateBouncerVisible = _isAlternateBouncerVisible.asStateFlow()
    var lastAlternateBouncerVisibleTime: Long = NOT_VISIBLE
    private val _isAlternateBouncerUIAvailable = MutableStateFlow<Boolean>(false)
    val isAlternateBouncerUIAvailable: StateFlow<Boolean> =
        _isAlternateBouncerUIAvailable.asStateFlow()

    init {
        setUpLogging()
    }

    fun setPrimaryScrimmed(isScrimmed: Boolean) {
        _primaryBouncerScrimmed.value = isScrimmed
    }

    fun setPrimaryVisible(isVisible: Boolean) {
        _primaryBouncerVisible.value = isVisible
    }

    fun setAlternateVisible(isVisible: Boolean) {
        if (isVisible && !_isAlternateBouncerVisible.value) {
            lastAlternateBouncerVisibleTime = clock.uptimeMillis()
        } else if (!isVisible) {
            lastAlternateBouncerVisibleTime = NOT_VISIBLE
        }
        _isAlternateBouncerVisible.value = isVisible
    }

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean) {
        _isAlternateBouncerUIAvailable.value = isAvailable
    }

    fun setPrimaryShow(keyguardBouncerModel: KeyguardBouncerModel?) {
        _primaryBouncerShow.value = keyguardBouncerModel
    }

    fun setPrimaryShowingSoon(showingSoon: Boolean) {
        _primaryBouncerShowingSoon.value = showingSoon
    }

    fun setPrimaryHide(hide: Boolean) {
        _primaryBouncerHide.value = hide
    }

    fun setPrimaryStartingToHide(startingToHide: Boolean) {
        _primaryBouncerStartingToHide.value = startingToHide
    }

    fun setPrimaryStartDisappearAnimation(runnable: Runnable?) {
        _primaryBouncerDisappearAnimation.value = runnable
    }

    fun setPanelExpansion(panelExpansion: Float) {
        _panelExpansionAmount.value = panelExpansion
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

    /** Sets up logs for state flows. */
    private fun setUpLogging() {
        if (!Build.IS_DEBUGGABLE) {
            return
        }

        primaryBouncerVisible
            .logDiffsForTable(buffer, "", "PrimaryBouncerVisible", false)
            .launchIn(applicationScope)
        primaryBouncerShow
            .map { it != null }
            .logDiffsForTable(buffer, "", "PrimaryBouncerShow", false)
            .launchIn(applicationScope)
        primaryBouncerShowingSoon
            .logDiffsForTable(buffer, "", "PrimaryBouncerShowingSoon", false)
            .launchIn(applicationScope)
        primaryBouncerHide
            .logDiffsForTable(buffer, "", "PrimaryBouncerHide", false)
            .launchIn(applicationScope)
        primaryBouncerStartingToHide
            .logDiffsForTable(buffer, "", "PrimaryBouncerStartingToHide", false)
            .launchIn(applicationScope)
        primaryBouncerStartingDisappearAnimation
            .map { it != null }
            .logDiffsForTable(buffer, "", "PrimaryBouncerStartingDisappearAnimation", false)
            .launchIn(applicationScope)
        primaryBouncerScrimmed
            .logDiffsForTable(buffer, "", "PrimaryBouncerScrimmed", false)
            .launchIn(applicationScope)
        panelExpansionAmount
            .map { (it * 1000).toInt() }
            .logDiffsForTable(buffer, "", "PanelExpansionAmountMillis", -1)
            .launchIn(applicationScope)
        keyguardPosition
            .map { it.toInt() }
            .logDiffsForTable(buffer, "", "KeyguardPosition", -1)
            .launchIn(applicationScope)
        onScreenTurnedOff
            .logDiffsForTable(buffer, "", "OnScreenTurnedOff", false)
            .launchIn(applicationScope)
        isBackButtonEnabled
            .filterNotNull()
            .logDiffsForTable(buffer, "", "IsBackButtonEnabled", false)
            .launchIn(applicationScope)
        showMessage
            .map { it?.message }
            .logDiffsForTable(buffer, "", "ShowMessage", null)
            .launchIn(applicationScope)
        resourceUpdateRequests
            .logDiffsForTable(buffer, "", "ResourceUpdateRequests", false)
            .launchIn(applicationScope)
    }

    companion object {
        private const val NOT_VISIBLE = -1L
    }
}
