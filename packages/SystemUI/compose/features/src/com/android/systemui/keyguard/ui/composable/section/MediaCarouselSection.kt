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

package com.android.systemui.keyguard.ui.composable.section

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.ui.viewmodel.MediaCarouselViewModel
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule
import javax.inject.Inject
import javax.inject.Named

class MediaCarouselSection
@Inject
constructor(
    private val mediaCarouselController: MediaCarouselController,
    @param:Named(MediaModule.KEYGUARD) private val mediaHost: MediaHost,
    private val mediaCarouselViewModel: MediaCarouselViewModel,
) {

    private fun isVisible(): Boolean {
        if (mediaCarouselController.mediaFrame == null) {
            return false
        }
        return mediaCarouselViewModel.isMediaVisible
    }

    @Composable
    fun SceneScope.KeyguardMediaCarousel() {
        MediaCarousel(
            isVisible = ::isVisible,
            mediaHost = mediaHost,
            modifier = Modifier.fillMaxWidth(),
            carouselController = mediaCarouselController,
        )
    }
}
