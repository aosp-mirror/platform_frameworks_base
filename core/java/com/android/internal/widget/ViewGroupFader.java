/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.widget;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.animation.BaseInterpolator;
import android.view.animation.PathInterpolator;

/**
 * This class is ported from
 * com.google.android.clockwork.common.wearable.wearmaterial.list.ViewGroupFader with minor
 * modifications set the opacity of the views during animation (uses setTransitionAlpha on the view
 * instead of setLayerType as the latter doesn't play nicely with a dialog. See - b/193583546)
 *
 * Fades of the children of a {@link ViewGroup} in and out, based on the position of the child.
 *
 * <p>Children are "faded" when they lie entirely in a region on the top and bottom of a {@link
 * ViewGroup}. This region is sized as a fraction of the {@link ViewGroup}'s height, based on the
 * height of the child. When not in the top or bottom regions, children have their default alpha and
 * scale.
 */
class ViewGroupFader {

    private static final float SCALE_LOWER_BOUND = 0.7f;
    private float mScaleLowerBound = SCALE_LOWER_BOUND;

    private static final float ALPHA_LOWER_BOUND = 0.5f;
    private float mAlphaLowerBound = ALPHA_LOWER_BOUND;

    private static final float CHAINED_BOUNDS_TOP_FRACTION = 0.6f;
    private static final float CHAINED_BOUNDS_BOTTOM_FRACTION = 0.2f;
    private static final float CHAINED_LOWER_REGION_FRACTION = 0.35f;
    private static final float CHAINED_UPPER_REGION_FRACTION = 0.55f;

    private float mChainedBoundsTop = CHAINED_BOUNDS_TOP_FRACTION;
    private float mChainedBoundsBottom = CHAINED_BOUNDS_BOTTOM_FRACTION;
    private float mChainedLowerRegion = CHAINED_LOWER_REGION_FRACTION;
    private float mChainedUpperRegion = CHAINED_UPPER_REGION_FRACTION;

    protected final ViewGroup mParent;

    private final Rect mContainerBounds = new Rect();
    private final Rect mOffsetViewBounds = new Rect();
    private final AnimationCallback mCallback;
    private final ChildViewBoundsProvider mChildViewBoundsProvider;

    private ContainerBoundsProvider mContainerBoundsProvider;
    private float mTopBoundPixels;
    private float mBottomBoundPixels;
    private BaseInterpolator mTopInterpolator = new PathInterpolator(0.3f, 0f, 0.7f, 1f);
    private BaseInterpolator mBottomInterpolator = new PathInterpolator(0.3f, 0f, 0.7f, 1f);

    /** Callback which is called when attempting to fade a view. */
    interface AnimationCallback {
        boolean shouldFadeFromTop(View view);

        boolean shouldFadeFromBottom(View view);

        void viewHasBecomeFullSize(View view);
    }

    /**
     * Interface for providing the bounds of the child views. This is needed because for
     * RecyclerViews, we might need to use bounds that represents the post-layout position, instead
     * of the current position.
     */
    // TODO(b/182846214): Clean up the interface design to avoid exposing too much details to users.
    interface ChildViewBoundsProvider {
        /**
         * Provide the bounds of the child view.
         *
         * @param parent the parent container.
         * @param child the child view.
         * @param bounds the bounds of the child view. The bounds are relative to
         * the value of the bounds for setContainerBoundsProvider. By default,
         * this is relative to the screen.
         */
        void provideBounds(ViewGroup parent, View child, Rect bounds);
    }

    /** Interface for providing the bounds of the container for use in calculating item fades. */
    interface ContainerBoundsProvider {
        /**
         * Provide the bounds of the container for use in calculating item fades.
         *
         * @param parent the parent of the container.
         * @param bounds the baseline bounds to which the child bounds are relative.
         */
        void provideBounds(ViewGroup parent, Rect bounds);
    }

