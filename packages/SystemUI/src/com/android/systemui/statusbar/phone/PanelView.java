package com.android.systemui.statusbar.phone;

import android.animation.TimeAnimator;
import android.animation.TimeAnimator.TimeListener;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;

public class PanelView extends FrameLayout {
    public static final boolean DEBUG = false;
    public static final String TAG = PanelView.class.getSimpleName();
    public final void LOG(String fmt, Object... args) {
        if (!DEBUG) return;
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

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    private float mFlingGestureMaxOutputVelocityPx; // how fast can it really go? (should be a little
                                                    // faster than mSelfCollapseVelocityPx)

    private float mCollapseBrakingDistancePx = 200; // XXX Resource
    private float mExpandBrakingDistancePx = 150; // XXX Resource
    private float mBrakingSpeedPx = 150; // XXX Resource

    private View mHandleView;
    private float mTouchOffset;
    private float mExpandedFraction = 0;
    private float mExpandedHeight = 0;

    private TimeAnimator mTimeAnimator;
    private VelocityTracker mVelocityTracker;

    private int[] mAbsPos = new int[2];
    PanelBar mBar;

    private final TimeListener mAnimationCallback = new TimeListener() {
        @Override
        public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
            animationTick(deltaTime);
        }
    };

    private float mVel, mAccel;
    private int mFullHeight = 0;
    private String mViewName; 

