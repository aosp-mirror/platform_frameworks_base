/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.lang.Math;

import com.android.internal.R;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StateSet;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.RemoteViews.RemoteView;

/**
 * A layout that switches between its children based on the requested layout height.
 * Each child specifies its minimum and maximum valid height.  Results are undefined
 * if children specify overlapping ranges.  A child may specify the maximum height
 * as 'unbounded' to indicate that it is willing to be displayed arbitrarily tall.
 *
 * <p>
 * See {@link SizeAdaptiveLayout.LayoutParams} for a full description of the
 * layout parameters used by SizeAdaptiveLayout.
 */
@RemoteView
public class SizeAdaptiveLayout extends ViewGroup {

    private static final String TAG = "SizeAdaptiveLayout";
    private static final boolean DEBUG = false;
    private static final boolean REPORT_BAD_BOUNDS = true;
    private static final long CROSSFADE_TIME = 250;

    // TypedArray indices
    private static final int MIN_VALID_HEIGHT =
            R.styleable.SizeAdaptiveLayout_Layout_layout_minHeight;
    private static final int MAX_VALID_HEIGHT =
            R.styleable.SizeAdaptiveLayout_Layout_layout_maxHeight;

    // view state
    private View mActiveChild;
    private View mLastActive;

    // animation state
    private AnimatorSet mTransitionAnimation;
    private AnimatorListener mAnimatorListener;
    private ObjectAnimator mFadePanel;
    private ObjectAnimator mFadeView;
    private int mCanceledAnimationCount;
    private View mEnteringView;
    private View mLeavingView;
    // View used to hide larger views under smaller ones to create a uniform crossfade
    private View mModestyPanel;
    private int mModestyPanelTop;

    public SizeAdaptiveLayout(Context context) {
        super(context);
        initialize();
    }

