/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.animation.Interpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface that allows the application to listen to animation events for windows that cause
 * insets.
 */
public interface WindowInsetsAnimationCallback {

    /**
     * Return value for {@link #getDispatchMode()}: Dispatching of animation events should
     * stop at this level in the view hierarchy, and no animation events should be dispatch to the
     * subtree of the view hierarchy.
     */
    int DISPATCH_MODE_STOP = 0;

    /**
     * Return value for {@link #getDispatchMode()}: Dispatching of animation events should
     * continue in the view hierarchy.
     */
    int DISPATCH_MODE_CONTINUE_ON_SUBTREE = 1;

    /** @hide */
    @IntDef(prefix = { "DISPATCH_MODE_" }, value = {
            DISPATCH_MODE_STOP,
            DISPATCH_MODE_CONTINUE_ON_SUBTREE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DispatchMode {}

    /**
     * Retrieves the dispatch mode of this listener. Dispatch of the all animation events is
     * hierarchical: It will starts at the root of the view hierarchy and then traverse it and
     * invoke the callback of the specific {@link View} that is being traversed.
     * The method may return either {@link #DISPATCH_MODE_CONTINUE_ON_SUBTREE} to indicate that
     * animation events should be propagated to the subtree of the view hierarchy, or
     * {@link #DISPATCH_MODE_STOP} to stop dispatching. In that case, all animation callbacks
     * related to the animation passed in will be stopped from propagating to the subtree of the
     * hierarchy.
     * <p>
     * Note that this method will only be invoked once when
     * {@link View#setWindowInsetsAnimationCallback setting the listener} and then the framework
     * will use the recorded result.
     * <p>
     * Also note that returning {@link #DISPATCH_MODE_STOP} here behaves the same way as returning
     * {@link WindowInsets#CONSUMED} during the regular insets dispatch in
     * {@link View#onApplyWindowInsets}.
     *
     * @return Either {@link #DISPATCH_MODE_CONTINUE_ON_SUBTREE} to indicate that dispatching of
     *         animation events will continue to the subtree of the view hierarchy, or
     *         {@link #DISPATCH_MODE_STOP} to indicate that animation events will stop dispatching.
     */
    @DispatchMode
    int getDispatchMode();

    /**
     * Called when an insets animation is about to start and before the views have been laid out in
     * the end state of the animation. The ordering of events during an insets animation is the
     * following:
     * <p>
     * <ul>
     *     <li>Application calls {@link WindowInsetsController#hideInputMethod()},
     *     {@link WindowInsetsController#showInputMethod()},
     *     {@link WindowInsetsController#controlInputMethodAnimation}</li>
     *     <li>onPrepare is called on the view hierarchy listeners</li>
     *     <li>{@link View#onApplyWindowInsets} will be called with the end state of the
     *     animation</li>
     *     <li>View hierarchy gets laid out according to the changes the application has requested
     *     due to the new insets being dispatched</li>
     *     <li>{@link #onStart} is called <em>before</em> the view
     *     hierarchy gets drawn in the new laid out state</li>
     *     <li>{@link #onProgress} is called immediately after with the animation start state</li>
     *     <li>The frame gets drawn.</li>
     * </ul>
     * <p>
     * This ordering allows the application to inspect the end state after the animation has
     * finished, and then revert to the starting state of the animation in the first
     * {@link #onProgress} callback by using post-layout view properties like {@link View#setX} and
     * related methods.
     * <p>
     * Note: If the animation is application controlled by using
     * {@link WindowInsetsController#controlInputMethodAnimation}, the end state of the animation
     * is undefined as the application may decide on the end state only by passing in the
     * {@code shown} parameter when calling {@link WindowInsetsAnimationController#finish}. In this
     * situation, the system will dispatch the insets in the opposite visibility state before the
     * animation starts. Example: When controlling the input method with
     * {@link WindowInsetsController#controlInputMethodAnimation} and the input method is currently
     * showing, {@link View#onApplyWindowInsets} will receive a {@link WindowInsets} instance for
     * which {@link WindowInsets#isVisible} will return {@code false} for {@link Type#ime}.
     *
     * @param animation The animation that is about to start.
     */
    default void onPrepare(@NonNull InsetsAnimation animation) {
    }

    /**
     * Called when an insets animation gets started.
     * <p>
     * Note that, like {@link #onProgress}, dispatch of the animation start event is hierarchical:
     * It will starts at the root of the view hierarchy and then traverse it and invoke the callback
     * of the specific {@link View} that is being traversed. The method may return a modified
     * instance of the bounds by calling {@link AnimationBounds#inset} to indicate that a part of
     * the insets have been used to offset or clip its children, and the children shouldn't worry
     * about that part anymore. Furthermore, if {@link #getDispatchMode()} returns
     * {@link #DISPATCH_MODE_STOP}, children of this view will not receive the callback anymore.
     *
     * @param animation The animation that is about to start.
     * @param bounds The bounds in which animation happens.
     * @return The animation representing the part of the insets that should be dispatched to the
     *         subtree of the hierarchy.
     */
    @NonNull
    default AnimationBounds onStart(
            @NonNull InsetsAnimation animation, @NonNull AnimationBounds bounds) {
        return bounds;
    }

    /**
     * Called when the insets change as part of running an animation. Note that even if multiple
     * animations for different types are running, there will only be one progress callback per
     * frame. The {@code insets} passed as an argument represents the overall state and will include
     * all types, regardless of whether they are animating or not.
     * <p>
     * Note that insets dispatch is hierarchical: It will start at the root of the view hierarchy,
     * and then traverse it and invoke the callback of the specific {@link View} being traversed.
     * The method may return a modified instance by calling
     * {@link WindowInsets#inset(int, int, int, int)} to indicate that a part of the insets have
     * been used to offset or clip its children, and the children shouldn't worry about that part
     * anymore. Furthermore, if {@link #getDispatchMode()} returns
     * {@link #DISPATCH_MODE_STOP}, children of this view will not receive the callback anymore.
     *
     * TODO: Introduce a way to map (type -> InsetAnimation) so app developer can query animation
     *  for a given type e.g. callback.getAnimation(type) OR controller.getAnimation(type).
     *  Or on the controller directly?
     * @param insets The current insets.
     * @return The insets to dispatch to the subtree of the hierarchy.
     */
    @NonNull
    WindowInsets onProgress(@NonNull WindowInsets insets);

    /**
     * Called when an insets animation has finished.
     *
     * @param animation The animation that has finished running. This will be the same instance as
     *                  passed into {@link #onStart}
     */
    default void onFinish(@NonNull InsetsAnimation animation) {
    }

    /**
     * Class representing an animation of a set of windows that cause insets.
     */
    final class InsetsAnimation {

        private final @InsetsType int mTypeMask;
        private float mFraction;
        @Nullable private final Interpolator mInterpolator;
        private final long mDurationMillis;
        private float mAlpha;

        /**
         * Creates a new {@link InsetsAnimation} object.
         * <p>
         * This should only be used for testing, as usually the system creates this object for the
         * application to listen to with {@link WindowInsetsAnimationCallback}.
         * </p>
         * @param typeMask The bitmask of {@link WindowInsets.Type}s that are animating.
         * @param interpolator The interpolator of the animation.
         * @param durationMillis The duration of the animation in
         *                   {@link java.util.concurrent.TimeUnit#MILLISECONDS}.
         */
        public InsetsAnimation(
                @InsetsType int typeMask, @Nullable Interpolator interpolator,
                long durationMillis) {
            mTypeMask = typeMask;
            mInterpolator = interpolator;
            mDurationMillis = durationMillis;
        }

        /**
         * @return The bitmask of {@link WindowInsets.Type.InsetsType}s that are animating.
         */
        public @InsetsType int getTypeMask() {
            return mTypeMask;
        }

        /**
         * Returns the raw fractional progress of this animation between
         * start state of the animation and the end state of the animation. Note
         * that this progress is the global progress of the animation, whereas
         * {@link WindowInsetsAnimationCallback#onProgress} will only dispatch the insets that may
         * be inset with {@link WindowInsets#inset} by parents of views in the hierarchy.
         * Progress per insets animation is global for the entire animation. One animation animates
         * all things together (in, out, ...). If they don't animate together, we'd have
         * multiple animations.
         * <p>
         * Note: In case the application is controlling the animation, the valued returned here will
         * be the same as the application passed into
         * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)}.
         * </p>
         * @return The current progress of this animation.
         */
        @FloatRange(from = 0f, to = 1f)
        public float getFraction() {
            return mFraction;
        }

        /**
         * Returns the interpolated fractional progress of this animation between
         * start state of the animation and the end state of the animation. Note
         * that this progress is the global progress of the animation, whereas
         * {@link WindowInsetsAnimationCallback#onProgress} will only dispatch the insets that may
         * be inset with {@link WindowInsets#inset} by parents of views in the hierarchy.
         * Progress per insets animation is global for the entire animation. One animation animates
         * all things together (in, out, ...). If they don't animate together, we'd have
         * multiple animations.
         * <p>
         * Note: In case the application is controlling the animation, the valued returned here will
         * be the same as the application passed into
         * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)},
         * interpolated with the interpolator passed into
         * {@link WindowInsetsController#controlInputMethodAnimation}.
         * </p>
         * <p>
         * Note: For system-initiated animations, this will always return a valid value between 0
         * and 1.
         * </p>
         * @see #getFraction() for raw fraction.
         * @return The current interpolated progress of this animation. -1 if interpolator isn't
         *         specified.
         */
        public float getInterpolatedFraction() {
            if (mInterpolator != null) {
                return mInterpolator.getInterpolation(mFraction);
            }
            return -1;
        }

        /**
         * Retrieves the interpolator used for this animation, or {@code null} if this animation
         * doesn't follow an interpolation curved. For system-initiated animations, this will never
         * return {@code null}.
         *
         * @return The interpolator used for this animation.
         */
        @Nullable
        public Interpolator getInterpolator() {
            return mInterpolator;
        }

        /**
         * @return duration of animation in {@link java.util.concurrent.TimeUnit#MILLISECONDS}, or
         *         -1 if the animation doesn't have a fixed duration.
         */
        public long getDurationMillis() {
            return mDurationMillis;
        }

        /**
         * Set fraction of the progress if {@link WindowInsets.Type.InsetsType} animation is
         * controlled by the app.
         * <p>
         * Note: This should only be used for testing, as the system fills in the fraction for the
         * application or the fraction that was passed into
         * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)} is being
         * used.
         * </p>
         * @param fraction fractional progress between 0 and 1 where 0 represents hidden and
         *                zero progress and 1 represent fully shown final state.
         * @see #getFraction()
         */
        public void setFraction(@FloatRange(from = 0f, to = 1f) float fraction) {
            mFraction = fraction;
        }

        /**
         * Retrieves the translucency of the windows that are animating.
         *
         * @return Alpha of windows that cause insets of type {@link WindowInsets.Type.InsetsType}.
         */
        @FloatRange(from = 0f, to = 1f)
        public float getAlpha() {
            return mAlpha;
        }

        /**
         * Sets the translucency of the windows that are animating.
         * <p>
         * Note: This should only be used for testing, as the system fills in the alpha for the
         * application or the alpha that was passed into
         * {@link WindowInsetsAnimationController#setInsetsAndAlpha(Insets, float, float)} is being
         * used.
         * </p>
         * @param alpha Alpha of windows that cause insets of type
         *              {@link WindowInsets.Type.InsetsType}.
         * @see #getAlpha()
         */
        public void setAlpha(@FloatRange(from = 0f, to = 1f) float alpha) {
            mAlpha = alpha;
        }
    }

    /**
     * Class representing the range of an {@link InsetsAnimation}
     */
    final class AnimationBounds {

        private final Insets mLowerBound;
        private final Insets mUpperBound;

        public AnimationBounds(@NonNull Insets lowerBound, @NonNull Insets upperBound) {
            mLowerBound = lowerBound;
            mUpperBound = upperBound;
        }

        /**
         * Queries the lower inset bound of the animation. If the animation is about showing or
         * hiding a window that cause insets, the lower bound is {@link Insets#NONE} and the upper
         * bound is the same as {@link WindowInsets#getInsets(int)} for the fully shown state. This
         * is the same as {@link WindowInsetsAnimationController#getHiddenStateInsets} and
         * {@link WindowInsetsAnimationController#getShownStateInsets} in case the listener gets
         * invoked because of an animation that originates from
         * {@link WindowInsetsAnimationController}.
         * <p>
         * However, if the size of a window that causes insets is changing, these are the
         * lower/upper bounds of that size animation.
         * </p>
         * There are no overlapping animations for a specific type, but there may be multiple
         * animations running at the same time for different inset types.
         *
         * @see #getUpperBound()
         * @see WindowInsetsAnimationController#getHiddenStateInsets
         */
        @NonNull
        public Insets getLowerBound() {
            return mLowerBound;
        }

        /**
         * Queries the upper inset bound of the animation. If the animation is about showing or
         * hiding a window that cause insets, the lower bound is {@link Insets#NONE}
         * nd the upper bound is the same as {@link WindowInsets#getInsets(int)} for the fully
         * shown state. This is the same as
         * {@link WindowInsetsAnimationController#getHiddenStateInsets} and
         * {@link WindowInsetsAnimationController#getShownStateInsets} in case the listener gets
         * invoked because of an animation that originates from
         * {@link WindowInsetsAnimationController}.
         * <p>
         * However, if the size of a window that causes insets is changing, these are the
         * lower/upper bounds of that size animation.
         * <p>
         * There are no overlapping animations for a specific type, but there may be multiple
         * animations running at the same time for different inset types.
         *
         * @see #getLowerBound()
         * @see WindowInsetsAnimationController#getShownStateInsets
         */
        @NonNull
        public Insets getUpperBound() {
            return mUpperBound;
        }

        /**
         * Insets both the lower and upper bound by the specified insets. This is to be used in
         * {@link WindowInsetsAnimationCallback#onStart} to indicate that a part of the insets has
         * been used to offset or clip its children, and the children shouldn't worry about that
         * part anymore.
         *
         * @param insets The amount to inset.
         * @return A copy of this instance inset in the given directions.
         * @see WindowInsets#inset
         * @see WindowInsetsAnimationCallback#onStart
         */
        @NonNull
        public AnimationBounds inset(@NonNull Insets insets) {
            return new AnimationBounds(
                    // TODO: refactor so that WindowInsets.insetInsets() is in a more appropriate
                    //  place eventually.
                    WindowInsets.insetInsets(
                            mLowerBound, insets.left, insets.top, insets.right, insets.bottom),
                    WindowInsets.insetInsets(
                            mUpperBound, insets.left, insets.top, insets.right, insets.bottom));
        }
    }
}
