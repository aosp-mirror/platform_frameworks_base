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
import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.provider.Settings.Global.USER_SWITCHER_ENABLED
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.dagger.QSFlagsModule.PM_LITE_ENABLED
import com.android.systemui.qs.dagger.QSScope
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.phone.SettingsButton
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener
import com.android.systemui.util.DualHeightHorizontalLinearLayout
import com.android.systemui.util.ViewController
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

/**
 * Manages [FooterActionsView] behaviour, both when it's placed in QS or QQS (split shade).
 * Main difference between QS and QQS behaviour is condition when buttons should be visible,
 * determined by [buttonsVisibleState]
 */
@QSScope
internal class FooterActionsController @Inject constructor(
    view: FooterActionsView,
    multiUserSwitchControllerFactory: MultiUserSwitchController.Factory,
    private val activityStarter: ActivityStarter,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    private val userInfoController: UserInfoController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val securityFooterController: QSSecurityFooter,
    private val fgsManagerFooterController: QSFgsManagerFooter,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val globalActionsDialogProvider: Provider<GlobalActionsDialogLite>,
    private val uiEventLogger: UiEventLogger,
    @Named(PM_LITE_ENABLED) private val showPMLiteButton: Boolean,
    private val globalSetting: GlobalSettings,
    private val handler: Handler,
    private val featureFlags: FeatureFlags
) : ViewController<FooterActionsView>(view) {

    private var globalActionsDialog: GlobalActionsDialogLite? = null

    private var lastExpansion = -1f
    private var listening: Boolean = false

    private val alphaAnimator = TouchAnimator.Builder()
            .addFloat(mView, "alpha", 0f, 1f)
            .setStartDelay(0.9f)
            .build()

    var visible = true
        set(value) {
            field = value
            updateVisibility()
        }

    private val settingsButton: SettingsButton = view.findViewById(R.id.settings_button)
    private val settingsButtonContainer: View? = view.findViewById(R.id.settings_button_container)
    private val securityFootersContainer: ViewGroup? =
        view.findViewById(R.id.security_footers_container)
    private val powerMenuLite: View = view.findViewById(R.id.pm_lite)
    private val multiUserSwitchController = multiUserSwitchControllerFactory.create(view)
    private val securityFootersSeparator = View(context).apply {
        visibility = View.GONE
    }

    private val onUserInfoChangedListener = OnUserInfoChangedListener { _, picture, _ ->
        val isGuestUser: Boolean = userManager.isGuestUser(KeyguardUpdateMonitor.getCurrentUser())
        mView.onUserInfoChanged(picture, isGuestUser)
    }

    private val multiUserSetting =
            object : SettingObserver(
                    globalSetting, handler, USER_SWITCHER_ENABLED, userTracker.userId) {
                override fun handleValueChanged(value: Int, observedChange: Boolean) {
                    if (observedChange) {
                        updateView()
                    }
                }
            }

    private val onClickListener = View.OnClickListener { v ->
        // Don't do anything if the tap looks suspicious.
        if (!visible || falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return@OnClickListener
        }
        if (v === settingsButton) {
            if (!deviceProvisionedController.isCurrentUserSetup) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                activityStarter.postQSRunnableDismissingKeyguard {}
                return@OnClickListener
            }
            metricsLogger.action(MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH)
            startSettingsActivity()
        } else if (v === powerMenuLite) {
            uiEventLogger.log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS)
            globalActionsDialog?.showOrHideDialog(false, true, v)
        }
    }

    override fun onInit() {
        multiUserSwitchController.init()
        fgsManagerFooterController.init()
    }

    private fun updateVisibility() {
        val previousVisibility = mView.visibility
        mView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (previousVisibility != mView.visibility) updateView()
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
        globalActionsDialog = globalActionsDialogProvider.get()
        if (showPMLiteButton) {
            powerMenuLite.visibility = View.VISIBLE
            powerMenuLite.setOnClickListener(onClickListener)
        } else {
            powerMenuLite.visibility = View.GONE
        }
        settingsButton.setOnClickListener(onClickListener)
        multiUserSetting.isListening = true
        if (featureFlags.isEnabled(Flags.NEW_FOOTER)) {
            val securityFooter = securityFooterController.view as DualHeightHorizontalLinearLayout
            securityFootersContainer?.addView(securityFooter)
            val separatorWidth = resources.getDimensionPixelSize(R.dimen.new_qs_footer_action_inset)
            securityFootersContainer?.addView(securityFootersSeparator, separatorWidth, 1)
            reformatForNewFooter(securityFooter)
            val fgsFooter = fgsManagerFooterController.view
            securityFootersContainer?.addView(fgsFooter)
            (fgsFooter.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f
            }

            val visibilityListener =
                VisibilityChangedDispatcher.OnVisibilityChangedListener { visibility ->
                    if (visibility == View.GONE) {
                        securityFootersSeparator.visibility = View.GONE
                    } else if (securityFooter.visibility == View.VISIBLE &&
                        fgsFooter.visibility == View.VISIBLE) {
                        securityFootersSeparator.visibility = View.VISIBLE
                    } else {
                        securityFootersSeparator.visibility = View.GONE
                    }
                    fgsManagerFooterController
                        .setCollapsed(securityFooter.visibility == View.VISIBLE)
                }
            securityFooterController.setOnVisibilityChangedListener(visibilityListener)
            fgsManagerFooterController.setOnVisibilityChangedListener(visibilityListener)
        }
        updateView()
    }

    private fun reformatForNewFooter(view: DualHeightHorizontalLinearLayout) {
        // This is only necessary while things are flagged as the view could be attached in two
        // different locations.
        (view.layoutParams as LinearLayout.LayoutParams).apply {
            bottomMargin = 0
            width = 0
            weight = 1f
            marginEnd = resources.getDimensionPixelSize(R.dimen.new_qs_footer_action_inset)
        }
        view.alwaysSingleLine = true
    }

    private fun updateView() {
        mView.updateEverything(multiUserSwitchController.isMultiUserEnabled)
    }

    override fun onViewDetached() {
        globalActionsDialog?.destroy()
        globalActionsDialog = null
        setListening(false)
        multiUserSetting.isListening = false
    }

    fun setListening(listening: Boolean) {
        if (this.listening == listening) {
            return
        }
        this.listening = listening
        if (this.listening) {
            userInfoController.addCallback(onUserInfoChangedListener)
            updateView()
        } else {
            userInfoController.removeCallback(onUserInfoChangedListener)
        }
        if (featureFlags.isEnabled(Flags.NEW_FOOTER)) {
            fgsManagerFooterController.setListening(listening)
            securityFooterController.setListening(listening)
        }
    }

    fun disable(state2: Int) {
        mView.disable(state2, multiUserSwitchController.isMultiUserEnabled)
    }

    fun setExpansion(headerExpansionFraction: Float) {
        alphaAnimator.setPosition(headerExpansionFraction)
    }

    fun setKeyguardShowing(showing: Boolean) {
        setExpansion(lastExpansion)
    }
}
