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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import com.android.systemui.statusbar.StatusBarIconView

/**
 * Defines interface for an object that acts as the binding between a modern status bar view and its
 * view-model.
 *
 * Users of the view binder classes in the modern status bar pipeline should use this to control the
 * binder after it is bound.
 */
interface ModernStatusBarViewBinding {
    /** Returns true if the icon should be visible and false otherwise. */
    fun getShouldIconBeVisible(): Boolean

    /** Notifies that the visibility state has changed. */
    fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int)

    /** Notifies that the icon tint has been updated. */
    fun onIconTintChanged(newTint: Int)

    /** Notifies that the decor tint has been updated (used only for the dot). */
    fun onDecorTintChanged(newTint: Int)
}
