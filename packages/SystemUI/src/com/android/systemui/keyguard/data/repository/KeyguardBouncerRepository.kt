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
interface KeyguardBouncerRepository {
    /** Values associated with the PrimaryBouncer (pin/pattern/password) input. */
    val primaryBouncerVisible: StateFlow<Boolean>
    val primaryBouncerShow: StateFlow<KeyguardBouncerModel?>
    val primaryBouncerShowingSoon: StateFlow<Boolean>
    val primaryBouncerHide: StateFlow<Boolean>
    val primaryBouncerStartingToHide: StateFlow<Boolean>
    val primaryBouncerStartingDisappearAnimation: StateFlow<Runnable?>
    /** Determines if we want to instantaneously show the primary bouncer instead of translating. */
    val primaryBouncerScrimmed: StateFlow<Boolean>
    /**
     * Set how much of the notification panel is showing on the screen.
     * ```
     *      0f = panel fully hidden = bouncer fully showing
     *      1f = panel fully showing = bouncer fully hidden
     * ```
     */
    val panelExpansionAmount: StateFlow<Float>
    val keyguardPosition: StateFlow<Float>
    val onScreenTurnedOff: StateFlow<Boolean>
    val isBackButtonEnabled: StateFlow<Boolean?>
    /** Determines if user is already unlocked */
    val keyguardAuthenticated: StateFlow<Boolean?>
    val showMessage: StateFlow<BouncerShowMessageModel?>
    val resourceUpdateRequests: StateFlow<Boolean>
    val bouncerPromptReason: Int
    val bouncerErrorMessage: CharSequence?
    val isAlternateBouncerVisible: StateFlow<Boolean>
    val isAlternateBouncerUIAvailable: StateFlow<Boolean>
    var lastAlternateBouncerVisibleTime: Long

    fun setPrimaryScrimmed(isScrimmed: Boolean)

    fun setPrimaryVisible(isVisible: Boolean)

    fun setPrimaryShow(keyguardBouncerModel: KeyguardBouncerModel?)

    fun setPrimaryShowingSoon(showingSoon: Boolean)

    fun setPrimaryHide(hide: Boolean)

    fun setPrimaryStartingToHide(startingToHide: Boolean)

    fun setPrimaryStartDisappearAnimation(runnable: Runnable?)

    fun setPanelExpansion(panelExpansion: Float)

    fun setKeyguardPosition(keyguardPosition: Float)

    fun setResourceUpdateRequests(willUpdateResources: Boolean)

    fun setShowMessage(bouncerShowMessageModel: BouncerShowMessageModel?)

    fun setKeyguardAuthenticated(keyguardAuthenticated: Boolean?)

    fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean)

    fun setOnScreenTurnedOff(onScreenTurnedOff: Boolean)

    fun setAlternateVisible(isVisible: Boolean)

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean)
}