    /**
     * Implementation of {@link ContainerBoundsProvider} that returns the screen bounds as the
     * container that is used for calculating the animation of the child elements in the ViewGroup.
     */
    static final class ScreenContainerBoundsProvider implements ContainerBoundsProvider {
        @Override
        public void provideBounds(ViewGroup parent, Rect bounds) {
            bounds.set(
                    0,
                    0,
                    parent.getResources().getDisplayMetrics().widthPixels,
                    parent.getResources().getDisplayMetrics().heightPixels);
        }
    }

    /**
     * Implementation of {@link ContainerBoundsProvider} that returns the parent ViewGroup bounds as
     * the container that is used for calculating the animation of the child elements in the
     * ViewGroup.
     */
    static final class ParentContainerBoundsProvider implements ContainerBoundsProvider {
        @Override
        public void provideBounds(ViewGroup parent, Rect bounds) {
            parent.getGlobalVisibleRect(bounds);
        }
    }

    /**
     * Default implementation of {@link ChildViewBoundsProvider} that returns the post-layout
     * bounds of the child view. This should be used when the {@link ViewGroupFader} is used
     * together with a RecyclerView.
     */
    static final class DefaultViewBoundsProvider implements ChildViewBoundsProvider {
        @Override
        public void provideBounds(ViewGroup parent, View child, Rect bounds) {
            child.getDrawingRect(bounds);
            bounds.offset(0, (int) child.getTranslationY());
            parent.offsetDescendantRectToMyCoords(child, bounds);

            // Additionally offset the bounds based on parent container's absolute position.
            Rect parentGlobalVisibleBounds = new Rect();
            parent.getGlobalVisibleRect(parentGlobalVisibleBounds);
            bounds.offset(parentGlobalVisibleBounds.left, parentGlobalVisibleBounds.top);
        }
    }

    /**
     * Implementation of {@link ChildViewBoundsProvider} that returns the global visible bounds of
     * the child view. This should be used when the {@link ViewGroupFader} is not used together with
     * a RecyclerView.
     */
    static final class GlobalVisibleViewBoundsProvider implements ChildViewBoundsProvider {
        @Override
        public void provideBounds(ViewGroup parent, View child, Rect bounds) {
            // Get the absolute position of the child. Normally we'd need to also reset the
            // transformation matrix before computing this, but the transformations we apply set
            // a pivot that preserves the coordinate of the top/bottom boundary used to compute the
            // scaling factor in the first place.
            child.getGlobalVisibleRect(bounds);
        }
    }

    ViewGroupFader(
            ViewGroup parent,
            AnimationCallback callback,
            ChildViewBoundsProvider childViewBoundsProvider) {
        this.mParent = parent;
        this.mCallback = callback;
        this.mChildViewBoundsProvider = childViewBoundsProvider;
        this.mContainerBoundsProvider = new ScreenContainerBoundsProvider();
    }

    AnimationCallback getAnimationCallback() {
        return mCallback;
    }

    /**
     * Sets the lower bound of the scale the view can reach, on a scale of 0 to 1.
     *
     * @param scale the value for the lower bound of the scale.
     */
    void setScaleLowerBound(float scale) {
        mScaleLowerBound = scale;
    }

    /**
     * Sets the lower bound of the alpha the view can reach, on a scale of 0 to 1.
     *
     * @param alpha the value for the lower bound of the alpha.
     */
    void setAlphaLowerBound(float alpha) {
        mAlphaLowerBound = alpha;
    }

    void setTopInterpolator(BaseInterpolator interpolator) {
        this.mTopInterpolator = interpolator;
    }

    void setBottomInterpolator(BaseInterpolator interpolator) {
        this.mBottomInterpolator = interpolator;
    }

    void setContainerBoundsProvider(ContainerBoundsProvider boundsProvider) {
        this.mContainerBoundsProvider = boundsProvider;
    }

