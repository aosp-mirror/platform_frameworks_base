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

package com.android.systemui.shade

interface QuickSettingsController {
    /** Returns whether or not QuickSettings is expanded. */
    val expanded: Boolean

    /** Returns whether or not QuickSettings is being customized. */
    val isCustomizing: Boolean

    /** Returns Whether we should intercept a gesture to open Quick Settings. */
    @Deprecated("specific to legacy touch handling")
    fun shouldQuickSettingsIntercept(x: Float, y: Float, yDiff: Float): Boolean

    /** Closes the Qs customizer. */
    fun closeQsCustomizer()

    /**
     * This method closes QS but in split shade it should be used only in special cases: to make
     * sure QS closes when shade is closed as well. Otherwise it will result in QS disappearing from
     * split shade
     */
    @Deprecated("specific to legacy split shade") fun closeQs()

    /** Calculate top padding for notifications */
    @Deprecated("specific to legacy DebugDrawable")
    fun calculateNotificationsTopPadding(
        isShadeExpanding: Boolean,
        keyguardNotificationStaticPadding: Int,
        expandedFraction: Float,
    ): Float

    /** Calculate height of QS panel */
    @Deprecated("specific to legacy DebugDrawable")
    fun calculatePanelHeightExpanded(stackScrollerPadding: Int): Int
}
