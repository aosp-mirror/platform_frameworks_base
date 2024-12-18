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

package com.android.wm.shell.shared.desktopmode
import android.annotation.ColorInt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.TypedValue
import android.view.MotionEvent.ACTION_OUTSIDE
import android.view.SurfaceView
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import android.window.TaskSnapshot

/**
 * View for the All Windows menu option, used by both Desktop Windowing and Taskbar.
 * The menu displays icons of all open instances of an app. Clicking the icon should launch
 * the instance, which will be performed by the child class.
 */
abstract class ManageWindowsViewContainer(
    val context: Context,
    @ColorInt private val menuBackgroundColor: Int
) {
    lateinit var menuView: ManageWindowsView

    /** Creates the base menu view and fills it with icon views. */
    fun show(snapshotList: List<Pair<Int, TaskSnapshot>>,
             onIconClickListener: ((Int) -> Unit),
             onOutsideClickListener: (() -> Unit)): ManageWindowsView {
        menuView = ManageWindowsView(context, menuBackgroundColor).apply {
            this.onOutsideClickListener = onOutsideClickListener
            this.onIconClickListener = onIconClickListener
            this.generateIconViews(snapshotList)
        }
        addToContainer(menuView)
        return menuView
    }

    /** Adds the menu view to the container responsible for displaying it. */
    abstract fun addToContainer(menuView: ManageWindowsView)

    /** Dispose of the menu, perform needed cleanup. */
    abstract fun close()

    companion object {
        const val MANAGE_WINDOWS_MINIMUM_INSTANCES = 2
    }

    class ManageWindowsView(
        private val context: Context,
        menuBackgroundColor: Int
    ) {
        val rootView: LinearLayout = LinearLayout(context)
        var menuHeight = 0
        var menuWidth = 0
        var onIconClickListener: ((Int) -> Unit)? = null
        var onOutsideClickListener: (() -> Unit)? = null

        init {
            rootView.orientation = LinearLayout.VERTICAL
            val menuBackground = ShapeDrawable()
            val menuRadius = getDimensionPixelSize(MENU_RADIUS_DP)
            menuBackground.shape = RoundRectShape(
                FloatArray(8) { menuRadius },
                null,
                null
            )
            menuBackground.paint.color = menuBackgroundColor
            rootView.background = menuBackground
            rootView.elevation = getDimensionPixelSize(MENU_ELEVATION_DP)
            rootView.setOnTouchListener { _, event ->
                if (event.actionMasked == ACTION_OUTSIDE) {
                    onOutsideClickListener?.invoke()
                }
                return@setOnTouchListener true
            }
        }

        private fun getDimensionPixelSize(sizeDp: Float): Float {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                sizeDp, context.resources.displayMetrics)
        }

        fun generateIconViews(
            snapshotList: List<Pair<Int, TaskSnapshot>>
        ) {
            menuWidth = 0
            menuHeight = 0
            rootView.removeAllViews()
            val instanceIconHeight = getDimensionPixelSize(ICON_HEIGHT_DP)
            val instanceIconWidth = getDimensionPixelSize(ICON_WIDTH_DP)
            val iconRadius = getDimensionPixelSize(ICON_RADIUS_DP)
            val iconMargin = getDimensionPixelSize(ICON_MARGIN_DP)
            var rowLayout: LinearLayout? = null
            // Add each icon to the menu, adding a new row when needed.
            for ((iconCount, taskInfoSnapshotPair) in snapshotList.withIndex()) {
                val taskId = taskInfoSnapshotPair.first
                val snapshot = taskInfoSnapshotPair.second
                // Once a row is filled, make a new row and increase the menu height.
                if (iconCount % MENU_MAX_ICONS_PER_ROW == 0) {
                    rowLayout = LinearLayout(context)
                    rowLayout.orientation = LinearLayout.HORIZONTAL
                    rootView.addView(rowLayout)
                    menuHeight += (instanceIconHeight + iconMargin).toInt()
                }
                val snapshotBitmap = Bitmap.wrapHardwareBuffer(
                    snapshot.hardwareBuffer,
                    snapshot.colorSpace
                )
                val scaledSnapshotBitmap = snapshotBitmap?.let {
                    Bitmap.createScaledBitmap(
                        it, instanceIconWidth.toInt(), instanceIconHeight.toInt(), true /* filter */
                    )
                }
                val appSnapshotButton = SurfaceView(context)
                appSnapshotButton.cornerRadius = iconRadius
                appSnapshotButton.setZOrderOnTop(true)
                appSnapshotButton.setOnClickListener {
                    onIconClickListener?.invoke(taskId)
                }
                val lp = MarginLayoutParams(
                    instanceIconWidth.toInt(), instanceIconHeight.toInt()
                )
                lp.apply {
                    marginStart = iconMargin.toInt()
                    topMargin = iconMargin.toInt()
                }
                appSnapshotButton.layoutParams = lp
                // If we haven't already reached one full row, increment width.
                if (iconCount < MENU_MAX_ICONS_PER_ROW) {
                    menuWidth += (instanceIconWidth + iconMargin).toInt()
                }
                rowLayout?.addView(appSnapshotButton)
                appSnapshotButton.requestLayout()
                rowLayout?.post {
                    appSnapshotButton.holder.surface
                        .attachAndQueueBufferWithColorSpace(
                            scaledSnapshotBitmap?.hardwareBuffer,
                            scaledSnapshotBitmap?.colorSpace
                        )
                }
            }
            // Add margin again for the right/bottom of the menu.
            menuWidth += iconMargin.toInt()
            menuHeight += iconMargin.toInt()
        }

        companion object {
            private const val MENU_RADIUS_DP = 26f
            private const val ICON_WIDTH_DP = 204f
            private const val ICON_HEIGHT_DP = 127.5f
            private const val ICON_RADIUS_DP = 16f
            private const val ICON_MARGIN_DP = 16f
            private const val MENU_ELEVATION_DP = 1f
            private const val MENU_MAX_ICONS_PER_ROW = 3
        }
    }
}
