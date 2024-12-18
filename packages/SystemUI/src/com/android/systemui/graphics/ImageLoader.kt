/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.graphics

import android.annotation.AnyThread
import android.annotation.DrawableRes
import android.annotation.Px
import android.annotation.SuppressLint
import android.annotation.WorkerThread
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.Resources.NotFoundException
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.DecodeException
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.util.Log
import android.util.Size
import androidx.core.content.res.ResourcesCompat
import com.android.app.tracing.traceSection
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.io.IOException
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Helper class to load images for SystemUI. It allows for memory efficient image loading with size
 * restriction and attempts to use hardware bitmaps when sensible.
 */
@SysUISingleton
class ImageLoader
@Inject
constructor(
    @Application private val defaultContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher
) {

    /** Source of the image data. */
    sealed interface Source

    /**
     * Load image from a Resource ID. If the resource is part of another package or if it requires
     * tinting, pass in a correct [Context].
     */
    data class Res(@DrawableRes val resId: Int, val context: Context?) : Source {
        constructor(@DrawableRes resId: Int) : this(resId, null)
    }

    /** Load image from a Uri. */
    data class Uri(val uri: android.net.Uri) : Source {
        constructor(uri: String) : this(android.net.Uri.parse(uri))
    }

    /** Load image from a [File]. */
    data class File(val file: java.io.File) : Source {
        constructor(path: String) : this(java.io.File(path))
    }

    /** Load image from an [InputStream]. */
    data class InputStream(val inputStream: java.io.InputStream, val context: Context?) : Source {
        constructor(inputStream: java.io.InputStream) : this(inputStream, null)
    }

    /**
     * Loads passed [Source] on a background thread and returns the [Bitmap].
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints while keeping aspect
     * ratio.
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Bitmap] or `null` if loading failed.
     */
    @AnyThread
    suspend fun loadBitmap(
        source: Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Bitmap? =
        withContext(backgroundDispatcher) { loadBitmapSync(source, maxWidth, maxHeight, allocator) }

    /**
     * Loads passed [Source] synchronously and returns the [Bitmap].
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints while keeping aspect
     * ratio.
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Bitmap] or `null` if loading failed.
     */
    @WorkerThread
    fun loadBitmapSync(
        source: Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Bitmap? {
        return try {
            loadBitmapSync(
                toImageDecoderSource(source, defaultContext),
                maxWidth,
                maxHeight,
                allocator
            )
        } catch (e: NotFoundException) {
            Log.w(TAG, "Couldn't load resource $source", e)
            null
        }
    }

    /**
     * Loads passed [ImageDecoder.Source] synchronously and returns the drawable.
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints (while keeping aspect
     * ratio).
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Bitmap] or `null` if loading failed.
     */
    @WorkerThread
    fun loadBitmapSync(
        source: ImageDecoder.Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Bitmap? =
        traceSection("ImageLoader#loadBitmap") {
            return try {
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    configureDecoderForMaximumSize(decoder, info.size, maxWidth, maxHeight)
                    decoder.allocator = allocator
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to load source $source", e)
                return null
            } catch (e: DecodeException) {
                Log.w(TAG, "Failed to decode source $source", e)
                return null
            }
        }

    /**
     * Loads passed [Source] on a background thread and returns the [Drawable].
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints (while keeping aspect
     * ratio).
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Drawable] or `null` if loading failed.
     */
    @AnyThread
    suspend fun loadDrawable(
        source: Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Drawable? =
        withContext(backgroundDispatcher) {
            loadDrawableSync(source, maxWidth, maxHeight, allocator)
        }

    /**
     * Loads passed [Icon] on a background thread and returns the drawable.
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints (while keeping aspect
     * ratio).
     *
     * @param context Alternate context to use for resource loading (for e.g. cross-process use)
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Drawable] or `null` if loading failed.
     */
    @AnyThread
    suspend fun loadDrawable(
        icon: Icon,
        context: Context = defaultContext,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Drawable? =
        withContext(backgroundDispatcher) {
            loadDrawableSync(icon, context, maxWidth, maxHeight, allocator)
        }

    /**
     * Loads passed [Source] synchronously and returns the drawable.
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints (while keeping aspect
     * ratio).
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Drawable] or `null` if loading failed.
     */
    @WorkerThread
    @SuppressLint("UseCompatLoadingForDrawables")
    fun loadDrawableSync(
        source: Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Drawable? =
        traceSection("ImageLoader#loadDrawable") {
            return try {
                loadDrawableSync(
                    toImageDecoderSource(source, defaultContext),
                    maxWidth,
                    maxHeight,
                    allocator
                )
                    ?:
                    // If we have a resource, retry fallback using the "normal" Resource loading
                    // system.
                    // This will come into effect in cases like trying to load
                    // AnimatedVectorDrawable.
                    if (source is Res) {
                        val context = source.context ?: defaultContext
                        ResourcesCompat.getDrawable(context.resources, source.resId, context.theme)
                    } else {
                        null
                    }
            } catch (e: NotFoundException) {
                Log.w(TAG, "Couldn't load resource $source", e)
                null
            }
        }

    /**
     * Loads passed [ImageDecoder.Source] synchronously and returns the drawable.
     *
     * Maximum height and width can be passed as optional parameters - the image decoder will make
     * sure to keep the decoded drawable size within those passed constraints (while keeping aspect
     * ratio).
     *
     * @param maxWidth Maximum width of the returned drawable (if able). 0 means no restriction. Set
     *   to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param maxHeight Maximum height of the returned drawable (if able). 0 means no restriction.
     *   Set to [DEFAULT_MAX_SAFE_BITMAP_SIZE_PX] by default.
     * @param allocator Allocator to use for the loaded drawable - one of [ImageDecoder] allocator
     *   ints. Use [ImageDecoder.ALLOCATOR_SOFTWARE] to force software bitmap.
     * @return loaded [Drawable] or `null` if loading failed.
     */
    @WorkerThread
    fun loadDrawableSync(
        source: ImageDecoder.Source,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Drawable? =
        traceSection("ImageLoader#loadDrawable") {
            return try {
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    configureDecoderForMaximumSize(decoder, info.size, maxWidth, maxHeight)
                    decoder.allocator = allocator
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to load source $source", e)
                return null
            } catch (e: DecodeException) {
                Log.w(TAG, "Failed to decode source $source", e)
                return null
            }
        }

    /** Loads icon drawable while attempting to size restrict the drawable. */
    @WorkerThread
    fun loadDrawableSync(
        icon: Icon,
        context: Context = defaultContext,
        @Px maxWidth: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        @Px maxHeight: Int = DEFAULT_MAX_SAFE_BITMAP_SIZE_PX,
        allocator: Int = ImageDecoder.ALLOCATOR_DEFAULT
    ): Drawable? =
        traceSection("ImageLoader#loadDrawable") {
            return when (icon.type) {
                Icon.TYPE_URI,
                Icon.TYPE_URI_ADAPTIVE_BITMAP -> {
                    val source = ImageDecoder.createSource(context.contentResolver, icon.uri)
                    loadDrawableSync(source, maxWidth, maxHeight, allocator)
                }
                Icon.TYPE_RESOURCE -> {
                    val resources = resolveResourcesForIcon(context, icon)
                    resources?.let {
                        loadDrawableSync(
                            ImageDecoder.createSource(it, icon.resId),
                            maxWidth,
                            maxHeight,
                            allocator
                        )
                    }
                        // Fallback to non-ImageDecoder load if the attempt failed (e.g. the
                        // resource
                        // is a Vector drawable which ImageDecoder doesn't support.)
                        ?: loadIconDrawable(icon, context)
                }
                Icon.TYPE_BITMAP -> {
                    BitmapDrawable(context.resources, icon.bitmap)
                }
                Icon.TYPE_ADAPTIVE_BITMAP -> {
                    AdaptiveIconDrawable(null, BitmapDrawable(context.resources, icon.bitmap))
                }
                Icon.TYPE_DATA -> {
                    loadDrawableSync(
                        ImageDecoder.createSource(icon.dataBytes, icon.dataOffset, icon.dataLength),
                        maxWidth,
                        maxHeight,
                        allocator
                    )
                }
                else -> {
                    // We don't recognize this icon, just fallback.
                    loadIconDrawable(icon, context)
                }
            }?.let { drawable ->
                // Icons carry tint which we need to propagate down to a Drawable.
                tintDrawable(icon, drawable)
                drawable
            }
        }

    @WorkerThread
    fun loadIconDrawable(icon: Icon, context: Context): Drawable? {
        icon.loadDrawable(context)?.let {
            return it
        }

        Log.w(TAG, "Failed to load drawable for $icon")
        return null
    }

    /**
     * Obtains the image size from the image header, without decoding the full image.
     *
     * @param icon an [Icon] representing the source of the image
     * @return the [Size] if it could be determined from the image header, or `null` otherwise
     */
    suspend fun loadSize(icon: Icon, context: Context): Size? =
        withContext(backgroundDispatcher) { loadSizeSync(icon, context) }

    /**
     * Obtains the image size from the image header, without decoding the full image.
     *
     * @param icon an [Icon] representing the source of the image
     * @return the [Size] if it could be determined from the image header, or `null` otherwise
     */
    @WorkerThread
    fun loadSizeSync(icon: Icon, context: Context): Size? {
        return when (icon.type) {
            Icon.TYPE_URI,
            Icon.TYPE_URI_ADAPTIVE_BITMAP -> {
                val source = ImageDecoder.createSource(context.contentResolver, icon.uri)
                loadSizeSync(source)
            }
            else -> null
        }
    }

    /**
     * Obtains the image size from the image header, without decoding the full image.
     *
     * @param source [ImageDecoder.Source] of the image
     * @return the [Size] if it could be determined from the image header, or `null` otherwise
     */
    @WorkerThread
    fun loadSizeSync(source: ImageDecoder.Source): Size? {
        return try {
            ImageDecoder.decodeHeader(source).size
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load source $source", e)
            return null
        } catch (e: DecodeException) {
            Log.w(TAG, "Failed to decode source $source", e)
            return null
        }
    }

    companion object {
        const val TAG = "ImageLoader"

        // 4096 is a reasonable default - most devices will support 4096x4096 texture size for
        // Canvas rendering and by default we SystemUI has no need to render larger bitmaps.
        // This prevents exceptions and crashes if the code accidentally loads larger Bitmap
        // and then attempts to render it on Canvas.
        // It can always be overridden by the parameters.
        const val DEFAULT_MAX_SAFE_BITMAP_SIZE_PX = 4096

        /**
         * This constant signals that ImageLoader shouldn't attempt to resize the passed bitmap in a
         * given dimension.
         *
         * Set both maxWidth and maxHeight to [DO_NOT_RESIZE] if you wish to prevent resizing.
         */
        const val DO_NOT_RESIZE = 0

        /** Maps [Source] to [ImageDecoder.Source]. */
        private fun toImageDecoderSource(source: Source, defaultContext: Context) =
            when (source) {
                is Res -> {
                    val context = source.context ?: defaultContext
                    ImageDecoder.createSource(context.resources, source.resId)
                }
                is File -> ImageDecoder.createSource(source.file)
                is Uri -> ImageDecoder.createSource(defaultContext.contentResolver, source.uri)
                is InputStream -> {
                    val context = source.context ?: defaultContext
                    ImageDecoder.createSource(context.resources, source.inputStream)
                }
            }

        /**
         * This sets target size on the image decoder to conform to the maxWidth / maxHeight
         * parameters. The parameters are chosen to keep the existing drawable aspect ratio.
         */
        @AnyThread
        private fun configureDecoderForMaximumSize(
            decoder: ImageDecoder,
            imgSize: Size,
            @Px maxWidth: Int,
            @Px maxHeight: Int
        ) {
            if (maxWidth == DO_NOT_RESIZE && maxHeight == DO_NOT_RESIZE) {
                return
            }

            if (imgSize.width <= maxWidth && imgSize.height <= maxHeight) {
                return
            }

            // Determine the scale factor for each dimension so it fits within the set constraint
            val wScale =
                if (maxWidth <= 0) {
                    1.0f
                } else {
                    maxWidth.toFloat() / imgSize.width.toFloat()
                }

            val hScale =
                if (maxHeight <= 0) {
                    1.0f
                } else {
                    maxHeight.toFloat() / imgSize.height.toFloat()
                }

            // Scale down to the dimension that demands larger scaling (smaller scale factor).
            // Use the same scale for both dimensions to keep the aspect ratio.
            val scale = min(wScale, hScale)
            if (scale < 1.0f) {
                val targetWidth = (imgSize.width * scale).toInt()
                val targetHeight = (imgSize.height * scale).toInt()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Configured image size to $targetWidth x $targetHeight")
                }

                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }

        /**
         * Attempts to retrieve [Resources] class required to load the passed icon. Icons can
         * originate from other processes so we need to make sure we load them from the right
         * package source.
         *
         * @return [Resources] to load the icon drawable or null if icon doesn't carry a resource or
         *   the resource package couldn't be resolved.
         */
        @WorkerThread
        private fun resolveResourcesForIcon(context: Context, icon: Icon): Resources? {
            if (icon.type != Icon.TYPE_RESOURCE) {
                return null
            }

            val resources = icon.resources
            if (resources != null) {
                return resources
            }

            val resPackage = icon.resPackage
            if (
                resPackage == null || resPackage.isEmpty() || context.packageName.equals(resPackage)
            ) {
                return context.resources
            }

            if ("android" == resPackage) {
                return Resources.getSystem()
            }

            val pm = context.packageManager
            try {
                val ai =
                    pm.getApplicationInfo(
                        resPackage,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES or
                            PackageManager.GET_SHARED_LIBRARY_FILES
                    )
                if (ai != null) {
                    return pm.getResourcesForApplication(ai)
                } else {
                    Log.w(TAG, "Failed to resolve application info for $resPackage")
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Failed to resolve resource package", e)
                return null
            }
            return null
        }

        /** Applies tinting from [Icon] to the passed [Drawable]. */
        @AnyThread
        private fun tintDrawable(icon: Icon, drawable: Drawable) {
            if (icon.hasTint()) {
                drawable.mutate()
                drawable.setTintList(icon.tintList)
                drawable.setTintBlendMode(icon.tintBlendMode)
            }
        }
    }
}
