package com.android.systemui.util.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimatedRotateDrawable
import android.graphics.drawable.AnimatedStateListDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.Px
import com.android.app.tracing.traceSection

class DrawableSize {

    companion object {

        const val TAG = "SysUiDrawableSize"

        /**
         * Downscales passed Drawable to set maximum width and height. This will only
         * be done for Drawables that can be downscaled non-destructively - e.g. animated
         * and stateful drawables will no be downscaled.
         *
         * Downscaling will keep the aspect ratio.
         * This method will not touch drawables that already fit into size specification.
         *
         * @param resources Resources on which to base the density of resized drawable.
         * @param drawable Drawable to downscale.
         * @param maxWidth Maximum width of the downscaled drawable.
         * @param maxHeight Maximum height of the downscaled drawable.
         *
         * @return returns downscaled drawable if it's possible to downscale it or original if it's
         *         not.
         */
        @JvmStatic
        fun downscaleToSize(
            res: Resources,
            drawable: Drawable,
            @Px maxWidth: Int,
            @Px maxHeight: Int
        ): Drawable {
            traceSection("DrawableSize#downscaleToSize") {
                // Bitmap drawables can contain big bitmaps as their content while sneaking it past
                // us using density scaling. Inspect inside the Bitmap drawables for actual bitmap
                // size for those.
                val originalWidth = (drawable as? BitmapDrawable)?.bitmap?.width
                                    ?: drawable.intrinsicWidth
                val originalHeight = (drawable as? BitmapDrawable)?.bitmap?.height
                                    ?: drawable.intrinsicHeight

                // Don't touch drawable if we can't resolve sizes for whatever reason.
                if (originalWidth <= 0 || originalHeight <= 0) {
                    return drawable
                }

                // Do not touch drawables that are already within bounds.
                if (originalWidth < maxWidth && originalHeight < maxHeight) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Not resizing $originalWidth x $originalHeight" + " " +
                                "to $maxWidth x $maxHeight")
                    }

                    return drawable
                }

                if (!isSimpleBitmap(drawable)) {
                    return drawable
                }

                val scaleWidth = maxWidth.toFloat() / originalWidth.toFloat()
                val scaleHeight = maxHeight.toFloat() / originalHeight.toFloat()
                val scale = minOf(scaleHeight, scaleWidth)

                val width = (originalWidth * scale).toInt()
                val height = (originalHeight * scale).toInt()

                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "Attempted to resize ${drawable.javaClass.simpleName} " +
                            "from $originalWidth x $originalHeight to invalid $width x $height.")
                    return drawable
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Resizing large drawable (${drawable.javaClass.simpleName}) " +
                            "from $originalWidth x $originalHeight to $width x $height")
                }

                // We want to keep existing config if it's more efficient than 32-bit RGB.
                val config = (drawable as? BitmapDrawable)?.bitmap?.config
                        ?: Bitmap.Config.ARGB_8888
                val scaledDrawableBitmap = Bitmap.createBitmap(width, height, config)
                val canvas = Canvas(scaledDrawableBitmap)

                val originalBounds = drawable.bounds
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                drawable.bounds = originalBounds

                return BitmapDrawable(res, scaledDrawableBitmap)
            }
        }

        private fun isSimpleBitmap(drawable: Drawable): Boolean {
            return !(drawable.isStateful || isAnimated(drawable))
        }

        private fun isAnimated(drawable: Drawable): Boolean {
            if (drawable is Animatable || drawable is Animatable2) {
                return true
            }

            return drawable is AnimatedImageDrawable ||
                drawable is AnimatedRotateDrawable ||
                drawable is AnimatedStateListDrawable ||
                drawable is AnimatedVectorDrawable
        }
    }
}