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

package com.android.systemui.statusbar.phone;

/** An interface for a UI element controlled by the {@link AutoHideController}. */
public interface AutoHideElement {
    /**
     * Synchronizes the UI State of this {@link AutoHideElement}. This method is posted as a
     * {@link Runnable} on the main thread.
     */
    void synchronizeState();

    /**
     * Returns {@code true} if the {@link AutoHideElement} is in a
     * {@link BarTransitions#MODE_SEMI_TRANSPARENT} state.
     */
    boolean isSemiTransparent();
}
