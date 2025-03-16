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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.view.WindowInsetsController
import com.android.internal.colorextraction.ColorExtractor
import com.android.internal.view.AppearanceRegion
import com.android.systemui.CoreStartable

/** Controls how light status bar flag applies to the icons. */
interface LightBarController : CoreStartable {

    fun stop()

    fun setNavigationBar(navigationBar: LightBarTransitionsController)

    fun onNavigationBarAppearanceChanged(
        @WindowInsetsController.Appearance appearance: Int,
        nbModeChanged: Boolean,
        navigationBarMode: Int,
        navbarColorManagedByIme: Boolean,
    )

    fun onNavigationBarModeChanged(newBarMode: Int)

    fun setQsCustomizing(customizing: Boolean)

    /** Set if Quick Settings is fully expanded, which affects notification scrim visibility. */
    fun setQsExpanded(expanded: Boolean)

    /** Set if Global Actions dialog is visible, which requires dark mode (light buttons). */
    fun setGlobalActionsVisible(visible: Boolean)

    /**
     * Controls the light status bar temporarily for back navigation.
     *
     * @param appearance the customized appearance.
     */
    fun customizeStatusBarAppearance(appearance: AppearanceRegion)

    /**
     * Sets whether the direct-reply is in use or not.
     *
     * @param directReplying `true` when the direct-reply is in-use.
     */
    fun setDirectReplying(directReplying: Boolean)

    fun setScrimState(
        scrimState: ScrimState,
        scrimBehindAlpha: Float,
        scrimInFrontColor: ColorExtractor.GradientColors,
    )

    fun interface Factory {
        fun create(context: Context): LightBarController
    }
}
