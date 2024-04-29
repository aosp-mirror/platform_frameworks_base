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

package com.android.wm.shell.windowdecor

import android.content.res.Resources
import com.android.window.flags.Flags.enableWindowingEdgeDragResize
import com.android.wm.shell.R
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.util.KtProtoLog
import java.util.function.Consumer

/** Repository for desktop mode drag resize handle sizes. */
class ResizeHandleSizeRepository {
    private val TAG = "ResizeHandleSizeRepository"
    private var edgeResizeHandleSizePixels: Int? = null
    private var sizeChangeFunctions: MutableList<Consumer<ResizeHandleSizeRepository>> =
        mutableListOf()

    /**
     * Resets the window edge resize handle size if necessary.
     */
    fun resetResizeEdgeHandlePixels() {
        if (enableWindowingEdgeDragResize() && edgeResizeHandleSizePixels != null) {
            edgeResizeHandleSizePixels = null
            applyToAll()
        }
    }

    /**
     * Sets the window edge resize handle to the given size in pixels.
     */
    fun setResizeEdgeHandlePixels(sizePixels: Int) {
        if (enableWindowingEdgeDragResize()) {
            KtProtoLog.d(WM_SHELL_DESKTOP_MODE, "$TAG: Set edge handle size to $sizePixels")
            if (edgeResizeHandleSizePixels != null && edgeResizeHandleSizePixels == sizePixels) {
                // Skip updating since override is the same size
                return
            }
            edgeResizeHandleSizePixels = sizePixels
            applyToAll()
        } else {
            KtProtoLog.d(
                WM_SHELL_DESKTOP_MODE,
                "$TAG: Can't set edge handle size to $sizePixels since " +
                    "enable_windowing_edge_drag_resize disabled"
            )
        }
    }

    /**
     * Returns the resource value, in pixels, to use for the resize handle on the edge of the
     * window.
     */
    fun getResizeEdgeHandlePixels(res: Resources): Int {
        try {
            return if (enableWindowingEdgeDragResize()) {
                val resPixelSize = res.getDimensionPixelSize(R.dimen.desktop_mode_edge_handle)
                val size = edgeResizeHandleSizePixels ?: resPixelSize
                KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "$TAG: Get edge handle size of $size from (vs base value $resPixelSize)"
                )
                size
            } else {
                KtProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "$TAG: Get edge handle size from freeform since flag is disabled"
                )
                res.getDimensionPixelSize(R.dimen.freeform_resize_handle)
            }
        } catch (e: Resources.NotFoundException) {
            KtProtoLog.e(WM_SHELL_DESKTOP_MODE, "$TAG: Unable to get edge handle size", e)
            return 0
        }
    }

    /** Register function to run when the resize handle size changes. */
    fun registerSizeChangeFunction(function: Consumer<ResizeHandleSizeRepository>) {
        sizeChangeFunctions.add(function)
    }

    private fun applyToAll() {
        for (f in sizeChangeFunctions) {
            f.accept(this)
        }
    }

    companion object {
        private val TAG = "ResizeHandleSizeRepositoryCompanion"

        /**
         * Returns the resource value in pixels to use for course input, such as touch, that
         * benefits from a large square on each of the window's corners.
         */
        @JvmStatic
        fun getLargeResizeCornerPixels(res: Resources): Int {
            return try {
                res.getDimensionPixelSize(R.dimen.desktop_mode_corner_resize_large)
            } catch (e: Resources.NotFoundException) {
                KtProtoLog.e(WM_SHELL_DESKTOP_MODE, "$TAG: Unable to get large corner size", e)
                0
            }
        }

        /**
         * Returns the resource value, in pixels, to use for fine input, such as stylus, that can
         * use a smaller square on each of the window's corners.
         */
        @JvmStatic
        fun getFineResizeCornerPixels(res: Resources): Int {
            return try {
                res.getDimensionPixelSize(R.dimen.freeform_resize_corner)
            } catch (e: Resources.NotFoundException) {
                KtProtoLog.e(WM_SHELL_DESKTOP_MODE, "$TAG: Unable to get fine corner size", e)
                0
            }
        }
    }
}
