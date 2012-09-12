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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Vibrator;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.View.OnClickListener;

import java.util.Stack;

public class ExpandHelper implements Gefingerpoken, OnClickListener {
    public interface Callback {
        View getChildAtRawPosition(float x, float y);
        View getChildAtPosition(float x, float y);
        boolean canChildBeExpanded(View v);
        boolean setUserExpandedChild(View v, boolean userExpanded);
        boolean setUserLockedChild(View v, boolean userLocked);
    }

    private static final String TAG = "ExpandHelper";
    protected static final boolean DEBUG = false;
    protected static final boolean DEBUG_SCALE = false;
    protected static final boolean DEBUG_GLOW = false;
    private static final long EXPAND_DURATION = 250;
    private static final long GLOW_DURATION = 150;

    // Set to false to disable focus-based gestures (two-finger pull).
    private static final boolean USE_DRAG = true;
    // Set to false to disable scale-based gestures (both horizontal and vertical).
    private static final boolean USE_SPAN = true;
    // Both gestures types may be active at the same time.
    // At least one gesture type should be active.
    // A variant of the screwdriver gesture will emerge from either gesture type.

    // amount of overstretch for maximum brightness expressed in U
    // 2f: maximum brightness is stretching a 1U to 3U, or a 4U to 6U
    private static final float STRETCH_INTERVAL = 2f;

    // level of glow for a touch, without overstretch
    // overstretch fills the range (GLOW_BASE, 1.0]
    private static final float GLOW_BASE = 0.5f;

    @SuppressWarnings("unused")
    private Context mContext;

    private boolean mStretching;
    private boolean mPullingWithOneFinger;
    private boolean mWatchingForPull;
    private boolean mHasPopped;
    private View mEventSource;
    private View mCurrView;
    private View mCurrViewTopGlow;
    private View mCurrViewBottomGlow;
    private float mOldHeight;
    private float mNaturalHeight;
    private float mInitialTouchFocusY;
    private float mInitialTouchY;
    private float mInitialTouchSpan;
    private int mTouchSlop;
    private int mLastMotionY;
    private float mPopLimit;
    private int mPopDuration;
    private Callback mCallback;
    private ScaleGestureDetector mDetector;
    private ViewScaler mScaler;
    private ObjectAnimator mScaleAnimation;
    private AnimatorSet mGlowAnimationSet;
    private ObjectAnimator mGlowTopAnimation;
    private ObjectAnimator mGlowBottomAnimation;
    private Vibrator mVibrator;

    private int mSmallSize;
    private int mLargeSize;
    private float mMaximumStretch;

    private int mGravity;

    private View mScrollView;

    private class ViewScaler {
        View mView;

        public ViewScaler() {}
        public void setView(View v) {
            mView = v;
        }
        public void setHeight(float h) {
            if (DEBUG_SCALE) Slog.v(TAG, "SetHeight: setting to " + h);
            ViewGroup.LayoutParams lp = mView.getLayoutParams();
            lp.height = (int)h;
            mView.setLayoutParams(lp);
            mView.requestLayout();
        }
        public float getHeight() {
            int height = mView.getLayoutParams().height;
            if (height < 0) {
                height = mView.getMeasuredHeight();
            }
            return (float) height;
        }
        public int getNaturalHeight(int maximum) {
            ViewGroup.LayoutParams lp = mView.getLayoutParams();
            if (DEBUG_SCALE) Slog.v(TAG, "Inspecting a child of type: " +
                    mView.getClass().getName());
            int oldHeight = lp.height;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mView.setLayoutParams(lp);
            mView.measure(
                    View.MeasureSpec.makeMeasureSpec(mView.getMeasuredWidth(),
                                                     View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maximum,
                                                     View.MeasureSpec.AT_MOST));
            lp.height = oldHeight;
            mView.setLayoutParams(lp);
            return mView.getMeasuredHeight();
        }
    }

