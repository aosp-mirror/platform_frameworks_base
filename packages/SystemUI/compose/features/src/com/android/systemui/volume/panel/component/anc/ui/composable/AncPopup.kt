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

package com.android.systemui.volume.panel.component.anc.ui.composable

import android.view.Gravity
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.slice.Slice
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.panel.component.anc.ui.viewmodel.AncViewModel
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject

/** ANC popup up displaying ANC control [Slice]. */
class AncPopup
@Inject
constructor(
    private val volumePanelPopup: VolumePanelPopup,
    private val viewModel: AncViewModel,
    private val uiEventLogger: UiEventLogger,
) {

    /** Shows a popup with the [expandable] animation. */
    fun show(expandable: Expandable?, horizontalGravity: Int) {
        uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_ANC_POPUP_SHOWN)
        val gravity = horizontalGravity or Gravity.BOTTOM
        volumePanelPopup.show(expandable, gravity, { Title() }, { Content(it) })
    }

    @Composable
    private fun Title() {
        Text(
            modifier = Modifier.basicMarquee(),
            text = stringResource(R.string.volume_panel_noise_control_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }

    @Composable
    private fun Content(dialog: SystemUIDialog) {
        val isAvailable by viewModel.isAvailable.collectAsStateWithLifecycle(true)

        if (!isAvailable) {
            SideEffect { dialog.dismiss() }
            return
        }

        val slice by viewModel.popupSlice.collectAsStateWithLifecycle()
        SliceAndroidView(
            modifier = Modifier.fillMaxWidth(),
            slice = slice,
            onWidthChanged = viewModel::onPopupSliceWidthChanged
        )
    }
}
