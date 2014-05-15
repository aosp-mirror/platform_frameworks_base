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

import android.animation.ObjectAnimator;
import android.animation.TimeAnimator;
import android.animation.TimeAnimator.TimeListener;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.systemui.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Iterator;

public class PanelView extends FrameLayout {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();

    public static final boolean DEBUG_NAN = true; // http://b/7686690

    private final void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    public static final boolean BRAKES = false;

    private float mSelfExpandVelocityPx; // classic value: 2000px/s
    private float mSelfCollapseVelocityPx; // classic value: 2000px/s (will be negated to collapse "up")
    private float mFlingExpandMinVelocityPx; // classic value: 200px/s
    private float mFlingCollapseMinVelocityPx; // classic value: 200px/s
    private float mCollapseMinDisplayFraction; // classic value: 0.08 (25px/min(320px,480px) on G1)
    private float mExpandMinDisplayFraction; // classic value: 0.5 (drag open halfway to expand)
    private float mFlingGestureMaxXVelocityPx; // classic value: 150px/s

    private float mFlingGestureMinDistPx;

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    private float mFlingGestureMaxOutputVelocityPx; // how fast can it really go? (should be a little
                                                    // faster than mSelfCollapseVelocityPx)

    private float mCollapseBrakingDistancePx = 200; // XXX Resource
    private float mExpandBrakingDistancePx = 150; // XXX Resource
    private float mBrakingSpeedPx = 150; // XXX Resource

    private float mPeekHeight;
    private float mInitialOffsetOnTouch;
    private float mExpandedFraction = 0;
    private float mExpandedHeight = 0;
    private boolean mJustPeeked;
    private boolean mClosing;
    private boolean mTracking;
    private int mTrackingPointer;
    protected int mTouchSlop;

    private TimeAnimator mTimeAnimator;
    private ObjectAnimator mPeekAnimator;
    private FlingTracker mVelocityTracker;

    /**
     * A very simple low-pass velocity filter for motion events; not nearly as sophisticated as
     * VelocityTracker but optimized for the kinds of gestures we expect to see in status bar
     * panels.
     */
    private static class FlingTracker {
        static final boolean DEBUG = false;
        final int MAX_EVENTS = 8;
        final float DECAY = 0.75f;
        ArrayDeque<MotionEventCopy> mEventBuf = new ArrayDeque<MotionEventCopy>(MAX_EVENTS);
        float mVX, mVY = 0;
        private static class MotionEventCopy {
            public MotionEventCopy(float x2, float y2, long eventTime) {
                this.x = x2;
                this.y = y2;
                this.t = eventTime;
            }
            public float x, y;
            public long t;
        }
        public FlingTracker() {
        }
        public void addMovement(MotionEvent event) {
            if (mEventBuf.size() == MAX_EVENTS) {
                mEventBuf.remove();
            }
            mEventBuf.add(new MotionEventCopy(event.getX(), event.getY(), event.getEventTime()));
        }
        public void computeCurrentVelocity(long timebase) {
            if (FlingTracker.DEBUG) {
                Log.v("FlingTracker", "computing velocities for " + mEventBuf.size() + " events");
            }
            mVX = mVY = 0;
            MotionEventCopy last = null;
            int i = 0;
            float totalweight = 0f;
            float weight = 10f;
            for (final Iterator<MotionEventCopy> iter = mEventBuf.iterator();
                    iter.hasNext();) {
                final MotionEventCopy event = iter.next();
                if (last != null) {
                    final float dt = (float) (event.t - last.t) / timebase;
                    final float dx = (event.x - last.x);
                    final float dy = (event.y - last.y);
                    if (FlingTracker.DEBUG) {
                        Log.v("FlingTracker", String.format(
                                "   [%d] (t=%d %.1f,%.1f) dx=%.1f dy=%.1f dt=%f vx=%.1f vy=%.1f",
                                i, event.t, event.x, event.y,
                                dx, dy, dt,
                                (dx/dt),
                                (dy/dt)
                                ));
                    }
                    if (event.t == last.t) {
                        // Really not sure what to do with events that happened at the same time,
                        // so we'll skip subsequent events.
                        if (DEBUG_NAN) {
                            Log.v("FlingTracker", "skipping simultaneous event at t=" + event.t);
                        }
                        continue;
                    }
                    mVX += weight * dx / dt;
                    mVY += weight * dy / dt;
                    totalweight += weight;
                    weight *= DECAY;
                }
                last = event;
                i++;
            }
            if (totalweight > 0) {
                mVX /= totalweight;
                mVY /= totalweight;
            } else {
                if (DEBUG_NAN) {
                    Log.v("FlingTracker", "computeCurrentVelocity warning: totalweight=0",
                            new Throwable());
                }
                // so as not to contaminate the velocities with NaN
                mVX = mVY = 0;
            }

            if (FlingTracker.DEBUG) {
                Log.v("FlingTracker", "computed: vx=" + mVX + " vy=" + mVY);
            }
        }
        public float getXVelocity() {
            if (Float.isNaN(mVX) || Float.isInfinite(mVX)) {
                if (DEBUG_NAN) {
                    Log.v("FlingTracker", "warning: vx=" + mVX);
                }
                mVX = 0;
            }
            return mVX;
        }
        public float getYVelocity() {
            if (Float.isNaN(mVY) || Float.isInfinite(mVX)) {
                if (DEBUG_NAN) {
                    Log.v("FlingTracker", "warning: vx=" + mVY);
                }
                mVY = 0;
            }
            return mVY;
        }
        public void recycle() {
            mEventBuf.clear();
        }

