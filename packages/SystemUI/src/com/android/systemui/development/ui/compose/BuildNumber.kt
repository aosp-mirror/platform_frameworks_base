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

package com.android.systemui.development.ui.compose

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.development.ui.viewmodel.BuildNumberViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R

@Composable
fun BuildNumber(
    viewModelFactory: BuildNumberViewModel.Factory,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberViewModel(traceName = "BuildNumber") { viewModelFactory.create() }

    val buildNumber = viewModel.buildNumber

    if (buildNumber != null) {
        val haptics = LocalHapticFeedback.current
        val copyToClipboardActionLabel = stringResource(id = R.string.copy_to_clipboard_a11y_action)

        Text(
            text = buildNumber.value,
            modifier =
                modifier
                    .focusable()
                    .wrapContentWidth()
                    // Using this instead of combinedClickable because this node should not support
                    // single click
                    .pointerInput(Unit) {
                        detectLongPressGesture {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.onBuildNumberLongPress()
                        }
                    }
                    .semantics {
                        onLongClick(copyToClipboardActionLabel) {
                            viewModel.onBuildNumberLongPress()
                            true
                        }
                    }
                    .basicMarquee(iterations = 1, initialDelayMillis = 2000)
                    .minimumInteractiveComponentSize(),
            color = textColor,
            maxLines = 1,
        )
    } else {
        Spacer(modifier)
    }
}
