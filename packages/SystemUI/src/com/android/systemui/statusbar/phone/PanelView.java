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
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
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
    private boolean mRubberbandingEnabled = true;

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

    private View mHandleView;
    private float mPeekHeight;
    private float mTouchOffset;
    private float mExpandedFraction = 0;
    private float mExpandedHeight = 0;
    protected boolean mJustPeeked;
    private boolean mClosing;
    private boolean mRubberbanding;
    private boolean mTracking;
    private int mTrackingPointer;

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

    private int[] mAbsPos = new int[2];
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
                mRubberbanding = false;
                mClosing = false;
            }
        }
    };

    private float mVel, mAccel;
    private int mFullHeight = 0;
    private String mViewName;
    protected float mInitialTouchY;
    protected float mFinalTouchY;

    public void setRubberbandingEnabled(boolean enable) {
        mRubberbandingEnabled = enable;
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

            mRubberbanding = mRubberbandingEnabled // is it enabled at all?
                    && mExpandedHeight > getFullHeight() // are we past the end?
                    && mVel >= -mFlingGestureMinDistPx; // was this not possibly a "close" gesture?
            if (mRubberbanding) {
                mClosing = true;
            } else if (mVel == 0) {
                // if the panel is less than halfway open, close it
                mClosing = (mFinalTouchY / getFullHeight()) < 0.5f;
            } else {
                mClosing = mExpandedHeight > 0 && mVel < 0;
            }
        } else if (dtms > 0) {
            final float dt = dtms * 0.001f;                  // ms -> s
            if (DEBUG) logf("tick: v=%.2fpx/s dt=%.4fs", mVel, dt);
            if (DEBUG) logf("tick: before: h=%d", (int) mExpandedHeight);

            final float fh = getFullHeight();
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

            if (mRubberbanding && h < fh) {
                h = fh;
            }

            if (DEBUG) logf("tick: new h=%d closing=%s", (int) h, mClosing?"true":"false");

            setExpandedHeightInternal(h);

            mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);

            if (mVel == 0
                    || (mClosing && mExpandedHeight == 0)
                    || ((mRubberbanding || !mClosing) && mExpandedHeight == fh)) {
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
            + getPaddingBottom() // our window might have a dropshadow
            - (mHandleView == null ? 0 : mHandleView.getPaddingTop()); // the handle might have a topshadow
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

    // Pass all touches along to the handle, allowing the user to drag the panel closed from its interior
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mHandleView.dispatchTouchEvent(event);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHandleView = findViewById(R.id.handle);

        loadDimens();

        if (DEBUG) logf("handle view: " + mHandleView);
        if (mHandleView != null) {
            mHandleView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int pointerIndex = event.findPointerIndex(mTrackingPointer);
                    if (pointerIndex < 0) {
                        pointerIndex = 0;
                        mTrackingPointer = event.getPointerId(pointerIndex);
                    }
                    final float y = event.getY(pointerIndex);
                    final float rawDelta = event.getRawY() - event.getY();
                    final float rawY = y + rawDelta;
                    if (DEBUG) logf("handle.onTouch: a=%s p=[%d,%d] y=%.1f rawY=%.1f off=%.1f",
                            MotionEvent.actionToString(event.getAction()),
                            mTrackingPointer, pointerIndex,
                            y, rawY, mTouchOffset);
                    PanelView.this.getLocationOnScreen(mAbsPos);

                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mTracking = true;
                            mHandleView.setPressed(true);
                            postInvalidate(); // catch the press state change
                            mInitialTouchY = y;
                            mVelocityTracker = FlingTracker.obtain();
                            trackMovement(event);
                            mTimeAnimator.cancel(); // end any outstanding animations
                            mBar.onTrackingStarted(PanelView.this);
                            mTouchOffset = (rawY - mAbsPos[1]) - mExpandedHeight;
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
                                final float newRawY = newY + rawDelta;
                                mTrackingPointer = event.getPointerId(newIndex);
                                mTouchOffset = (newRawY - mAbsPos[1]) - mExpandedHeight;
                                mInitialTouchY = newY;
                            }
                            break;

                        case MotionEvent.ACTION_MOVE:
                            final float h = rawY - mAbsPos[1] - mTouchOffset;
                            if (h > mPeekHeight) {
                                if (mPeekAnimator != null && mPeekAnimator.isStarted()) {
                                    mPeekAnimator.cancel();
                                }
                                mJustPeeked = false;
                            }
                            if (!mJustPeeked) {
                                PanelView.this.setExpandedHeightInternal(h);
                                mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);
                            }

                            trackMovement(event);
                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            mFinalTouchY = y;
                            mTracking = false;
                            mTrackingPointer = -1;
                            mHandleView.setPressed(false);
                            postInvalidate(); // catch the press state change
                            mBar.onTrackingStopped(PanelView.this);
                            trackMovement(event);

                            float vel = 0, yVel = 0, xVel = 0;
                            boolean negative = false;

                            if (mVelocityTracker != null) {
                                // the velocitytracker might be null if we got a bad input stream
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

                                vel = (float)Math.hypot(yVel, xVel);
                                if (vel > mFlingGestureMaxOutputVelocityPx) {
                                    vel = mFlingGestureMaxOutputVelocityPx;
                                }

                                mVelocityTracker.recycle();
                                mVelocityTracker = null;
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

                            if (DEBUG) logf("gesture: dy=%f vel=(%f,%f) vlinear=%f",
                                    deltaY,
                                    xVel, yVel,
                                    vel);

                            fling(vel, true);

                            break;
                    }
                    return true;
                }});
        }
    }

    public void fling(float vel, boolean always) {
        if (DEBUG) logf("fling: vel=%.3f, this=%s", vel, this);
        mVel = vel;

        if (always||mVel != 0) {
            animationTick(0); // begin the animation
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

    @Override
    protected void onViewAdded(View child) {
        if (DEBUG) logf("onViewAdded: " + child);
    }

    public View getHandle() {
        return mHandleView;
    }

    // Rubberbands the panel to hold its contents.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (DEBUG) logf("onMeasure(%d, %d) -> (%d, %d)",
                widthMeasureSpec, heightMeasureSpec, getMeasuredWidth(), getMeasuredHeight());

        // Did one of our children change size?
        int newHeight = getMeasuredHeight();
        if (newHeight != mFullHeight) {
            mFullHeight = newHeight;
            // If the user isn't actively poking us, let's rubberband to the content
            if (!mTracking && !mRubberbanding && !mTimeAnimator.isStarted()
                    && mExpandedHeight > 0 && mExpandedHeight != mFullHeight) {
                mExpandedHeight = mFullHeight;
            }
        }
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    (int) mExpandedHeight, MeasureSpec.AT_MOST); // MeasureSpec.getMode(heightMeasureSpec));
        setMeasuredDimension(widthMeasureSpec, heightMeasureSpec);
    }


    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        mRubberbanding = false;
        if (mTimeAnimator.isStarted()) {
            post(mStopAnimator);
        }
        setExpandedHeightInternal(height);
        mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) logf("onLayout: changed=%s, bottom=%d eh=%d fh=%d", changed?"T":"f", bottom, (int)mExpandedHeight, mFullHeight);
        super.onLayout(changed, left, top, right, bottom);
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

        float fh = getFullHeight();
        if (fh == 0) {
            // Hmm, full height hasn't been computed yet
        }

        if (h < 0) h = 0;
        if (!(mRubberbandingEnabled && (mTracking || mRubberbanding)) && h > fh) h = fh;

        mExpandedHeight = h;

        if (DEBUG) logf("setExpansion: height=%.1f fh=%.1f tracking=%s rubber=%s", h, fh, mTracking?"T":"f", mRubberbanding?"T":"f");

        requestLayout();
