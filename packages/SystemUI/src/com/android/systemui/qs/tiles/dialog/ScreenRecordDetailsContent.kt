/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.res.R
import com.android.systemui.screenrecord.ScreenRecordPermissionViewBinder

@Composable
fun ScreenRecordDetailsContent(viewModel: ScreenRecordDetailsViewModel) {
    // TODO(b/378514312): Finish implementing this function.

    if (viewModel.recordingController.isScreenCaptureDisabled) {
        // TODO(b/388345506): Show disabled page here.
        return
    }

    val viewBinder: ScreenRecordPermissionViewBinder = remember {
        viewModel.recordingController.createScreenRecordPermissionViewBinder(
            viewModel.onStartRecordingClicked
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        factory = { context ->
            // Inflate with the existing dialog xml layout
            val view = LayoutInflater.from(context).inflate(R.layout.screen_share_dialog, null)
            viewBinder.bind(view)

            view
            // TODO(b/378514473): Revamp the details view according to the spec.
        },
        onRelease = { viewBinder.unbind() },
    )
}
