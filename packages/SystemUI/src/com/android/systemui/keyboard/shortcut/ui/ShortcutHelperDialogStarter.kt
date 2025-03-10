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

package com.android.systemui.keyboard.shortcut.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.shortcut.ui.composable.ShortcutHelper
import com.android.systemui.keyboard.shortcut.ui.composable.ShortcutHelperBottomSheet
import com.android.systemui.keyboard.shortcut.ui.composable.getWidth
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.createBottomSheet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

@SysUISingleton
class ShortcutHelperDialogStarter
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val shortcutHelperViewModel: ShortcutHelperViewModel,
    shortcutCustomizationDialogStarterFactory: ShortcutCustomizationDialogStarter.Factory,
    private val dialogFactory: SystemUIDialogFactory,
    private val activityStarter: ActivityStarter,
) : CoreStartable {

    @VisibleForTesting var dialog: Dialog? = null
    private val shortcutCustomizationDialogStarter =
        shortcutCustomizationDialogStarterFactory.create()

    override fun start() {
        shortcutHelperViewModel.shouldShow
            .map { shouldShow ->
                if (shouldShow) {
                    dialog = createShortcutHelperDialog().also { it.show() }
                } else {
                    dialog?.dismiss()
                }
            }
            .launchIn(applicationScope)
    }

    private fun createShortcutHelperDialog(): Dialog {
        return dialogFactory.createBottomSheet(
            content = { dialog ->
                val shortcutsUiState by
                    shortcutHelperViewModel.shortcutsUiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { shortcutCustomizationDialogStarter.activate() }
                ShortcutHelper(
                    modifier = Modifier.width(getWidth()),
                    shortcutsUiState = shortcutsUiState,
                    onKeyboardSettingsClicked = { onKeyboardSettingsClicked(dialog) },
                    onSearchQueryChanged = { shortcutHelperViewModel.onSearchQueryChanged(it) },
                    onCustomizationRequested = {
                        shortcutCustomizationDialogStarter.onShortcutCustomizationRequested(it)
                    },
                )
                dialog.setOnDismissListener { shortcutHelperViewModel.onViewClosed() }
                dialog.setTitle(stringResource(R.string.shortcut_helper_title))
            },
            maxWidth = ShortcutHelperBottomSheet.LargeScreenWidthLandscape,
        )
    }

    private fun onKeyboardSettingsClicked(dialog: Dialog) {
        try {
            activityStarter.startActivity(
                Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS).addFlags(FLAG_ACTIVITY_NEW_TASK),
                /* dismissShade= */ true,
                /* animationController = */ null,
                /* showOverLockscreenWhenLocked = */ false,
                UserHandle.CURRENT,
            )
        } catch (e: ActivityNotFoundException) {
            // From the Settings docs: In some cases, a matching Activity may not exist, so ensure
            // you safeguard against this.
            e.printStackTrace()
            return
        }
        dialog.dismiss()
    }
}