    /**
     * Handle expansion gestures to expand and contract children of the callback.
     *
     * @param context application context
     * @param callback the container that holds the items to be manipulated
     * @param small the smallest allowable size for the manuipulated items.
     * @param large the largest allowable size for the manuipulated items.
     * @param scoller if non-null also manipulate the scroll position to obey the gravity.
     */
    public ExpandHelper(Context context, Callback callback, int small, int large) {
        mSmallSize = small;
        mMaximumStretch = mSmallSize * STRETCH_INTERVAL;
        mLargeSize = large;
        mContext = context;
        mCallback = callback;
        mScaler = new ViewScaler();
        mGravity = Gravity.TOP;
        mScaleAnimation = ObjectAnimator.ofFloat(mScaler, "height", 0f);
        mScaleAnimation.setDuration(EXPAND_DURATION);
        mPopLimit = mContext.getResources().getDimension(R.dimen.one_finger_pop_limit);
        mPopDuration = mContext.getResources().getInteger(R.integer.one_finger_pop_duration_ms);

        AnimatorListenerAdapter glowVisibilityController = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                View target = (View) ((ObjectAnimator) animation).getTarget();
                if (target.getAlpha() <= 0.0f) {
                    target.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                View target = (View) ((ObjectAnimator) animation).getTarget();
                if (target.getAlpha() <= 0.0f) {
                    target.setVisibility(View.INVISIBLE);
                }
            }
        };

        mGlowTopAnimation = ObjectAnimator.ofFloat(null, "alpha", 0f);
        mGlowTopAnimation.addListener(glowVisibilityController);
        mGlowBottomAnimation = ObjectAnimator.ofFloat(null, "alpha", 0f);
        mGlowBottomAnimation.addListener(glowVisibilityController);
        mGlowAnimationSet = new AnimatorSet();
        mGlowAnimationSet.play(mGlowTopAnimation).with(mGlowBottomAnimation);
        mGlowAnimationSet.setDuration(GLOW_DURATION);

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mTouchSlop = configuration.getScaledTouchSlop();

