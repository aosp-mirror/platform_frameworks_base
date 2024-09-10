/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.ui.dialog

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.statusbar.policy.ui.dialog.composable.ModeTileGrid
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModesDialogViewModel
import com.android.systemui.util.Assert
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

@SysUISingleton
class ModesDialogDelegate
@Inject
constructor(
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
    // Using a provider to avoid a circular dependency.
    private val viewModel: Provider<ModesDialogViewModel>,
    private val dialogEventLogger: ModesDialogEventLogger,
    @Main private val mainCoroutineContext: CoroutineContext,
) : SystemUIDialog.Delegate {
    // NOTE: This should only be accessed/written from the main thread.
    @VisibleForTesting var currentDialog: ComponentSystemUIDialog? = null

    override fun createDialog(): SystemUIDialog {
        Assert.isMainThread()
        if (currentDialog != null) {
            Log.w(TAG, "Dialog is already open, dismissing it and creating a new one.")
            currentDialog?.dismiss()
        }

        currentDialog = sysuiDialogFactory.create() { ModesDialogContent(it) }
        currentDialog
            ?.lifecycle
            ?.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        Assert.isMainThread()
                        currentDialog = null
                    }
                }
            )

        return currentDialog!!
    }

    @Composable
    private fun ModesDialogContent(dialog: SystemUIDialog) {
        AlertDialogContent(
            modifier = Modifier.semantics {
                testTagsAsResourceId = true
            },
            title = {
                Text(
                    modifier = Modifier.testTag("modes_title"),
                    text = stringResource(R.string.zen_modes_dialog_title)
                )
            },
            content = { ModeTileGrid(viewModel.get()) },
            neutralButton = {
                PlatformOutlinedButton(onClick = { openSettings(dialog) }) {
                    Text(stringResource(R.string.zen_modes_dialog_settings))
                }
            },
            positiveButton = {
                PlatformButton(onClick = { dialog.dismiss() }) {
                    Text(stringResource(R.string.zen_modes_dialog_done))
                }
            },
        )
    }

    @VisibleForTesting
    fun openSettings(dialog: SystemUIDialog) {
        dialogEventLogger.logDialogSettings()
        val animationController =
            dialogTransitionAnimator.createActivityTransitionController(dialog)
        if (animationController == null) {
            // The controller will take care of dismissing for us after
            // the animation, but let's make sure we dismiss the dialog
            // if we don't animate it.
            dialog.dismiss()
        }
        activityStarter.startActivity(
            ZEN_MODE_SETTINGS_INTENT,
            true /* dismissShade */,
            animationController
        )
    }

    suspend fun showDialog(expandable: Expandable? = null): SystemUIDialog {
        // Dialogs shown by the DialogTransitionAnimator must be created and shown on the main
        // thread, so we post it to the UI handler.
        withContext(mainCoroutineContext) {
            // Create the dialog if necessary
            if (currentDialog == null) {
                createDialog()
            }

            expandable
                ?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
                ?.let { controller -> dialogTransitionAnimator.show(currentDialog!!, controller) }
                ?: currentDialog!!.show()
        }

        return currentDialog!!
    }

    /**
     * Launches the [intent] by animating from the dialog. If the dialog is not showing, just
     * launches it normally without animating.
     */
    fun launchFromDialog(intent: Intent) {
        Assert.isMainThread()
        if (currentDialog == null) {
            Log.w(
                TAG,
                "Cannot launch from dialog, the dialog is not present. " +
                    "Will launch activity without animating."
            )
        }

        val animationController =
            currentDialog?.let { dialogTransitionAnimator.createActivityTransitionController(it) }
        if (animationController == null) {
            currentDialog?.dismiss()
        }
        activityStarter.startActivity(
            intent,
            true, /* dismissShade */
            animationController,
        )
    }

    companion object {
        private const val TAG = "ModesDialogDelegate"
        private val ZEN_MODE_SETTINGS_INTENT = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)
        private const val INTERACTION_JANK_TAG = "configure_priority_modes"
    }
}
