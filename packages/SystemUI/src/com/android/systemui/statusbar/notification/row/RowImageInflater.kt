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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.traceSection
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUiAod
import com.android.systemui.statusbar.notification.row.shared.IconData
import com.android.systemui.statusbar.notification.row.shared.ImageModel
import com.android.systemui.statusbar.notification.row.shared.ImageModelProvider
import com.android.systemui.statusbar.notification.row.shared.ImageModelProvider.ImageSizeClass
import com.android.systemui.statusbar.notification.row.shared.ImageModelProvider.ImageTransform
import java.util.Date

/**
 * A class used as part of the notification content inflation process to generate image models,
 * resolve image content for active models, and manage a generational index of images to reduce
 * image load overhead.
 *
 * Each round of inflation follows this process:
 * 1. Instantiate a [newInstance] of this class using the current index.
 * 2. Call [useForContentModel] and use that object to generate [ImageModel] objects.
 * 3. [ImageModel] objects may be stored in a content model, which may be used in Flows or States.
 * 4. On the background thread, call [loadImagesSynchronously] to ensure all models have images.
 * 5. In case of success, call [getNewImageIndex] and if the result is not null, replace the
 *    original index with this one that is a generation newer. In case the inflation failed, the
 *    [RowImageInflater] can be discarded and while all newly resolved images will be discarded, no
 *    change will have been made to the previous index.
 */
interface RowImageInflater {
    /**
     * This returns an [ImageModelProvider] that can be used for getting models of images for use in
     * a content model. Calling this method marks the inflater as being used, which means that
     * instead of [getNewImageIndex] returning the previous index, it will now suddenly return
     * nothing unless until other models are provided. This behavior allows the implicit absence of
     * calls to evict unused images from the new index.
     *
     * NOTE: right now there is only one inflation process which uses this to access images. In the
     * future we will likely have more. In that case, we will need this method and the
     * [ImageModelIndex] to support the concept of different optional inflation lanes.
     *
     * Here's an example to illustrate why this would be necessary:
     * 1. We inflate just the general content and save the index with 6 images.
     * 2. Later, we inflate just the AOD RON content and save the index with 3 images, discarding
     *    the 3 from the general content.
     * 3. Later, we reinflate the general content and have to reload 3 images that should've been in
     *    the index.
     */
    fun useForContentModel(): ImageModelProvider

    /**
     * Synchronously load all drawables that are not in the index, and ensure the [ImageModel]s
     * previously returned by an [ImageModelProvider] all provide access to those drawables.
     */
    fun loadImagesSynchronously(context: Context)

    /**
     * Get the next generation of the [ImageModelIndex] for this row. This will return the previous
     * index if this inflater was never used.
     */
    fun getNewImageIndex(): ImageModelIndex?

    companion object {
        @Suppress("NOTHING_TO_INLINE")
        @JvmStatic
        inline fun featureFlagEnabled() = PromotedNotificationUiAod.isEnabled

        @JvmStatic
        fun newInstance(previousIndex: ImageModelIndex?): RowImageInflater =
            if (featureFlagEnabled()) {
                RowImageInflaterImpl(previousIndex)
            } else {
                RowImageInflaterStub
            }
    }
}

/** A no-op implementation that does nothing */
private object ImageModelProviderStub : ImageModelProvider {
    override fun getImageModel(
        icon: Icon,
        sizeClass: ImageSizeClass,
        transform: ImageTransform,
    ): ImageModel? = null
}

/** A no-op implementation that does nothing */
private object RowImageInflaterStub : RowImageInflater {
    override fun useForContentModel(): ImageModelProvider = ImageModelProviderStub

    override fun loadImagesSynchronously(context: Context) = Unit

    override fun getNewImageIndex(): ImageModelIndex? = null
}

class RowImageInflaterImpl(private val previousIndex: ImageModelIndex?) : RowImageInflater {
    private val providedImages = mutableListOf<LazyImage>()

    /**
     * For now there is only one way we use this, so we don't need to track which "model" it was
     * used for. If in the future we use it for more models, then we can do that, and also track the
     * parts of the index that should or shouldn't be copied.
     */
    private var wasUsed = false

    /** Gets the ImageModelProvider that is used for inflating the content model. */
    override fun useForContentModel(): ImageModelProvider {
        wasUsed = true
        return object : ImageModelProvider {
            override fun getImageModel(
                icon: Icon,
                sizeClass: ImageSizeClass,
                transform: ImageTransform,
            ): ImageModel? {
                val iconData = IconData.fromIcon(icon) ?: return null
                // if we've already provided an equivalent image, return it again.
                providedImages.firstOrNull(iconData, sizeClass, transform)?.let {
                    return it
                }
                // create and return a new entry
                return LazyImage(iconData, sizeClass, transform).also { newImage ->
                    // ensure all entries are stored
                    providedImages.add(newImage)
                    // load the image result from the index into our new object
                    previousIndex?.findImage(iconData, sizeClass, transform)?.let {
                        // copy the result into our new object
                        newImage.result = it
                    }
                }
            }
        }
    }

    override fun loadImagesSynchronously(context: Context) {
        traceSection("RowImageInflater.loadImageDrawablesSync") {
            providedImages.forEach { lazyImage ->
                if (lazyImage.result == null) {
                    lazyImage.result = lazyImage.load(context)
                }
            }
        }
    }

    private fun LazyImage.load(context: Context): ImageResult {
        traceSection("LazyImage.load") {
            // TODO: use the sizeClass to load the drawable into a correctly sized bitmap,
            //  and be sure to respect [lazyImage.transform.requiresSoftwareBitmapInput]
            val iconDrawable =
                icon.toIcon().loadDrawable(context)
                    ?: return ImageResult.Empty("Icon.loadDrawable() returned null for $icon")
            return transform.transformDrawable(iconDrawable)?.let { ImageResult.Image(it) }
                ?: return ImageResult.Empty("Transform ${transform.key} returned null")
        }
    }

    override fun getNewImageIndex(): ImageModelIndex? =
        if (wasUsed) ImageModelIndex(providedImages) else previousIndex
}

class ImageModelIndex internal constructor(data: Collection<LazyImage>) {
    private val images = data.toMutableList()

    fun findImage(
        icon: IconData,
        sizeClass: ImageSizeClass,
        transform: ImageTransform,
    ): ImageResult? = images.firstOrNull(icon, sizeClass, transform)?.result

    @VisibleForTesting
    val contentsForTesting: MutableList<LazyImage>
        get() = images
}

private fun Collection<LazyImage>.firstOrNull(
    icon: IconData,
    sizeClass: ImageSizeClass,
    transform: ImageTransform,
): LazyImage? = firstOrNull {
    it.sizeClass == sizeClass && it.icon == icon && it.transform == transform
}

data class LazyImage(
    val icon: IconData,
    val sizeClass: ImageSizeClass,
    val transform: ImageTransform,
    var result: ImageResult? = null,
) : ImageModel {
    override val drawable: Drawable?
        get() = (result as? ImageResult.Image)?.drawable
}

/** The result of attempting to load an image. */
sealed interface ImageResult {
    /** Indicates a null result from the image loading process, with a reason for debugging */
    data class Empty(val reason: String, val time: Date = Date()) : ImageResult

    /** Stores the drawable result of loading an image */
    data class Image(val drawable: Drawable) : ImageResult
}
