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

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import com.android.internal.widget.SizeAdaptiveLayout;

public class ExpandHelper implements Gefingerpoken, OnClickListener {
    public interface Callback {
        View getChildAtPosition(MotionEvent ev);
        View getChildAtPosition(float x, float y);
        boolean canChildBeExpanded(View v);
    }

    private static final String TAG = "ExpandHelper";
    protected static final boolean DEBUG = false;
    private static final long EXPAND_DURATION = 250;

    // amount of overstretch for maximum brightness expressed in U
    // 2f: maximum brightness is stretching a 1U to 3U, or a 4U to 6U
    private static final float STRETCH_INTERVAL = 2f;

    // level of glow for a touch, without overstretch
    // overstretch fills the range (GLOW_BASE, 1.0]
    private static final float GLOW_BASE = 0.5f;

    @SuppressWarnings("unused")
    private Context mContext;

    private boolean mStretching;
    private View mCurrView;
    private View mCurrViewTopGlow;
    private View mCurrViewBottomGlow;
    private float mOldHeight;
    private float mNaturalHeight;
    private float mInitialTouchSpan;
    private Callback mCallback;
    private ScaleGestureDetector mDetector;
    private ViewScaler mScaler;
    private ObjectAnimator mAnimation;

    private int mSmallSize;
    private int mLargeSize;
    private float mMaximumStretch;

    private class ViewScaler {
        View mView;
        public ViewScaler() {}
        public void setView(View v) {
            mView = v;
        }
        public void setHeight(float h) {
            if (DEBUG) Log.v(TAG, "SetHeight: setting to " + h);
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
	    if (DEBUG) Log.v(TAG, "Inspecting a child of type: " + mView.getClass().getName());
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

    public ExpandHelper(Context context, Callback callback, int small, int large) {
        mSmallSize = small;
        mMaximumStretch = mSmallSize * STRETCH_INTERVAL;
        mLargeSize = large;
        mContext = context;
        mCallback = callback;
        mScaler = new ViewScaler();
        mDetector =
                new ScaleGestureDetector(context,
                                         new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (DEBUG) Log.v(TAG, "onscalebegin()");
                View v = mCallback.getChildAtPosition(detector.getFocusX(), detector.getFocusY());

                // your fingers have to be somewhat close to the bounds of the view in question
                mInitialTouchSpan = Math.abs(detector.getCurrentSpanY());
                if (DEBUG) Log.d(TAG, "got mInitialTouchSpan: " + mInitialTouchSpan);

                mStretching = initScale(v);
                return mStretching;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (DEBUG) Log.v(TAG, "onscale() on " + mCurrView);
                float h = Math.abs(detector.getCurrentSpanY());
                if (DEBUG) Log.d(TAG, "current span is: " + h);
                h = h + mOldHeight - mInitialTouchSpan;
                float target = h;
                if (DEBUG) Log.d(TAG, "target is: " + target);
                h = h<mSmallSize?mSmallSize:(h>mLargeSize?mLargeSize:h);
                h = h>mNaturalHeight?mNaturalHeight:h;
                if (DEBUG) Log.d(TAG, "scale continues: h=" + h);
                mScaler.setHeight(h);
                float stretch = (float) Math.abs((target - h) / mMaximumStretch);
                float strength = 1f / (1f + (float) Math.pow(Math.E, -1 * ((8f * stretch) - 5f)));
                if (DEBUG) Log.d(TAG, "stretch: " + stretch + " strength: " + strength);
                setGlow(GLOW_BASE + strength * (1f - GLOW_BASE));
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (DEBUG) Log.v(TAG, "onscaleend()");
                // I guess we're alone now
                if (DEBUG) Log.d(TAG, "scale end");
                finishScale(false);
            }
        });
    }
    public void setGlow(float glow) {
        if (mCurrViewTopGlow != null) {
            mCurrViewTopGlow.setAlpha(glow);
        }
        if (mCurrViewBottomGlow != null) {
            mCurrViewBottomGlow.setAlpha(glow);
        }
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.d(TAG, "interceptTouch: act=" + (ev.getAction()) +
                         " stretching=" + mStretching);
        mDetector.onTouchEvent(ev);
        return mStretching;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        if (DEBUG) Log.d(TAG, "touch: act=" + (action) + " stretching=" + mStretching);
        if (mStretching) {
            mDetector.onTouchEvent(ev);
        }
        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mStretching = false;
                clearView();
                break;
        }
        return true;
    }
    private boolean initScale(View v) {
        if (v != null) {
            if (DEBUG) Log.d(TAG, "scale begins on view: " + v);
            mStretching = true;
            setView(v);
            setGlow(GLOW_BASE);
            mScaler.setView(v);
            mOldHeight = mScaler.getHeight();
            if (mCallback.canChildBeExpanded(v)) {
                if (DEBUG) Log.d(TAG, "working on an expandable child");
                mNaturalHeight = mScaler.getNaturalHeight(mLargeSize);
            } else {
                if (DEBUG) Log.d(TAG, "working on a non-expandable child");
                mNaturalHeight = mOldHeight;
            }
            if (DEBUG) Log.d(TAG, "got mOldHeight: " + mOldHeight +
                        " mNaturalHeight: " + mNaturalHeight);
            v.getParent().requestDisallowInterceptTouchEvent(true);
        }
        return mStretching;
    }

    private void finishScale(boolean force) {
        float h = mScaler.getHeight();
        final boolean wasClosed = (mOldHeight == mSmallSize);
        if (wasClosed) {
            h = (force || h > mSmallSize) ? mNaturalHeight : mSmallSize;
        } else {
            h = (force || h < mNaturalHeight) ? mSmallSize : mNaturalHeight;
        }
        if (DEBUG && mCurrView != null) mCurrView.setBackgroundColor(0);
        mAnimation = ObjectAnimator.ofFloat(mScaler, "height", h).setDuration(EXPAND_DURATION);
        mAnimation.start();
        mStretching = false;
        setGlow(0f);
        clearView();
    }

    private void clearView() {
        mCurrView = null;
        mCurrViewTopGlow = null;
        mCurrViewBottomGlow = null;
    }

    private void setView(View v) {
        mCurrView = null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            mCurrViewTopGlow = g.findViewById(R.id.top_glow);
            mCurrViewBottomGlow = g.findViewById(R.id.bottom_glow);
	    if (DEBUG) {
                String debugLog = "Looking for glows: " + 
                        (mCurrViewTopGlow != null ? "found top " : "didn't find top") +
                        (mCurrViewBottomGlow != null ? "found bottom " : "didn't find bottom");
                Log.v(TAG,  debugLog);
            }
        }
    }

    @Override
    public void onClick(View v) {
        initScale(v);
        finishScale(true);

    }
}
