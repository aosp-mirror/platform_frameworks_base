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
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
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
import kotlinx.coroutines.flow.onEach

/**
 * Encapsulates app state for the lock screen primary and alternate bouncer.
 *
 * Make sure to add newly added flows to the logger.
 */
interface KeyguardBouncerRepository {
    /** Values associated with the PrimaryBouncer (pin/pattern/password) input. */
    val primaryBouncerShow: StateFlow<Boolean>
    val primaryBouncerShowingSoon: StateFlow<Boolean>
    val primaryBouncerStartingToHide: StateFlow<Boolean>
    val primaryBouncerStartingDisappearAnimation: StateFlow<Runnable?>
    /** Determines if we want to instantaneously show the primary bouncer instead of translating. */
    val primaryBouncerScrimmed: StateFlow<Boolean>
    /**
     * Set how much of the notification panel is showing on the screen.
     *
     * ```
     *      0f = panel fully hidden = bouncer fully showing
     *      1f = panel fully showing = bouncer fully hidden
     * ```
     */
    val panelExpansionAmount: StateFlow<Float>
    val keyguardPosition: StateFlow<Float>
    val isBackButtonEnabled: StateFlow<Boolean?>
    /** Determines if user is already unlocked */
    val keyguardAuthenticated: StateFlow<Boolean?>
    val showMessage: StateFlow<BouncerShowMessageModel?>
    val resourceUpdateRequests: StateFlow<Boolean>
    val alternateBouncerVisible: StateFlow<Boolean>
    val alternateBouncerUIAvailable: StateFlow<Boolean>
    val sideFpsShowing: StateFlow<Boolean>

    var lastAlternateBouncerVisibleTime: Long

    fun setPrimaryScrimmed(isScrimmed: Boolean)

    fun setPrimaryShow(isShowing: Boolean)

    fun setPrimaryShowingSoon(showingSoon: Boolean)

    fun setPrimaryStartingToHide(startingToHide: Boolean)

    fun setPrimaryStartDisappearAnimation(runnable: Runnable?)

    fun setPanelExpansion(panelExpansion: Float)

    fun setKeyguardPosition(keyguardPosition: Float)

    fun setResourceUpdateRequests(willUpdateResources: Boolean)

    fun setShowMessage(bouncerShowMessageModel: BouncerShowMessageModel?)

    fun setKeyguardAuthenticated(keyguardAuthenticated: Boolean?)

    fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean)

    fun setAlternateVisible(isVisible: Boolean)

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean)

    fun setSideFpsShowing(isShowing: Boolean)
}

@SysUISingleton
class KeyguardBouncerRepositoryImpl
@Inject
constructor(
    private val clock: SystemClock,
    @Application private val applicationScope: CoroutineScope,
    @BouncerLog private val buffer: TableLogBuffer,
) : KeyguardBouncerRepository {
    /** Values associated with the PrimaryBouncer (pin/pattern/password) input. */
    private val _primaryBouncerShow = MutableStateFlow(false)
    override val primaryBouncerShow = _primaryBouncerShow.asStateFlow()
    private val _primaryBouncerShowingSoon = MutableStateFlow(false)
    override val primaryBouncerShowingSoon = _primaryBouncerShowingSoon.asStateFlow()
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
     *
     * ```
     *      0f = panel fully hidden = bouncer fully showing
     *      1f = panel fully showing = bouncer fully hidden
     * ```
     */
    private val _panelExpansionAmount = MutableStateFlow(EXPANSION_HIDDEN)
    override val panelExpansionAmount = _panelExpansionAmount.asStateFlow()
    private val _keyguardPosition = MutableStateFlow(0f)
    override val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    override val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()
    private val _keyguardAuthenticated = MutableStateFlow<Boolean?>(null)
    /** Determines if user is already unlocked */
    override val keyguardAuthenticated = _keyguardAuthenticated.asStateFlow()
    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    override val showMessage = _showMessage.asStateFlow()
    private val _resourceUpdateRequests = MutableStateFlow(false)
    override val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()
    /** Values associated with the AlternateBouncer */
    private val _alternateBouncerVisible = MutableStateFlow(false)
    override val alternateBouncerVisible = _alternateBouncerVisible.asStateFlow()
    override var lastAlternateBouncerVisibleTime: Long = NOT_VISIBLE
    private val _alternateBouncerUIAvailable = MutableStateFlow(false)
    override val alternateBouncerUIAvailable: StateFlow<Boolean> =
        _alternateBouncerUIAvailable.asStateFlow()
    private val _sideFpsShowing = MutableStateFlow(false)
    override val sideFpsShowing: StateFlow<Boolean> = _sideFpsShowing.asStateFlow()

    init {
        setUpLogging()
    }

    override fun setPrimaryScrimmed(isScrimmed: Boolean) {
        _primaryBouncerScrimmed.value = isScrimmed
    }

    override fun setAlternateVisible(isVisible: Boolean) {
        if (isVisible && !_alternateBouncerVisible.value) {
            lastAlternateBouncerVisibleTime = clock.uptimeMillis()
        } else if (!isVisible) {
            lastAlternateBouncerVisibleTime = NOT_VISIBLE
        }
        _alternateBouncerVisible.value = isVisible
    }

    override fun setAlternateBouncerUIAvailable(isAvailable: Boolean) {
        _alternateBouncerUIAvailable.value = isAvailable
    }

    override fun setPrimaryShow(isShowing: Boolean) {
        _primaryBouncerShow.value = isShowing
    }

    override fun setPrimaryShowingSoon(showingSoon: Boolean) {
        _primaryBouncerShowingSoon.value = showingSoon
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

    override fun setSideFpsShowing(isShowing: Boolean) {
        _sideFpsShowing.value = isShowing
    }

    /** Sets up logs for state flows. */
    private fun setUpLogging() {
        if (!Build.IS_DEBUGGABLE) {
            return
        }

        primaryBouncerShow
            .logDiffsForTable(buffer, "", "PrimaryBouncerShow", false)
            .onEach { Log.d(TAG, "Keyguard Bouncer is ${if (it) "showing" else "hiding."}") }
            .launchIn(applicationScope)
        primaryBouncerShowingSoon
            .logDiffsForTable(buffer, "", "PrimaryBouncerShowingSoon", false)
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
        alternateBouncerUIAvailable
            .logDiffsForTable(buffer, "", "IsAlternateBouncerUIAvailable", false)
            .launchIn(applicationScope)
        sideFpsShowing
            .logDiffsForTable(buffer, "", "isSideFpsShowing", false)
            .launchIn(applicationScope)
    }

    companion object {
        private const val NOT_VISIBLE = -1L
        private const val TAG = "KeyguardBouncerRepositoryImpl"
    }
}
