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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.keyguard.ui.viewmodel.KeyguardMediaViewModel
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.res.R
import javax.inject.Inject
import javax.inject.Named

class MediaCarouselSection
@Inject
constructor(
    private val mediaCarouselController: MediaCarouselController,
    @param:Named(MediaModule.KEYGUARD) private val mediaHost: MediaHost,
    private val keyguardMediaViewModel: KeyguardMediaViewModel,
) {

    @Composable
    fun SceneScope.KeyguardMediaCarousel(
        isShadeLayoutWide: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val isMediaVisible by keyguardMediaViewModel.isMediaVisible.collectAsStateWithLifecycle()
        val horizontalPadding =
            if (isShadeLayoutWide) {
                dimensionResource(id = R.dimen.notification_side_paddings)
            } else {
                dimensionResource(id = R.dimen.notification_side_paddings) +
                    dimensionResource(id = R.dimen.notification_panel_margin_horizontal)
            }
        MediaCarousel(
            isVisible = isMediaVisible,
            mediaHost = mediaHost,
            modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            carouselController = mediaCarouselController,
        )
    }
}
