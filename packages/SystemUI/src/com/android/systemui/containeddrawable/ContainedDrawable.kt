/*
 *  Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.containeddrawable

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes

/** Convenience container for [Drawable] or a way to load it later. */
sealed class ContainedDrawable {
    data class WithDrawable(val drawable: Drawable) : ContainedDrawable()
    data class WithResource(@DrawableRes val resourceId: Int) : ContainedDrawable()
}
