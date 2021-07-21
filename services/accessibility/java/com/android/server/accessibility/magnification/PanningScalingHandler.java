/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static java.lang.Math.abs;

import android.annotation.NonNull;
import android.annotation.UiContext;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.android.internal.R;

/**
 * Handles the behavior while receiving scaling and panning gestures if it's enabled.
 * Note it needs to receives all touch events even it's not enabled.
 */

class PanningScalingHandler extends
        GestureDetector.SimpleOnGestureListener
        implements ScaleGestureDetector.OnScaleGestureListener {

    private static final String TAG = "PanningScalingHandler";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    interface MagnificationDelegate {
        boolean processScroll(int displayId, float distanceX, float distanceY);
        void setScale(int displayId, float scale);
        float getScale(int displayId);
    }

    private final ScaleGestureDetector mScaleGestureDetector;
    private final GestureDetector mScrollGestureDetector;
    private final MagnificationDelegate mMagnificationDelegate;
    private final float mScalingThreshold;
    private final float mMinScale;
    private final float mMaxScale;
    private final int mDisplayId;
    private float mInitialScaleFactor = -1;
    // Used to identify if need to disable onScroll once scaling operation is ongoing.
    // We can remove it if we can fully distinguish these two gestures.
    private final boolean mBlockScroll;

    private boolean mScaling;
    private boolean mEnable;

    PanningScalingHandler(@UiContext Context context, float maxScale, float minScale,
            boolean blockScroll, @NonNull MagnificationDelegate magnificationDelegate) {
        mDisplayId = context.getDisplayId();
        mMaxScale = maxScale;
        mMinScale = minScale;
        mBlockScroll = blockScroll;
        mScaleGestureDetector = new ScaleGestureDetector(context, this, Handler.getMain());
        mScrollGestureDetector = new GestureDetector(context, this, Handler.getMain());
        mScaleGestureDetector.setQuickScaleEnabled(false);
        mMagnificationDelegate = magnificationDelegate;
        final TypedValue scaleValue = new TypedValue();
        context.getResources().getValue(
                R.dimen.config_screen_magnification_scaling_threshold,
                scaleValue, false);
        mScalingThreshold = scaleValue.getFloat();
    }

    void setEnabled(boolean enable) {
        clear();
        mEnable = enable;
    }

    void onTouchEvent(MotionEvent motionEvent) {
        mScaleGestureDetector.onTouchEvent(motionEvent);
        mScrollGestureDetector.onTouchEvent(motionEvent);
    }

    @Override
    // TODO: Try to distinguish onScroll with onScale correctly.
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!mEnable || (mBlockScroll && mScaling)) {
            return true;
        }
        return mMagnificationDelegate.processScroll(mDisplayId, distanceX, distanceY);
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        if (DEBUG) {
            Slog.i(TAG, "onScale: triggered ");
        }
        if (!mScaling) {
            if (mInitialScaleFactor < 0) {
                mInitialScaleFactor = detector.getScaleFactor();
                return false;
            }
            final float deltaScale = detector.getScaleFactor() - mInitialScaleFactor;
            mScaling = abs(deltaScale) > mScalingThreshold;
            return mScaling;
        }

        // Don't allow a gesture to move the user further outside the
        // desired bounds for gesture-controlled scaling.
        final float scale;
        final float initialScale = mMagnificationDelegate.getScale(mDisplayId);
        final float targetScale = initialScale * detector.getScaleFactor();

        if (targetScale > mMaxScale && targetScale > initialScale) {
            // The target scale is too big and getting bigger.
            scale = mMaxScale;
        } else if (targetScale < mMinScale && targetScale < initialScale) {
            // The target scale is too small and getting smaller.
            scale = mMinScale;
        } else {
            // The target scale may be outside our bounds, but at least
            // it's moving in the right direction. This avoids a "jump" if
            // we're at odds with some other service's desired bounds.
            scale = targetScale;
        }

        if (DEBUG) Slog.i(TAG, "Scaled content to: " + scale + "x");
        mMagnificationDelegate.setScale(mDisplayId, scale);
        return /* handled: */ true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        return mEnable;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        clear();
    }

    void clear() {
        mInitialScaleFactor = -1;
        mScaling = false;
    }

    @Override
    public String toString() {
        return "PanningScalingHandler{"
                + "mInitialScaleFactor=" + mInitialScaleFactor
                + ", mScaling=" + mScaling
                + '}';
    }
}

