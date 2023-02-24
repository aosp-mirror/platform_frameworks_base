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

package com.android.systemui.keyguard.domain.interactor

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Trace
import android.os.UserHandle
import android.os.UserManager
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.DejankUtils
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.shared.model.BouncerCallbackActionsModel
import com.android.systemui.keyguard.shared.model.BouncerShowMessageModel
import com.android.systemui.keyguard.shared.model.KeyguardBouncerModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.phone.KeyguardBouncer
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Encapsulates business logic for interacting with the lock-screen bouncer. */
@SysUISingleton
class BouncerInteractor
@Inject
constructor(
    private val repository: KeyguardBouncerRepository,
    private val bouncerView: BouncerView,
    @Main private val mainHandler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardSecurityModel: KeyguardSecurityModel,
    private val callbackInteractor: BouncerCallbackInteractor,
    private val falsingCollector: FalsingCollector,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    keyguardBypassController: KeyguardBypassController,
    keyguardUpdateMonitor: KeyguardUpdateMonitor,
) {
    /** Whether we want to wait for face auth. */
    private val bouncerFaceDelay =
        keyguardStateController.isFaceAuthEnabled &&
            !keyguardUpdateMonitor.getCachedIsUnlockWithFingerprintPossible(
                KeyguardUpdateMonitor.getCurrentUser()
            ) &&
            !needsFullscreenBouncer() &&
            !keyguardUpdateMonitor.userNeedsStrongAuth() &&
            !keyguardBypassController.bypassEnabled

    /** Runnable to show the bouncer. */
    val showRunnable = Runnable {
        repository.setVisible(true)
        repository.setShow(
            KeyguardBouncerModel(
                promptReason = repository.bouncerPromptReason ?: 0,
                errorMessage = repository.bouncerErrorMessage,
                expansionAmount = repository.expansionAmount.value
            )
        )
        repository.setShowingSoon(false)
    }

    val keyguardAuthenticated: Flow<Boolean> = repository.keyguardAuthenticated.filterNotNull()
    val screenTurnedOff: Flow<Unit> = repository.onScreenTurnedOff.filter { it }.map {}
    val show: Flow<KeyguardBouncerModel> = repository.show.filterNotNull()
    val hide: Flow<Unit> = repository.hide.filter { it }.map {}
    val startingToHide: Flow<Unit> = repository.startingToHide.filter { it }.map {}
    val isVisible: Flow<Boolean> = repository.isVisible
    val isBackButtonEnabled: Flow<Boolean> = repository.isBackButtonEnabled.filterNotNull()
    val expansionAmount: Flow<Float> = repository.expansionAmount
    val showMessage: Flow<BouncerShowMessageModel> = repository.showMessage.filterNotNull()
    val startingDisappearAnimation: Flow<Runnable> =
        repository.startingDisappearAnimation.filterNotNull()
    val onDismissAction: Flow<BouncerCallbackActionsModel> =
        repository.onDismissAction.filterNotNull()
    val resourceUpdateRequests: Flow<Boolean> = repository.resourceUpdateRequests.filter { it }
    val keyguardPosition: Flow<Float> = repository.keyguardPosition

    // TODO(b/243685699): Move isScrimmed logic to data layer.
    // TODO(b/243695312): Encapsulate all of the show logic for the bouncer.
    /** Show the bouncer if necessary and set the relevant states. */
    @JvmOverloads
    fun show(isScrimmed: Boolean) {
        // Reset some states as we show the bouncer.
        repository.setShowMessage(null)
        repository.setOnScreenTurnedOff(false)
        repository.setKeyguardAuthenticated(null)
        repository.setHide(false)
        repository.setStartingToHide(false)

        val resumeBouncer =
            (repository.isVisible.value || repository.showingSoon.value) && needsFullscreenBouncer()

        if (!resumeBouncer && repository.show.value != null) {
            // If bouncer is visible, the bouncer is already showing.
            return
        }

        val keyguardUserId = KeyguardUpdateMonitor.getCurrentUser()
        if (keyguardUserId == UserHandle.USER_SYSTEM && UserManager.isSplitSystemUser()) {
            // In split system user mode, we never unlock system user.
            return
        }

        Trace.beginSection("KeyguardBouncer#show")
        repository.setScrimmed(isScrimmed)
        if (isScrimmed) {
            setExpansion(KeyguardBouncer.EXPANSION_VISIBLE)
        }

        if (resumeBouncer) {
            bouncerView.delegate?.resume()
            // Bouncer is showing the next security screen and we just need to prompt a resume.
            return
        }
        if (bouncerView.delegate?.showNextSecurityScreenOrFinish() == true) {
            // Keyguard is done.
            return
        }

        repository.setShowingSoon(true)
        if (bouncerFaceDelay) {
            mainHandler.postDelayed(showRunnable, 1200L)
        } else {
            DejankUtils.postAfterTraversal(showRunnable)
        }
        keyguardStateController.notifyBouncerShowing(true)
        callbackInteractor.dispatchStartingToShow()

        Trace.endSection()
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

        falsingCollector.onBouncerHidden()
        keyguardStateController.notifyBouncerShowing(false /* showing */)
        cancelShowRunnable()
        repository.setShowingSoon(false)
        repository.setOnDismissAction(null)
        repository.setVisible(false)
        repository.setHide(true)
        repository.setShow(null)
        Trace.endSection()
    }

    /**
     * Sets the panel expansion which is calculated further upstream. Expansion is from 0f to 1f
     * where 0f => showing and 1f => hiding
     */
    fun setExpansion(expansion: Float) {
        val oldExpansion = repository.expansionAmount.value
        val expansionChanged = oldExpansion != expansion
        if (repository.startingDisappearAnimation.value == null) {
            repository.setExpansion(expansion)
        }

        if (
            expansion == KeyguardBouncer.EXPANSION_VISIBLE &&
                oldExpansion != KeyguardBouncer.EXPANSION_VISIBLE
        ) {
            falsingCollector.onBouncerShown()
            callbackInteractor.dispatchFullyShown()
        } else if (
            expansion == KeyguardBouncer.EXPANSION_HIDDEN &&
                oldExpansion != KeyguardBouncer.EXPANSION_HIDDEN
        ) {
            repository.setVisible(false)
            repository.setShow(null)
            falsingCollector.onBouncerHidden()
            DejankUtils.postAfterTraversal { callbackInteractor.dispatchReset() }
            callbackInteractor.dispatchFullyHidden()
        } else if (
            expansion != KeyguardBouncer.EXPANSION_VISIBLE &&
                oldExpansion == KeyguardBouncer.EXPANSION_VISIBLE
        ) {
            callbackInteractor.dispatchStartingToHide()
            repository.setStartingToHide(true)
        }
        if (expansionChanged) {
            callbackInteractor.dispatchExpansionChanged(expansion)
        }
    }

    /** Set the initial keyguard message to show when bouncer is shown. */
    fun showMessage(message: String?, colorStateList: ColorStateList?) {
        repository.setShowMessage(BouncerShowMessageModel(message, colorStateList))
    }

    /**
     * Sets actions to the bouncer based on how the bouncer is dismissed. If the bouncer is
     * unlocked, we will run the onDismissAction. If the bouncer is existed before unlocking, we
     * call cancelAction.
     */
    fun setDismissAction(
        onDismissAction: ActivityStarter.OnDismissAction?,
        cancelAction: Runnable?
    ) {
        repository.setOnDismissAction(BouncerCallbackActionsModel(onDismissAction, cancelAction))
    }

    /** Update the resources of the views. */
    fun updateResources() {
        repository.setResourceUpdateRequests(true)
    }

    /** Tell the bouncer that keyguard is authenticated. */
    fun notifyKeyguardAuthenticated(strongAuth: Boolean) {
        repository.setKeyguardAuthenticated(strongAuth)
    }

    /** Tell the bouncer the screen has turned off. */
    fun onScreenTurnedOff() {
        repository.setOnScreenTurnedOff(true)
    }

    /** Update the position of the bouncer when showing. */
    fun setKeyguardPosition(position: Float) {
        repository.setKeyguardPosition(position)
    }

    /** Notifies that the state change was handled. */
    fun notifyKeyguardAuthenticatedHandled() {
        repository.setKeyguardAuthenticated(null)
    }

    /** Notify that view visibility has changed. */
    fun notifyBouncerVisibilityHasChanged(visibility: Int) {
        callbackInteractor.dispatchVisibilityChanged(visibility)
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
        val finishRunnable = Runnable {
            repository.setStartDisappearAnimation(null)
            runnable.run()
        }
        repository.setStartDisappearAnimation(finishRunnable)
    }

    /** Returns whether bouncer is fully showing. */
    fun isFullyShowing(): Boolean {
        return (repository.showingSoon.value || repository.isVisible.value) &&
            repository.expansionAmount.value == KeyguardBouncer.EXPANSION_VISIBLE &&
            repository.startingDisappearAnimation.value == null
    }

    /** Returns whether bouncer is scrimmed. */
    fun isScrimmed(): Boolean {
        return repository.isScrimmed.value
    }

    /** If bouncer expansion is between 0f and 1f non-inclusive. */
    fun isInTransit(): Boolean {
        return repository.showingSoon.value ||
            repository.expansionAmount.value != KeyguardBouncer.EXPANSION_HIDDEN &&
                repository.expansionAmount.value != KeyguardBouncer.EXPANSION_VISIBLE
    }

    /** Return whether bouncer is animating away. */
    fun isAnimatingAway(): Boolean {
        return repository.startingDisappearAnimation.value != null
    }

    /** Return whether bouncer will dismiss with actions */
    fun willDismissWithAction(): Boolean {
        return repository.onDismissAction.value?.onDismissAction != null
    }

    /** Returns whether the bouncer should be full screen. */
    private fun needsFullscreenBouncer(): Boolean {
        val mode: KeyguardSecurityModel.SecurityMode =
            keyguardSecurityModel.getSecurityMode(KeyguardUpdateMonitor.getCurrentUser())
        return mode == KeyguardSecurityModel.SecurityMode.SimPin ||
            mode == KeyguardSecurityModel.SecurityMode.SimPuk
    }

    /** Remove the show runnable from the main handler queue to improve performance. */
    private fun cancelShowRunnable() {
        DejankUtils.removeCallbacks(showRunnable)
        mainHandler.removeCallbacks(showRunnable)
    }
}
