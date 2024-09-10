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

package com.android.systemui.bouncer.data.repository

import android.os.Build
import android.util.Log
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.bouncer.shared.model.BouncerDismissActionModel
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.log.dagger.BouncerTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val keyguardPosition: StateFlow<Float?>
    val isBackButtonEnabled: StateFlow<Boolean?>

    /**
     * Triggers when the user has successfully used biometrics to authenticate. True = biometrics
     * used to authenticate is Class 3, else false. When null, biometrics haven't authenticated the
     * device.
     */
    val keyguardAuthenticatedBiometrics: StateFlow<Boolean?>

    /**
     * Triggers when the given userId (Int) has successfully used primary authentication to
     * authenticate
     */
    val keyguardAuthenticatedPrimaryAuth: Flow<Int>

    /** Triggers when the given userId (Int) has requested the bouncer when already authenticated */
    val userRequestedBouncerWhenAlreadyAuthenticated: Flow<Int>

    val showMessage: StateFlow<BouncerShowMessageModel?>
    val resourceUpdateRequests: StateFlow<Boolean>
    val alternateBouncerVisible: StateFlow<Boolean>
    val alternateBouncerUIAvailable: StateFlow<Boolean>

    /** Last shown security mode of the primary bouncer (ie: pin/pattern/password/SIM) */
    val lastShownSecurityMode: StateFlow<KeyguardSecurityModel.SecurityMode>

    /** Action that should be run right after the bouncer is dismissed. */
    var bouncerDismissActionModel: BouncerDismissActionModel?

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

    fun setKeyguardAuthenticatedBiometrics(keyguardAuthenticatedBiometrics: Boolean?)

    suspend fun setKeyguardAuthenticatedPrimaryAuth(userId: Int)

    suspend fun setUserRequestedBouncerWhenAlreadyAuthenticated(userId: Int)

    fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean)

    fun setAlternateVisible(isVisible: Boolean)

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean)

    fun setLastShownSecurityMode(securityMode: KeyguardSecurityModel.SecurityMode)
}

@SysUISingleton
class KeyguardBouncerRepositoryImpl
@Inject
constructor(
    private val clock: SystemClock,
    @Application private val applicationScope: CoroutineScope,
    @BouncerTableLog private val buffer: TableLogBuffer,
) : KeyguardBouncerRepository {
    override var bouncerDismissActionModel: BouncerDismissActionModel? = null

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
    private val _keyguardPosition = MutableStateFlow<Float?>(null)
    override val keyguardPosition = _keyguardPosition.asStateFlow()
    private val _isBackButtonEnabled = MutableStateFlow<Boolean?>(null)
    override val isBackButtonEnabled = _isBackButtonEnabled.asStateFlow()

    /** Whether the user is already unlocked by biometrics */
    private val _keyguardAuthenticatedBiometrics = MutableStateFlow<Boolean?>(null)
    override val keyguardAuthenticatedBiometrics = _keyguardAuthenticatedBiometrics.asStateFlow()

    /** Whether the user is unlocked via a primary authentication method (pin/pattern/password). */
    private val _keyguardAuthenticatedPrimaryAuth = MutableSharedFlow<Int>()
    override val keyguardAuthenticatedPrimaryAuth: Flow<Int> =
        _keyguardAuthenticatedPrimaryAuth.asSharedFlow()

    /** Whether the user requested to show the bouncer when device is already authenticated */
    private val _userRequestedBouncerWhenAlreadyAuthenticated = MutableSharedFlow<Int>()
    override val userRequestedBouncerWhenAlreadyAuthenticated: Flow<Int> =
        _userRequestedBouncerWhenAlreadyAuthenticated.asSharedFlow()

    private val _showMessage = MutableStateFlow<BouncerShowMessageModel?>(null)
    override val showMessage = _showMessage.asStateFlow()
    private val _lastShownSecurityMode =
        MutableStateFlow(KeyguardSecurityModel.SecurityMode.Invalid)
    override val lastShownSecurityMode: StateFlow<KeyguardSecurityModel.SecurityMode> =
        _lastShownSecurityMode.asStateFlow()

    private val _resourceUpdateRequests = MutableStateFlow(false)
    override val resourceUpdateRequests = _resourceUpdateRequests.asStateFlow()

    /** Values associated with the AlternateBouncer */
    private val _alternateBouncerVisible = MutableStateFlow(false)
    override val alternateBouncerVisible = _alternateBouncerVisible.asStateFlow()
    override var lastAlternateBouncerVisibleTime: Long = NOT_VISIBLE
    private val _alternateBouncerUIAvailable = MutableStateFlow(false)
    override val alternateBouncerUIAvailable: StateFlow<Boolean> =
        _alternateBouncerUIAvailable.asStateFlow()

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
        DeviceEntryUdfpsRefactor.assertInLegacyMode()
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

    override fun setKeyguardAuthenticatedBiometrics(keyguardAuthenticatedBiometrics: Boolean?) {
        _keyguardAuthenticatedBiometrics.value = keyguardAuthenticatedBiometrics
    }

    override suspend fun setKeyguardAuthenticatedPrimaryAuth(userId: Int) {
        _keyguardAuthenticatedPrimaryAuth.emit(userId)
    }

    override suspend fun setUserRequestedBouncerWhenAlreadyAuthenticated(userId: Int) {
        _userRequestedBouncerWhenAlreadyAuthenticated.emit(userId)
    }

    override fun setIsBackButtonEnabled(isBackButtonEnabled: Boolean) {
        _isBackButtonEnabled.value = isBackButtonEnabled
    }

    override fun setLastShownSecurityMode(securityMode: KeyguardSecurityModel.SecurityMode) {
        _lastShownSecurityMode.value = securityMode
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
            .filterNotNull()
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
        alternateBouncerVisible
            .logDiffsForTable(buffer, "", "AlternateBouncerVisible", false)
            .launchIn(applicationScope)
        lastShownSecurityMode
            .map { it.name }
            .logDiffsForTable(buffer, "", "lastShownSecurityMode", null)
            .launchIn(applicationScope)
    }

    companion object {
        private const val NOT_VISIBLE = -1L
        private const val TAG = "KeyguardBouncerRepositoryImpl"
    }
}
