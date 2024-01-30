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

package com.android.systemui.bouncer.ui.binder

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.window.OnBackAnimationCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.KeyguardMessageAreaController
import com.android.keyguard.KeyguardSecurityContainerController
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityView
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.biometrics.shared.SideFpsControllerRefactor
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE
import com.android.systemui.bouncer.ui.BouncerViewDelegate
import com.android.systemui.bouncer.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.BouncerLogger
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Binds the bouncer container to its view model. */
object KeyguardBouncerViewBinder {
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardBouncerViewModel,
        primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
        componentFactory: KeyguardBouncerComponent.Factory,
        messageAreaControllerFactory: KeyguardMessageAreaController.Factory,
        bouncerMessageInteractor: BouncerMessageInteractor,
        bouncerLogger: BouncerLogger,
        featureFlags: FeatureFlags,
        selectedUserInteractor: SelectedUserInteractor,
    ) {
        // Builds the KeyguardSecurityContainerController from bouncer view group.
        val securityContainerController: KeyguardSecurityContainerController =
            componentFactory.create(view).securityContainerController
        securityContainerController.init()
        val delegate =
            object : BouncerViewDelegate {
                override fun isFullScreenBouncer(): Boolean {
                    val mode = securityContainerController.currentSecurityMode
                    return mode == KeyguardSecurityModel.SecurityMode.SimPin ||
                        mode == KeyguardSecurityModel.SecurityMode.SimPuk
                }

                override fun getBackCallback(): OnBackAnimationCallback {
                    return securityContainerController.backCallback
                }

                override fun shouldDismissOnMenuPressed(): Boolean {
                    return securityContainerController.shouldEnableMenuKey()
                }

                override fun interceptMediaKey(event: KeyEvent?): Boolean {
                    return securityContainerController.interceptMediaKey(event)
                }

                override fun dispatchBackKeyEventPreIme(): Boolean {
                    return securityContainerController.dispatchBackKeyEventPreIme()
                }

                override fun showNextSecurityScreenOrFinish(): Boolean {
                    return securityContainerController.dismiss(
                        selectedUserInteractor.getSelectedUserId()
                    )
                }

                override fun resume() {
                    securityContainerController.showPrimarySecurityScreen(/* isTurningOff= */ false)
                    securityContainerController.onResume(KeyguardSecurityView.SCREEN_ON)
                }

                override fun setDismissAction(
                    onDismissAction: ActivityStarter.OnDismissAction?,
                    cancelAction: Runnable?
                ) {
                    securityContainerController.setOnDismissAction(onDismissAction, cancelAction)
                }

                override fun willDismissWithActions(): Boolean {
                    return securityContainerController.hasDismissActions()
                }

                override fun willRunDismissFromKeyguard(): Boolean {
                    return securityContainerController.willRunDismissFromKeyguard()
                }

                override fun showPromptReason(reason: Int) {
                    securityContainerController.showPromptReason(reason)
                }
            }
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    viewModel.setBouncerViewDelegate(delegate)
                    launch {
                        viewModel.isShowing.collect { isShowing ->
                            view.visibility = if (isShowing) View.VISIBLE else View.INVISIBLE
                            if (isShowing) {
                                // Reset security container because these views are not reinflated.
                                securityContainerController.prepareToShow()
                                securityContainerController.reinflateViewFlipper {
                                    // Reset Security Container entirely.
                                    securityContainerController.onBouncerVisibilityChanged(
                                        /* isVisible= */ true
                                    )
                                    securityContainerController.showPrimarySecurityScreen(
                                        /* turningOff= */ false
                                    )
                                    securityContainerController.setInitialMessage()
                                    securityContainerController.appear()
                                    securityContainerController.onResume(
                                        KeyguardSecurityView.SCREEN_ON
                                    )
                                    bouncerLogger.bindingBouncerMessageView()
                                    it.bindMessageView(
                                        bouncerMessageInteractor,
                                        messageAreaControllerFactory,
                                        bouncerLogger,
                                        featureFlags
                                    )
                                }
                            } else {
                                securityContainerController.onBouncerVisibilityChanged(
                                    /* isVisible= */ false
                                )
                                securityContainerController.cancelDismissAction()
                                securityContainerController.reset()
                                securityContainerController.onPause()
                            }
                        }
                    }

                    launch {
                        viewModel.startingToHide.collect {
                            securityContainerController.onStartingToHide()
                        }
                    }

                    launch {
                        viewModel.startDisappearAnimation.collect {
                            securityContainerController.startDisappearAnimation(it)
                        }
                    }

                    launch {
                        viewModel.bouncerExpansionAmount.collect { expansion ->
                            securityContainerController.setExpansion(expansion)
                        }
                    }

                    launch {
                        primaryBouncerToGoneTransitionViewModel.bouncerAlpha.collect { alpha ->
                            securityContainerController.setAlpha(alpha)
                        }
                    }

                    launch {
                        viewModel.bouncerExpansionAmount
                            .filter { it == EXPANSION_VISIBLE }
                            .collect {
                                securityContainerController.onResume(KeyguardSecurityView.SCREEN_ON)
                                view.announceForAccessibility(securityContainerController.title)
                            }
                    }

                    launch {
                        viewModel.isInteractable.collect { isInteractable ->
                            securityContainerController.setInteractable(isInteractable)
                        }
                    }

                    launch {
                        viewModel.keyguardPosition.collect { position ->
                            securityContainerController.updateKeyguardPosition(position)
                        }
                    }

                    launch {
                        viewModel.updateResources.collect {
                            securityContainerController.updateResources()
                            viewModel.notifyUpdateResources()
                        }
                    }

                    launch {
                        viewModel.bouncerShowMessage.collect {
                            securityContainerController.showMessage(
                                it.message,
                                it.colorStateList,
                                /* animated= */ true
                            )
                            viewModel.onMessageShown()
                        }
                    }

                    launch {
                        viewModel.keyguardAuthenticated.collect {
                            securityContainerController.finish(
                                selectedUserInteractor.getSelectedUserId()
                            )
                            viewModel.notifyKeyguardAuthenticated()
                        }
                    }

                    launch {
                        viewModel
                            .observeOnIsBackButtonEnabled { view.systemUiVisibility }
                            .collect { view.systemUiVisibility = it }
                    }

                    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
                    if (!SideFpsControllerRefactor.isEnabled) {
                        launch {
                            viewModel.shouldUpdateSideFps.collect {
                                viewModel.updateSideFpsVisibility()
                            }
                        }
                    }

                    // TODO(b/288175061): remove with Flags.FLAG_SIDEFPS_CONTROLLER_REFACTOR
                    if (!SideFpsControllerRefactor.isEnabled) {
                        launch {
                            viewModel.sideFpsShowing.collect {
                                securityContainerController.updateSideFpsVisibility(it)
                            }
                        }
                    }
                    awaitCancellation()
                } finally {
                    viewModel.setBouncerViewDelegate(null)
                }
            }
        }
    }
}