        static FlingTracker sTracker;
        static FlingTracker obtain() {
            if (sTracker == null) {
                sTracker = new FlingTracker();
            }
            return sTracker;
        }
    }

    PanelBar mBar;

    private final TimeListener mAnimationCallback = new TimeListener() {
        @Override
        public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
            animationTick(deltaTime);
        }
    };

    private final Runnable mStopAnimator = new Runnable() {
        @Override
        public void run() {
            if (mTimeAnimator != null && mTimeAnimator.isStarted()) {
                mTimeAnimator.end();
                mClosing = false;
                onExpandingFinished();
            }
        }
    };

    private float mVel, mAccel;
    protected int mMaxPanelHeight = -1;
    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;
    private float mFinalTouchY;

    protected void onExpandingFinished() {
    }

    protected void onExpandingStarted() {
    }

    private void runPeekAnimation() {
        if (DEBUG) logf("peek to height=%.1f", mPeekHeight);
        if (mTimeAnimator.isStarted()) {
            return;
        }
        if (mPeekAnimator == null) {
            mPeekAnimator = ObjectAnimator.ofFloat(this,
                    "expandedHeight", mPeekHeight)
                .setDuration(250);
        }
        mPeekAnimator.start();
    }

    private void animationTick(long dtms) {
        if (!mTimeAnimator.isStarted()) {
            // XXX HAX to work around bug in TimeAnimator.end() not resetting its last time
            mTimeAnimator = new TimeAnimator();
            mTimeAnimator.setTimeListener(mAnimationCallback);

            if (mPeekAnimator != null) mPeekAnimator.cancel();

            mTimeAnimator.start();

            if (mVel == 0) {
                // if the panel is less than halfway open, close it
                mClosing = (mFinalTouchY / getMaxPanelHeight()) < 0.5f;
            } else {
                mClosing = mExpandedHeight > 0 && mVel < 0;
            }
        } else if (dtms > 0) {
            final float dt = dtms * 0.001f;                  // ms -> s
            if (DEBUG) logf("tick: v=%.2fpx/s dt=%.4fs", mVel, dt);
            if (DEBUG) logf("tick: before: h=%d", (int) mExpandedHeight);

            final float fh = getMaxPanelHeight();
            boolean braking = false;
            if (BRAKES) {
                if (mClosing) {
                    braking = mExpandedHeight <= mCollapseBrakingDistancePx;
                    mAccel = braking ? 10*mCollapseAccelPx : -mCollapseAccelPx;
                } else {
                    braking = mExpandedHeight >= (fh-mExpandBrakingDistancePx);
                    mAccel = braking ? 10*-mExpandAccelPx : mExpandAccelPx;
                }
            } else {
                mAccel = mClosing ? -mCollapseAccelPx : mExpandAccelPx;
            }

            mVel += mAccel * dt;

            if (braking) {
                if (mClosing && mVel > -mBrakingSpeedPx) {
                    mVel = -mBrakingSpeedPx;
                } else if (!mClosing && mVel < mBrakingSpeedPx) {
                    mVel = mBrakingSpeedPx;
                }
            } else {
                if (mClosing && mVel > -mFlingCollapseMinVelocityPx) {
                    mVel = -mFlingCollapseMinVelocityPx;
                } else if (!mClosing && mVel > mFlingGestureMaxOutputVelocityPx) {
                    mVel = mFlingGestureMaxOutputVelocityPx;
                }
            }

            float h = mExpandedHeight + mVel * dt;

            if (DEBUG) logf("tick: new h=%d closing=%s", (int) h, mClosing?"true":"false");

            setExpandedHeightInternal(h);

            mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);

            if (mVel == 0
                    || (mClosing && mExpandedHeight == 0)
                    || (!mClosing && mExpandedHeight == fh)) {
                post(mStopAnimator);
            }
        } else {
            Log.v(TAG, "animationTick called with dtms=" + dtms + "; nothing to do (h="
                    + mExpandedHeight + " v=" + mVel + ")");
        }
    }

    public PanelView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mTimeAnimator = new TimeAnimator();
        mTimeAnimator.setTimeListener(mAnimationCallback);
        setOnHierarchyChangeListener(mHierarchyListener);
    }

    private void loadDimens() {
        final Resources res = getContext().getResources();

        mSelfExpandVelocityPx = res.getDimension(R.dimen.self_expand_velocity);
        mSelfCollapseVelocityPx = res.getDimension(R.dimen.self_collapse_velocity);
        mFlingExpandMinVelocityPx = res.getDimension(R.dimen.fling_expand_min_velocity);
        mFlingCollapseMinVelocityPx = res.getDimension(R.dimen.fling_collapse_min_velocity);

        mFlingGestureMinDistPx = res.getDimension(R.dimen.fling_gesture_min_dist);

        mCollapseMinDisplayFraction = res.getFraction(R.dimen.collapse_min_display_fraction, 1, 1);
        mExpandMinDisplayFraction = res.getFraction(R.dimen.expand_min_display_fraction, 1, 1);

        mExpandAccelPx = res.getDimension(R.dimen.expand_accel);
        mCollapseAccelPx = res.getDimension(R.dimen.collapse_accel);

        mFlingGestureMaxXVelocityPx = res.getDimension(R.dimen.fling_gesture_max_x_velocity);

        mFlingGestureMaxOutputVelocityPx = res.getDimension(R.dimen.fling_gesture_max_output_velocity);

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
                initVelocityTracker();
                trackMovement(event);
                mTimeAnimator.cancel(); // end any outstanding animations
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
                mFinalTouchY = y;
                mTracking = false;
                mTrackingPointer = -1;
                onTrackingStopped();
                trackMovement(event);

                float vel = getCurrentVelocity();
                fling(vel, true);

                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    protected void onTrackingStopped() {
        mBar.onTrackingStopped(PanelView.this);
    }

    protected void onTrackingStarted() {
        mBar.onTrackingStarted(PanelView.this);
        onExpandingStarted();
    }

    private float getCurrentVelocity() {
        float vel = 0;
        float yVel = 0, xVel = 0;
        boolean negative = false;

        // the velocitytracker might be null if we got a bad input stream
        if (mVelocityTracker == null) {
            return 0;
        }

        mVelocityTracker.computeCurrentVelocity(1000);

        yVel = mVelocityTracker.getYVelocity();
        negative = yVel < 0;

        xVel = mVelocityTracker.getXVelocity();
        if (xVel < 0) {
            xVel = -xVel;
        }
        if (xVel > mFlingGestureMaxXVelocityPx) {
            xVel = mFlingGestureMaxXVelocityPx; // limit how much we care about the x axis
        }

        vel = (float) Math.hypot(yVel, xVel);
        if (vel > mFlingGestureMaxOutputVelocityPx) {
            vel = mFlingGestureMaxOutputVelocityPx;
        }

        // if you've barely moved your finger, we treat the velocity as 0
        // preventing spurious flings due to touch screen jitter
        final float deltaY = Math.abs(mFinalTouchY - mInitialTouchY);
        if (deltaY < mFlingGestureMinDistPx
                || vel < mFlingExpandMinVelocityPx
                ) {
            vel = 0;
        }

        if (negative) {
            vel = -vel;
        }

        if (DEBUG) {
            logf("gesture: dy=%f vel=(%f,%f) vlinear=%f",
                    deltaY,
                    xVel, yVel,
                    vel);
        }
        return vel;
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
                if (mTimeAnimator.isRunning()) {
                    mTimeAnimator.cancel(); // end any outstanding animations
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
        mVelocityTracker = FlingTracker.obtain();
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

    public void fling(float vel, boolean always) {
        if (DEBUG) logf("fling: vel=%.3f, this=%s", vel, this);
        mVel = vel;

        if (always||mVel != 0) {
            animationTick(0); // begin the animation
        } else {
            onExpandingFinished();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mViewName = getResources().getResourceName(getId());
    }

    public String getName() {
        return mViewName;
    }

    // Rubberbands the panel to hold its contents.
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
            if (!mTracking && !mTimeAnimator.isStarted()
                    && mExpandedHeight > 0 && mExpandedHeight != mMaxPanelHeight
                    && mMaxPanelHeight > 0) {
                mExpandedHeight = mMaxPanelHeight;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), getDesiredMeasureHeight());
    }

    protected int getDesiredMeasureHeight() {
        return (int) mExpandedHeight;
    }


    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        if (mTimeAnimator.isStarted()) {
            post(mStopAnimator);
        }
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
        if (!mTracking && !mTimeAnimator.isStarted()
                && mExpandedHeight > 0 && currentMaxPanelHeight != mExpandedHeight) {
            setExpandedHeightInternal(currentMaxPanelHeight);
        }
    }

    public void setExpandedHeightInternal(float h) {
        if (Float.isNaN(h)) {
            // If a NaN gets in here, it will freeze the Animators.
            if (DEBUG_NAN) {
                Log.v(TAG, "setExpandedHeightInternal: warning: h=NaN, using 0 instead",
                        new Throwable());
            }
            h = 0;
        }

        float fh = getMaxPanelHeight();
        if (fh == 0) {
            // Hmm, full height hasn't been computed yet
        }

        if (h < 0) h = 0;
        if (h > fh) h = fh;

        mExpandedHeight = h;

        if (DEBUG) {
            logf("setExpansion: height=%.1f fh=%.1f tracking=%s", h, fh,
                    mTracking ? "T" : "f");
        }

        onHeightUpdated(mExpandedHeight);

//        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
//        lp.height = (int) mExpandedHeight;
//        setLayoutParams(lp);

        mExpandedFraction = Math.min(1f, (fh == 0) ? 0 : h / fh);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mMaxPanelHeight = -1;
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
        if (Float.isNaN(frac)) {
            // If a NaN gets in here, it will freeze the Animators.
            if (DEBUG_NAN) {
                Log.v(TAG, "setExpandedFraction: frac=NaN, using 0 instead",
                        new Throwable());
            }
            frac = 0;
        }
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
            mTimeAnimator.cancel();
            mClosing = true;
            onExpandingStarted();
            // collapse() should never be a rubberband, even if an animation is already running
            fling(-mSelfCollapseVelocityPx, /*always=*/ true);
        }
    }

    public void expand() {
        if (DEBUG) logf("expand: " + this);
        if (isFullyCollapsed()) {
            mBar.startOpeningPanel(this);
            onExpandingStarted();
            fling(mSelfExpandVelocityPx, /*always=*/ true);
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
                mTimeAnimator, ((mTimeAnimator!=null && mTimeAnimator.isStarted())?" (started)":"")
        ));
    }

    private final OnHierarchyChangeListener mHierarchyListener = new OnHierarchyChangeListener() {
        @Override
        public void onChildViewAdded(View parent, View child) {
            if (DEBUG) logf("onViewAdded: " + child);
        }

        @Override
        public void onChildViewRemoved(View parent, View child) {
        }
    };
}
