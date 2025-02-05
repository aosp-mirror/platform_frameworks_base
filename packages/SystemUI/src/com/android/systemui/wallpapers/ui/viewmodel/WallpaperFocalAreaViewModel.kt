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

package com.android.systemui.wallpapers.ui.viewmodel

import android.graphics.RectF
import com.android.systemui.wallpapers.domain.interactor.WallpaperFocalAreaInteractor
import javax.inject.Inject

class WallpaperFocalAreaViewModel
@Inject
constructor(private val wallpaperFocalAreaInteractor: WallpaperFocalAreaInteractor) {
    val hasFocalArea = wallpaperFocalAreaInteractor.hasFocalArea

    val wallpaperFocalAreaBounds = wallpaperFocalAreaInteractor.wallpaperFocalAreaBounds

    fun setFocalAreaBounds(bounds: RectF) {
        wallpaperFocalAreaInteractor.setFocalAreaBounds(bounds)
    }

    fun setTapPosition(x: Float, y: Float) {
        wallpaperFocalAreaInteractor.setTapPosition(x, y)
    }
}