    void updateFade() {
        mContainerBoundsProvider.provideBounds(mParent, mContainerBounds);
        mTopBoundPixels = mContainerBounds.height() * mChainedBoundsTop;
        mBottomBoundPixels = mContainerBounds.height() * mChainedBoundsBottom;

        updateListElementFades(mParent, true);
    }

    /** For each list element, calculate and adjust the scale and alpha based on its position */
    private void updateListElementFades(ViewGroup parent, boolean shouldFade) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (shouldFade) {
                fadeElement(parent, child);
            }
        }
    }

    private void fadeElement(ViewGroup parent, View child) {
        mChildViewBoundsProvider.provideBounds(parent, child, mOffsetViewBounds);
        setViewPropertiesByPosition(child, mOffsetViewBounds, mTopBoundPixels, mBottomBoundPixels);
    }

    /** Set the bounds and change the view's scale and alpha accordingly */
    private void setViewPropertiesByPosition(
            View view, Rect bounds, float topBoundPixels, float bottomBoundPixels) {
        float fadeOutRegionFraction;
        if (view.getHeight() < topBoundPixels && view.getHeight() > bottomBoundPixels) {
            // Scale from LOWER_REGION_FRACTION to UPPER_REGION_FRACTION based on the ratio of view
            // height to chain region height.
            fadeOutRegionFraction = lerp(
                    mChainedLowerRegion,
                    mChainedUpperRegion,
                    (view.getHeight() - bottomBoundPixels) / (topBoundPixels - bottomBoundPixels));
        } else if (view.getHeight() < bottomBoundPixels) {
            fadeOutRegionFraction = mChainedLowerRegion;
        } else {
            fadeOutRegionFraction = mChainedUpperRegion;
        }
        int fadeOutRegionHeight = (int) (mContainerBounds.height() * fadeOutRegionFraction);
        int topFadeBoundary = fadeOutRegionHeight + mContainerBounds.top;
        int bottomFadeBoundary = mContainerBounds.bottom - fadeOutRegionHeight;
        boolean wasFullSize = (view.getScaleX() == 1);

        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        view.setPivotX(view.getWidth() * 0.5f);
        if (bounds.top > bottomFadeBoundary && mCallback.shouldFadeFromBottom(view)) {
            view.setPivotY((float) -lp.topMargin);
            scaleAndFadeByRelativeOffsetFraction(
                    view,
                    mBottomInterpolator.getInterpolation(
                            (float) (mContainerBounds.bottom - bounds.top) / fadeOutRegionHeight));
        } else if (bounds.bottom < topFadeBoundary && mCallback.shouldFadeFromTop(view)) {
            view.setPivotY(view.getMeasuredHeight() + (float) lp.bottomMargin);
            scaleAndFadeByRelativeOffsetFraction(
                    view,
                    mTopInterpolator.getInterpolation(
                            (float) (bounds.bottom - mContainerBounds.top) / fadeOutRegionHeight));
        } else {
            if (!wasFullSize) {
                mCallback.viewHasBecomeFullSize(view);
            }
            setDefaultSizeAndAlphaForView(view);
        }
    }

    /**
     * Change the scale and opacity of the view based on its offset fraction to the
     * determining bound.
     */
    private void scaleAndFadeByRelativeOffsetFraction(View view, float offsetFraction) {
        float alpha = lerp(mAlphaLowerBound, 1, offsetFraction);
        view.setTransitionAlpha(alpha);
        float scale = lerp(mScaleLowerBound, 1, offsetFraction);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    /** Set the scale and alpha of the view to the full default */
    private void setDefaultSizeAndAlphaForView(View view) {
        view.setTransitionAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    /**
     * Linear interpolation between [min, max] using [fraction].
     *
     * @param min   the starting point of the interpolation range.
     * @param max   the ending point of the interpolation range.
     * @param fraction the proportion of the range to linearly interpolate for.
     * @return the interpolated value.
     */
    private static float lerp(float min, float max, float fraction) {
        return min + (max - min) * fraction;
    }
}
