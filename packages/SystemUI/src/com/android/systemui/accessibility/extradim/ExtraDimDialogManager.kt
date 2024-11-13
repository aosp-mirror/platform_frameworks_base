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
package com.android.systemui.accessibility.extradim

import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import javax.inject.Provider

/** Managing the Extra Dim Dialog behaviors. */
@SysUISingleton
class ExtraDimDialogManager
@Inject
constructor(
    private val extraDimDialogDelegateProvider: Provider<ExtraDimDialogDelegate>,
    private val mActivityStarter: ActivityStarter,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) {
    private var dialog: SystemUIDialog? = null

    @JvmOverloads
    fun dismissKeyguardIfNeededAndShowDialog(expandable: Expandable? = null) {
        mActivityStarter.executeRunnableDismissingKeyguard(
            { showRemoveExtraDimShortcutsDialog(expandable) },
            /* cancelAction= */ null,
            /* dismissShade= */ false,
            /* afterKeyguardGone= */ true,
            /* deferred= */ false,
        )
    }

    /** Show the dialog for removing all Extra Dim shortcuts. */
    private fun showRemoveExtraDimShortcutsDialog(expandable: Expandable?) {
        dialog?.dismiss()
        val dialog2 = extraDimDialogDelegateProvider.get().createDialog()
        dialog = dialog2

        val controller =
            expandable?.dialogTransitionController(
                DialogCuj(com.android.internal.jank.Cuj.CUJ_SHADE_DIALOG_OPEN)
            )

        controller?.let {
            dialogTransitionAnimator.show(dialog2, it, animateBackgroundBoundsChange = true)
        } ?: dialog2.show()
    }
}
