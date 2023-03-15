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
 *
 */

package com.android.systemui.controls.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.DialogInterface
import android.content.SharedPreferences
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.controls.settings.ControlsSettingsDialogManager.Companion.MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG
import com.android.systemui.controls.settings.ControlsSettingsDialogManager.Companion.PREFS_SETTINGS_DIALOG_ATTEMPTS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

/**
 * Manager to display a dialog to prompt user to enable controls related Settings:
 * * [Settings.Secure.LOCKSCREEN_SHOW_CONTROLS]
 * * [Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS]
 */
interface ControlsSettingsDialogManager {

    /**
     * Shows the corresponding dialog. In order for a dialog to appear, the following must be true
     * * At least one of the Settings in [ControlsSettingsRepository] are `false`.
     * * The dialog has not been seen by the user too many times (as defined by
     *   [MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG]).
     *
     * When the dialogs are shown, the following outcomes are possible:
     * * User cancels the dialog by clicking outside or going back: we register that the dialog was
     *   seen but the settings don't change.
     * * User responds negatively to the dialog: we register that the user doesn't want to change
     *   the settings (dialog will not appear again) and the settings don't change.
     * * User responds positively to the dialog: the settings are set to `true` and the dialog will
     *   not appear again.
     * * SystemUI closes the dialogs (for example, the activity showing it is closed). In this case,
     *   we don't modify anything.
     *
     * Of those four scenarios, only the first three will cause [onAttemptCompleted] to be called.
     * It will also be called if the dialogs are not shown.
     */
    fun maybeShowDialog(activityContext: Context, onAttemptCompleted: () -> Unit)

    /**
     * Closes the dialog without registering anything from the user. The state of the settings after
     * this is called will be the same as before the dialogs were shown.
     */
    fun closeDialog()

    companion object {
        @VisibleForTesting internal const val MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG = 2
        @VisibleForTesting
        internal const val PREFS_SETTINGS_DIALOG_ATTEMPTS = "show_settings_attempts"
    }
}

@SysUISingleton
class ControlsSettingsDialogManagerImpl
@VisibleForTesting
internal constructor(
    private val secureSettings: SecureSettings,
    private val userFileManager: UserFileManager,
    private val controlsSettingsRepository: ControlsSettingsRepository,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val dialogProvider: (context: Context, theme: Int) -> AlertDialog
) : ControlsSettingsDialogManager {

    @Inject
    constructor(
        secureSettings: SecureSettings,
        userFileManager: UserFileManager,
        controlsSettingsRepository: ControlsSettingsRepository,
        userTracker: UserTracker,
        activityStarter: ActivityStarter
    ) : this(
        secureSettings,
        userFileManager,
        controlsSettingsRepository,
        userTracker,
        activityStarter,
        { context, theme -> SettingsDialog(context, theme) }
    )

    private var dialog: AlertDialog? = null
        private set

    private val showDeviceControlsInLockscreen: Boolean
        get() = controlsSettingsRepository.canShowControlsInLockscreen.value

    private val allowTrivialControls: Boolean
        get() = controlsSettingsRepository.allowActionOnTrivialControlsInLockscreen.value

    override fun maybeShowDialog(activityContext: Context, onAttemptCompleted: () -> Unit) {
        closeDialog()

        val prefs =
            userFileManager.getSharedPreferences(
                DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                MODE_PRIVATE,
                userTracker.userId
            )
        val attempts = prefs.getInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, 0)
        if (
            attempts >= MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG ||
                (showDeviceControlsInLockscreen && allowTrivialControls)
        ) {
            onAttemptCompleted()
            return
        }

        val listener = DialogListener(prefs, attempts, onAttemptCompleted)
        val d =
            dialogProvider(activityContext, R.style.Theme_SystemUI_Dialog).apply {
                setIcon(R.drawable.ic_warning)
                setOnCancelListener(listener)
                setNeutralButton(R.string.controls_settings_dialog_neutral_button, listener)
                setPositiveButton(R.string.controls_settings_dialog_positive_button, listener)
                if (showDeviceControlsInLockscreen) {
                    setTitle(R.string.controls_settings_trivial_controls_dialog_title)
                    setMessage(R.string.controls_settings_trivial_controls_dialog_message)
                } else {
                    setTitle(R.string.controls_settings_show_controls_dialog_title)
                    setMessage(R.string.controls_settings_show_controls_dialog_message)
                }
            }

        SystemUIDialog.registerDismissListener(d) { dialog = null }
        SystemUIDialog.setDialogSize(d)
        SystemUIDialog.setShowForAllUsers(d, true)
        dialog = d
        d.show()
    }

    private fun turnOnSettingSecurely(settings: List<String>, onComplete: () -> Unit) {
        val action =
            ActivityStarter.OnDismissAction {
                settings.forEach { setting ->
                    secureSettings.putIntForUser(setting, 1, userTracker.userId)
                }
                onComplete()
                true
            }
        activityStarter.dismissKeyguardThenExecute(
            action,
            /* cancel */ onComplete,
            /* afterKeyguardGone */ true
        )
    }

    override fun closeDialog() {
        dialog?.dismiss()
    }

    private inner class DialogListener(
        private val prefs: SharedPreferences,
        private val attempts: Int,
        private val onComplete: () -> Unit
    ) : DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
        override fun onClick(dialog: DialogInterface?, which: Int) {
            if (dialog == null) return
            if (which == DialogInterface.BUTTON_POSITIVE) {
                val settings = mutableListOf(Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS)
                if (!showDeviceControlsInLockscreen) {
                    settings.add(Settings.Secure.LOCKSCREEN_SHOW_CONTROLS)
                }
                // If we are toggling the flag, we want to call onComplete after the keyguard is
                // dismissed (and the setting is turned on), to pass the correct value.
                turnOnSettingSecurely(settings, onComplete)
            } else {
                onComplete()
            }
            if (attempts != MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG) {
                prefs
                    .edit()
                    .putInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG)
                    .apply()
            }
        }

        override fun onCancel(dialog: DialogInterface?) {
            if (dialog == null) return
            if (attempts < MAX_NUMBER_ATTEMPTS_CONTROLS_DIALOG) {
                prefs.edit().putInt(PREFS_SETTINGS_DIALOG_ATTEMPTS, attempts + 1).apply()
            }
            onComplete()
        }
    }

    private fun AlertDialog.setNeutralButton(
        msgId: Int,
        listener: DialogInterface.OnClickListener
    ) {
        setButton(DialogInterface.BUTTON_NEUTRAL, context.getText(msgId), listener)
    }

    private fun AlertDialog.setPositiveButton(
        msgId: Int,
        listener: DialogInterface.OnClickListener
    ) {
        setButton(DialogInterface.BUTTON_POSITIVE, context.getText(msgId), listener)
    }

    private fun AlertDialog.setMessage(msgId: Int) {
        setMessage(context.getText(msgId))
    }

    /** This is necessary because the constructors are `protected`. */
    private class SettingsDialog(context: Context, theme: Int) : AlertDialog(context, theme)
}
