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

package com.android.systemui.keyguard.ui.binder

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.window.OnBackAnimationCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.policy.SystemBarUtils
import com.android.keyguard.KeyguardHostViewController
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.keyguard.data.BouncerViewDelegate
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.KeyguardBouncer.EXPANSION_VISIBLE
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Binds the bouncer container to its view model. */
object KeyguardBouncerViewBinder {
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardBouncerViewModel,
        componentFactory: KeyguardBouncerComponent.Factory
    ) {
        // Builds the KeyguardHostViewController from bouncer view group.
        val hostViewController: KeyguardHostViewController =
            componentFactory.create(view).keyguardHostViewController
        hostViewController.init()
        val delegate =
            object : BouncerViewDelegate {
                override fun isFullScreenBouncer(): Boolean {
                    val mode = hostViewController.currentSecurityMode
                    return mode == KeyguardSecurityModel.SecurityMode.SimPin ||
                        mode == KeyguardSecurityModel.SecurityMode.SimPuk
                }

                override fun getBackCallback(): OnBackAnimationCallback {
                    return hostViewController.backCallback
                }

                override fun shouldDismissOnMenuPressed(): Boolean {
                    return hostViewController.shouldEnableMenuKey()
                }

                override fun interceptMediaKey(event: KeyEvent?): Boolean {
                    return hostViewController.interceptMediaKey(event)
                }

                override fun dispatchBackKeyEventPreIme(): Boolean {
                    return hostViewController.dispatchBackKeyEventPreIme()
                }

                override fun showNextSecurityScreenOrFinish(): Boolean {
                    return hostViewController.dismiss(KeyguardUpdateMonitor.getCurrentUser())
                }

                override fun resume() {
                    hostViewController.showPrimarySecurityScreen()
                    hostViewController.onResume()
                }

                override fun setDismissAction(
                    onDismissAction: ActivityStarter.OnDismissAction?,
                    cancelAction: Runnable?
                ) {
                    hostViewController.setOnDismissAction(onDismissAction, cancelAction)
                }

                override fun willDismissWithActions(): Boolean {
                    return hostViewController.hasDismissActions()
                }
            }
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    viewModel.setBouncerViewDelegate(delegate)
                    launch {
                        viewModel.show.collect {
                            hostViewController.showPromptReason(it.promptReason)
                            it.errorMessage?.let { errorMessage ->
                                hostViewController.showErrorMessage(errorMessage)
                            }
                            hostViewController.showPrimarySecurityScreen()
                            hostViewController.appear(
                                SystemBarUtils.getStatusBarHeight(view.context)
                            )
                        }
                    }

                    launch {
                        viewModel.showWithFullExpansion.collect { model ->
                            hostViewController.resetSecurityContainer()
                            hostViewController.showPromptReason(model.promptReason)
                            hostViewController.onResume()
                        }
                    }

                    launch {
                        viewModel.hide.collect {
                            hostViewController.cancelDismissAction()
                            hostViewController.cleanUp()
                            hostViewController.resetSecurityContainer()
                        }
                    }

                    launch {
                        viewModel.startingToHide.collect { hostViewController.onStartingToHide() }
                    }

                    launch {
                        viewModel.startDisappearAnimation.collect {
                            hostViewController.startDisappearAnimation(it)
                        }
                    }

                    launch {
                        viewModel.bouncerExpansionAmount.collect { expansion ->
                            hostViewController.setExpansion(expansion)
                        }
                    }

                    launch {
                        viewModel.bouncerExpansionAmount
                            .filter { it == EXPANSION_VISIBLE }
                            .collect {
                                hostViewController.onResume()
                                view.announceForAccessibility(
                                    hostViewController.accessibilityTitleForCurrentMode
                                )
                            }
                    }

                    launch {
                        viewModel.isBouncerVisible.collect { isVisible ->
                            val visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                            view.visibility = visibility
                            hostViewController.onBouncerVisibilityChanged(visibility)
                        }
                    }

                    launch {
                        viewModel.isBouncerVisible
                            .filter { !it }
                            .collect {
                                // Remove existing input for security reasons.
                                hostViewController.resetSecurityContainer()
                            }
                    }

                    launch {
                        viewModel.keyguardPosition.collect { position ->
                            hostViewController.updateKeyguardPosition(position)
                        }
                    }

                    launch {
                        viewModel.updateResources.collect {
                            hostViewController.updateResources()
                            viewModel.notifyUpdateResources()
                        }
                    }

                    launch {
                        viewModel.bouncerShowMessage.collect {
                            hostViewController.showMessage(it.message, it.colorStateList)
                            viewModel.onMessageShown()
                        }
                    }

                    launch {
                        viewModel.keyguardAuthenticated.collect {
                            hostViewController.finish(it, KeyguardUpdateMonitor.getCurrentUser())
                            viewModel.notifyKeyguardAuthenticated()
                        }
                    }

                    launch {
                        viewModel
                            .observeOnIsBackButtonEnabled { view.systemUiVisibility }
                            .collect { view.systemUiVisibility = it }
                    }

                    launch {
                        viewModel.screenTurnedOff.collect {
                            if (view.visibility == View.VISIBLE) {
                                hostViewController.onPause()
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
