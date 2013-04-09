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

package com.android.systemui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.Choreographer;
import android.view.Display;
import android.view.IWindowSession;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.Transformation;
import android.widget.FrameLayout;

public class UniverseBackground extends FrameLayout {
    static final String TAG = "UniverseBackground";
    static final boolean SPEW = false;
    static final boolean CHATTY = false;

    final IWindowSession mSession;
    final View mContent;
    final View mBottomAnchor;

    final Runnable mAnimationCallback = new Runnable() {
        @Override
        public void run() {
            doAnimation(mChoreographer.getFrameTimeNanos());
        }
    };

    // fling gesture tuning parameters, scaled to display density
    private float mSelfExpandVelocityPx; // classic value: 2000px/s
    private float mSelfCollapseVelocityPx; // classic value: 2000px/s (will be negated to collapse "up")
    private float mFlingExpandMinVelocityPx; // classic value: 200px/s
    private float mFlingCollapseMinVelocityPx; // classic value: 200px/s
    private float mCollapseMinDisplayFraction; // classic value: 0.08 (25px/min(320px,480px) on G1)
    private float mExpandMinDisplayFraction; // classic value: 0.5 (drag open halfway to expand)
    private float mFlingGestureMaxXVelocityPx; // classic value: 150px/s

    private float mExpandAccelPx; // classic value: 2000px/s/s
    private float mCollapseAccelPx; // classic value: 2000px/s/s (will be negated to collapse "up")

    static final int STATE_CLOSED = 0;
    static final int STATE_OPENING = 1;
    static final int STATE_OPEN = 2;
    private int mState = STATE_CLOSED;

    private float mDragStartX, mDragStartY;
    private float mAverageX, mAverageY;

    // position
    private int[] mPositionTmp = new int[2];
    private boolean mExpanded;
    private boolean mExpandedVisible;

    private boolean mTracking;
    private VelocityTracker mVelocityTracker;

    private Choreographer mChoreographer;
    private boolean mAnimating;
    private boolean mClosing; // only valid when mAnimating; indicates the initial acceleration
    private float mAnimY;
    private float mAnimVel;
    private float mAnimAccel;
    private long mAnimLastTimeNanos;
    private boolean mAnimatingReveal = false;

    private int mYDelta = 0;
    private Transformation mUniverseTransform = new Transformation();
    private final float[] mTmpFloats = new float[9];

