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

package com.android.systemui.media.dialog

import android.content.Context
import android.view.View
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastSender
import javax.inject.Inject

/** Manager to create and show a [MediaOutputBroadcastDialog]. */
class MediaOutputBroadcastDialogManager
@Inject
constructor(
    private val context: Context,
    private val broadcastSender: BroadcastSender,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val mediaOutputControllerFactory: MediaOutputController.Factory
) {
    var mediaOutputBroadcastDialog: MediaOutputBroadcastDialog? = null

    /** Creates a [MediaOutputBroadcastDialog] for the given package. */
    fun createAndShow(packageName: String, aboveStatusBar: Boolean, view: View? = null) {
        // Dismiss the previous dialog, if any.
        mediaOutputBroadcastDialog?.dismiss()

        // TODO: b/321969740 - Populate the userHandle parameter. The user handle is necessary to
        //  disambiguate the same package running on different users.
        val controller =
            mediaOutputControllerFactory.create(
                packageName,
                /* userHandle= */ null,
                /* token */ null,
            )
        val dialog =
            MediaOutputBroadcastDialog(context, aboveStatusBar, broadcastSender, controller)
        mediaOutputBroadcastDialog = dialog

        // Show the dialog.
        if (view != null) {
            dialogTransitionAnimator.showFromView(dialog, view)
        } else {
            dialog.show()
        }
    }

    /** dismiss [MediaOutputBroadcastDialog] if exist. */
    fun dismiss() {
        mediaOutputBroadcastDialog?.dismiss()
        mediaOutputBroadcastDialog = null
    }
}
