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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.animation.Interpolator;

/**
 * Interface that allows the application to listen to animation events for windows that cause
 * insets.
 */
public interface WindowInsetsAnimationCallback {

    /**
     * Called when an inset animation gets started.
     * <p>
     * Note that, like {@link #onProgress}, dispatch of the animation start event is hierarchical:
     * It will starts at the root of the view hierarchy and then traverse it and invoke the callback
     * of the specific {@link View} that is being traversed. The method my return a modified
     * instance of the bounds by calling {@link AnimationBounds#inset} to indicate that a part of
     * the insets have been used to offset or clip its children, and the children shouldn't worry
     * about that part anymore.
     *
     * @param animation The animation that is about to start.
     * @param bounds The bounds in which animation happens.
     * @return The animation representing the part of the insets that should be dispatched to the
     *         subtree of the hierarchy.
     */
    @NonNull
    default AnimationBounds onStarted(
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
     * anymore.
     * TODO: Introduce a way to map (type -> InsetAnimation) so app developer can query animation
     *  for a given type e.g. callback.getAnimation(type) OR controller.getAnimation(type).
     *  Or on the controller directly?
     * @param insets The current insets.
     * @return The insets to dispatch to the subtree of the hierarchy.
     */
    @NonNull
    WindowInsets onProgress(@NonNull WindowInsets insets);

    /**
     * Called when an inset animation has finished.
     *
     * @param animation The animation that has finished running. This will be the same instance as
     *                  passed into {@link #onStarted}
     */
    default void onFinished(@NonNull InsetsAnimation animation) {
    }

    /**
     * Class representing an animation of a set of windows that cause insets.
     */
    final class InsetsAnimation {

        private final @InsetsType int mTypeMask;
        private float mFraction;
        @Nullable private final Interpolator mInterpolator;
        private long mDurationMs;

        public InsetsAnimation(
                @InsetsType int typeMask, @Nullable Interpolator interpolator, long durationMs) {
            mTypeMask = typeMask;
            mInterpolator = interpolator;
            mDurationMs = durationMs;
        }

        /**
         * @return The bitmask of {@link WindowInsets.Type.InsetsType}s that are animating.
         */
        public @InsetsType int getTypeMask() {
            return mTypeMask;
        }

        /**
         * Returns the raw fractional progress of this animation between
         * {@link AnimationBounds#getLowerBound()} and {@link AnimationBounds#getUpperBound()}. Note
         * that this progress is the global progress of the animation, whereas
         * {@link WindowInsetsAnimationCallback#onProgress} will only dispatch the insets that may
         * be inset with {@link WindowInsets#inset} by parents of views in the hierarchy.
         * Progress per insets animation is global for the entire animation. One animation animates
         * all things together (in, out, ...). If they don't animate together, we'd have
         * multiple animations.
         *
         * @return The current progress of this animation.
         */
        @FloatRange(from = 0f, to = 1f)
        public float getFraction() {
            return mFraction;
        }

        /**
         * Returns the interpolated fractional progress of this animation between
         * {@link AnimationBounds#getLowerBound()} and {@link AnimationBounds#getUpperBound()}. Note
         * that this progress is the global progress of the animation, whereas
         * {@link WindowInsetsAnimationCallback#onProgress} will only dispatch the insets that may
         * be inset with {@link WindowInsets#inset} by parents of views in the hierarchy.
         * Progress per insets animation is global for the entire animation. One animation animates
         * all things together (in, out, ...). If they don't animate together, we'd have
         * multiple animations.
         * @see #getFraction() for raw fraction.
         * @return The current interpolated progress of this animation. -1 if interpolator isn't
         * specified.
         */
        public float getInterpolatedFraction() {
            if (mInterpolator != null) {
                return mInterpolator.getInterpolation(mFraction);
            }
            return -1;
        }

        @Nullable
        public Interpolator getInterpolator() {
            return mInterpolator;
        }

        /**
         * @return duration of animation in {@link java.util.concurrent.TimeUnit#MILLISECONDS}.
         */
        public long getDurationMillis() {
            return mDurationMs;
        }

        /**
         * Set fraction of the progress if {@link WindowInsets.Type.InsetsType} animation is
         * controlled by the app {@see #getCurrentFraction}.
         * <p>Note: If app didn't create {@link InsetsAnimation}, it shouldn't set progress either.
         * Progress would be set by system with the system-default animation.
         * </p>
         * @param fraction fractional progress between 0 and 1 where 0 represents hidden and
         *                zero progress and 1 represent fully shown final state.
         */
        public void setFraction(@FloatRange(from = 0f, to = 1f) float fraction) {
            mFraction = fraction;
        }

        /**
         * Set duration of the animation if {@link WindowInsets.Type.InsetsType} animation is
         * controlled by the app.
         * <p>Note: If app didn't create {@link InsetsAnimation}, it shouldn't set duration either.
         * Duration would be set by system with the system-default animation.
         * </p>
         * @param durationMs in {@link java.util.concurrent.TimeUnit#MILLISECONDS}
         */
        public void setDuration(long durationMs) {
            mDurationMs = durationMs;
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
         * {@link WindowInsetsAnimationCallback#onStarted} to indicate that a part of the insets has
         * been used to offset or clip its children, and the children shouldn't worry about that
         * part anymore.
         *
         * @param insets The amount to inset.
         * @return A copy of this instance inset in the given directions.
         * @see WindowInsets#inset
         * @see WindowInsetsAnimationCallback#onStarted
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
