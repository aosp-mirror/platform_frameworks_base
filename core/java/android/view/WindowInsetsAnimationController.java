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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.Insets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;

/**
 * Controller for app-driven animation of system windows.
 *  <p>
 *  {@code WindowInsetsAnimationController} lets apps animate system windows such as
 *  the {@link android.inputmethodservice.InputMethodService IME}. The animation is
 *  synchronized, such that changes the system windows and the app's current frame
 *  are rendered at the same time.
 *  <p>
 *  Control is obtained through {@link WindowInsetsController#controlWindowInsetsAnimation}.
 */
@SuppressLint("NotCloseable")
public interface WindowInsetsAnimationController {

    /**
     * Retrieves the {@link Insets} when the windows this animation is controlling are fully hidden.
     * <p>
     * Note that these insets are always relative to the window, which is the same as being relative
     * to {@link View#getRootView}
     * <p>
     * If there are any animation listeners registered, this value is the same as
     * {@link Bounds#getLowerBound()} that is being be passed into the root view of the
     * hierarchy.
     *
     * @return Insets when the windows this animation is controlling are fully hidden.
     *
     * @see Bounds#getLowerBound()
     */
    @NonNull Insets getHiddenStateInsets();

    /**
     * Retrieves the {@link Insets} when the windows this animation is controlling are fully shown.
     * <p>
     * Note that these insets are always relative to the window, which is the same as being relative
     * to {@link View#getRootView}
     * <p>
     * If there are any animation listeners registered, this value is the same as
     * {@link Bounds#getUpperBound()} that is being passed into the root view of hierarchy.
     *
     * @return Insets when the windows this animation is controlling are fully shown.
     *
     * @see Bounds#getUpperBound()
     */
    @NonNull Insets getShownStateInsets();

    /**
     * Retrieves the current insets.
     * <p>
     * Note that these insets are always relative to the window, which is the same as
     * being relative
     * to {@link View#getRootView}
     * @return The current insets on the currently showing frame. These insets will change as the
     * animation progresses to reflect the current insets provided by the controlled window.
     */
    @NonNull Insets getCurrentInsets();

    /**
     *  Returns the progress as previously set by {@code fraction} in {@link #setInsetsAndAlpha}
     *
     *  @return the progress of the animation, where {@code 0} is fully hidden and {@code 1} is
     *  fully shown.
     * <p>
     *  Note: this value represents raw overall progress of the animation
     *  i.e. the combined progress of insets and alpha.
     *  <p>
     */
    @FloatRange(from = 0f, to = 1f)
    float getCurrentFraction();

    /**
     * Current alpha value of the window.
     * @return float value between 0 and 1.
     */
    float getCurrentAlpha();

    /**
     * @return The {@link InsetsType}s this object is currently controlling.
     */
    @InsetsType int getTypes();

    /**
     * Modifies the insets for the frame being drawn by indirectly moving the windows around in the
     * system that are causing window insets.
     * <p>
     * Note that these insets are always relative to the window, which is the same as being relative
     * to {@link View#getRootView}
     * <p>
     * Also note that this will <b>not</b> inform the view system of a full inset change via
     * {@link View#dispatchApplyWindowInsets} in order to avoid a full layout pass during the
     * animation. If you'd like to animate views during a window inset animation, register a
     * {@link WindowInsetsAnimation.Callback} by calling
     * {@link View#setWindowInsetsAnimationCallback(WindowInsetsAnimation.Callback)} that will be
     * notified about any insets change via {@link WindowInsetsAnimation.Callback#onProgress} during
     * the animation.
     * <p>
     * {@link View#dispatchApplyWindowInsets} will instead be called once the animation has
     * finished, i.e. once {@link #finish} has been called.
     * Note: If there are no insets, alpha animation is still applied.
     *
     * @param insets The new insets to apply. Based on the requested insets, the system will
     *               calculate the positions of the windows in the system causing insets such that
     *               the resulting insets of that configuration will match the passed in parameter.
     *               Note that these insets are being clamped to the range from
     *               {@link #getHiddenStateInsets} to {@link #getShownStateInsets}.
     *               If you intend on changing alpha only, pass null or {@link #getCurrentInsets()}.
     * @param alpha  The new alpha to apply to the inset side.
     * @param fraction instantaneous animation progress. This value is dispatched to
     *                 {@link WindowInsetsAnimation.Callback}.
     *
     * @see WindowInsetsAnimation.Callback
     * @see View#setWindowInsetsAnimationCallback(WindowInsetsAnimation.Callback)
     */
    void setInsetsAndAlpha(@Nullable Insets insets, @FloatRange(from = 0f, to = 1f) float alpha,
            @FloatRange(from = 0f, to = 1f) float fraction);

    /**
     * Finishes the animation, and leaves the windows shown or hidden.
     * <p>
     * After invoking {@link #finish}, this instance is no longer {@link #isReady ready}.
     * <p>
     * Note: Finishing an animation implicitly {@link #setInsetsAndAlpha sets insets and alpha}
     * according to the requested end state without any further animation.
     *
     * @param shown if {@code true}, the windows will be shown after finishing the
     *              animation. Otherwise they will be hidden.
     */
    void finish(boolean shown);

    /**
     * Returns whether this instance is ready to be used to control window insets.
     * <p>
     * Instances are ready when passed in {@link WindowInsetsAnimationControlListener#onReady}
     * and stop being ready when it is either {@link #isFinished() finished} or
     * {@link #isCancelled() cancelled}.
     *
     * @return {@code true} if the instance is ready, {@code false} otherwise.
     */
    default boolean isReady() {
        return !isFinished() && !isCancelled();
    }

    /**
     * Returns whether this instance has been finished by a call to {@link #finish}.
     *
     * @see WindowInsetsAnimationControlListener#onFinished
     * @return {@code true} if the instance is finished, {@code false} otherwise.
     */
    boolean isFinished();

    /**
     * Returns whether this instance has been cancelled by the system, or by invoking the
     * {@link android.os.CancellationSignal} passed into
     * {@link WindowInsetsController#controlWindowInsetsAnimation}.
     *
     * @see WindowInsetsAnimationControlListener#onCancelled
     * @return {@code true} if the instance is cancelled, {@code false} otherwise.
     */
    boolean isCancelled();

    /**
     * @hide
     * @return {@code true} when controller controls IME and IME has no insets (floating,
     *  fullscreen or non-overlapping).
     */
    boolean hasZeroInsetsIme();
}
