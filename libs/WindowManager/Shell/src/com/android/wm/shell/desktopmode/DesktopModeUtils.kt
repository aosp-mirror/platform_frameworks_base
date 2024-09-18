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

@file:JvmName("DesktopModeUtils")

package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.pm.ActivityInfo.isFixedOrientationLandscape
import android.content.pm.ActivityInfo.isFixedOrientationPortrait
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Rect
import android.os.SystemProperties
import android.util.Size
import com.android.wm.shell.common.DisplayLayout

val DESKTOP_MODE_INITIAL_BOUNDS_SCALE: Float =
    SystemProperties.getInt("persist.wm.debug.desktop_mode_initial_bounds_scale", 75) / 100f

val DESKTOP_MODE_LANDSCAPE_APP_PADDING: Int =
    SystemProperties.getInt("persist.wm.debug.desktop_mode_landscape_app_padding", 25)

/**
 * Calculates the initial bounds required for an application to fill a scale of the display bounds
 * without any letterboxing. This is done by taking into account the applications fullscreen size,
 * aspect ratio, orientation and resizability to calculate an area this is compatible with the
 * applications previous configuration.
 */
fun calculateInitialBounds(
    displayLayout: DisplayLayout,
    taskInfo: RunningTaskInfo,
    scale: Float = DESKTOP_MODE_INITIAL_BOUNDS_SCALE
): Rect {
    val screenBounds = Rect(0, 0, displayLayout.width(), displayLayout.height())
    val appAspectRatio = calculateAspectRatio(taskInfo)
    val idealSize = calculateIdealSize(screenBounds, scale)
    // If no top activity exists, apps fullscreen bounds and aspect ratio cannot be calculated.
    // Instead default to the desired initial bounds.
    val stableBounds = Rect()
    displayLayout.getStableBoundsForDesktopMode(stableBounds)
    if (hasFullscreenOverride(taskInfo)) {
        // If the activity has a fullscreen override applied, it should be treated as
        // resizeable and match the device orientation. Thus the ideal size can be
        // applied.
        return positionInScreen(idealSize, stableBounds)
    }
    val topActivityInfo =
        taskInfo.topActivityInfo ?: return positionInScreen(idealSize, stableBounds)

    val initialSize: Size =
        when (taskInfo.configuration.orientation) {
            ORIENTATION_LANDSCAPE -> {
                if (taskInfo.canChangeAspectRatio) {
                    if (isFixedOrientationPortrait(topActivityInfo.screenOrientation)) {
                        // For portrait resizeable activities, respect apps fullscreen width but
                        // apply ideal size height.
                        Size(taskInfo.appCompatTaskInfo.topActivityLetterboxAppWidth,
                            idealSize.height)
                    } else {
                        // For landscape resizeable activities, simply apply ideal size.
                        idealSize
                    }
                } else {
                    // If activity is unresizeable, regardless of orientation, calculate maximum
                    // size (within the ideal size) maintaining original aspect ratio.
                    maximizeSizeGivenAspectRatio(taskInfo, idealSize, appAspectRatio)
                }
            }
            ORIENTATION_PORTRAIT -> {
                val customPortraitWidthForLandscapeApp =
                    screenBounds.width() - (DESKTOP_MODE_LANDSCAPE_APP_PADDING * 2)
                if (taskInfo.canChangeAspectRatio) {
                    if (isFixedOrientationLandscape(topActivityInfo.screenOrientation)) {
                        // For landscape resizeable activities, respect apps fullscreen height and
                        // apply custom app width.
                        Size(
                            customPortraitWidthForLandscapeApp,
                            taskInfo.appCompatTaskInfo.topActivityLetterboxAppHeight
                        )
                    } else {
                        // For portrait resizeable activities, simply apply ideal size.
                        idealSize
                    }
                } else {
                    if (isFixedOrientationLandscape(topActivityInfo.screenOrientation)) {
                        // For landscape unresizeable activities, apply custom app width to ideal
                        // size and calculate maximum size with this area while maintaining original
                        // aspect ratio.
                        maximizeSizeGivenAspectRatio(
                            taskInfo,
                            Size(customPortraitWidthForLandscapeApp, idealSize.height),
                            appAspectRatio
                        )
                    } else {
                        // For portrait unresizeable activities, calculate maximum size (within the
                        // ideal size) maintaining original aspect ratio.
                        maximizeSizeGivenAspectRatio(taskInfo, idealSize, appAspectRatio)
                    }
                }
            }
            else -> {
                idealSize
            }
        }

    return positionInScreen(initialSize, stableBounds)
}

/**
 * Calculates the largest size that can fit in a given area while maintaining a specific aspect
 * ratio.
 */
