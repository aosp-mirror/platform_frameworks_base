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

class QSFooterActionsController @Inject constructor(
    view: QSFooterActionsView,
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
    @Named(PM_LITE_ENABLED) private val showPMLiteButton: Boolean
) : ViewController<QSFooterActionsView>(view) {

    private var listening: Boolean = false
    var expanded = false
        set(value) {
            field = value
            mView.setExpanded(value, isTunerEnabled(),
                    multiUserSwitchController.isMultiUserEnabled)
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
        if (!expanded || falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
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

    override fun onInit() {
        multiUserSwitchController.init()
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

        mView.updateEverything(isTunerEnabled(), multiUserSwitchController.isMultiUserEnabled)
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
        mView.disable(state2, isTunerEnabled(), multiUserSwitchController.isMultiUserEnabled)
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