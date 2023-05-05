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

package com.android.server.wm;

import android.annotation.NonNull;
import android.os.IBinder;
import android.view.WindowManager;

/**
 * Callback the IME targeting window visibility change state for
 * {@link com.android.server.inputmethod.InputMethodManagerService} to manage the IME surface
 * visibility and z-ordering.
 */
public interface ImeTargetChangeListener {
    /**
     * Called when a non-IME-focusable overlay window being the IME layering target (e.g. a
     * window with {@link android.view.WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE} and
     * {@link android.view.WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM} flags)
     * has changed its window visibility.
     *
     * @param overlayWindowToken the window token of the overlay window.
     * @param windowType         the window type of the overlay window.
     * @param visible            the visibility of the overlay window, {@code true} means visible
     *                           and {@code false} otherwise.
     * @param removed            Whether the IME target overlay window has being removed.
     */
    default void onImeTargetOverlayVisibilityChanged(@NonNull IBinder overlayWindowToken,
            @WindowManager.LayoutParams.WindowType int windowType,
            boolean visible, boolean removed) {
    }

    /**
     * Called when the visibility of IME input target window has changed.
     *
     * @param imeInputTarget   the window token of the IME input target window.
     * @param visible          the new window visibility made by {@param imeInputTarget}. visible is
     *                         {@code true} when switching to the new visible IME input target
     *                         window and started input, or the same input target relayout to
     *                         visible from invisible. In contrast, visible is {@code false} when
     *                         closing the input target, or the same input target relayout to
     *                         invisible from visible.
     * @param removed          Whether the IME input target window has being removed.
     */
    default void onImeInputTargetVisibilityChanged(@NonNull IBinder imeInputTarget, boolean visible,
            boolean removed) {
    }
}
