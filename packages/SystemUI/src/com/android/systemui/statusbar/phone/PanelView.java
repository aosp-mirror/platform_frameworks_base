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
import android.util.Slog;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;

public class PanelView extends FrameLayout {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();
    public final void LOG(String fmt, Object... args) {
        if (!DEBUG) return;
        Slog.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
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
    private boolean mJustPeeked;
    private boolean mClosing;
    private boolean mRubberbanding;
    private boolean mTracking;

    private TimeAnimator mTimeAnimator;
    private ObjectAnimator mPeekAnimator;
    private VelocityTracker mVelocityTracker;

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
        if (DEBUG) LOG("peek to height=%.1f", mPeekHeight);
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
            if (DEBUG) LOG("tick: v=%.2fpx/s dt=%.4fs", mVel, dt);
            if (DEBUG) LOG("tick: before: h=%d", (int) mExpandedHeight);

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

            if (DEBUG) LOG("tick: new h=%d closing=%s", (int) h, mClosing?"true":"false");

            setExpandedHeightInternal(h);

            mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);

            if (mVel == 0
                    || (mClosing && mExpandedHeight == 0)
                    || ((mRubberbanding || !mClosing) && mExpandedHeight == fh)) {
                post(mStopAnimator);
            }
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

        if (DEBUG) LOG("handle view: " + mHandleView);
        if (mHandleView != null) {
            mHandleView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    final float y = event.getY();
                    final float rawY = event.getRawY();
                    if (DEBUG) LOG("handle.onTouch: a=%s y=%.1f rawY=%.1f off=%.1f",
                            MotionEvent.actionToString(event.getAction()),
                            y, rawY, mTouchOffset);
                    PanelView.this.getLocationOnScreen(mAbsPos);

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            mTracking = true;
                            mHandleView.setPressed(true);
                            postInvalidate(); // catch the press state change
                            mInitialTouchY = y;
                            mVelocityTracker = VelocityTracker.obtain();
                            trackMovement(event);
                            mTimeAnimator.cancel(); // end any outstanding animations
                            mBar.onTrackingStarted(PanelView.this);
                            mTouchOffset = (rawY - mAbsPos[1]) - PanelView.this.getExpandedHeight();
                            if (mExpandedHeight == 0) {
                                mJustPeeked = true;
                                runPeekAnimation();
                            }
                            break;

                        case MotionEvent.ACTION_MOVE:
                            final float h = rawY - mAbsPos[1] - mTouchOffset;
                            if (h > mPeekHeight) {
                                if (mPeekAnimator != null && mPeekAnimator.isRunning()) {
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

                            if (DEBUG) LOG("gesture: dy=%f vel=(%f,%f) vlinear=%f",
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
        if (DEBUG) LOG("fling: vel=%.3f, this=%s", vel, this);
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
        if (DEBUG) LOG("onViewAdded: " + child);
    }

    public View getHandle() {
        return mHandleView;
    }

    // Rubberbands the panel to hold its contents.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (DEBUG) LOG("onMeasure(%d, %d) -> (%d, %d)",
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
        if (DEBUG) LOG("setExpandedHeight(%.1f)", height);
        mRubberbanding = false;
        if (mTimeAnimator.isRunning()) {
            post(mStopAnimator);
        }
        setExpandedHeightInternal(height);
        mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) LOG("onLayout: changed=%s, bottom=%d eh=%d fh=%d", changed?"T":"f", bottom, (int)mExpandedHeight, mFullHeight);
        super.onLayout(changed, left, top, right, bottom);
    }

    public void setExpandedHeightInternal(float h) {
        float fh = getFullHeight();
        if (fh == 0) {
            // Hmm, full height hasn't been computed yet
        }

        if (h < 0) h = 0;
        if (!(mRubberbandingEnabled && (mTracking || mRubberbanding)) && h > fh) h = fh;
        mExpandedHeight = h;

        if (DEBUG) LOG("setExpansion: height=%.1f fh=%.1f tracking=%s rubber=%s", h, fh, mTracking?"T":"f", mRubberbanding?"T":"f");

        requestLayout();
//        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
//        lp.height = (int) mExpandedHeight;
//        setLayoutParams(lp);

        mExpandedFraction = Math.min(1f, (fh == 0) ? 0 : h / fh);
    }

    private float getFullHeight() {
        if (mFullHeight <= 0) {
            if (DEBUG) LOG("Forcing measure() since fullHeight=" + mFullHeight);
            measure(MeasureSpec.makeMeasureSpec(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, MeasureSpec.EXACTLY));
        }
        return mFullHeight;
    }

    public void setExpandedFraction(float frac) {
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

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse() {
        // TODO: abort animation or ongoing touch
        if (DEBUG) LOG("collapse: " + this);
        if (!isFullyCollapsed()) {
            mTimeAnimator.cancel();
            mClosing = true;
            // collapse() should never be a rubberband, even if an animation is already running
            mRubberbanding = false;
            fling(-mSelfCollapseVelocityPx, /*always=*/ true);
        }
    }

    public void expand() {
        if (DEBUG) LOG("expand: " + this);
        if (isFullyCollapsed()) {
            mBar.startOpeningPanel(this);
            fling(mSelfExpandVelocityPx, /*always=*/ true);
        } else if (DEBUG) {
            if (DEBUG) LOG("skipping expansion: is expanded");
        }
    }
}
