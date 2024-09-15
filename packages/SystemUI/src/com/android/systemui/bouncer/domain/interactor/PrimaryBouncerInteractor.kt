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
 * limitations under the License.
 */

package com.android.systemui.bouncer.domain.interactor

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Trace
import android.util.Log
import android.view.View
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.DejankUtils
import com.android.systemui.Flags
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.bouncer.shared.model.BouncerDismissActionModel
import com.android.systemui.bouncer.shared.model.BouncerShowMessageModel
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Encapsulates business logic for interacting with the lock-screen primary (pin/pattern/password)
 * bouncer.
 */
@SysUISingleton
class PrimaryBouncerInteractor
@Inject
constructor(
    private val repository: KeyguardBouncerRepository,
    private val primaryBouncerView: BouncerView,
    @Main private val mainHandler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardSecurityModel: KeyguardSecurityModel,
    private val primaryBouncerCallbackInteractor: PrimaryBouncerCallbackInteractor,
    private val falsingCollector: FalsingCollector,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val trustRepository: TrustRepository,
    @Application private val applicationScope: CoroutineScope,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
) {
    private val passiveAuthBouncerDelay =
        context.resources.getInteger(R.integer.primary_bouncer_passive_auth_delay).toLong()

    /** Runnable to show the primary bouncer. */
    val showRunnable = Runnable {
        repository.setPrimaryShow(true)
        repository.setPrimaryShowingSoon(false)
        primaryBouncerCallbackInteractor.dispatchVisibilityChanged(View.VISIBLE)
    }
    val keyguardAuthenticatedPrimaryAuth: Flow<Int> = repository.keyguardAuthenticatedPrimaryAuth
    val keyguardAuthenticatedBiometrics: Flow<Boolean> =
        repository.keyguardAuthenticatedBiometrics.filterNotNull()
    val keyguardAuthenticatedBiometricsHandled: Flow<Unit> =
        repository.keyguardAuthenticatedBiometrics.filter { it == null }.map {}
    val userRequestedBouncerWhenAlreadyAuthenticated: Flow<Int> =
        repository.userRequestedBouncerWhenAlreadyAuthenticated.filterNotNull()
    val isShowing: StateFlow<Boolean> = repository.primaryBouncerShow
    val startingToHide: Flow<Unit> = repository.primaryBouncerStartingToHide.filter { it }.map {}
    val isBackButtonEnabled: Flow<Boolean> = repository.isBackButtonEnabled.filterNotNull()
    val showMessage: Flow<BouncerShowMessageModel> = repository.showMessage.filterNotNull()
    val startingDisappearAnimation: Flow<Runnable> =
        repository.primaryBouncerStartingDisappearAnimation.filterNotNull()
    val resourceUpdateRequests: Flow<Boolean> = repository.resourceUpdateRequests.filter { it }
    val keyguardPosition: Flow<Float> = repository.keyguardPosition.filterNotNull()
    val panelExpansionAmount: Flow<Float> = repository.panelExpansionAmount
    val lastShownSecurityMode: Flow<KeyguardSecurityModel.SecurityMode> =
        repository.lastShownSecurityMode

    /** 0f = bouncer fully hidden. 1f = bouncer fully visible. */
    val bouncerExpansion: Flow<Float> =
        combine(repository.panelExpansionAmount, repository.primaryBouncerShow) {
            panelExpansion,
            primaryBouncerIsShowing ->
            if (primaryBouncerIsShowing) {
                1f - panelExpansion
            } else {
                0f
            }
        }

    /** Allow for interaction when just about fully visible */
    val isInteractable: Flow<Boolean> = bouncerExpansion.map { it > 0.9 }
    private var currentUserActiveUnlockRunning = false

    init {
        applicationScope.launch {
            trustRepository.isCurrentUserActiveUnlockRunning.collect {
                currentUserActiveUnlockRunning = it
            }
        }
    }

    // TODO(b/243685699): Move isScrimmed logic to data layer.
    // TODO(b/243695312): Encapsulate all of the show logic for the bouncer.
    /** Show the bouncer if necessary and set the relevant states. */
    @JvmOverloads
    fun show(isScrimmed: Boolean): Boolean {
        // When the scene container framework is enabled, instead of calling this, call
        // SceneInteractor#changeScene(Scenes.Bouncer, ...).
        SceneContainerFlag.assertInLegacyMode()

        if (primaryBouncerView.delegate == null && !Flags.composeBouncer()) {
            Log.d(
                TAG,
                "PrimaryBouncerInteractor#show is being called before the " +
                    "primaryBouncerDelegate is set. Let's exit early so we don't " +
                    "set the wrong primaryBouncer state."
            )
            return false
        }

        try {
            Trace.beginSection("KeyguardBouncer#show")
            // Reset some states as we show the bouncer.
            repository.setKeyguardAuthenticatedBiometrics(null)
            repository.setPrimaryStartingToHide(false)

            val resumeBouncer =
                (isBouncerShowing() || repository.primaryBouncerShowingSoon.value) &&
                    needsFullscreenBouncer()

            repository.setPrimaryScrimmed(isScrimmed)
            if (isScrimmed) {
                setPanelExpansion(KeyguardBouncerConstants.EXPANSION_VISIBLE)
            }

            // In this special case, we want to hide the bouncer and show it again. We want to emit
            // show(true) again so that we can reinflate the new view.
            if (resumeBouncer) {
                repository.setPrimaryShow(false)
            }

            if (primaryBouncerView.delegate?.showNextSecurityScreenOrFinish() == true) {
                // Keyguard is done.
                return false
            }

            repository.setPrimaryShowingSoon(true)
            if (usePrimaryBouncerPassiveAuthDelay()) {
                Log.d(TAG, "delay bouncer, passive auth may succeed")
                mainHandler.postDelayed(showRunnable, passiveAuthBouncerDelay)
            } else {
                DejankUtils.postAfterTraversal(showRunnable)
            }
            keyguardStateController.notifyPrimaryBouncerShowing(true)
            primaryBouncerCallbackInteractor.dispatchStartingToShow()
            return true
        } finally {
            Trace.endSection()
        }
    }

    /** Sets the correct bouncer states to hide the bouncer. */
    fun hide() {
        Trace.beginSection("KeyguardBouncer#hide")
        if (isFullyShowing()) {
            SysUiStatsLog.write(
                SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED,
                SysUiStatsLog.KEYGUARD_BOUNCER_STATE_CHANGED__STATE__HIDDEN
            )
            dismissCallbackRegistry.notifyDismissCancelled()
        }

        repository.setPrimaryStartDisappearAnimation(null)
        falsingCollector.onBouncerHidden()
        keyguardStateController.notifyPrimaryBouncerShowing(false /* showing */)
        cancelShowRunnable()
        repository.setPrimaryShowingSoon(false)
        repository.setPrimaryShow(false)
        repository.setPanelExpansion(EXPANSION_HIDDEN)
        primaryBouncerCallbackInteractor.dispatchVisibilityChanged(View.INVISIBLE)
        Trace.endSection()
    }

    /**
     * Sets the panel expansion which is calculated further upstream. Panel expansion is from 0f
     * (panel fully hidden) to 1f (panel fully showing). As the panel shows (from 0f => 1f), the
     * bouncer hides and as the panel becomes hidden (1f => 0f), the bouncer starts to show.
     * Therefore, a panel expansion of 1f represents the bouncer fully hidden and a panel expansion
     * of 0f represents the bouncer fully showing.
     */
    fun setPanelExpansion(expansion: Float) {
        val oldExpansion = repository.panelExpansionAmount.value
        val expansionChanged = oldExpansion != expansion
        if (repository.primaryBouncerStartingDisappearAnimation.value == null) {
            repository.setPanelExpansion(expansion)
        }

        if (
            expansion == KeyguardBouncerConstants.EXPANSION_VISIBLE &&
                oldExpansion != KeyguardBouncerConstants.EXPANSION_VISIBLE
        ) {
            falsingCollector.onBouncerShown()
            primaryBouncerCallbackInteractor.dispatchFullyShown()
        } else if (
            expansion == KeyguardBouncerConstants.EXPANSION_HIDDEN &&
                oldExpansion != KeyguardBouncerConstants.EXPANSION_HIDDEN
        ) {
            /*
             * There are cases where #hide() was not invoked, such as when
             * NotificationPanelViewController controls the hide animation. Make sure the state gets
             * updated by calling #hide() directly.
             */
            hide()
            DejankUtils.postAfterTraversal { primaryBouncerCallbackInteractor.dispatchReset() }
            primaryBouncerCallbackInteractor.dispatchFullyHidden()
        } else if (
            expansion != KeyguardBouncerConstants.EXPANSION_VISIBLE &&
                oldExpansion == KeyguardBouncerConstants.EXPANSION_VISIBLE
        ) {
            primaryBouncerCallbackInteractor.dispatchStartingToHide()
            repository.setPrimaryStartingToHide(true)
        }
        if (expansionChanged) {
            primaryBouncerCallbackInteractor.dispatchExpansionChanged(expansion)
        }
    }

    /** Set the initial keyguard message to show when bouncer is shown. */
    fun showMessage(message: String?, colorStateList: ColorStateList?) {
        repository.setShowMessage(BouncerShowMessageModel(message, colorStateList))
    }

    val bouncerDismissAction: BouncerDismissActionModel?
        get() = repository.bouncerDismissActionModel

    /**
     * Sets actions to the bouncer based on how the bouncer is dismissed. If the bouncer is
     * unlocked, we will run the onDismissAction. If the bouncer is exited before unlocking, we call
     * cancelAction.
     */
    fun setDismissAction(
        onDismissAction: ActivityStarter.OnDismissAction?,
        cancelAction: Runnable?
    ) {
        repository.bouncerDismissActionModel =
            if (onDismissAction != null && cancelAction != null) {
                BouncerDismissActionModel(onDismissAction, cancelAction)
            } else {
                null
            }
        primaryBouncerView.delegate?.setDismissAction(onDismissAction, cancelAction)
    }

    /** Update the resources of the views. */
    fun updateResources() {
        repository.setResourceUpdateRequests(true)
    }

    /** Tell the bouncer that keyguard is authenticated with primary authentication. */
    fun notifyKeyguardAuthenticatedPrimaryAuth(userId: Int) {
        applicationScope.launch { repository.setKeyguardAuthenticatedPrimaryAuth(userId) }
    }

    /** Tell the bouncer that bouncer is requested when device is already authenticated */
    fun notifyUserRequestedBouncerWhenAlreadyAuthenticated(userId: Int) {
        applicationScope.launch {
            repository.setUserRequestedBouncerWhenAlreadyAuthenticated(userId)
        }
    }

    /** Tell the bouncer that keyguard is authenticated with biometrics. */
    fun notifyKeyguardAuthenticatedBiometrics(strongAuth: Boolean) {
        repository.setKeyguardAuthenticatedBiometrics(strongAuth)
    }

    /** Update the position of the bouncer when showing. */
    fun setKeyguardPosition(position: Float) {
        repository.setKeyguardPosition(position)
    }

    /** Notifies that the state change was handled. */
    fun notifyKeyguardAuthenticatedHandled() {
        repository.setKeyguardAuthenticatedBiometrics(null)
    }

    /** Notifies that the message was shown. */
    fun onMessageShown() {
        repository.setShowMessage(null)
    }

    /** Notify that the resources have been updated */
    fun notifyUpdatedResources() {
        repository.setResourceUpdateRequests(false)
    }

    /** Set whether back button is enabled when on the bouncer screen. */
    fun setBackButtonEnabled(enabled: Boolean) {
        repository.setIsBackButtonEnabled(enabled)
    }

    /** Tell the bouncer to start the pre hide animation. */
    fun startDisappearAnimation(runnable: Runnable) {
        if (willRunDismissFromKeyguard()) {
            runnable.run()
            return
        }

        repository.setPrimaryStartDisappearAnimation(runnable)
    }

    /** Returns whether bouncer is fully showing. */
    fun isFullyShowing(): Boolean {
        return (repository.primaryBouncerShowingSoon.value || isBouncerShowing()) &&
            repository.panelExpansionAmount.value == KeyguardBouncerConstants.EXPANSION_VISIBLE &&
            repository.primaryBouncerStartingDisappearAnimation.value == null
    }

    /** Returns whether bouncer is scrimmed. */
    fun isScrimmed(): Boolean {
        return repository.primaryBouncerScrimmed.value
    }

    /** If bouncer expansion is between 0f and 1f non-inclusive. */
    fun isInTransit(): Boolean {
        return repository.primaryBouncerShowingSoon.value ||
            repository.panelExpansionAmount.value != KeyguardBouncerConstants.EXPANSION_HIDDEN &&
                repository.panelExpansionAmount.value != KeyguardBouncerConstants.EXPANSION_VISIBLE
    }

    /** Return whether bouncer is animating away. */
    fun isAnimatingAway(): Boolean {
        return repository.primaryBouncerStartingDisappearAnimation.value != null
    }

    /** Return whether bouncer will dismiss with actions */
    fun willDismissWithAction(): Boolean {
        return primaryBouncerView.delegate?.willDismissWithActions() == true
    }

    /** Will the dismissal run from the keyguard layout (instead of from bouncer) */
    fun willRunDismissFromKeyguard(): Boolean {
        return primaryBouncerView.delegate?.willRunDismissFromKeyguard() == true
    }

    /** Returns whether the bouncer should be full screen. */
    private fun needsFullscreenBouncer(): Boolean {
        val mode: KeyguardSecurityModel.SecurityMode =
            keyguardSecurityModel.getSecurityMode(selectedUserInteractor.getSelectedUserId())
        return mode == KeyguardSecurityModel.SecurityMode.SimPin ||
            mode == KeyguardSecurityModel.SecurityMode.SimPuk
    }

    /** Remove the show runnable from the main handler queue to improve performance. */
    private fun cancelShowRunnable() {
        DejankUtils.removeCallbacks(showRunnable)
        mainHandler.removeCallbacks(showRunnable)
    }

    /** Returns whether the primary bouncer is currently showing. */
    fun isBouncerShowing(): Boolean {
        return isShowing.value
    }

    fun setLastShownPrimarySecurityScreen(securityMode: KeyguardSecurityModel.SecurityMode) {
        repository.setLastShownSecurityMode(securityMode)
    }

    /** Whether we want to wait to show the bouncer in case passive auth succeeds. */
    private fun usePrimaryBouncerPassiveAuthDelay(): Boolean {
        val canRunActiveUnlock =
            currentUserActiveUnlockRunning &&
                keyguardUpdateMonitor.canTriggerActiveUnlockBasedOnDeviceState()

        return !needsFullscreenBouncer() &&
            (deviceEntryFaceAuthInteractor.canFaceAuthRun() || canRunActiveUnlock)
    }

    companion object {
        private const val TAG = "PrimaryBouncerInteractor"
    }
}
