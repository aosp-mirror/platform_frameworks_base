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

package com.android.systemui.volume.panel.component.spatialaudio.ui.composable

import android.view.Gravity
import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.internal.logging.UiEventLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import com.android.systemui.volume.panel.component.selector.ui.composable.VolumePanelRadioButtonBar
import com.android.systemui.volume.panel.component.spatial.ui.viewmodel.SpatialAudioViewModel
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject

class SpatialAudioPopup
@Inject
constructor(
    private val viewModel: SpatialAudioViewModel,
    private val volumePanelPopup: VolumePanelPopup,
    private val uiEventLogger: UiEventLogger,
) {

    /** Shows a popup with the [expandable] animation. */
    fun show(expandable: Expandable, horizontalGravity: Int) {
        uiEventLogger.logWithPosition(
            VolumePanelUiEvent.VOLUME_PANEL_SPATIAL_AUDIO_POP_UP_SHOWN,
            0,
            null,
            viewModel.spatialAudioButtons.value.indexOfFirst { it.button.isActive }
        )
        val gravity = horizontalGravity or Gravity.BOTTOM
        volumePanelPopup.show(expandable, gravity, { Title() }, { Content(it) })
    }

    @Composable
    private fun Title() {
        Text(
            modifier = Modifier.basicMarquee(),
            text = stringResource(R.string.volume_panel_spatial_audio_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }

    @Composable
    private fun Content(dialog: SystemUIDialog) {
        val isAvailable by viewModel.isAvailable.collectAsStateWithLifecycle()

        if (!isAvailable) {
            SideEffect { dialog.dismiss() }
            return
        }

        val enabledModelStates by viewModel.spatialAudioButtons.collectAsStateWithLifecycle()
        if (enabledModelStates.isEmpty()) {
            return
        }
        VolumePanelRadioButtonBar {
            for (buttonViewModel in enabledModelStates) {
                val label = buttonViewModel.button.label.toString()
                item(
                    isSelected = buttonViewModel.button.isActive,
                    onItemSelected = { viewModel.setEnabled(buttonViewModel.model) },
                    contentDescription = label,
                    icon = { Icon(icon = buttonViewModel.button.icon) },
                    label = {
                        Text(
                            modifier = Modifier.basicMarquee(),
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalContentColor.current,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                )
            }
        }
    }
}
