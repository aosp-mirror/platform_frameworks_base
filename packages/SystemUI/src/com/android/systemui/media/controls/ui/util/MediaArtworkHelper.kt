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

package com.android.systemui.media.controls.ui.util

import android.app.WallpaperColors
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.util.Log
import com.android.systemui.media.controls.ui.animation.backgroundEndFromScheme
import com.android.systemui.media.controls.ui.animation.backgroundStartFromScheme
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.util.getColorWithAlpha
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

object MediaArtworkHelper {

    /**
     * This method should be called from a background thread. WallpaperColors.fromBitmap takes a
     * good amount of time. We do that work on the background executor to avoid stalling animations
     * on the UI Thread.
     */
    suspend fun getWallpaperColor(
        applicationContext: Context,
        backgroundDispatcher: CoroutineDispatcher,
        artworkIcon: Icon?,
        tag: String,
    ): WallpaperColors? =
        withContext(backgroundDispatcher) {
            return@withContext artworkIcon?.let {
                if (it.type == Icon.TYPE_BITMAP || it.type == Icon.TYPE_ADAPTIVE_BITMAP) {
                    // Avoids extra processing if this is already a valid bitmap
                    it.bitmap.let { artworkBitmap ->
                        if (artworkBitmap.isRecycled) {
                            Log.d(tag, "Cannot load wallpaper color from a recycled bitmap")
                            null
                        } else {
                            WallpaperColors.fromBitmap(artworkBitmap)
                        }
                    }
                } else {
                    it.loadDrawable(applicationContext)?.let { artworkDrawable ->
                        WallpaperColors.fromDrawable(artworkDrawable)
                    }
                }
            }
        }

    /**
     * Returns a scaled [Drawable] of a given [Icon] centered in [width]x[height] background size.
     */
    fun getScaledBackground(context: Context, icon: Icon, width: Int, height: Int): Drawable? {
        val drawable = icon.loadDrawable(context)
        val bounds = Rect(0, 0, width, height)
        if (bounds.width() > width || bounds.height() > height) {
            val offsetX = (bounds.width() - width) / 2.0f
            val offsetY = (bounds.height() - height) / 2.0f
            bounds.offset(-offsetX.toInt(), -offsetY.toInt())
        }
        drawable?.bounds = bounds
        return drawable
    }

    /** Adds [gradient] on a given [albumArt] drawable using [colorScheme]. */
    fun setUpGradientColorOnDrawable(
        albumArt: Drawable?,
        gradient: GradientDrawable,
        colorScheme: ColorScheme,
        startAlpha: Float,
        endAlpha: Float
    ): LayerDrawable {
        gradient.colors =
            intArrayOf(
                getColorWithAlpha(backgroundStartFromScheme(colorScheme), startAlpha),
                getColorWithAlpha(backgroundEndFromScheme(colorScheme), endAlpha)
            )
        return LayerDrawable(arrayOf(albumArt, gradient))
    }

    /** Returns [ColorScheme] of media app given its [packageName]. */
    fun getColorScheme(
        applicationContext: Context,
        packageName: String,
        tag: String,
        style: Style = Style.TONAL_SPOT
    ): ColorScheme? {
        return try {
            // Set up media source app's logo.
            val icon = applicationContext.packageManager.getApplicationIcon(packageName)
            ColorScheme(WallpaperColors.fromDrawable(icon), true, style)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(tag, "Fail to get media app info", e)
            null
        }
    }
}
