/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * A frame layout containing the actual payload of the notification, including the contracted and
 * expanded layout. This class is responsible for clipping the content and and switching between the
 * expanded and contracted view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private static final long ANIMATION_DURATION_LENGTH = 170;
    private static final int CONTRACTED = 1;
    private static final int EXPANDED = 2;
    private static final int HEADSUP = 3;

    private final Rect mClipBounds = new Rect();

    private View mContractedChild;
    private View mExpandedChild;
    private View mHeadsUpChild;

    private NotificationViewWrapper mContractedWrapper;

    private final int mSmallHeight;
    private final int mHeadsUpHeight;
    private int mClipTopAmount;

    private int mContentHeight;

    private final Interpolator mLinearInterpolator = new LinearInterpolator();
    private int mVisibleView = CONTRACTED;

    private boolean mDark;
    private final Paint mFadePaint = new Paint();
    private boolean mAnimate;
    private ViewTreeObserver.OnPreDrawListener mEnableAnimationPredrawListener
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            mAnimate = true;
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };
    private boolean mIsHeadsUp;

    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        mSmallHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        mHeadsUpHeight = getResources().getDimensionPixelSize(R.dimen.notification_mid_height);
        reset(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int maxSize = Integer.MAX_VALUE;
        if (hasFixedHeight || isHeightLimited) {
            maxSize = MeasureSpec.getSize(heightMeasureSpec);
        }
        int maxChildHeight = 0;
        if (mContractedChild != null) {
            int size = Math.min(maxSize, mSmallHeight);
            mContractedChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            maxChildHeight = Math.max(maxChildHeight, mContractedChild.getMeasuredHeight());
        }
        if (mExpandedChild != null) {
            int size = maxSize;
            ViewGroup.LayoutParams layoutParams = mExpandedChild.getLayoutParams();
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(maxSize, layoutParams.height);
            }
            int spec = size == Integer.MAX_VALUE ?
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) :
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
            mExpandedChild.measure(widthMeasureSpec, spec);
            maxChildHeight = Math.max(maxChildHeight, mExpandedChild.getMeasuredHeight());
        }
        if (mHeadsUpChild != null) {
            int size = Math.min(maxSize, mHeadsUpHeight);
            ViewGroup.LayoutParams layoutParams = mHeadsUpChild.getLayoutParams();
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(maxSize, layoutParams.height);
            }
            mHeadsUpChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mHeadsUpChild.getMeasuredHeight());
        }
        int ownHeight = Math.min(maxChildHeight, maxSize);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, ownHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateClipping();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
    }

    public void reset(boolean resetActualHeight) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
        }
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
        }
        if (mHeadsUpChild != null) {
            mHeadsUpChild.animate().cancel();
        }
        removeAllViews();
        mContractedChild = null;
        mExpandedChild = null;
        mHeadsUpChild = null;
        mVisibleView = CONTRACTED;
        if (resetActualHeight) {
            mContentHeight = mSmallHeight;
        }
    }

    public View getContractedChild() {
        return mContractedChild;
    }

    public View getExpandedChild() {
        return mExpandedChild;
    }

    public View getHeadsUpChild() {
        return mHeadsUpChild;
    }

    public void setContractedChild(View child) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
            removeView(mContractedChild);
        }
        addView(child);
        mContractedChild = child;
        mContractedWrapper = NotificationViewWrapper.wrap(getContext(), child);
        selectLayout(false /* animate */, true /* force */);
        mContractedWrapper.setDark(mDark, false /* animate */, 0 /* delay */);
    }

    public void setExpandedChild(View child) {
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
            removeView(mExpandedChild);
        }
        addView(child);
        mExpandedChild = child;
        selectLayout(false /* animate */, true /* force */);
    }

    public void setHeadsUpChild(View child) {
        if (mHeadsUpChild != null) {
            mHeadsUpChild.animate().cancel();
            removeView(mHeadsUpChild);
        }
        addView(child);
        mHeadsUpChild = child;
        selectLayout(false /* animate */, true /* force */);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateVisibility();
    }

    private void updateVisibility() {
        setVisible(isShown());
    }

    private void setVisible(final boolean isVisible) {
        if (isVisible) {

            // We only animate if we are drawn at least once, otherwise the view might animate when
            // it's shown the first time
            getViewTreeObserver().addOnPreDrawListener(mEnableAnimationPredrawListener);
        } else {
            getViewTreeObserver().removeOnPreDrawListener(mEnableAnimationPredrawListener);
            mAnimate = false;
        }
    }

    public void setContentHeight(int contentHeight) {
        contentHeight = Math.max(Math.min(contentHeight, getHeight()), getMinHeight());
        mContentHeight = contentHeight;
        selectLayout(mAnimate /* animate */, false /* force */);
        updateClipping();
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    public int getMaxHeight() {
        if (mIsHeadsUp && mHeadsUpChild != null) {
            return mHeadsUpChild.getHeight();
        } else if (mExpandedChild != null) {
            return mExpandedChild.getHeight();
        }
        return mSmallHeight;
    }

    public int getMinHeight() {
        return mSmallHeight;
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }

    private void updateClipping() {
        mClipBounds.set(0, mClipTopAmount, getWidth(), mContentHeight);
        setClipBounds(mClipBounds);
    }

    private void selectLayout(boolean animate, boolean force) {
        if (mContractedChild == null) {
            return;
        }
        int visibleView = calculateVisibleView();
        if (visibleView != mVisibleView || force) {
            if (animate && mExpandedChild != null) {
                runSwitchAnimation(visibleView);
            } else {
                updateViewVisibilities(visibleView);
            }
            mVisibleView = visibleView;
        }
    }

    private void updateViewVisibilities(int visibleView) {
        boolean contractedVisible = visibleView == CONTRACTED;
        mContractedChild.setVisibility(contractedVisible ? View.VISIBLE : View.INVISIBLE);
        mContractedChild.setAlpha(contractedVisible ? 1f : 0f);
        mContractedChild.setLayerType(LAYER_TYPE_NONE, null);
        if (mExpandedChild != null) {
            boolean expandedVisible = visibleView == EXPANDED;
            mExpandedChild.setVisibility(expandedVisible ? View.VISIBLE : View.INVISIBLE);
            mExpandedChild.setAlpha(expandedVisible ? 1f : 0f);
            mExpandedChild.setLayerType(LAYER_TYPE_NONE, null);
        }
        if (mHeadsUpChild != null) {
            boolean headsUpVisible = visibleView == HEADSUP;
            mHeadsUpChild.setVisibility(headsUpVisible ? View.VISIBLE : View.INVISIBLE);
            mHeadsUpChild.setAlpha(headsUpVisible ? 1f : 0f);
            mHeadsUpChild.setLayerType(LAYER_TYPE_NONE, null);
        }
        setLayerType(LAYER_TYPE_NONE, null);
    }

    private void runSwitchAnimation(int visibleView) {
        View shownView = getViewFromFlag(visibleView);
        View hiddenView = getViewFromFlag(mVisibleView);
        shownView.setVisibility(View.VISIBLE);
        hiddenView.setVisibility(View.VISIBLE);
        shownView.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        hiddenView.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        hiddenView.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator)
                .withEndAction(null); // In case we have multiple changes in one frame.
        shownView.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        updateViewVisibilities(mVisibleView);
                    }
                });
    }

    private View getViewFromFlag(int visibleView) {
        switch (visibleView) {
            case EXPANDED:
                return mExpandedChild;
            case HEADSUP:
                return mHeadsUpChild;
        }
        return mContractedChild;
    }

    private int calculateVisibleView() {
        boolean noExpandedChild = mExpandedChild == null;
        if (mIsHeadsUp && mHeadsUpChild != null) {
            if (mContentHeight <= mHeadsUpChild.getHeight() || noExpandedChild) {
                return HEADSUP;
            } else {
                return EXPANDED;
            }
        } else {
            if (mContentHeight <= mSmallHeight || noExpandedChild) {
                return CONTRACTED;
            } else {
                return EXPANDED;
            }
        }
    }

    public void notifyContentUpdated() {
        selectLayout(false /* animate */, true /* force */);
        if (mContractedChild != null) {
            mContractedWrapper.notifyContentUpdated();
            mContractedWrapper.setDark(mDark, false /* animate */, 0 /* delay */);
        }
    }

    public boolean isContentExpandable() {
        return mExpandedChild != null;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mDark == dark || mContractedChild == null) return;
        mDark = dark;
        mContractedWrapper.setDark(dark, fade, delay);
    }

    public void setHeadsUp(boolean headsUp) {
        mIsHeadsUp = headsUp;
        selectLayout(false /* animate */, true /* force */);
    }

    @Override
    public boolean hasOverlappingRendering() {

        // This is not really true, but good enough when fading from the contracted to the expanded
        // layout, and saves us some layers.
        return false;
    }
}
