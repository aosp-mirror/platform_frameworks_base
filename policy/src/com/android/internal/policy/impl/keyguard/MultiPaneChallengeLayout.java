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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.R;

public class MultiPaneChallengeLayout extends ViewGroup implements ChallengeLayout {
    private static final String TAG = "MultiPaneChallengeLayout";

    final int mOrientation;
    private boolean mIsBouncing;

    public static final int HORIZONTAL = LinearLayout.HORIZONTAL;
    public static final int VERTICAL = LinearLayout.VERTICAL;

    private View mChallengeView;
    private View mUserSwitcherView;
    private View mScrimView;
    private OnBouncerStateChangedListener mBouncerListener;

    private final Rect mTempRect = new Rect();

    private final OnClickListener mScrimClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            hideBouncer();
        }
    };

    public MultiPaneChallengeLayout(Context context) {
        this(context, null);
    }

    public MultiPaneChallengeLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiPaneChallengeLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.MultiPaneChallengeLayout, defStyleAttr, 0);
        mOrientation = a.getInt(R.styleable.MultiPaneChallengeLayout_orientation,
                HORIZONTAL);
        a.recycle();
    }

    @Override
    public boolean isChallengeShowing() {
        return true;
    }

    @Override
    public boolean isChallengeOverlapping() {
        return false;
    }

    @Override
    public void showChallenge(boolean b) {
    }

    @Override
    public void showBouncer() {
        if (mIsBouncing) return;
        mIsBouncing = true;
        if (mScrimView != null) {
            mScrimView.setVisibility(GONE);
        }
        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(true);
        }
    }

    @Override
    public void hideBouncer() {
        if (!mIsBouncing) return;
        mIsBouncing = false;
        if (mScrimView != null) {
            mScrimView.setVisibility(GONE);
        }
        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(false);
        }
    }

    @Override
    public boolean isBouncing() {
        return mIsBouncing;
    }

    @Override
    public void setOnBouncerStateChangedListener(OnBouncerStateChangedListener listener) {
        mBouncerListener = listener;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (mIsBouncing && child != mChallengeView) {
            // Clear out of the bouncer if the user tries to move focus outside of
            // the security challenge view.
            hideBouncer();
        }
        super.requestChildFocus(child, focused);
    }

    void setScrimView(View scrim) {
        if (mScrimView != null) {
            mScrimView.setOnClickListener(null);
        }
        mScrimView = scrim;
        mScrimView.setVisibility(mIsBouncing ? VISIBLE : GONE);
        mScrimView.setFocusable(true);
        mScrimView.setOnClickListener(mScrimClickListener);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY ||
                MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            throw new IllegalArgumentException(
                    "MultiPaneChallengeLayout must be measured with an exact size");
        }

        final int width = MeasureSpec.getSize(widthSpec);
        final int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);

        int widthUsed = 0;
        int heightUsed = 0;

        // First pass. Find the challenge view and measure the user switcher,
        // which consumes space in the layout.
        mChallengeView = null;
        mUserSwitcherView = null;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.childType == LayoutParams.CHILD_TYPE_CHALLENGE) {
                if (mChallengeView != null) {
                    throw new IllegalStateException(
                            "There may only be one child of type challenge");
                }
                mChallengeView = child;
            } else if (lp.childType == LayoutParams.CHILD_TYPE_USER_SWITCHER) {
                if (mUserSwitcherView != null) {
                    throw new IllegalStateException(
                            "There may only be one child of type userSwitcher");
                }
                mUserSwitcherView = child;

                if (child.getVisibility() == GONE) continue;

                int adjustedWidthSpec = widthSpec;
                int adjustedHeightSpec = heightSpec;
                if (lp.maxWidth >= 0) {
                    adjustedWidthSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(lp.maxWidth, MeasureSpec.getSize(widthSpec)),
                            MeasureSpec.EXACTLY);
                }
                if (lp.maxHeight >= 0) {
                    adjustedHeightSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(lp.maxHeight, MeasureSpec.getSize(heightSpec)),
                            MeasureSpec.EXACTLY);
                }
                // measureChildWithMargins will resolve layout direction for the LayoutParams
                measureChildWithMargins(child, adjustedWidthSpec, 0, adjustedHeightSpec, 0);

                // Only subtract out space from one dimension. Favor vertical.
                // Offset by 1.5x to add some balance along the other edge.
                if (Gravity.isVertical(lp.gravity)) {
                    heightUsed += child.getMeasuredHeight() * 1.5f;
                } else if (Gravity.isHorizontal(lp.gravity)) {
                    widthUsed += child.getMeasuredWidth() * 1.5f;
                }
            } else if (lp.childType == LayoutParams.CHILD_TYPE_SCRIM) {
                setScrimView(child);
                child.measure(widthSpec, heightSpec);
            }
        }

        // Second pass. Measure everything that's left.
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.childType == LayoutParams.CHILD_TYPE_USER_SWITCHER ||
                    lp.childType == LayoutParams.CHILD_TYPE_SCRIM ||
                    child.getVisibility() == GONE) {
                // Don't need to measure GONE children, and the user switcher was already measured.
                continue;
            }

            int adjustedWidthSpec;
            int adjustedHeightSpec;
            if (lp.centerWithinArea > 0) {
                if (mOrientation == HORIZONTAL) {
                    adjustedWidthSpec = MeasureSpec.makeMeasureSpec(
                            (int) ((width - widthUsed) * lp.centerWithinArea + 0.5f),
                            MeasureSpec.EXACTLY);
                    adjustedHeightSpec = MeasureSpec.makeMeasureSpec(
                            MeasureSpec.getSize(heightSpec) - heightUsed, MeasureSpec.EXACTLY);
                } else {
                    adjustedWidthSpec = MeasureSpec.makeMeasureSpec(
                            MeasureSpec.getSize(widthSpec) - widthUsed, MeasureSpec.EXACTLY);
                    adjustedHeightSpec = MeasureSpec.makeMeasureSpec(
                            (int) ((height - heightUsed) * lp.centerWithinArea + 0.5f),
                            MeasureSpec.EXACTLY);
                }
            } else {
                adjustedWidthSpec = MeasureSpec.makeMeasureSpec(
                        MeasureSpec.getSize(widthSpec) - widthUsed, MeasureSpec.EXACTLY);
                adjustedHeightSpec = MeasureSpec.makeMeasureSpec(
                        MeasureSpec.getSize(heightSpec) - heightUsed, MeasureSpec.EXACTLY);
            }
            if (lp.maxWidth >= 0) {
                adjustedWidthSpec = MeasureSpec.makeMeasureSpec(
                        Math.min(lp.maxWidth, MeasureSpec.getSize(adjustedWidthSpec)),
                        MeasureSpec.EXACTLY);
            }
            if (lp.maxHeight >= 0) {
                adjustedHeightSpec = MeasureSpec.makeMeasureSpec(
                        Math.min(lp.maxHeight, MeasureSpec.getSize(adjustedHeightSpec)),
                        MeasureSpec.EXACTLY);
            }

            measureChildWithMargins(child, adjustedWidthSpec, 0, adjustedHeightSpec, 0);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final Rect padding = mTempRect;
        padding.left = getPaddingLeft();
        padding.top = getPaddingTop();
        padding.right = getPaddingRight();
        padding.bottom = getPaddingBottom();
        final int width = r - l;
        final int height = b - t;

        // Reserve extra space in layout for the user switcher by modifying
        // local padding during this layout pass
        if (mUserSwitcherView != null && mUserSwitcherView.getVisibility() != GONE) {
            layoutWithGravity(width, height, mUserSwitcherView, padding, true);
        }

        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);

            // We did the user switcher above if we have one.
            if (child == mUserSwitcherView || child.getVisibility() == GONE) continue;

            if (child == mScrimView) {
                child.layout(0, 0, width, height);
                continue;
            }

            layoutWithGravity(width, height, child, padding, false);
        }
    }

    private void layoutWithGravity(int width, int height, View child, Rect padding,
            boolean adjustPadding) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();

        final int gravity = Gravity.getAbsoluteGravity(lp.gravity, getLayoutDirection());

        final boolean fixedLayoutSize = lp.centerWithinArea > 0;
        final boolean fixedLayoutHorizontal = fixedLayoutSize && mOrientation == HORIZONTAL;
        final boolean fixedLayoutVertical = fixedLayoutSize && mOrientation == VERTICAL;

        final int adjustedWidth;
        final int adjustedHeight;
        if (fixedLayoutHorizontal) {
            final int paddedWidth = width - padding.left - padding.right;
            adjustedWidth = (int) (paddedWidth * lp.centerWithinArea + 0.5f);
            adjustedHeight = height;
        } else if (fixedLayoutVertical) {
            final int paddedHeight = height - padding.top - padding.bottom;
            adjustedWidth = width;
            adjustedHeight = (int) (paddedHeight * lp.centerWithinArea + 0.5f);
        } else {
            adjustedWidth = width;
            adjustedHeight = height;
        }

        final boolean isVertical = Gravity.isVertical(gravity);
        final boolean isHorizontal = Gravity.isHorizontal(gravity);
        final int childWidth = child.getMeasuredWidth();
        final int childHeight = child.getMeasuredHeight();

        int left = padding.left;
        int top = padding.top;
        int right = left + childWidth;
        int bottom = top + childHeight;
        switch (gravity & Gravity.VERTICAL_GRAVITY_MASK) {
            case Gravity.TOP:
                top = fixedLayoutVertical ?
                        padding.top + (adjustedHeight - childHeight) / 2 : padding.top;
                bottom = top + childHeight;
                if (adjustPadding && isVertical) {
                    padding.top = bottom;
                    padding.bottom += childHeight / 2;
                }
                break;
            case Gravity.BOTTOM:
                bottom = fixedLayoutVertical
                        ? height - padding.bottom - (adjustedHeight - childHeight) / 2
                        : height - padding.bottom;
                top = bottom - childHeight;
                if (adjustPadding && isVertical) {
                    padding.bottom = height - top;
                    padding.top += childHeight / 2;
                }
                break;
            case Gravity.CENTER_VERTICAL:
                final int paddedHeight = height - padding.top - padding.bottom;
                top = padding.top + (paddedHeight - childHeight) / 2;
                bottom = top + childHeight;
                break;
        }
        switch (gravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.LEFT:
                left = fixedLayoutHorizontal ?
                        padding.left + (adjustedWidth - childWidth) / 2 : padding.left;
                right = left + childWidth;
                if (adjustPadding && isHorizontal && !isVertical) {
                    padding.left = right;
                    padding.right += childWidth / 2;
                }
                break;
            case Gravity.RIGHT:
                right = fixedLayoutHorizontal
                        ? width - padding.right - (adjustedWidth - childWidth) / 2
                        : width - padding.right;
                left = right - childWidth;
                if (adjustPadding && isHorizontal && !isVertical) {
                    padding.right = width - left;
                    padding.left += childWidth / 2;
                }
                break;
            case Gravity.CENTER_HORIZONTAL:
                final int paddedWidth = width - padding.left - padding.right;
                left = (paddedWidth - childWidth) / 2;
                right = left + childWidth;
                break;
        }
        child.layout(left, top, right, bottom);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs, this);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams ? new LayoutParams((LayoutParams) p) :
                p instanceof MarginLayoutParams ? new LayoutParams((MarginLayoutParams) p) :
                new LayoutParams(p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    public static class LayoutParams extends MarginLayoutParams {

        public float centerWithinArea = 0;

        public int childType = 0;

        public static final int CHILD_TYPE_NONE = 0;
        public static final int CHILD_TYPE_WIDGET = 1;
        public static final int CHILD_TYPE_CHALLENGE = 2;
        public static final int CHILD_TYPE_USER_SWITCHER = 3;
        public static final int CHILD_TYPE_SCRIM = 4;

        public int gravity = Gravity.NO_GRAVITY;

        public int maxWidth = -1;
        public int maxHeight = -1;

        public LayoutParams() {
            this(WRAP_CONTENT, WRAP_CONTENT);
        }

        LayoutParams(Context c, AttributeSet attrs, MultiPaneChallengeLayout parent) {
            super(c, attrs);

            final TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.MultiPaneChallengeLayout_Layout);

            centerWithinArea = a.getFloat(
                    R.styleable.MultiPaneChallengeLayout_Layout_layout_centerWithinArea, 0);
            childType = a.getInt(R.styleable.MultiPaneChallengeLayout_Layout_layout_childType,
                    CHILD_TYPE_NONE);
            gravity = a.getInt(R.styleable.MultiPaneChallengeLayout_Layout_layout_gravity,
                    Gravity.NO_GRAVITY);
            maxWidth = a.getDimensionPixelSize(
                    R.styleable.MultiPaneChallengeLayout_Layout_layout_maxWidth, -1);
            maxHeight = a.getDimensionPixelSize(
                    R.styleable.MultiPaneChallengeLayout_Layout_layout_maxHeight, -1);

            // Default gravity settings based on type and parent orientation
            if (gravity == Gravity.NO_GRAVITY) {
                if (parent.mOrientation == HORIZONTAL) {
                    switch (childType) {
                        case CHILD_TYPE_WIDGET:
                            gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                            break;
                        case CHILD_TYPE_CHALLENGE:
                            gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                            break;
                        case CHILD_TYPE_USER_SWITCHER:
                            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                            break;
                    }
                } else {
                    switch (childType) {
                        case CHILD_TYPE_WIDGET:
                            gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                            break;
                        case CHILD_TYPE_CHALLENGE:
                            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                            break;
                        case CHILD_TYPE_USER_SWITCHER:
                            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                            break;
                    }
                }
            }

            a.recycle();
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            this((MarginLayoutParams) source);

            centerWithinArea = source.centerWithinArea;
            childType = source.childType;
            gravity = source.gravity;
            maxWidth = source.maxWidth;
            maxHeight = source.maxHeight;
        }
    }
}
