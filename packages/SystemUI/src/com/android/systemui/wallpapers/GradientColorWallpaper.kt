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

package com.android.systemui.wallpapers

import android.app.Flags
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toRectF
import com.android.systemui.res.R

/** A wallpaper that shows a static gradient color image wallpaper. */
class GradientColorWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine =
        if (Flags.enableConnectedDisplaysWallpaper()) {
            GradientColorWallpaperEngine()
        } else {
            EmptyWallpaperEngine()
        }

    /** Empty engine used when the feature flag is disabled. */
    inner class EmptyWallpaperEngine : Engine()

    inner class GradientColorWallpaperEngine : Engine() {
        init {
            setShowForAllUsers(true)
        }

        override fun onSurfaceRedrawNeeded(surfaceHolder: SurfaceHolder) {
            drawFrameInternal(surfaceHolder)
        }

        private fun drawFrameInternal(surfaceHolder: SurfaceHolder) {
            val context = displayContext ?: return
            val surface = surfaceHolder.surface
            var canvas: Canvas? = null
            try {
                canvas = surface.lockHardwareCanvas()
                val destRectF = surfaceHolder.surfaceFrame.toRectF()
                val toColor = context.getColor(com.android.internal.R.color.materialColorPrimary)
                val fromColor =
                    ColorUtils.setAlphaComponent(
                        context.getColor(
                            com.android.internal.R.color.materialColorPrimaryContainer
                        ),
                        /* alpha= */ 153, // 0.6f * 255
                    )

                canvas.drawRect(destRectF, Paint().apply { color = toColor })

                val offsetPx: Float =
                    context.resources
                        .getDimensionPixelSize(R.dimen.gradient_color_wallpaper_center_offset)
                        .toFloat()
                val totalHeight = destRectF.height() + (offsetPx * 2)
                val leftCenterX = -offsetPx
                val leftCenterY = -offsetPx
                val rightCenterX = offsetPx + destRectF.width()
                val rightCenterY = totalHeight - offsetPx
                val radius = (destRectF.width() / 2) + offsetPx

                canvas.drawCircle(
                    leftCenterX,
                    leftCenterY,
                    radius,
                    Paint().apply {
                        shader =
                            RadialGradient(
                                /* centerX= */ leftCenterX,
                                /* centerY= */ leftCenterY,
                                /* radius= */ radius,
                                /* centerColor= */ fromColor,
                                /* edgeColor= */ toColor,
                                /* tileMode= */ Shader.TileMode.CLAMP,
                            )
                    },
                )

                canvas.drawCircle(
                    rightCenterX,
                    rightCenterY,
                    radius,
                    Paint().apply {
                        shader =
                            RadialGradient(
                                /* centerX= */ rightCenterX,
                                /* centerY= */ rightCenterY,
                                /* radius= */ radius,
                                /* centerColor= */ fromColor,
                                /* edgeColor= */ toColor,
                                /* tileMode= */ Shader.TileMode.CLAMP,
                            )
                    },
                )
            } catch (exception: IllegalStateException) {
                Log.d(TAG, "Fail to draw in the canvas", exception)
            } finally {
                canvas?.let { surface.unlockCanvasAndPost(it) }
            }
        }
    }

    private companion object {
        const val TAG = "GradientColorWallpaper"
    }
}
