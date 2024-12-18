/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.popup.ui.composable

import android.view.Gravity
import androidx.annotation.GravityInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import javax.inject.Inject

/** Volume panel bottom popup menu. */
class VolumePanelPopup
@Inject
constructor(
    private val dialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
) {

    /**
     * Shows a popup with the [expandable] animation.
     *
     * @param title is shown on the top of the popup
     * @param content is the popup body
     */
    fun show(
        expandable: Expandable?,
        @GravityInt gravity: Int,
        title: @Composable (SystemUIDialog) -> Unit,
        content: @Composable (SystemUIDialog) -> Unit,
    ) {
        val dialog =
            dialogFactory.create(
                theme = R.style.Theme_VolumePanel_Popup,
                dialogGravity = gravity,
            ) {
                PopupComposable(it, title, content)
            }
        val controller = expandable?.dialogTransitionController()
        if (controller == null) {
            dialog.show()
        } else {
            dialogTransitionAnimator.show(dialog, controller)
        }
    }

    @Composable
    private fun PopupComposable(
        dialog: SystemUIDialog,
        title: @Composable (SystemUIDialog) -> Unit,
        content: @Composable (SystemUIDialog) -> Unit,
    ) {
        val paneTitle = stringResource(R.string.accessibility_volume_settings)
        Box(Modifier.fillMaxWidth().semantics(properties = { this.paneTitle = paneTitle })) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier =
                        Modifier.padding(horizontal = 80.dp).fillMaxWidth().wrapContentHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    title(dialog)
                }

                Box(
                    modifier =
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().wrapContentHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    content(dialog)
                }
            }

            IconButton(
                modifier = Modifier.align(Alignment.TopEnd).size(64.dp).padding(20.dp),
                onClick = { dialog.dismiss() },
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.outline
                    )
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.accessibility_desc_close),
                )
            }
        }
    }

    companion object {

        /**
         * Returns absolute ([Gravity.LEFT], [Gravity.RIGHT] or [Gravity.CENTER_HORIZONTAL])
         * [GravityInt] for the popup based on the [coordinates] global position relative to the
         * [screenWidthPx].
         */
        @GravityInt
        fun calculateGravity(coordinates: LayoutCoordinates, screenWidthPx: Float): Int {
            val bottomCenter: Float = coordinates.boundsInRoot().bottomCenter.x
            val rootBottomCenter: Float = screenWidthPx / 2
            return when {
                bottomCenter < rootBottomCenter -> Gravity.LEFT
                bottomCenter > rootBottomCenter -> Gravity.RIGHT
                else -> Gravity.CENTER_HORIZONTAL
            }
        }
    }
}
