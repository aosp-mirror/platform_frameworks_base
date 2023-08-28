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

package com.android.settingslib.spa.slice.presenter

import android.net.Uri
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.slice.widget.SliceLiveData
import androidx.slice.widget.SliceView

@Composable
fun SliceDemo(sliceUri: Uri) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sliceData = remember {
        SliceLiveData.fromUri(context, sliceUri)
    }

    HorizontalDivider()
    AndroidView(
        factory = { localContext ->
            val view = SliceView(localContext)
            view.setShowTitleItems(true)
            view.isScrollable = false
            view
        },
        update = { view -> sliceData.observe(lifecycleOwner, view) }
    )
}
