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

import android.graphics.Insets;

/**
 * Interface that allows the application to listen to animation events for windows that cause
 * insets.
 * @hide pending unhide
 */
public interface WindowInsetsAnimationListener {

    /**
     * Called when an inset animation gets started.
     *
     * @param animation The animation that is about to start.
     */
    void onStarted(InsetsAnimation animation);

    /**
     * Called when the insets change as part of running an animation. Note that even if multiple
     * animations for different types are running, there will only be one progress callback per
     * frame. The {@code insets} passed as an argument represents the overall state and will include
     * all types, regardless of whether they are animating or not.
     * <p>
     * Note that insets dispatch is hierarchical: It will start at the root of the view hierarchy,
     * and then traverse it and invoke the callback of the specific {@link View} being traversed.
     * The callback may return a modified instance by calling {@link WindowInsets#inset(int, int, int, int)}
     * to indicate that a part of the insets have been used to offset or clip its children, and the
     * children shouldn't worry about that part anymore.
     *
     * @param insets The current insets.
     * @return The insets to dispatch to the subtree of the hierarchy.
     */
    WindowInsets onProgress(WindowInsets insets);

    /**
     * Called when an inset animation has finished.
     *
     * @param animation The animation that has finished running.
     */
    void onFinished(InsetsAnimation animation);

    /**
     * Class representing an animation of a set of windows that cause insets.
     */
    class InsetsAnimation {

        private final @WindowInsets.Type.InsetType int mTypeMask;
        private final Insets mLowerBound;
        private final Insets mUpperBound;

        /**
         * @hide
         */
        InsetsAnimation(int typeMask, Insets lowerBound, Insets upperBound) {
            mTypeMask = typeMask;
            mLowerBound = lowerBound;
            mUpperBound = upperBound;
        }

        /**
         * @return The bitmask of {@link WindowInsets.Type.InsetType}s that are animating.
         */
        public @WindowInsets.Type.InsetType int getTypeMask() {
            return mTypeMask;
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
         * <p>
         * There are no overlapping animations for a specific type, but there may be two animations
         * running at the same time for different inset types.
         *
         * @see #getUpperBound()
         * @see WindowInsetsAnimationController#getHiddenStateInsets
         * TODO: It's a bit weird that these are global per window but onProgress is hierarchical.
         * TODO: If multiple types are animating, querying the bound per type isn't possible. Should
         * we:
         * 1. Offer bounds by type here?
         * 2. Restrict one animation to one single type only?
         * Returning WindowInsets here isn't feasible in case of overlapping animations: We can't
         * fill in the insets for the types from the other animation into the WindowInsets object
         * as it's changing as well.
         */
        public Insets getLowerBound() {
            return mLowerBound;
        }

        /**
         * @see #getLowerBound()
         * @see WindowInsetsAnimationController#getShownStateInsets
         */
        public Insets getUpperBound() {
            return mUpperBound;
        }
    }
}
