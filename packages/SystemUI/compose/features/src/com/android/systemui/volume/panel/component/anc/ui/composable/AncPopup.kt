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

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.slice.Slice
import androidx.slice.widget.SliceView
import com.android.systemui.animation.Expandable
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.panel.component.anc.ui.viewmodel.AncViewModel
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import javax.inject.Inject

/** ANC popup up displaying ANC control [Slice]. */
class AncPopup
@Inject
constructor(
    private val volumePanelPopup: VolumePanelPopup,
    private val viewModel: AncViewModel,
) {

    /** Shows a popup with the [expandable] animation. */
    fun show(expandable: Expandable) {
        volumePanelPopup.show(expandable, { Title() }, { Content(it) })
    }

    @Composable
    private fun Title() {
        Text(
            text = stringResource(R.string.volume_panel_noise_control_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }

    @Composable
    private fun Content(dialog: SystemUIDialog) {
        val slice: Slice? by viewModel.slice.collectAsState()

        if (slice == null) {
            SideEffect { dialog.dismiss() }
            return
        }

        AndroidView<SliceView>(
            modifier = Modifier.fillMaxWidth(),
            factory = { context: Context ->
                SliceView(ContextThemeWrapper(context, R.style.Widget_SliceView_VolumePanel))
                    .apply {
                        mode = SliceView.MODE_LARGE
                        isScrollable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        setShowTitleItems(true)
                        addOnLayoutChangeListener(
                            OnWidthChangedLayoutListener(viewModel::changeSliceWidth)
                        )
                    }
            },
            update = { sliceView: SliceView -> sliceView.slice = slice }
        )
    }

    private class OnWidthChangedLayoutListener(private val widthChanged: (Int) -> Unit) :
        View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View?,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        ) {
            val newWidth = right - left
            val oldWidth = oldRight - oldLeft
            if (oldWidth != newWidth) {
                widthChanged(newWidth)
            }
        }
    }
}
