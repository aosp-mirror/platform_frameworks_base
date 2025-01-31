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

package com.android.systemui.statusbar.notification.row.shared

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/**
 * An [ImageModelProvider.ImageTransform] which acts as a filter to only return a drawable that is
 * grayscale, and tints it to white for display on the AOD.
 */
@SysUISingleton
class SkeletonImageTransform @Inject constructor(@ShadeDisplayAware context: Context) :
    ImageModelProvider.ImageTransform("Skeleton") {

    override val requiresSoftwareBitmapInput: Boolean = true

    private val contrastColorUtil = ContrastColorUtil.getInstance(context)

    override fun transformDrawable(input: Drawable): Drawable? {
        return input
            .takeIf { contrastColorUtil.isGrayscaleIcon(it) }
            ?.mutate()
            ?.apply { setTint(Color.WHITE) }
    }
}