    private void animationTick(long dtms) {
        if (!mTimeAnimator.isStarted()) {
            // XXX HAX to work around bug in TimeAnimator.end() not resetting its last time
            mTimeAnimator = new TimeAnimator();
            mTimeAnimator.setTimeListener(mAnimationCallback);

            mTimeAnimator.start();
        } else if (dtms > 0) {
            final float dt = dtms * 0.001f;                  // ms -> s
            LOG("tick: v=%.2fpx/s dt=%.4fs", mVel, dt);
            LOG("tick: before: h=%d", (int) mExpandedHeight);

            final float fh = getFullHeight();
            final boolean closing = mExpandedHeight > 0 && mVel < 0;
            boolean braking = false;
            if (BRAKES) {
                if (closing) {
                    braking = mExpandedHeight <= mCollapseBrakingDistancePx;
                    mAccel = braking ? 10*mCollapseAccelPx : -mCollapseAccelPx;
                } else {
                    braking = mExpandedHeight >= (fh-mExpandBrakingDistancePx);
                    mAccel = braking ? 10*-mExpandAccelPx : mExpandAccelPx;
                }
            } else {
                mAccel = closing ? -mCollapseAccelPx : mExpandAccelPx;
            }

            mVel += mAccel * dt;

            if (braking) {
                if (closing && mVel > -mBrakingSpeedPx) {
                    mVel = -mBrakingSpeedPx;
                } else if (!closing && mVel < mBrakingSpeedPx) {
                    mVel = mBrakingSpeedPx;
                }
            } else {
                if (closing && mVel > -mFlingCollapseMinVelocityPx) {
                    mVel = -mFlingCollapseMinVelocityPx;
                } else if (!closing && mVel > mFlingGestureMaxOutputVelocityPx) {
                    mVel = mFlingGestureMaxOutputVelocityPx;
                }
            }

            float h = mExpandedHeight + mVel * dt;

            LOG("tick: new h=%d closing=%s", (int) h, closing?"true":"false");

            setExpandedHeightInternal(h);

            mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);

            if (mVel == 0
                    || (closing && mExpandedHeight == 0)
                    || (!closing && mExpandedHeight == getFullHeight())) {
                mTimeAnimator.end();
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

        mCollapseMinDisplayFraction = res.getFraction(R.dimen.collapse_min_display_fraction, 1, 1);
        mExpandMinDisplayFraction = res.getFraction(R.dimen.expand_min_display_fraction, 1, 1);

        mExpandAccelPx = res.getDimension(R.dimen.expand_accel);
        mCollapseAccelPx = res.getDimension(R.dimen.collapse_accel);

        mFlingGestureMaxXVelocityPx = res.getDimension(R.dimen.fling_gesture_max_x_velocity);

        mFlingGestureMaxOutputVelocityPx = res.getDimension(R.dimen.fling_gesture_max_output_velocity);
    }

    private void trackMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        loadDimens();

        mHandleView = findViewById(R.id.handle);
        LOG("handle view: " + mHandleView);
        if (mHandleView != null) {
            mHandleView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    final float y = event.getY();
                    final float rawY = event.getRawY();
                    LOG("handle.onTouch: a=%s y=%.1f rawY=%.1f off=%.1f",
                            MotionEvent.actionToString(event.getAction()),
                            y, rawY, mTouchOffset);
                    PanelView.this.getLocationOnScreen(mAbsPos);

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            mVelocityTracker = VelocityTracker.obtain();
                            trackMovement(event);
                            mBar.onTrackingStarted(PanelView.this);
                            mTouchOffset = (rawY - mAbsPos[1]) - PanelView.this.getExpandedHeight();
                            break;

                        case MotionEvent.ACTION_MOVE:
                            PanelView.this.setExpandedHeight(rawY - mAbsPos[1] - mTouchOffset);

                            mBar.panelExpansionChanged(PanelView.this, mExpandedFraction);

                            trackMovement(event);
                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            mBar.onTrackingStopped(PanelView.this);
                            trackMovement(event);
                            mVelocityTracker.computeCurrentVelocity(1000);

                            float yVel = mVelocityTracker.getYVelocity();
                            boolean negative = yVel < 0;

                            float xVel = mVelocityTracker.getXVelocity();
                            if (xVel < 0) {
                                xVel = -xVel;
                            }
                            if (xVel > mFlingGestureMaxXVelocityPx) {
                                xVel = mFlingGestureMaxXVelocityPx; // limit how much we care about the x axis
                            }

                            float vel = (float)Math.hypot(yVel, xVel);
                            if (vel > mFlingGestureMaxOutputVelocityPx) {
                                vel = mFlingGestureMaxOutputVelocityPx;
                            }
                            if (negative) {
                                vel = -vel;
                            }

                            LOG("gesture: vraw=(%f,%f) vnorm=(%f,%f) vlinear=%f",
                                    mVelocityTracker.getXVelocity(),
                                    mVelocityTracker.getYVelocity(),
                                    xVel, yVel,
                                    vel);

                            fling(vel, true);

                            mVelocityTracker.recycle();
                            mVelocityTracker = null;

                            break;
                    }
                    return true;
                }});
        }
    }

    public void fling(float vel, boolean always) {
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
        LOG("onViewAdded: " + child);
    }

    public View getHandle() {
        return mHandleView;
    }

    // Rubberbands the panel to hold its contents.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        LOG("onMeasure(%d, %d) -> (%d, %d)",
                widthMeasureSpec, heightMeasureSpec, getMeasuredWidth(), getMeasuredHeight());
        mFullHeight = getMeasuredHeight();
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    (int) mExpandedHeight, MeasureSpec.AT_MOST); // MeasureSpec.getMode(heightMeasureSpec));
        setMeasuredDimension(widthMeasureSpec, heightMeasureSpec);
    }


    public void setExpandedHeight(float height) {
        mTimeAnimator.end();
        setExpandedHeightInternal(height);
    }

    public void setExpandedHeightInternal(float h) {
        float fh = getFullHeight();
        if (fh == 0) {
            // Hmm, full height hasn't been computed yet
        }

        LOG("setExpansion: height=%.1f fh=%.1f", h, fh);

        if (h < 0) h = 0;
        else if (h > fh) h = fh;

        mExpandedHeight = h;

        requestLayout();
//        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
//        lp.height = (int) mExpandedHeight;
//        setLayoutParams(lp);

        mExpandedFraction = Math.min(1f, h / fh);
    }

    private float getFullHeight() {
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

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse() {
        // TODO: abort animation or ongoing touch
        if (mExpandedHeight > 0) {
            fling(-mSelfCollapseVelocityPx, /*always=*/ true);
        }
    }

    public void expand() {
        if (mExpandedHeight < getFullHeight()) {
            fling (mSelfExpandVelocityPx, /*always=*/ true);
        }
    }
}