    public UniverseBackground(Context context) {
        super(context);
        setBackgroundColor(0xff000000);
        mSession = WindowManagerGlobal.getWindowSession();
        mContent = View.inflate(context, R.layout.universe, null);
        addView(mContent);
        mContent.findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                animateCollapse();
            }
        });
        mBottomAnchor = mContent.findViewById(R.id.bottom);
        mChoreographer = Choreographer.getInstance();
        loadDimens();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        loadDimens();
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
    }

    private void computeAveragePos(MotionEvent event) {
        final int num = event.getPointerCount();
        float x = 0, y = 0;
        for (int i=0; i<num; i++) {
            x += event.getX(i);
            y += event.getY(i);
        }
        mAverageX = x / num;
        mAverageY = y / num;
    }

    private void sendUniverseTransform() {
        if (getWindowToken() != null) {
            mUniverseTransform.getMatrix().getValues(mTmpFloats);
            try {
                mSession.setUniverseTransform(getWindowToken(), mUniverseTransform.getAlpha(),
                        mTmpFloats[Matrix.MTRANS_X], mTmpFloats[Matrix.MTRANS_Y],
                        mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                        mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);
            } catch (RemoteException e) {
            }
        }
    }

    public WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_UNIVERSE_BACKGROUND,
                    0
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
        // this will allow the window to run in an overlay on devices that support this
        if (ActivityManager.isHighEndGfx()) {
            lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        lp.setTitle("UniverseBackground");
        lp.windowAnimations = 0;
        return lp;
    }

    private int getExpandedViewMaxHeight() {
        return mBottomAnchor.getTop();
    }

    public void animateCollapse() {
        animateCollapse(1.0f);
    }

    public void animateCollapse(float velocityMultiplier) {
        if (SPEW) {
            Slog.d(TAG, "animateCollapse(): mExpanded=" + mExpanded
                    + " mExpandedVisible=" + mExpandedVisible
                    + " mExpanded=" + mExpanded
                    + " mAnimating=" + mAnimating
                    + " mAnimY=" + mAnimY
                    + " mAnimVel=" + mAnimVel);
        }

        mState = STATE_CLOSED;
        if (!mExpandedVisible) {
            return;
        }

        int y;
        if (mAnimating) {
            y = (int)mAnimY;
        } else {
            y = getExpandedViewMaxHeight()-1;
        }
        // Let the fling think that we're open so it goes in the right direction
        // and doesn't try to re-open the windowshade.
        mExpanded = true;
        prepareTracking(y, false);
        performFling(y, -mSelfCollapseVelocityPx*velocityMultiplier, true);
    }

    private void updateUniverseScale() {
        if (mYDelta > 0) {
            int w = getWidth();
            int h = getHeight();
            float scale = (h-mYDelta+.5f) / (float)h;
            mUniverseTransform.getMatrix().setScale(scale, scale, w/2, h);
            if (CHATTY) Log.i(TAG, "w=" + w + " h=" + h + " scale=" + scale
                    + ": " + mUniverseTransform);
            sendUniverseTransform();
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
        } else {
            if (CHATTY) Log.i(TAG, "mYDelta=" + mYDelta);
            mUniverseTransform.clear();
            sendUniverseTransform();
            if (getVisibility() == VISIBLE) {
                setVisibility(GONE);
            }
        }
    }

    void resetLastAnimTime() {
        mAnimLastTimeNanos = System.nanoTime();
        if (SPEW) {
            Throwable t = new Throwable();
            t.fillInStackTrace();
            Slog.d(TAG, "resetting last anim time=" + mAnimLastTimeNanos, t);
        }
    }

    void doAnimation(long frameTimeNanos) {
        if (mAnimating) {
            if (SPEW) Slog.d(TAG, "doAnimation dt=" + (frameTimeNanos - mAnimLastTimeNanos));
            if (SPEW) Slog.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim(frameTimeNanos);
            if (SPEW) {
                Slog.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
            }

            if (mAnimY >= getExpandedViewMaxHeight()-1 && !mClosing) {
                if (SPEW) Slog.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                mYDelta = getExpandedViewMaxHeight();
                updateUniverseScale();
                mExpanded = true;
                mState = STATE_OPEN;
                return;
            }

            if (mAnimY <= 0 && mClosing) {
                if (SPEW) Slog.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                mYDelta = 0;
                updateUniverseScale();
                mExpanded = false;
                mState = STATE_CLOSED;
                return;
            }

            mYDelta = (int)mAnimY;
            updateUniverseScale();
            mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                    mAnimationCallback, null);
        }
    }

    void stopTracking() {
        mTracking = false;
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    void incrementAnim(long frameTimeNanos) {
        final long deltaNanos = Math.max(frameTimeNanos - mAnimLastTimeNanos, 0);
        final float t = deltaNanos * 0.000000001f;                  // ns -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTimeNanos = frameTimeNanos;                        // ns
        //Slog.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }

    void prepareTracking(int y, boolean opening) {
        if (CHATTY) {
            Slog.d(TAG, "panel: beginning to track the user's touch, y=" + y + " opening=" + opening);
        }

        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        if (opening) {
            mAnimAccel = mExpandAccelPx;
            mAnimVel = mFlingExpandMinVelocityPx;
            mAnimY = y;
            mAnimating = true;
            mAnimatingReveal = true;
            resetLastAnimTime();
            mExpandedVisible = true;
        }
        if (mAnimating) {
            mAnimating = false;
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                    mAnimationCallback, null);
        }
    }

    void performFling(int y, float vel, boolean always) {
        if (CHATTY) {
            Slog.d(TAG, "panel: will fling, y=" + y + " vel=" + vel);
        }

        mAnimatingReveal = false;

        mAnimY = y;
        mAnimVel = vel;

        //Slog.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > mFlingCollapseMinVelocityPx
                    || (y > (getExpandedViewMaxHeight()*(1f-mCollapseMinDisplayFraction)) &&
                        vel > -mFlingExpandMinVelocityPx))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = mExpandAccelPx;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -mCollapseAccelPx;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        } else {
            if (always || (
                    vel > mFlingExpandMinVelocityPx
                    || (y > (getExpandedViewMaxHeight()*(1f-mExpandMinDisplayFraction)) &&
                        vel > -mFlingCollapseMinVelocityPx))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = mExpandAccelPx;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -mCollapseAccelPx;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        }
        //Slog.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        resetLastAnimTime();
        mAnimating = true;
        mClosing = mAnimAccel < 0;
        mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                mAnimationCallback, null);
        mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION,
                mAnimationCallback, null);

        stopTracking();
    }

    private void trackMovement(MotionEvent event) {
        mVelocityTracker.addMovement(event);
    }

    public boolean consumeEvent(MotionEvent event) {
        if (mState == STATE_CLOSED) {
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                // Second finger down, time to start opening!
                computeAveragePos(event);
                mDragStartX = mAverageX;
                mDragStartY = mAverageY;
                mYDelta = 0;
                mUniverseTransform.clear();
                sendUniverseTransform();
                setVisibility(VISIBLE);
                mState = STATE_OPENING;
                prepareTracking((int)mDragStartY, true);
                mVelocityTracker.clear();
                trackMovement(event);
                return true;
            }
            return false;
        }

        if (mState == STATE_OPENING) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mVelocityTracker.computeCurrentVelocity(1000);
                computeAveragePos(event);

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
                if (negative) {
                    vel = -vel;
                }

                if (CHATTY) {
                    Slog.d(TAG, String.format("gesture: vraw=(%f,%f) vnorm=(%f,%f) vlinear=%f",
                        mVelocityTracker.getXVelocity(),
                        mVelocityTracker.getYVelocity(),
                        xVel, yVel,
                        vel));
                }

                performFling((int)mAverageY, vel, false);
                mState = STATE_OPEN;
                return true;
            }

            computeAveragePos(event);
            mYDelta = (int)(mAverageY - mDragStartY);
            if (mYDelta > getExpandedViewMaxHeight()) {
                mYDelta = getExpandedViewMaxHeight();
            }
            updateUniverseScale();
            return true;
        }

        return false;
    }
}
