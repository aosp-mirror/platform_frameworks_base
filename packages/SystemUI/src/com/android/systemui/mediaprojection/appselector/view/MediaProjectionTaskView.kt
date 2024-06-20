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

package com.android.systemui.mediaprojection.appselector.view

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import androidx.core.content.getSystemService
import androidx.core.content.res.use
import com.android.systemui.res.R
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.recents.utilities.PreviewPositionHelper
import com.android.systemui.shared.recents.utilities.Utilities.isLargeScreen

/**
 * Custom view that shows a thumbnail preview of one recent task based on [ThumbnailData].
 * It handles proper cropping and positioning of the thumbnail using [PreviewPositionHelper].
 */
class MediaProjectionTaskView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    private val defaultBackgroundColor: Int

    init {
        val backgroundColorAttribute = intArrayOf(android.R.attr.colorBackgroundFloating)
        defaultBackgroundColor =
            context.obtainStyledAttributes(backgroundColorAttribute).use {
                it.getColor(/* index= */ 0, /* defValue= */ Color.BLACK)
            }
    }

    private val windowManager: WindowManager = context.getSystemService()!!
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = defaultBackgroundColor }
    private val cornerRadius =
        context.resources.getDimensionPixelSize(
            R.dimen.media_projection_app_selector_task_rounded_corners
        )
    private val previewPositionHelper = PreviewPositionHelper()
    private val previewRect = Rect()

    private var task: RecentTask? = null
    private var thumbnailData: ThumbnailData? = null

    private var bitmapShader: BitmapShader? = null

    fun bindTask(task: RecentTask?, thumbnailData: ThumbnailData?) {
        this.task = task
        this.thumbnailData = thumbnailData

        // Strip alpha channel to make sure that the color is not semi-transparent
        val color = (task?.colorBackground ?: Color.BLACK) or 0xFF000000.toInt()

        paint.color = color
        backgroundPaint.color = color

        refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateThumbnailMatrix()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Always draw the background since the snapshots might be translucent or partially empty
        // (For example, tasks been reparented out of dismissing split root when drag-to-dismiss
        // split screen).
        canvas.drawRoundRect(
            0f,
            1f,
            width.toFloat(),
            (height - 1).toFloat(),
            cornerRadius.toFloat(),
            cornerRadius.toFloat(),
            backgroundPaint
        )

        val drawBackgroundOnly = task == null || bitmapShader == null || thumbnailData == null
        if (drawBackgroundOnly) {
            return
        }

        // Draw the task thumbnail using bitmap shader in the paint
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            cornerRadius.toFloat(),
            cornerRadius.toFloat(),
            paint
        )
    }

    private fun refresh() {
        val thumbnailBitmap = thumbnailData?.thumbnail

        if (thumbnailBitmap != null) {
            thumbnailBitmap.prepareToDraw()
            bitmapShader =
                BitmapShader(thumbnailBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = bitmapShader
            updateThumbnailMatrix()
        } else {
            bitmapShader = null
            paint.shader = null
        }

        invalidate()
    }

    private fun updateThumbnailMatrix() {
        previewPositionHelper.isOrientationChanged = false

        val bitmapShader = bitmapShader ?: return
        val thumbnailData = thumbnailData ?: return
        val thumbnail = thumbnailData.thumbnail ?: return
        val display = context.display ?: return
        val windowMetrics = windowManager.maximumWindowMetrics

        previewRect.set(0, 0, thumbnail.width, thumbnail.height)

        val currentRotation: Int = display.rotation
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val isLargeScreen = isLargeScreen(context)

        previewPositionHelper.updateThumbnailMatrix(
            previewRect,
            thumbnailData,
            measuredWidth,
            measuredHeight,
            isLargeScreen,
            currentRotation,
            isRtl
        )

        bitmapShader.setLocalMatrix(previewPositionHelper.matrix)
        paint.shader = bitmapShader
    }
}