    public SizeAdaptiveLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public SizeAdaptiveLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initialize();
    }

    private void initialize() {
        mModestyPanel = new View(getContext());
        // If the SizeAdaptiveLayout has a solid background, use it as a transition hint.
        Drawable background = getBackground();
        if (background instanceof StateListDrawable) {
            StateListDrawable sld = (StateListDrawable) background;
            sld.setState(StateSet.WILD_CARD);
            background = sld.getCurrent();
        }
        if (background instanceof ColorDrawable) {
            mModestyPanel.setBackgroundDrawable(background);
        } else {
            mModestyPanel.setBackgroundColor(Color.BLACK);
        }
        SizeAdaptiveLayout.LayoutParams layout =
                new SizeAdaptiveLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT);
        mModestyPanel.setLayoutParams(layout);
        addView(mModestyPanel);
        mFadePanel = ObjectAnimator.ofFloat(mModestyPanel, "alpha", 0f);
        mFadeView = ObjectAnimator.ofFloat(null, "alpha", 0f);
        mAnimatorListener = new BringToFrontOnEnd();
        mTransitionAnimation = new AnimatorSet();
        mTransitionAnimation.play(mFadeView).with(mFadePanel);
        mTransitionAnimation.setDuration(CROSSFADE_TIME);
        mTransitionAnimation.addListener(mAnimatorListener);
    }

    /**
     * Visible for testing
     * @hide
     */
    public Animator getTransitionAnimation() {
        return mTransitionAnimation;
    }

    /**
     * Visible for testing
     * @hide
     */
    public View getModestyPanel() {
        return mModestyPanel;
    }

    @Override
    public void onAttachedToWindow() {
        mLastActive = null;
        // make sure all views start off invisible.
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (DEBUG) Log.d(TAG, this + " measure spec: " +
                         MeasureSpec.toString(heightMeasureSpec));
        View model = selectActiveChild(heightMeasureSpec);
        SizeAdaptiveLayout.LayoutParams lp =
          (SizeAdaptiveLayout.LayoutParams) model.getLayoutParams();
        if (DEBUG) Log.d(TAG, "active min: " + lp.minHeight + " max: " + lp.maxHeight);
        measureChild(model, widthMeasureSpec, heightMeasureSpec);
        int childHeight = model.getMeasuredHeight();
        int childWidth = model.getMeasuredHeight();
        int childState = combineMeasuredStates(0, model.getMeasuredState());
        if (DEBUG) Log.d(TAG, "measured child at: " + childHeight);
        int resolvedWidth = resolveSizeAndState(childWidth, widthMeasureSpec, childState);
        int resolvedHeight = resolveSizeAndState(childHeight, heightMeasureSpec, childState);
        if (DEBUG) Log.d(TAG, "resolved to: " + resolvedHeight);
        int boundedHeight = clampSizeToBounds(resolvedHeight, model);
        if (DEBUG) Log.d(TAG, "bounded to: " + boundedHeight);
        setMeasuredDimension(resolvedWidth, boundedHeight);
    }

    private int clampSizeToBounds(int measuredHeight, View child) {
        SizeAdaptiveLayout.LayoutParams lp =
                (SizeAdaptiveLayout.LayoutParams) child.getLayoutParams();
        int heightIn = View.MEASURED_SIZE_MASK & measuredHeight;
        int height = Math.max(heightIn, lp.minHeight);
        if (lp.maxHeight != SizeAdaptiveLayout.LayoutParams.UNBOUNDED) {
            height = Math.min(height, lp.maxHeight);
        }

        if (REPORT_BAD_BOUNDS && heightIn != height) {
            Log.d(TAG, this + "child view " + child + " " +
                  "measured out of bounds at " + heightIn +"px " +
                  "clamped to " + height + "px");
        }

        return height;
    }

    //TODO extend to width and height
    private View selectActiveChild(int heightMeasureSpec) {
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        View unboundedView = null;
        View tallestView = null;
        int tallestViewSize = 0;
        View smallestView = null;
        int smallestViewSize = Integer.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != mModestyPanel) {
                SizeAdaptiveLayout.LayoutParams lp =
                    (SizeAdaptiveLayout.LayoutParams) child.getLayoutParams();
                if (DEBUG) Log.d(TAG, "looking at " + i +
                                 " with min: " + lp.minHeight +
                                 " max: " +  lp.maxHeight);
                if (lp.maxHeight == SizeAdaptiveLayout.LayoutParams.UNBOUNDED &&
                    unboundedView == null) {
                    unboundedView = child;
                }
                if (lp.maxHeight > tallestViewSize) {
                    tallestViewSize = lp.maxHeight;
                    tallestView = child;
                }
                if (lp.minHeight < smallestViewSize) {
                    smallestViewSize = lp.minHeight;
                    smallestView = child;
                }
                if (heightMode != MeasureSpec.UNSPECIFIED &&
                    heightSize >= lp.minHeight && heightSize <= lp.maxHeight) {
                    if (DEBUG) Log.d(TAG, "  found exact match, finishing early");
                    return child;
                }
            }
        }
        if (unboundedView != null) {
            tallestView = unboundedView;
        }
        if (heightMode == MeasureSpec.UNSPECIFIED || heightSize > tallestViewSize) {
            return tallestView;
        } else {
            return smallestView;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Log.d(TAG, this + " onlayout height: " + (bottom - top));
        mLastActive = mActiveChild;
        int measureSpec = View.MeasureSpec.makeMeasureSpec(bottom - top,
                                                           View.MeasureSpec.EXACTLY);
        mActiveChild = selectActiveChild(measureSpec);
        mActiveChild.setVisibility(View.VISIBLE);

        if (mLastActive != mActiveChild && mLastActive != null) {
            if (DEBUG) Log.d(TAG, this + " changed children from: " + mLastActive +
                    " to: " + mActiveChild);

            mEnteringView = mActiveChild;
            mLeavingView = mLastActive;

            mEnteringView.setAlpha(1f);

            mModestyPanel.setAlpha(1f);
            mModestyPanel.bringToFront();
            mModestyPanelTop = mLeavingView.getHeight();
            mModestyPanel.setVisibility(View.VISIBLE);
            // TODO: mModestyPanel background should be compatible with mLeavingView

            mLeavingView.bringToFront();

            if (mTransitionAnimation.isRunning()) {
                mTransitionAnimation.cancel();
            }
            mFadeView.setTarget(mLeavingView);
            mFadeView.setFloatValues(0f);
            mFadePanel.setFloatValues(0f);
            mTransitionAnimation.setupStartValues();
            mTransitionAnimation.start();
        }
        final int childWidth = mActiveChild.getMeasuredWidth();
        final int childHeight = mActiveChild.getMeasuredHeight();
        // TODO investigate setting LAYER_TYPE_HARDWARE on mLastActive
        mActiveChild.layout(0, 0, childWidth, childHeight);

        if (DEBUG) Log.d(TAG, "got modesty offset of " + mModestyPanelTop);
        mModestyPanel.layout(0, mModestyPanelTop, childWidth, mModestyPanelTop + childHeight);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        if (DEBUG) Log.d(TAG, "generate layout from attrs");
        return new SizeAdaptiveLayout.LayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (DEBUG) Log.d(TAG, "generate default layout from viewgroup");
        return new SizeAdaptiveLayout.LayoutParams(p);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        if (DEBUG) Log.d(TAG, "generate default layout from null");
        return new SizeAdaptiveLayout.LayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof SizeAdaptiveLayout.LayoutParams;
    }

    /**
     * Per-child layout information associated with ViewSizeAdaptiveLayout.
     *
     * TODO extend to width and height
     *
     * @attr ref android.R.styleable#SizeAdaptiveLayout_Layout_layout_minHeight
     * @attr ref android.R.styleable#SizeAdaptiveLayout_Layout_layout_maxHeight
     */
    public static class LayoutParams extends ViewGroup.LayoutParams {

        /**
         * Indicates the minimum valid height for the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int minHeight;

        /**
         * Indicates the maximum valid height for the child.
         */
        @ViewDebug.ExportedProperty(category = "layout")
        public int maxHeight;

        /**
         * Constant value for maxHeight that indicates there is not maximum height.
         */
        public static final int UNBOUNDED = -1;

        /**
         * {@inheritDoc}
         */
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
            if (DEBUG) {
                Log.d(TAG, "construct layout from attrs");
                for (int i = 0; i < attrs.getAttributeCount(); i++) {
                    Log.d(TAG, " " + attrs.getAttributeName(i) + " = " +
                          attrs.getAttributeValue(i));
                }
            }
            TypedArray a =
                    c.obtainStyledAttributes(attrs,
                            R.styleable.SizeAdaptiveLayout_Layout);

            minHeight = a.getDimensionPixelSize(MIN_VALID_HEIGHT, 0);
            if (DEBUG) Log.d(TAG, "got minHeight of: " + minHeight);

            try {
                maxHeight = a.getLayoutDimension(MAX_VALID_HEIGHT, UNBOUNDED);
                if (DEBUG) Log.d(TAG, "got maxHeight of: " + maxHeight);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "caught exception looking for maxValidHeight " + e);
            }

            a.recycle();
        }

        /**
         * Creates a new set of layout parameters with the specified width, height
         * and valid height bounds.
         *
         * @param width the width, either {@link #MATCH_PARENT},
         *        {@link #WRAP_CONTENT} or a fixed size in pixels
         * @param height the height, either {@link #MATCH_PARENT},
         *        {@link #WRAP_CONTENT} or a fixed size in pixels
         * @param minHeight the minimum height of this child
         * @param maxHeight the maximum height of this child
         *        or {@link #UNBOUNDED} if the child can grow forever
         */
        public LayoutParams(int width, int height, int minHeight, int maxHeight) {
            super(width, height);
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(int width, int height) {
            this(width, height, UNBOUNDED, UNBOUNDED);
        }

        /**
         * Constructs a new LayoutParams with default values as defined in {@link LayoutParams}.
         */
        public LayoutParams() {
            this(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        }

        /**
         * {@inheritDoc}
         */
        public LayoutParams(ViewGroup.LayoutParams p) {
            super(p);
            minHeight = UNBOUNDED;
            maxHeight = UNBOUNDED;
        }

        public String debug(String output) {
            return output + "SizeAdaptiveLayout.LayoutParams={" +
                    ", max=" + maxHeight +
                    ", max=" + minHeight + "}";
        }
    }

    class BringToFrontOnEnd implements AnimatorListener {
        @Override
            public void onAnimationEnd(Animator animation) {
            if (mCanceledAnimationCount == 0) {
                mLeavingView.setVisibility(View.GONE);
                mModestyPanel.setVisibility(View.GONE);
                mEnteringView.bringToFront();
                mEnteringView = null;
                mLeavingView = null;
            } else {
                mCanceledAnimationCount--;
            }
        }

        @Override
            public void onAnimationCancel(Animator animation) {
            mCanceledAnimationCount++;
        }

        @Override
            public void onAnimationRepeat(Animator animation) {
            if (DEBUG) Log.d(TAG, "fade animation repeated: should never happen.");
            assert(false);
        }

        @Override
            public void onAnimationStart(Animator animation) {
        }
    }
}
