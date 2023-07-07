package com.android.systemui.user.ui.dialog

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.qs.tiles.UserDetailView
import com.android.systemui.statusbar.phone.SystemUIDialog

/**
 * Extracted from the old UserSwitchDialogController. This is the dialog version of the full-screen
 * user switcher. See config_enableFullscreenUserSwitcher
 */
class UserSwitchDialog(
    context: Context,
    adapter: UserDetailView.Adapter,
    uiEventLogger: UiEventLogger,
    falsingManager: FalsingManager,
    activityStarter: ActivityStarter,
    dialogLaunchAnimator: DialogLaunchAnimator,
) : SystemUIDialog(context) {
    init {
        setShowForAllUsers(true)
        setCanceledOnTouchOutside(true)
        setTitle(R.string.qs_user_switch_dialog_title)
        setPositiveButton(R.string.quick_settings_done) { _, _ ->
            uiEventLogger.log(QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE)
        }
        setNeutralButton(
            R.string.quick_settings_more_user_settings,
            { _, _ ->
                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                    uiEventLogger.log(QSUserSwitcherEvent.QS_USER_MORE_SETTINGS)
                    val controller =
                        dialogLaunchAnimator.createActivityLaunchController(
                            getButton(BUTTON_NEUTRAL)
                        )

                    if (controller == null) {
                        dismiss()
                    }

                    activityStarter.postStartActivityDismissingKeyguard(
                        USER_SETTINGS_INTENT,
                        0,
                        controller
                    )
                }
            },
            false /* dismissOnClick */
        )
        val gridFrame =
            LayoutInflater.from(this.context).inflate(R.layout.qs_user_dialog_content, null)
        setView(gridFrame)

        adapter.linkToViewGroup(gridFrame.findViewById(R.id.grid))
        adapter.injectDialogShower(DialogShowerImpl(this, dialogLaunchAnimator))
    }

    companion object {
        private val USER_SETTINGS_INTENT = Intent(Settings.ACTION_USER_SETTINGS)
    }
}
