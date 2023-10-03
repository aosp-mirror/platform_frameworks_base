/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.icon.ui.viewmodel

import android.graphics.Rect
import com.android.systemui.util.ui.AnimatedValue
import kotlinx.coroutines.flow.Flow

/**
 * View-model for the row of notification icons displayed in the NotificationShelf, StatusBar, and
 * AOD.
 */
interface NotificationIconContainerViewModel {

    /** Are changes to the icon container animated? */
    val animationsEnabled: Flow<Boolean>

    /** Should icons be rendered in "dozing" mode? */
    val isDozing: Flow<AnimatedValue<Boolean>>

    /** Is the icon container visible? */
    val isVisible: Flow<AnimatedValue<Boolean>>

    /** The colors with which to display the notification icons. */
    val iconColors: Flow<ColorLookup>

    /**
     * Signal completion of the [isDozing] animation; if [isDozing]'s [AnimatedValue.isAnimating]
     * property was `true`, calling this method will update it to `false`.
     */
    fun completeDozeAnimation()

    /**
     * Signal completion of the [isVisible] animation; if [isVisible]'s [AnimatedValue.isAnimating]
     * property was `true`, calling this method will update it to `false`.
     */
    fun completeVisibilityAnimation()

    /**
     * Lookup the colors to use for the notification icons based on the bounds of the icon
     * container. A result of `null` indicates that no color changes should be applied.
     */
    fun interface ColorLookup {
        fun iconColors(viewBounds: Rect): IconColors?
    }

    /** Colors to apply to notification icons. */
    interface IconColors {

        /** A tint to apply to the icons. */
        val tint: Int

        /**
         * Returns the color to be applied to an icon, based on that icon's view bounds and whether
         * or not the notification icon is colorized.
         */
        fun staticDrawableColor(viewBounds: Rect, isColorized: Boolean): Int
    }
}
