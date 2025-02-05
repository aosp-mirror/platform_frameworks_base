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

import android.graphics.drawable.Drawable

/**
 * This object can provides access to a drawable in the future.
 *
 * Additionally, all implementations must provide stable equality which means that you can compare
 * to other instances before the drawable has been resolved.
 *
 * This means you can use these in fields of a Model that is stored in State or StateFlow object to
 * provide access to a drawable while still debouncing duplicates.
 */
interface ImageModel {
    /** The image, once resolved. */
    val drawable: Drawable?

    /** Returns whether this model does not currently provide access to an image. */
    fun isEmpty() = drawable == null

    /** Returns whether this model currently provides access to an image. */
    fun isNotEmpty() = drawable != null
}

/** Returns whether this model is null or does not currently provide access to an image. */
fun ImageModel?.isNullOrEmpty() = this == null || this.isEmpty()
