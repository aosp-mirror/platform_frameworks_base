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
package com.android.systemui.qs.tiles.dialog

import android.util.Log
import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject

private const val TAG = "InternetDialogFactory"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Factory to create [InternetDialogDelegate] objects.
 */
@SysUISingleton
class InternetDialogManager @Inject constructor(
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogFactory: InternetDialogDelegate.Factory
) {
    companion object {
        private const val INTERACTION_JANK_TAG = "internet"
        var dialog: SystemUIDialog? = null
    }

    /** Creates a [InternetDialogDelegate]. The dialog will be animated from [view] if it is not null. */
    fun create(
        aboveStatusBar: Boolean,
        canConfigMobileData: Boolean,
        canConfigWifi: Boolean,
        view: View?
    ) {
        if (dialog != null) {
            if (DEBUG) {
                Log.d(TAG, "InternetDialog is showing, do not create it twice.")
            }
            return
        } else {
            dialog = dialogFactory.create(
                    aboveStatusBar, canConfigMobileData, canConfigWifi).createDialog()
            if (view != null) {
                dialogTransitionAnimator.showFromView(
                        dialog!!, view,
                    animateBackgroundBoundsChange = true,
                    cuj = DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                        INTERACTION_JANK_TAG
                    )
                )
            } else {
                dialog!!.show()
            }
        }
    }

    fun destroyDialog() {
        if (DEBUG) {
            Log.d(TAG, "destroyDialog")
        }
        dialog = null
    }
}