        mDetector =
                new ScaleGestureDetector(context,
                                         new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (DEBUG_SCALE) Slog.v(TAG, "onscalebegin()");
                float x = detector.getFocusX();
                float y = detector.getFocusY();

                // your fingers have to be somewhat close to the bounds of the view in question
                mInitialTouchFocusY = detector.getFocusY();
                mInitialTouchSpan = Math.abs(detector.getCurrentSpan());
                if (DEBUG_SCALE) Slog.d(TAG, "got mInitialTouchSpan: (" + mInitialTouchSpan + ")");

                mStretching = initScale(findView(x, y));
                return mStretching;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (DEBUG_SCALE) Slog.v(TAG, "onscale() on " + mCurrView);

                // are we scaling or dragging?
                float span = Math.abs(detector.getCurrentSpan()) - mInitialTouchSpan;
                span *= USE_SPAN ? 1f : 0f;
                float drag = detector.getFocusY() - mInitialTouchFocusY;
                drag *= USE_DRAG ? 1f : 0f;
                drag *= mGravity == Gravity.BOTTOM ? -1f : 1f;
                float pull = Math.abs(drag) + Math.abs(span) + 1f;
                float hand = drag * Math.abs(drag) / pull + span * Math.abs(span) / pull;
                float target = hand + mOldHeight;
                float newHeight = clamp(target);
                mScaler.setHeight(newHeight);

                setGlow(calculateGlow(target, newHeight));
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (DEBUG_SCALE) Slog.v(TAG, "onscaleend()");
                // I guess we're alone now
                if (DEBUG_SCALE) Slog.d(TAG, "scale end");
                finishScale(false);
                clearView();
                mStretching = false;
            }
        });
    }

    private float clamp(float target) {
        float out = target;
        out = out < mSmallSize ? mSmallSize : (out > mLargeSize ? mLargeSize : out);
        out = out > mNaturalHeight ? mNaturalHeight : out;
        return out;
    }

    private View findView(float x, float y) {
        View v = null;
        if (mEventSource != null) {
            int[] location = new int[2];
            mEventSource.getLocationOnScreen(location);
            x += (float) location[0];
            y += (float) location[1];
            v = mCallback.getChildAtRawPosition(x, y);
        } else {
            v = mCallback.getChildAtPosition(x, y);
        }
        return v;
    }

    private boolean isInside(View v, float x, float y) {
        if (DEBUG) Slog.d(TAG, "isinside (" + x + ", " + y + ")");

        if (v == null) {
            if (DEBUG) Slog.d(TAG, "isinside null subject");
            return false;
        }
        if (mEventSource != null) {
            int[] location = new int[2];
            mEventSource.getLocationOnScreen(location);
            x += (float) location[0];
            y += (float) location[1];
            if (DEBUG) Slog.d(TAG, "  to global (" + x + ", " + y + ")");
        }
        int[] location = new int[2];
        v.getLocationOnScreen(location);
        x -= (float) location[0];
        y -= (float) location[1];
        if (DEBUG) Slog.d(TAG, "  to local (" + x + ", " + y + ")");
        if (DEBUG) Slog.d(TAG, "  inside (" + v.getWidth() + ", " + v.getHeight() + ")");
        boolean inside = (x > 0f && y > 0f && x < v.getWidth() & y < v.getHeight());
        return inside;
    }

    public void setEventSource(View eventSource) {
        mEventSource = eventSource;
    }

    public void setGravity(int gravity) {
        mGravity = gravity;
    }

    public void setScrollView(View scrollView) {
        mScrollView = scrollView;
    }

    private float calculateGlow(float target, float actual) {
        // glow if overscale
        if (DEBUG_GLOW) Slog.d(TAG, "target: " + target + " actual: " + actual);
        float stretch = (float) Math.abs((target - actual) / mMaximumStretch);
        float strength = 1f / (1f + (float) Math.pow(Math.E, -1 * ((8f * stretch) - 5f)));
        if (DEBUG_GLOW) Slog.d(TAG, "stretch: " + stretch + " strength: " + strength);
        return (GLOW_BASE + strength * (1f - GLOW_BASE));
    }

    public void setGlow(float glow) {
        if (!mGlowAnimationSet.isRunning() || glow == 0f) {
            if (mGlowAnimationSet.isRunning()) {
                mGlowAnimationSet.end();
            }
            if (mCurrViewTopGlow != null && mCurrViewBottomGlow != null) {
                if (glow == 0f || mCurrViewTopGlow.getAlpha() == 0f) {
                    // animate glow in and out
                    mGlowTopAnimation.setTarget(mCurrViewTopGlow);
                    mGlowBottomAnimation.setTarget(mCurrViewBottomGlow);
                    mGlowTopAnimation.setFloatValues(glow);
                    mGlowBottomAnimation.setFloatValues(glow);
                    mGlowAnimationSet.setupStartValues();
                    mGlowAnimationSet.start();
                } else {
                    // set it explicitly in reponse to touches.
                    mCurrViewTopGlow.setAlpha(glow);
                    mCurrViewBottomGlow.setAlpha(glow);
                    handleGlowVisibility();
                }
            }
        }
    }

    private void handleGlowVisibility() {
        mCurrViewTopGlow.setVisibility(mCurrViewTopGlow.getAlpha() <= 0.0f ?
                View.INVISIBLE : View.VISIBLE);
        mCurrViewBottomGlow.setVisibility(mCurrViewBottomGlow.getAlpha() <= 0.0f ?
                View.INVISIBLE : View.VISIBLE);
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Slog.d(TAG, "interceptTouch: act=" + (ev.getAction()) +
                         " stretching=" + mStretching +
                         " onefinger=" + mPullingWithOneFinger);
        // check for a two-finger gesture
        mDetector.onTouchEvent(ev);
        if (mStretching) {
            return true;
        } else {
            final int action = ev.getAction();
            if ((action == MotionEvent.ACTION_MOVE) && mPullingWithOneFinger) {
                return true;
            }
            if (mScrollView != null && mScrollView.getScrollY() > 0) {
                return false;
            }
            switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                if (mWatchingForPull) {
                    final int x = (int) ev.getX();
                    final int y = (int) ev.getY();
                    final int yDiff = y - mLastMotionY;
                    if (yDiff > mTouchSlop) {
                        mLastMotionY = y;
                        mPullingWithOneFinger = initScale(findView(x, y));
                        if (mPullingWithOneFinger) {
                            mInitialTouchY = mLastMotionY;
                            mHasPopped = false;
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN:
                mWatchingForPull = isInside(mScrollView, ev.getX(), ev.getY());
                mLastMotionY = (int) ev.getY();
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mPullingWithOneFinger) {
                    finishScale(false);
                    clearView();
                }
                mPullingWithOneFinger = false;
                mWatchingForPull = false;
                break;
            }
            return mPullingWithOneFinger;
        }
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        if (DEBUG_SCALE) Slog.d(TAG, "touch: act=" + (action) +
                         " stretching=" + mStretching +
                         " onefinger=" + mPullingWithOneFinger);
        if (mStretching) {
            mDetector.onTouchEvent(ev);
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                if (mPullingWithOneFinger) {
                    final float rawHeight = ev.getY() - mInitialTouchY + mOldHeight;
                    final float newHeight = clamp(rawHeight);
                    final boolean wasClosed = (mOldHeight == mSmallSize);
                    boolean isFinished = false;
                    if (rawHeight > mNaturalHeight) {
                        isFinished = true;
                    }
                    if (rawHeight < mSmallSize) {
                        isFinished = true;
                    }

                    final float pull = Math.abs(ev.getY() - mInitialTouchY);
                    if (mHasPopped || pull > mPopLimit) {
                        if (!mHasPopped) {
                            vibrate(mPopDuration);
                            mHasPopped = true;
                        }
                    }

                    if (mHasPopped) {
                        mScaler.setHeight(newHeight);
                        setGlow(GLOW_BASE);
                    } else {
                        setGlow(calculateGlow(4f * pull, 0f));
                    }

                    final int x = (int) ev.getX();
                    final int y = (int) ev.getY();
                    View underPointer = findView(x, y);
                    if (isFinished && underPointer != null && underPointer != mCurrView) {
                        finishScale(false);
                        initScale(underPointer);
                        mInitialTouchY = ev.getY();
                        mHasPopped = false;
                    }
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (DEBUG) Slog.d(TAG, "cancel");
                mStretching = false;
                if (mPullingWithOneFinger) {
                    finishScale(false);
                    mPullingWithOneFinger = false;
                }
                clearView();
                break;
        }
        return true;
    }
    private boolean initScale(View v) {
        if (v != null) {
            if (DEBUG) Slog.d(TAG, "scale begins on view: " + v);
            mCallback.setUserLockedChild(v, true);
            setView(v);
            setGlow(GLOW_BASE);
            mScaler.setView(v);
            mOldHeight = mScaler.getHeight();
            if (mCallback.canChildBeExpanded(v)) {
                if (DEBUG) Slog.d(TAG, "working on an expandable child");
                mNaturalHeight = mScaler.getNaturalHeight(mLargeSize);
            } else {
                if (DEBUG) Slog.d(TAG, "working on a non-expandable child");
                mNaturalHeight = mOldHeight;
            }
            if (DEBUG) Slog.d(TAG, "got mOldHeight: " + mOldHeight +
                        " mNaturalHeight: " + mNaturalHeight);
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        } else {
            return false;
        }
    }

    private void finishScale(boolean force) {
        float currentHeight = mScaler.getHeight();
        float targetHeight = mSmallSize;
        float h = mScaler.getHeight();
        final boolean wasClosed = (mOldHeight == mSmallSize);
        if (wasClosed) {
            targetHeight = (force || currentHeight > mSmallSize) ? mNaturalHeight : mSmallSize;
        } else {
            targetHeight = (force || currentHeight < mNaturalHeight) ? mSmallSize : mNaturalHeight;
        }
        if (mScaleAnimation.isRunning()) {
            mScaleAnimation.cancel();
        }
        setGlow(0f);
        mCallback.setUserExpandedChild(mCurrView, h == mNaturalHeight);
        if (targetHeight != currentHeight) {
            mScaleAnimation.setFloatValues(targetHeight);
            mScaleAnimation.setupStartValues();
            mScaleAnimation.start();
        }
        mCallback.setUserLockedChild(mCurrView, false);
        if (DEBUG) Slog.d(TAG, "scale was finished on view: " + mCurrView);
    }

    private void clearView() {
        mCurrView = null;
        mCurrViewTopGlow = null;
        mCurrViewBottomGlow = null;
    }

    private void setView(View v) {
        mCurrView = v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            mCurrViewTopGlow = g.findViewById(R.id.top_glow);
            mCurrViewBottomGlow = g.findViewById(R.id.bottom_glow);
            if (DEBUG) {
                String debugLog = "Looking for glows: " +
                        (mCurrViewTopGlow != null ? "found top " : "didn't find top") +
                        (mCurrViewBottomGlow != null ? "found bottom " : "didn't find bottom");
                Slog.v(TAG,  debugLog);
            }
        }
    }

    @Override
    public void onClick(View v) {
        initScale(v);
        finishScale(true);
        clearView();
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        if (mVibrator == null) {
            mVibrator = (android.os.Vibrator)
                    mContext.getSystemService(Context.VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(duration);
    }
}

