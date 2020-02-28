/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar;

/**
 * Common interface for a UI element controlled by
 * {@link com.android.systemui.statusbar.phone.AutoHideController}. These UI elements  automatically
 * hidden by {@link com.android.systemui.statusbar.phone.AutoHideController} when in some transient
 * state.
 */
public interface AutoHideUiElement {

    /**
     * Ensures that the {@link AutoHideUiElement} reflects the current expected state. This
     * method will be posted as a {@link Runnable} in the main thread.
     */
    void synchronizeState();

    /**
     * The {@link com.android.systemui.statusbar.phone.AutoHideController} is responsible for
     * automatically hiding ui elements that are only shown transiently. This method determines
     * whether a manual touch should also hide the ui elements that are temporarily visible.
     *
     * Note that all {@link AutoHideUiElement} instances should return true for a manual touch to
     * trigger {@link #hide()} on the ui elements.
     */
    default boolean shouldHideOnTouch() {
        return true;
    }

    /** Returns true if the {@link AutoHideUiElement} is visible. */
    boolean isVisible();

    /**
     * Called to hide the {@link AutoHideUiElement} through the
     * {@link com.android.systemui.statusbar.phone.AutoHideController}.
     */
    void hide();
}
