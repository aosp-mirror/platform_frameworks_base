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
 */

package com.android.systemui.volume

import android.content.Context
import android.util.Log
import android.view.View
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

private const val TAG = "VolumePanelFactory"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/**
 * Factory to create [VolumePanelDialog] objects. This is the dialog that allows the user to adjust
 * multiple streams with sliders.
 */
@SysUISingleton
class VolumePanelFactory @Inject constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val dialogTransitionAnimator: DialogTransitionAnimator
) {
    companion object {
        var volumePanelDialog: VolumePanelDialog? = null
    }

    /** Creates a [VolumePanelDialog]. The dialog will be animated from [view] if it is not null. */
    fun create(aboveStatusBar: Boolean, view: View? = null) {
        if (volumePanelDialog?.isShowing == true) {
            return
        }

        val dialog = VolumePanelDialog(context, activityStarter, aboveStatusBar)
        volumePanelDialog = dialog

        // Show the dialog.
        if (view != null) {
            dialogTransitionAnimator.showFromView(
                dialog,
                view,
                animateBackgroundBoundsChange = true
            )
        } else {
            dialog.show()
        }
    }

    /** Dismiss [VolumePanelDialog] if exist. */
    fun dismiss() {
        if (DEBUG) {
            Log.d(TAG, "dismiss dialog")
        }
        volumePanelDialog?.dismiss()
        volumePanelDialog = null
    }
}
