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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Insets;
import android.view.WindowInsets.Type.InsetType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface to control windows that generate insets.
 *
 * TODO Needs more information and examples once the API is more baked.
 * @hide pending unhide
 */
public interface WindowInsetsController {

    /**
     * Makes system bars become opaque with solid dark background and light foreground.
     * @hide
     */
    int APPEARANCE_OPAQUE_BARS = 1;

    /**
     * Makes items on system bars become less noticeable without changing the layout of the bars.
     * @hide
     */
    int APPEARANCE_LOW_PROFILE_BARS = 1 << 1;

    /**
     * Changes the foreground color for the light top bar so that the items on the bar can be read
     * clearly.
     */
    int APPEARANCE_LIGHT_TOP_BAR = 1 << 2;

    /**
     * Changes the foreground color for the light side bars so that the items on the bar can be read
     * clearly.
     */
    int APPEARANCE_LIGHT_SIDE_BARS = 1 << 3;

    /** Determines the appearance of system bars. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {APPEARANCE_OPAQUE_BARS, APPEARANCE_LOW_PROFILE_BARS,
            APPEARANCE_LIGHT_TOP_BAR, APPEARANCE_LIGHT_SIDE_BARS})
    @interface Appearance {
    }

    /**
     * The default option for {@link #setSystemBarsBehavior(int)}. The side bars will be forcibly
     * shown by the system on any user interaction on the corresponding display if the side bars are
     * hidden by {@link #hide(int)} or {@link WindowInsetsAnimationController#changeInsets(Insets)}.
     */
    int BEHAVIOR_SHOW_SIDE_BARS_BY_TOUCH = 0;

    /**
     * Option for {@link #setSystemBarsBehavior(int)}: Window would like to remain interactive when
     * hiding the side bars by calling {@link #hide(int)} or
     * {@link WindowInsetsAnimationController#changeInsets(Insets)}.
     *
     * <p>When system bars are hidden in this mode, they can be revealed with system gestures, such
     * as swiping from the edge of the screen where the bar is hidden from.</p>
     */
    int BEHAVIOR_SHOW_BARS_BY_SWIPE = 1;

    /**
     * Option for {@link #setSystemBarsBehavior(int)}: Window would like to remain interactive when
     * hiding the side bars by calling {@link #hide(int)} or
     * {@link WindowInsetsAnimationController#changeInsets(Insets)}.
     *
     * <p>When system bars are hidden in this mode, they can be revealed temporarily with system
     * gestures, such as swiping from the edge of the screen where the bar is hidden from. These
     * transient system bars will overlay app’s content, may have some degree of transparency, and
     * will automatically hide after a short timeout.</p>
     */
    int BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;

    /** Determines the behavior of system bars when hiding them by calling {@link #hide}. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {BEHAVIOR_SHOW_SIDE_BARS_BY_TOUCH, BEHAVIOR_SHOW_BARS_BY_SWIPE,
            BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE})
    @interface Behavior {
    }

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

    /**
     * Controls the appearance of system bars.
     *
     * @param appearance Bitmask of {@link Appearance} flags.
     * @see Appearance
     */
    void setSystemBarsAppearance(@Appearance int appearance);

    /**
     * Controls the behavior of system bars.
     *
     * @param behavior Determines how the bars behave when being hidden by the application.
     * @see Behavior
     */
    void setSystemBarsBehavior(@Behavior int behavior);
}
