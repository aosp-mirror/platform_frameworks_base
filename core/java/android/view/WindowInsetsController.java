/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.view.WindowInsets.Type.ime;

import android.annotation.NonNull;
import android.view.WindowInsets.Type.InsetType;

/**
 * Interface to control windows that generate insets.
 *
 * TODO Needs more information and examples once the API is more baked.
 * @hide pending unhide
 */
public interface WindowInsetsController {

    /**
     * Makes a set of windows that cause insets appear on screen.
     * <p>
     * Note that if the window currently doesn't have control over a certain type, it will apply the
     * change as soon as the window gains control. The app can listen to the event by observing
     * {@link View#onApplyWindowInsets} and checking visibility with {@link WindowInsets#isVisible}.
     *
     * @param types A bitmask of {@link WindowInsets.Type.InsetType} specifying what windows the app
     *              would like to make appear on screen.
     * @hide
     */
    void show(@InsetType int types);

    /**
     * Makes a set of windows causing insets disappear.
     * <p>
     * Note that if the window currently doesn't have control over a certain type, it will apply the
     * change as soon as the window gains control. The app can listen to the event by observing
     * {@link View#onApplyWindowInsets} and checking visibility with {@link WindowInsets#isVisible}.
     *
     * @param types A bitmask of {@link WindowInsets.Type.InsetType} specifying what windows the app
     *              would like to make disappear.
     * @hide
     */
    void hide(@InsetType int types);

    /**
     * Lets the application control window inset animations in a frame-by-frame manner by modifying
     * the position of the windows in the system causing insets directly.
     *
     * @param types The {@link InsetType}s the application has requested to control.
     * @param listener The {@link WindowInsetsAnimationControlListener} that gets called when the
     *                 windows are ready to be controlled, among other callbacks.
     * @hide
     */
    void controlWindowInsetsAnimation(@InsetType int types,
            @NonNull WindowInsetsAnimationControlListener listener);

    /**
     * Lets the application control the animation for showing the IME in a frame-by-frame manner by
     * modifying the position of the IME when it's causing insets.
     *
     * @param listener The {@link WindowInsetsAnimationControlListener} that gets called when the
     *                 IME are ready to be controlled, among other callbacks.
     */
    default void controlInputMethodAnimation(
            @NonNull WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(ime(), listener);
    }

    /**
     * Makes the IME appear on screen.
     * <p>
     * Note that if the window currently doesn't have control over the IME, because it doesn't have
     * focus, it will apply the change as soon as the window gains control. The app can listen to
     * the event by observing {@link View#onApplyWindowInsets} and checking visibility with
     * {@link WindowInsets#isVisible}.
     *
     * @see #controlInputMethodAnimation(WindowInsetsAnimationControlListener)
     * @see #hideInputMethod()
     */
    default void showInputMethod() {
        show(ime());
    }

    /**
     * Makes the IME disappear on screen.
     * <p>
     * Note that if the window currently doesn't have control over IME, because it doesn't have
     * focus, it will apply the change as soon as the window gains control. The app can listen to
     * the event by observing {@link View#onApplyWindowInsets} and checking visibility with
     * {@link WindowInsets#isVisible}.
     *
     * @see #controlInputMethodAnimation(WindowInsetsAnimationControlListener)
     * @see #showInputMethod()
     */
    default void hideInputMethod() {
        hide(ime());
    }
}