fun maximizeSizeGivenAspectRatio(
    taskInfo: RunningTaskInfo,
    targetArea: Size,
    aspectRatio: Float
): Size {
    val targetHeight = targetArea.height
    val targetWidth = targetArea.width
    val finalHeight: Int
    val finalWidth: Int
    // Get orientation either through top activity or task's orientation
    if (taskInfo.hasPortraitTopActivity()) {
        val tempWidth = (targetHeight / aspectRatio).toInt()
        if (tempWidth <= targetWidth) {
            finalHeight = targetHeight
            finalWidth = tempWidth
        } else {
            finalWidth = targetWidth
            finalHeight = (finalWidth * aspectRatio).toInt()
        }
    } else {
        val tempWidth = (targetHeight * aspectRatio).toInt()
        if (tempWidth <= targetWidth) {
            finalHeight = targetHeight
            finalWidth = tempWidth
        } else {
            finalWidth = targetWidth
            finalHeight = (finalWidth / aspectRatio).toInt()
        }
    }
    return Size(finalWidth, finalHeight)
}

/** Calculates the aspect ratio of an activity from its fullscreen bounds. */
fun calculateAspectRatio(taskInfo: RunningTaskInfo): Float {
    val appLetterboxWidth = taskInfo.appCompatTaskInfo.topActivityLetterboxAppWidth
    val appLetterboxHeight = taskInfo.appCompatTaskInfo.topActivityLetterboxAppHeight
    if (taskInfo.appCompatTaskInfo.isTopActivityLetterboxed || !taskInfo.canChangeAspectRatio) {
        return maxOf(appLetterboxWidth, appLetterboxHeight) /
            minOf(appLetterboxWidth, appLetterboxHeight).toFloat()
    }
    val appBounds = taskInfo.configuration.windowConfiguration.appBounds ?: return 1f
    return maxOf(appBounds.height(), appBounds.width()) /
        minOf(appBounds.height(), appBounds.width()).toFloat()
}

/** Returns true if task's width or height is maximized else returns false. */
fun isTaskWidthOrHeightEqual(taskBounds: Rect, stableBounds: Rect): Boolean {
    return taskBounds.width() == stableBounds.width() ||
            taskBounds.height() == stableBounds.height()
}

/** Returns true if task bound is equal to stable bounds else returns false. */
fun isTaskBoundsEqual(taskBounds: Rect, stableBounds: Rect): Boolean {
    return taskBounds.width() == stableBounds.width() &&
            taskBounds.height() == stableBounds.height()
}

/**
 * Calculates the desired initial bounds for applications in desktop windowing. This is done as a
 * scale of the screen bounds.
 */
private fun calculateIdealSize(screenBounds: Rect, scale: Float): Size {
    val width = (screenBounds.width() * scale).toInt()
    val height = (screenBounds.height() * scale).toInt()
    return Size(width, height)
}

/** Adjusts bounds to be positioned in the middle of the screen. */
private fun positionInScreen(desiredSize: Size, stableBounds: Rect): Rect =
    Rect(0, 0, desiredSize.width, desiredSize.height).apply {
        val offset = DesktopTaskPosition.Center.getTopLeftCoordinates(stableBounds, this)
        offsetTo(offset.x, offset.y)
    }

/**
 * Whether the activity's aspect ratio can be changed or if it should be maintained as if it was
 * unresizeable.
 */
private val TaskInfo.canChangeAspectRatio: Boolean
    get() = isResizeable && !appCompatTaskInfo.hasMinAspectRatioOverride()

/**
 * Adjusts bounds to be positioned in the middle of the area provided, not necessarily the
 * entire screen, as area can be offset by left and top start.
 */
fun centerInArea(desiredSize: Size, areaBounds: Rect, leftStart: Int, topStart: Int): Rect {
    val heightOffset = (areaBounds.height() - desiredSize.height) / 2
    val widthOffset = (areaBounds.width() - desiredSize.width) / 2

    val newLeft = leftStart + widthOffset
    val newTop = topStart + heightOffset
    val newRight = newLeft + desiredSize.width
    val newBottom = newTop + desiredSize.height

    return Rect(newLeft, newTop, newRight, newBottom)
}

private fun TaskInfo.hasPortraitTopActivity(): Boolean {
    val topActivityScreenOrientation =
        topActivityInfo?.screenOrientation ?: SCREEN_ORIENTATION_UNSPECIFIED
    val appBounds = configuration.windowConfiguration.appBounds

    return when {
        // First check if activity has portrait screen orientation
        topActivityScreenOrientation != SCREEN_ORIENTATION_UNSPECIFIED -> {
            isFixedOrientationPortrait(topActivityScreenOrientation)
        }

        // Then check if the activity is portrait when letterboxed
        appCompatTaskInfo.isTopActivityLetterboxed -> appCompatTaskInfo.isTopActivityPillarboxed

        // Then check if the activity is portrait
        appBounds != null -> appBounds.height() > appBounds.width()

        // Otherwise just take the orientation of the task
        else -> isFixedOrientationPortrait(configuration.orientation)
    }
}

private fun hasFullscreenOverride(taskInfo: RunningTaskInfo): Boolean {
    return taskInfo.appCompatTaskInfo.isUserFullscreenOverrideEnabled
            || taskInfo.appCompatTaskInfo.isSystemFullscreenOverrideEnabled
}
