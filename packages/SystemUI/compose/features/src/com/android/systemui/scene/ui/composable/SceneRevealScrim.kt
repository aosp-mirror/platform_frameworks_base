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

package com.android.systemui.scene.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.keyguard.ui.binder.LightRevealScrimViewBinder
import com.android.systemui.keyguard.ui.viewmodel.LightRevealScrimViewModel
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.wallpapers.ui.viewmodel.WallpaperViewModel

@Composable
fun SceneRevealScrim(
    viewModel: LightRevealScrimViewModel,
    wallpaperViewModel: WallpaperViewModel,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            LightRevealScrim(context).apply {
                LightRevealScrimViewBinder.bind(
                    revealScrim = this,
                    viewModel = viewModel,
                    wallpaperViewModel = wallpaperViewModel,
                )
            }
        },
        modifier = modifier,
    )
}
