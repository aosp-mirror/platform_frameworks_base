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

package com.android.systemui.common.shared.model

import android.annotation.DrawableRes
import android.graphics.drawable.Drawable

/**
 * Models an icon, that can either be already [loaded][Icon.Loaded] or be a [reference]
 * [Icon.Resource] to a resource.
 */
sealed class Icon {
    abstract val contentDescription: ContentDescription?

    data class Loaded(
        val drawable: Drawable,
        override val contentDescription: ContentDescription?,
    ) : Icon()

    data class Resource(
        @DrawableRes val res: Int,
        override val contentDescription: ContentDescription?,
    ) : Icon()
}

/** Creates [Icon.Loaded] for a given drawable with an optional [contentDescription]. */
fun Drawable.asIcon(contentDescription: ContentDescription? = null): Icon =
    Icon.Loaded(this, contentDescription)
