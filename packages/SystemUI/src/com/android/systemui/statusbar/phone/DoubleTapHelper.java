/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.systemui.R;

/**
 * Detects a double tap.
 */
public class DoubleTapHelper {

    private static final long DOUBLETAP_TIMEOUT_MS = 1200;

    private final View mView;
    private final ActivationListener mActivationListener;
    private final DoubleTapListener mDoubleTapListener;
    private final SlideBackListener mSlideBackListener;
    private final DoubleTapLogListener mDoubleTapLogListener;

    private float mTouchSlop;
    private float mDoubleTapSlop;

    private boolean mActivated;

    private float mDownX;
    private float mDownY;
    private boolean mTrackTouch;

    private float mActivationX;
    private float mActivationY;
    private Runnable mTapTimeoutRunnable = this::makeInactive;

    public DoubleTapHelper(View view, ActivationListener activationListener,
            DoubleTapListener doubleTapListener, SlideBackListener slideBackListener,
            DoubleTapLogListener doubleTapLogListener) {
        mTouchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        mDoubleTapSlop = view.getResources().getDimension(R.dimen.double_tap_slop);
        mView = view;

        mActivationListener = activationListener;
        mDoubleTapListener = doubleTapListener;
        mSlideBackListener = slideBackListener;
        mDoubleTapLogListener = doubleTapLogListener;
    }

    public boolean onTouchEvent(MotionEvent event) {
        return onTouchEvent(event, Integer.MAX_VALUE);
    }

    public boolean onTouchEvent(MotionEvent event, int maxTouchableHeight) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mTrackTouch = true;
                if (mDownY > maxTouchableHeight) {
                    mTrackTouch = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isWithinTouchSlop(event)) {
                    makeInactive();
                    mTrackTouch = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isWithinTouchSlop(event)) {
                    if (mSlideBackListener != null && mSlideBackListener.onSlideBack()) {
                        return true;
                    }
                    if (!mActivated) {
                        makeActive();
                        mView.postDelayed(mTapTimeoutRunnable, DOUBLETAP_TIMEOUT_MS);
                        mActivationX = event.getX();
                        mActivationY = event.getY();
                    } else {
                        boolean withinDoubleTapSlop = isWithinDoubleTapSlop(event);
                        if (mDoubleTapLogListener != null) {
                            mDoubleTapLogListener.onDoubleTapLog(withinDoubleTapSlop,
                                    event.getX() - mActivationX,
                                    event.getY() - mActivationY);
                        }
                        if (withinDoubleTapSlop) {
                            makeInactive();
                            if (!mDoubleTapListener.onDoubleTap()) {
                                return false;
                            }
                        } else {
                            makeInactive();
                            mTrackTouch = false;
                        }
                    }
                } else {
                    makeInactive();
                    mTrackTouch = false;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                makeInactive();
                mTrackTouch = false;
                break;
            default:
                break;
        }
        return mTrackTouch;
    }

    private void makeActive() {
        if (!mActivated) {
            mActivated = true;
            mActivationListener.onActiveChanged(true);
        }
    }

    private void makeInactive() {
        if (mActivated) {
            mActivated = false;
            mActivationListener.onActiveChanged(false);
            mView.removeCallbacks(mTapTimeoutRunnable);
        }
    }

    private boolean isWithinTouchSlop(MotionEvent event) {
        return Math.abs(event.getX() - mDownX) < mTouchSlop
                && Math.abs(event.getY() - mDownY) < mTouchSlop;
    }

    public boolean isWithinDoubleTapSlop(MotionEvent event) {
        if (!mActivated) {
            // If we're not activated there's no double tap slop to satisfy.
            return true;
        }

        return Math.abs(event.getX() - mActivationX) < mDoubleTapSlop
                && Math.abs(event.getY() - mActivationY) < mDoubleTapSlop;
    }

    @FunctionalInterface
    public interface ActivationListener {
        void onActiveChanged(boolean active);
    }

    @FunctionalInterface
    public interface DoubleTapListener {
        boolean onDoubleTap();
    }

    @FunctionalInterface
    public interface SlideBackListener {
        boolean onSlideBack();
    }

    @FunctionalInterface
    public interface DoubleTapLogListener {
        void onDoubleTapLog(boolean accepted, float dx, float dy);
    }
}