//        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
//        lp.height = (int) mExpandedHeight;
//        setLayoutParams(lp);

        mExpandedFraction = Math.min(1f, (fh == 0) ? 0 : h / fh);
    }

    private float getFullHeight() {
        if (mFullHeight <= 0) {
            if (DEBUG) logf("Forcing measure() since fullHeight=" + mFullHeight);
            measure(MeasureSpec.makeMeasureSpec(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, MeasureSpec.EXACTLY));
        }
        return mFullHeight;
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
        setExpandedHeight(getFullHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getFullHeight();
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
            // collapse() should never be a rubberband, even if an animation is already running
            mRubberbanding = false;
            fling(-mSelfCollapseVelocityPx, /*always=*/ true);
        }
    }

    public void expand() {
        if (DEBUG) logf("expand: " + this);
        if (isFullyCollapsed()) {
            mBar.startOpeningPanel(this);
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
        pw.println(String.format("[PanelView(%s): expandedHeight=%f fullHeight=%f closing=%s"
                + " tracking=%s rubberbanding=%s justPeeked=%s peekAnim=%s%s timeAnim=%s%s"
                + "]",
                this.getClass().getSimpleName(),
                getExpandedHeight(),
                getFullHeight(),
                mClosing?"T":"f",
                mTracking?"T":"f",
                mRubberbanding?"T":"f",
                mJustPeeked?"T":"f",
                mPeekAnimator, ((mPeekAnimator!=null && mPeekAnimator.isStarted())?" (started)":""),
                mTimeAnimator, ((mTimeAnimator!=null && mTimeAnimator.isStarted())?" (started)":"")
        ));
    }
}
