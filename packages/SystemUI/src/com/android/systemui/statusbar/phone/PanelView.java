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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class PanelView extends FrameLayout {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();
    protected float mOverExpansion;

    private final void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    private float mPeekHeight;
    private float mInitialOffsetOnTouch;
    private float mExpandedFraction = 0;
    private float mExpandedHeight = 0;
    private boolean mJustPeeked;
    private boolean mClosing;
    private boolean mTracking;
    private int mTrackingPointer;
    protected int mTouchSlop;

    private ValueAnimator mHeightAnimator;
    private ObjectAnimator mPeekAnimator;
    private VelocityTrackerInterface mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;

    PanelBar mBar;

    protected int mMaxPanelHeight = -1;
    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;

    protected void onExpandingFinished() {
        mBar.onExpandingFinished();
    }

    protected void onExpandingStarted() {
    }

    private void runPeekAnimation() {
        if (DEBUG) logf("peek to height=%.1f", mPeekHeight);
        if (mHeightAnimator != null) {
            return;
        }
        if (mPeekAnimator == null) {
            mPeekAnimator = ObjectAnimator.ofFloat(this,
                    "expandedHeight", mPeekHeight)
                .setDuration(250);
        }
        mPeekAnimator.start();
    }

    public PanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.6f);
    }

    protected void loadDimens() {
        final Resources res = getContext().getResources();
        mPeekHeight = res.getDimension(R.dimen.peek_height)
            + getPaddingBottom(); // our window might have a dropshadow

        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
    }

    private void trackMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        /*
         * We capture touch events here and update the expand height here in case according to
         * the users fingers. This also handles multi-touch.
         *
         * If the user just clicks shortly, we give him a quick peek of the shade.
         *
         * Flinging is also enabled in order to open or close the shade.
         */

        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracking = true;

                mInitialTouchY = y;
                mInitialTouchX = x;
                if (mVelocityTracker == null) {
                    initVelocityTracker();
                }
                trackMovement(event);
                if (mHeightAnimator != null) {
                    mHeightAnimator.cancel(); // end any outstanding animations
                }
                onTrackingStarted();
                mInitialOffsetOnTouch = mExpandedHeight;
                if (mExpandedHeight == 0) {
                    mJustPeeked = true;
                    runPeekAnimation();
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialOffsetOnTouch = mExpandedHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY + mInitialOffsetOnTouch;
                if (h > mPeekHeight) {
                    if (mPeekAnimator != null && mPeekAnimator.isStarted()) {
                        mPeekAnimator.cancel();
                    }
                    mJustPeeked = false;
                }
                if (!mJustPeeked) {
                    setExpandedHeightInternal(h);
                    mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);
                }

                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                boolean expand = flingWithCurrentVelocity();
                onTrackingStopped(expand);
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    protected void onTrackingStopped(boolean expand) {
        mBar.onTrackingStopped(PanelView.this, expand);
    }

    protected void onTrackingStarted() {
        mBar.onTrackingStarted(PanelView.this);
        onExpandingStarted();
    }

    private float getCurrentVelocity() {

        // the velocitytracker might be null if we got a bad input stream
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {

        /*
         * If the user drags anywhere inside the panel we intercept it if he moves his finger
         * upwards. This allows closing the shade from anywhere inside the panel.
         *
         * We only do this if the current content is scrolled to the bottom,
         * i.e isScrolledToBottom() is true and therefore there is no conflicting scrolling gesture
         * possible.
         */
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        boolean scrolledToBottom = isScrolledToBottom();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mHeightAnimator != null) {
                    mHeightAnimator.cancel(); // end any outstanding animations
                    return true;
                }
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (scrolledToBottom) {
                    if (h < -mTouchSlop && h < -Math.abs(x - mInitialTouchX)) {
                        mInitialOffsetOnTouch = mExpandedHeight;
                        mInitialTouchY = y;
                        mInitialTouchX = x;
                        mTracking = true;
                        onTrackingStarted();
                        return true;
                    }
                }
                break;
        }
        return false;
    }

    private void initVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = VelocityTrackerFactory.obtain(getContext());
    }

    protected boolean isScrolledToBottom() {
        return true;
    }

    protected float getContentHeight() {
        return mExpandedHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        loadDimens();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        loadDimens();
        mMaxPanelHeight = -1;
    }

    /**
     * @return whether the panel will be expanded after the animation
     */
    private boolean flingWithCurrentVelocity() {
        float vel = getCurrentVelocity();
        boolean expand;
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            expand = getExpandedFraction() > 0.5f;
        } else {
            expand = vel > 0;
        }
        fling(vel, expand);
        return expand;
    }

    protected void fling(float vel, boolean expand) {
        cancelPeek();
        float target = expand ? getMaxPanelHeight() : 0.0f;
        if (target == mExpandedHeight) {
            onExpandingFinished();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, target);
        if (expand) {
            mFlingAnimationUtils.apply(animator, mExpandedHeight, target, vel, getHeight());
        } else {
            mFlingAnimationUtils.applyDismissing(animator, mExpandedHeight, target, vel,
                    getHeight());

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / 1.75f));
            }
        }
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setExpandedHeight((Float) animation.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHeightAnimator = null;
                onExpandingFinished();
            }
        });
        animator.start();
        mHeightAnimator = animator;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mViewName = getResources().getResourceName(getId());
    }

    public String getName() {
        return mViewName;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (DEBUG) logf("onMeasure(%d, %d) -> (%d, %d)",
                widthMeasureSpec, heightMeasureSpec, getMeasuredWidth(), getMeasuredHeight());

        // Did one of our children change size?
        int newHeight = getMeasuredHeight();
        if (newHeight > mMaxPanelHeight) {
            // we only adapt the max height if it's bigger
            mMaxPanelHeight = newHeight;
            // If the user isn't actively poking us, let's rubberband to the content
            if (!mTracking && mHeightAnimator == null
                    && mExpandedHeight > 0 && mExpandedHeight != mMaxPanelHeight
                    && mMaxPanelHeight > 0) {
                mExpandedHeight = mMaxPanelHeight;
            }
        }
    }

    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height);
        mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) logf("onLayout: changed=%s, bottom=%d eh=%d fh=%d", changed?"T":"f", bottom,
                (int)mExpandedHeight, mMaxPanelHeight);
        super.onLayout(changed, left, top, right, bottom);
        requestPanelHeightUpdate();
    }

    protected void requestPanelHeightUpdate() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        // If the user isn't actively poking us, let's update the height
        if (!mTracking && mHeightAnimator == null
                && mExpandedHeight > 0 && currentMaxPanelHeight != mExpandedHeight) {
            setExpandedHeightInternal(currentMaxPanelHeight);
        }
    }

    public void setExpandedHeightInternal(float h) {
        float fh = getMaxPanelHeight();
        mExpandedHeight = Math.min(fh, h);
        float overExpansion = h - fh;
        overExpansion = Math.max(0, overExpansion);
        if (overExpansion != mOverExpansion) {
            onOverExpansionChanged(overExpansion);
        }

        if (DEBUG) {
            logf("setExpansion: height=%.1f fh=%.1f tracking=%s", h, fh, mTracking ? "T" : "f");
        }

        onHeightUpdated(mExpandedHeight);
        mExpandedFraction = Math.min(1f, (fh == 0) ? 0 : h / fh);
    }

    protected void onOverExpansionChanged(float overExpansion) {
        mOverExpansion = overExpansion;
    }

    protected void onHeightUpdated(float expandedHeight) {
        requestLayout();
    }

    /**
     * This returns the maximum height of the panel. Children should override this if their
     * desired height is not the full height.
     *
     * @return the default implementation simply returns the maximum height.
     */
    protected int getMaxPanelHeight() {
        mMaxPanelHeight = Math.max(mMaxPanelHeight, getHeight());
        return mMaxPanelHeight;
    }

    public void setExpandedFraction(float frac) {
        setExpandedHeight(getMaxPanelHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelHeight();
    }

    public boolean isFullyCollapsed() {
        return mExpandedHeight <= 0;
    }

    public boolean isCollapsing() {
        return mClosing;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse() {
        // TODO: abort animation or ongoing touch
        if (DEBUG) logf("collapse: " + this);
        if (!isFullyCollapsed()) {
            if (mHeightAnimator != null) {
                mHeightAnimator.cancel();
            }
            mClosing = true;
            onExpandingStarted();
            fling(0, false /* expand */);
        }
    }

    public void expand() {
        if (DEBUG) logf("expand: " + this);
        if (isFullyCollapsed()) {
            mBar.startOpeningPanel(this);
            onExpandingStarted();
            fling(0, true /* expand */);
        } else if (DEBUG) {
            if (DEBUG) logf("skipping expansion: is expanded");
        }
    }

    public void cancelPeek() {
        if (mPeekAnimator != null && mPeekAnimator.isStarted()) {
            mPeekAnimator.cancel();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[PanelView(%s): expandedHeight=%f maxPanelHeight=%d closing=%s"
                + " tracking=%s justPeeked=%s peekAnim=%s%s timeAnim=%s%s"
                + "]",
                this.getClass().getSimpleName(),
                getExpandedHeight(),
                getMaxPanelHeight(),
                mClosing?"T":"f",
                mTracking?"T":"f",
                mJustPeeked?"T":"f",
                mPeekAnimator, ((mPeekAnimator!=null && mPeekAnimator.isStarted())?" (started)":""),
                mHeightAnimator, ((mHeightAnimator !=null && mHeightAnimator.isStarted())?" (started)":"")
        ));
    }
}
