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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.media.dialog.MediaOutputDialogFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaDeviceSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject

/** User actions interactor for Media Output Volume Panel component. */
@VolumePanelScope
class MediaOutputActionsInteractor
@Inject
constructor(
    private val mediaOutputDialogFactory: MediaOutputDialogFactory,
    private val activityStarter: ActivityStarter,
) {

    fun onDeviceClick(expandable: Expandable) {
        activityStarter.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            true,
            expandable.activityTransitionController(),
        )
    }

    fun onBarClick(session: MediaDeviceSession, expandable: Expandable) {
        when (session) {
            is MediaDeviceSession.Active -> {
                mediaOutputDialogFactory.createWithController(
                    session.packageName,
                    false,
                    expandable.dialogController()
                )
            }
            is MediaDeviceSession.Inactive -> {
                mediaOutputDialogFactory.createDialogForSystemRouting(expandable.dialogController())
            }
            else -> {
                /* do nothing */
            }
        }
    }

    private fun Expandable.dialogController(): DialogTransitionAnimator.Controller? {
        return dialogTransitionController(
            cuj =
                DialogCuj(
                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                    MediaOutputDialogFactory.INTERACTION_JANK_TAG
                )
        )
    }
}
