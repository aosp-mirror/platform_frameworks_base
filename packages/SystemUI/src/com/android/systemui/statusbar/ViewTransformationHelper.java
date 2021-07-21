/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.notification.TransformState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

import java.util.Stack;

/**
 * A view that can be transformed to and from.
 */
public class ViewTransformationHelper implements TransformableView,
        TransformState.TransformInfo {

    private static final int TAG_CONTAINS_TRANSFORMED_VIEW = R.id.contains_transformed_view;

    private ArrayMap<Integer, View> mTransformedViews = new ArrayMap<>();
    private ArraySet<Integer> mKeysTransformingToSimilar = new ArraySet<>();
    private ArrayMap<Integer, CustomTransformation> mCustomTransformations = new ArrayMap<>();
    private ValueAnimator mViewTransformationAnimation;

    public void addTransformedView(int key, View transformedView) {
        mTransformedViews.put(key, transformedView);
    }

    public void addTransformedView(View transformedView) {
        int key = transformedView.getId();
        if (key == View.NO_ID) {
            throw new IllegalArgumentException("View argument does not have a valid id");
        }
        addTransformedView(key, transformedView);
    }

    /**
     * Add a view that transforms to a similar sibling, meaning that we should consider any mapping
     * found treated as the same viewType. This is useful for imageViews, where it's hard to compare
     * if the source images are the same when they are bitmap based.
     *
     * @param key The key how this is added
     * @param transformedView the view that is added
     */
    public void addViewTransformingToSimilar(int key, View transformedView) {
        addTransformedView(key, transformedView);
        mKeysTransformingToSimilar.add(key);
    }

    public void addViewTransformingToSimilar(View transformedView) {
        int key = transformedView.getId();
        if (key == View.NO_ID) {
            throw new IllegalArgumentException("View argument does not have a valid id");
        }
        addViewTransformingToSimilar(key, transformedView);
    }

    public void reset() {
        mTransformedViews.clear();
        mKeysTransformingToSimilar.clear();
    }

    public void setCustomTransformation(CustomTransformation transformation, int viewType) {
        mCustomTransformations.put(viewType, transformation);
    }

    @Override
    public TransformState getCurrentState(int fadingView) {
        View view = mTransformedViews.get(fadingView);
        if (view != null && view.getVisibility() != View.GONE) {
            TransformState transformState = TransformState.createFrom(view, this);
            if (mKeysTransformingToSimilar.contains(fadingView)) {
                transformState.setIsSameAsAnyView(true);
            }
            return transformState;
        }
        return null;
    }

    @Override
    public void transformTo(final TransformableView notification, final Runnable endRunnable) {
        if (mViewTransformationAnimation != null) {
            mViewTransformationAnimation.cancel();
        }
        mViewTransformationAnimation = ValueAnimator.ofFloat(0.0f, 1.0f);
        mViewTransformationAnimation.addUpdateListener(
                animation -> transformTo(notification, animation.getAnimatedFraction()));
        mViewTransformationAnimation.setInterpolator(Interpolators.LINEAR);
        mViewTransformationAnimation.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        mViewTransformationAnimation.addListener(new AnimatorListenerAdapter() {
            public boolean mCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled) {
                    if (endRunnable != null) {
                        endRunnable.run();
                    }
                    setVisible(false);
                    mViewTransformationAnimation = null;
                } else {
                    abortTransformations();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }
        });
        mViewTransformationAnimation.start();
    }

    @Override
    public void transformTo(TransformableView notification, float transformationAmount) {
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                CustomTransformation customTransformation = mCustomTransformations.get(viewType);
                if (customTransformation != null && customTransformation.transformTo(
                        ownState, notification, transformationAmount)) {
                    ownState.recycle();
                    continue;
                }
                TransformState otherState = notification.getCurrentState(viewType);
                if (otherState != null) {
                    ownState.transformViewTo(otherState, transformationAmount);
                    otherState.recycle();
                } else {
                    ownState.disappear(transformationAmount, notification);
                }
                ownState.recycle();
            }
        }
    }

    @Override
    public void transformFrom(final TransformableView notification) {
        if (mViewTransformationAnimation != null) {
            mViewTransformationAnimation.cancel();
        }
        mViewTransformationAnimation = ValueAnimator.ofFloat(0.0f, 1.0f);
        mViewTransformationAnimation.addUpdateListener(
                animation -> transformFrom(notification, animation.getAnimatedFraction()));
        mViewTransformationAnimation.addListener(new AnimatorListenerAdapter() {
            public boolean mCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled) {
                    setVisible(true);
                } else {
                    abortTransformations();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }
        });
        mViewTransformationAnimation.setInterpolator(Interpolators.LINEAR);
        mViewTransformationAnimation.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        mViewTransformationAnimation.start();
    }

    @Override
    public void transformFrom(TransformableView notification, float transformationAmount) {
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                CustomTransformation customTransformation = mCustomTransformations.get(viewType);
                if (customTransformation != null && customTransformation.transformFrom(
                        ownState, notification, transformationAmount)) {
                    ownState.recycle();
                    continue;
                }
                TransformState otherState = notification.getCurrentState(viewType);
                if (otherState != null) {
                    ownState.transformViewFrom(otherState, transformationAmount);
                    otherState.recycle();
                } else {
                    ownState.appear(transformationAmount, notification);
                }
                ownState.recycle();
            }
        }
    }

    @Override
    public void setVisible(boolean visible) {
        if (mViewTransformationAnimation != null) {
            mViewTransformationAnimation.cancel();
        }
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                ownState.setVisible(visible, false /* force */);
                ownState.recycle();
            }
        }
    }

    private void abortTransformations() {
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                ownState.abortTransformation();
                ownState.recycle();
            }
        }
    }

    /**
     * Add the remaining transformation views such that all views are being transformed correctly
     * @param viewRoot the root below which all elements need to be transformed
     */
    public void addRemainingTransformTypes(View viewRoot) {
        // lets now tag the right views
        int numValues = mTransformedViews.size();
        for (int i = 0; i < numValues; i++) {
            View view = mTransformedViews.valueAt(i);
            while (view != viewRoot.getParent()) {
                view.setTag(TAG_CONTAINS_TRANSFORMED_VIEW, true);
                view = (View) view.getParent();
            }
        }
        Stack<View> stack = new Stack<>();
        // Add the right views now
        stack.push(viewRoot);
        while (!stack.isEmpty()) {
            View child = stack.pop();
            Boolean containsView = (Boolean) child.getTag(TAG_CONTAINS_TRANSFORMED_VIEW);
            if (containsView == null) {
                // This one is unhandled, let's add it to our list.
                int id = child.getId();
                if (id != View.NO_ID) {
                    // We only fade views with an id
                    addTransformedView(id, child);
                    continue;
                }
            }
            child.setTag(TAG_CONTAINS_TRANSFORMED_VIEW, null);
            if (child instanceof ViewGroup && !mTransformedViews.containsValue(child)){
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.push(group.getChildAt(i));
                }
            }
        }
    }

    public void resetTransformedView(View view) {
        TransformState state = TransformState.createFrom(view, this);
        state.setVisible(true /* visible */, true /* force */);
        state.recycle();
    }

    /**
     * @return a set of all views are being transformed.
     */
    public ArraySet<View> getAllTransformingViews() {
        return new ArraySet<>(mTransformedViews.values());
    }

    @Override
    public boolean isAnimating() {
        return mViewTransformationAnimation != null && mViewTransformationAnimation.isRunning();
    }

    public static abstract class CustomTransformation {
        /**
         * Transform a state to the given view
         * @param ownState the state to transform
         * @param notification the view to transform to
         * @param transformationAmount how much transformation should be done
         * @return whether a custom transformation is performed
         */
        public abstract boolean transformTo(TransformState ownState,
                TransformableView notification,
                float transformationAmount);

        /**
         * Transform to this state from the given view
         * @param ownState the state to transform to
         * @param notification the view to transform from
         * @param transformationAmount how much transformation should be done
         * @return whether a custom transformation is performed
         */
        public abstract boolean transformFrom(TransformState ownState,
                TransformableView notification,
                float transformationAmount);

        /**
         * Perform a custom initialisation before transforming.
         *
         * @param ownState our own state
         * @param otherState the other state
         * @return whether a custom initialization is done
         */
        public boolean initTransformation(TransformState ownState,
                TransformState otherState) {
            return false;
        }

        public boolean customTransformTarget(TransformState ownState,
                TransformState otherState) {
            return false;
        }

        /**
         * Get a custom interpolator for this animation
         * @param interpolationType the type of the interpolation, i.e TranslationX / TranslationY
         * @param isFrom true if this transformation from the other view
         */
        public Interpolator getCustomInterpolator(int interpolationType, boolean isFrom) {
            return null;
        }
    }
}
