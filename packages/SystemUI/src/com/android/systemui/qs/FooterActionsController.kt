/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Intent
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.FooterActionsController.ExpansionState.COLLAPSED
import com.android.systemui.qs.FooterActionsController.ExpansionState.EXPANDED
import com.android.systemui.qs.dagger.QSFlagsModule.PM_LITE_ENABLED
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.phone.SettingsButton
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.ViewController
import javax.inject.Inject
import javax.inject.Named

/**
 * Manages [FooterActionsView] behaviour, both when it's placed in QS or QQS (split shade).
 * Main difference between QS and QQS behaviour is condition when buttons should be visible,
 * determined by [buttonsVisibleState]
 */
class FooterActionsController @Inject constructor(
    view: FooterActionsView,
    private val qsPanelController: QSPanelController,
    private val activityStarter: ActivityStarter,
    private val userManager: UserManager,
    private val userInfoController: UserInfoController,
    private val multiUserSwitchController: MultiUserSwitchController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val tunerService: TunerService,
    private val globalActionsDialog: GlobalActionsDialogLite,
    private val uiEventLogger: UiEventLogger,
    @Named(PM_LITE_ENABLED) private val showPMLiteButton: Boolean,
    private val buttonsVisibleState: ExpansionState
) : ViewController<FooterActionsView>(view) {

    enum class ExpansionState { COLLAPSED, EXPANDED }

    private var listening: Boolean = false

    var expanded = false
        set(value) {
            if (field != value) {
                field = value
                updateView()
            }
        }

    private val settingsButton: SettingsButton = view.findViewById(R.id.settings_button)
    private val settingsButtonContainer: View? = view.findViewById(R.id.settings_button_container)
    private val editButton: View = view.findViewById(android.R.id.edit)
    private val powerMenuLite: View = view.findViewById(R.id.pm_lite)

    private val onUserInfoChangedListener = OnUserInfoChangedListener { _, picture, _ ->
        val isGuestUser: Boolean = userManager.isGuestUser(KeyguardUpdateMonitor.getCurrentUser())
        mView.onUserInfoChanged(picture, isGuestUser)
    }

    private val onClickListener = View.OnClickListener { v ->
        // Don't do anything until views are unhidden. Don't do anything if the tap looks
        // suspicious.
        if (!buttonsVisible() || falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return@OnClickListener
        }
        if (v === settingsButton) {
            if (!deviceProvisionedController.isCurrentUserSetup) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                activityStarter.postQSRunnableDismissingKeyguard {}
                return@OnClickListener
            }
            metricsLogger.action(
                    if (expanded) MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                    else MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH)
            if (settingsButton.isTunerClick) {
                activityStarter.postQSRunnableDismissingKeyguard {
                    if (isTunerEnabled()) {
                        tunerService.showResetRequest {
                            // Relaunch settings so that the tuner disappears.
                            startSettingsActivity()
                        }
                    } else {
                        Toast.makeText(context, R.string.tuner_toast, Toast.LENGTH_LONG).show()
                        tunerService.isTunerEnabled = true
                    }
                    startSettingsActivity()
                }
            } else {
                startSettingsActivity()
            }
        } else if (v === powerMenuLite) {
            uiEventLogger.log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS)
            globalActionsDialog.showOrHideDialog(false, true)
        }
    }

    private fun buttonsVisible(): Boolean {
        return when (buttonsVisibleState) {
            EXPANDED -> expanded
            COLLAPSED -> !expanded
        }
    }

    override fun onInit() {
        multiUserSwitchController.init()
    }

    fun hideFooter() {
        mView.visibility = View.GONE
    }

    fun showFooter() {
        mView.visibility = View.VISIBLE
        updateView()
    }

    private fun startSettingsActivity() {
        val animationController = settingsButtonContainer?.let {
            ActivityLaunchAnimator.Controller.fromView(
                    it,
                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON)
            }
        activityStarter.startActivity(Intent(Settings.ACTION_SETTINGS),
                true /* dismissShade */, animationController)
    }

    @VisibleForTesting
    public override fun onViewAttached() {
        if (showPMLiteButton) {
            powerMenuLite.visibility = View.VISIBLE
            powerMenuLite.setOnClickListener(onClickListener)
        } else {
            powerMenuLite.visibility = View.GONE
        }
        settingsButton.setOnClickListener(onClickListener)
        editButton.setOnClickListener(View.OnClickListener { view: View? ->
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return@OnClickListener
            }
            activityStarter.postQSRunnableDismissingKeyguard { qsPanelController.showEdit(view) }
        })

        updateView()
    }

    private fun updateView() {
        mView.updateEverything(buttonsVisible(), isTunerEnabled(),
                multiUserSwitchController.isMultiUserEnabled)
    }

    override fun onViewDetached() {
        setListening(false)
    }

    fun setListening(listening: Boolean) {
        if (this.listening == listening) {
            return
        }
        this.listening = listening
        if (this.listening) {
            userInfoController.addCallback(onUserInfoChangedListener)
        } else {
            userInfoController.removeCallback(onUserInfoChangedListener)
        }
    }

    fun disable(state2: Int) {
        mView.disable(buttonsVisible(), state2, isTunerEnabled(),
                multiUserSwitchController.isMultiUserEnabled)
    }

    fun setExpansion(headerExpansionFraction: Float) {
        mView.setExpansion(headerExpansionFraction)
    }

    fun updateAnimator(width: Int, numTiles: Int) {
        mView.updateAnimator(width, numTiles)
    }

    fun setKeyguardShowing() {
        mView.setKeyguardShowing()
    }

    private fun isTunerEnabled() = tunerService.isTunerEnabled
}