@SysUISingleton
class KeyguardBouncerRepositoryImpl
@Inject
constructor(
    private val viewMediatorCallback: ViewMediatorCallback,
    private val clock: SystemClock,
    @Application private val applicationScope: CoroutineScope,
    @BouncerLog private val buffer: TableLogBuffer,
) : KeyguardBouncerRepository {
    /** Values associated with the PrimaryBouncer (pin/pattern/password) input. */
    private val _primaryBouncerVisible = MutableStateFlow(false)
    override val primaryBouncerVisible = _primaryBouncerVisible.asStateFlow()
    private val _primaryBouncerShow = MutableStateFlow<KeyguardBouncerModel?>(null)
    override val primaryBouncerShow = _primaryBouncerShow.asStateFlow()
    private val _primaryBouncerShowingSoon = MutableStateFlow(false)
    override val primaryBouncerShowingSoon = _primaryBouncerShowingSoon.asStateFlow()
    private val _primaryBouncerHide = MutableStateFlow(false)
    override val primaryBouncerHide = _primaryBouncerHide.asStateFlow()
    private val _primaryBouncerStartingToHide = MutableStateFlow(false)
    override val primaryBouncerStartingToHide = _primaryBouncerStartingToHide.asStateFlow()
    private val _primaryBouncerDisappearAnimation = MutableStateFlow<Runnable?>(null)
    override val primaryBouncerStartingDisappearAnimation =
        _primaryBouncerDisappearAnimation.asStateFlow()
    /** Determines if we want to instantaneously show the primary bouncer instead of translating. */
    private val _primaryBouncerScrimmed = MutableStateFlow(false)
    override val primaryBouncerScrimmed = _primaryBouncerScrimmed.asStateFlow()
    /**
     * Set how much of the notification panel is showing on the screen.
     * ```
     *      0f = panel fully hidden = bouncer fully showing
     *      1f = panel fully showing = bouncer fully hidden
     * ```
     */
    private val _panelExpansionAmount = MutableStateFlow(EXPANSION_HIDDEN)
    override val panelExpansionAmount = _panelExpansionAmount.asStateFlow()
    private val _keyguardPosition = MutableStateFlow(0f)
    override val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _onScreenTurnedOff = MutableStateFlow(false)
    override val onScreenTurnedOff = _onScreenTurnedOff.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    override val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    /** Determines if user is already unlocked */
    override val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    override val showMessage = _showMessage.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    override val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
    override val bouncerPromptReason: Int
        get() = viewMediatorCallback.bouncerPromptReason
    override val bouncerErrorMessage: CharSequence?
        get() = viewMediatorCallback.consumeCustomMessage()

    init {
        setUpLogging()
    }

    /** Values associated with the AlternateBouncer */
    private val _isAlternateBouncerVisible = MutableStateFlow(false)
    override val isAlternateBouncerVisible = _isAlternateBouncerVisible.asStateFlow()
    override var lastAlternateBouncerVisibleTime: Long = NOT_VISIBLE
    private val _isAlternateBouncerUIAvailable = MutableStateFlow<Boolean>(false)
    override val isAlternateBouncerUIAvailable: StateFlow<Boolean> =
        _isAlternateBouncerUIAvailable.asStateFlow()

    override fun setPrimaryScrimmed(isScrimmed: Boolean) {
        _primaryBouncerScrimmed.value = isScrimmed
    }

    override fun setPrimaryVisible(isVisible: Boolean) {
        _primaryBouncerVisible.value = isVisible
    }

    override fun setAlternateVisible(isVisible: Boolean) {
        if (isVisible && !_isAlternateBouncerVisible.value) {
            lastAlternateBouncerVisibleTime = clock.uptimeMillis()
        } else if (!isVisible) {
            lastAlternateBouncerVisibleTime = NOT_VISIBLE
        }
        _isAlternateBouncerVisible.value = isVisible
    }

    override fun setAlternateBouncerUIAvailable(isAvailable: Boolean) {
        _isAlternateBouncerUIAvailable.value = isAvailable
    }

    override fun setPrimaryShow(keyguardBouncerModel: KeyguardBouncerModel?) {
        _primaryBouncerShow.value = keyguardBouncerModel
    }

    override fun setPrimaryShowingSoon(showingSoon: Boolean) {
        _primaryBouncerShowingSoon.value = showingSoon
    }

    override fun setPrimaryHide(hide: Boolean) {
        _primaryBouncerHide.value = hide
    }

    override fun setPrimaryStartingToHide(startingToHide: Boolean) {
        _primaryBouncerStartingToHide.value = startingToHide
    }

    override fun setPrimaryStartDisappearAnimation(runnable: Runnable?) {
        _primaryBouncerDisappearAnimation.value = runnable
    }

    override fun setPanelExpansion(panelExpansion: Float) {
        _panelExpansionAmount.value = panelExpansion
    }

    override fun setKeyguardPosition(keyguardPosition: Float) {
        _keyguardPosition.value = keyguardPosition
    }

    override fun setResourceUpdateRequests(willUpdateResources: Boolean) {
        _resourceUpdateRequests.value = willUpdateResources
    }

    override fun setShowMessage(bouncerShowMessageModel: BouncerShowMessageModel?) {
        _showMessage.value = bouncerShowMessageModel
    }

    override fun setKeyguardAuthenticated(keyguardAuthenticated: Boolean?) {
        _keyguardAuthenticated.value = keyguardAuthenticated
    }

    override fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean) {
        _isBackButtonEnabled.value = isBackButtonEnabled
    }

    override fun setOnScreenTurnedOff(onScreenTurnedOff: Boolean) {
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
