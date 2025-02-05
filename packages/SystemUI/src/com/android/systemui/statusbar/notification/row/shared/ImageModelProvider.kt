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
import android.graphics.drawable.Icon

/**
 * An interface which allows the background inflation process, when loading content from a
 * notification, to acquire a [ImageModel] that can be used later in the inflation process to access
 * the actual [Drawable] which it represents.
 */
interface ImageModelProvider {
    /**
     * Defines the rough size we expect the image to be. We use this abstraction to reduce memory
     * footprint without coupling image loading to the view layer.
     */
    enum class ImageSizeClass {
        /** Roughly 24dp */
        SmallSquare,

        /** Around 48dp */
        MediumSquare,

        /** About as wide as a notification. */
        LargeWide,
    }

    /**
     * This is the base class for an image transform that allows the loaded icon to be altered in
     * some way (other than resizing) prior to being indexed. This transform will be used as part of
     * the index key.
     */
    abstract class ImageTransform(val key: String) {
        open val requiresSoftwareBitmapInput: Boolean = false

        abstract fun transformDrawable(input: Drawable): Drawable?

        final override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null) return false
            if (other !is ImageTransform) return false
            return key == other.key
        }

        final override fun hashCode(): Int {
            return key.hashCode()
        }
    }

    /** The default passthrough transform for images */
    object IdentityImageTransform : ImageTransform("Identity") {
        override fun transformDrawable(input: Drawable) = input
    }

    /** Returns an [ImageModel] which will provide access to a [Drawable] in the future. */
    fun getImageModel(
        icon: Icon,
        sizeClass: ImageSizeClass,
        transform: ImageTransform = IdentityImageTransform,
    ): ImageModel?
}
