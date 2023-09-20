/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import android.annotation.IntRange;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.inspector.InspectableProperty;
import android.widget.RemoteViews.RemoteView;

/**
 * Simple {@link ViewAnimator} that will animate between two or more views
 * that have been added to it.  Only one child is shown at a time.  If
 * requested, can automatically flip between each child at a regular interval.
 *
 * @attr ref android.R.styleable#ViewFlipper_flipInterval
 * @attr ref android.R.styleable#ViewFlipper_autoStart
 */
@RemoteView
public class ViewFlipper extends ViewAnimator {
    private static final String TAG = "ViewFlipper";
    private static final boolean LOGD = false;

    private static final int DEFAULT_INTERVAL = 3000;

    private int mFlipInterval = DEFAULT_INTERVAL;
    private boolean mAutoStart = false;

    private boolean mRunning = false;
    private boolean mStarted = false;
    private boolean mVisible = false;

    public ViewFlipper(Context context) {
        super(context);
    }

    public ViewFlipper(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ViewFlipper);
        mFlipInterval = a.getInt(
                com.android.internal.R.styleable.ViewFlipper_flipInterval, DEFAULT_INTERVAL);
        mAutoStart = a.getBoolean(
                com.android.internal.R.styleable.ViewFlipper_autoStart, false);
        a.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mAutoStart) {
            // Automatically start when requested
            startFlipping();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mVisible = false;
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mVisible = visibility == VISIBLE;
        updateRunning(false);
    }

    /**
     * How long to wait before flipping to the next view
     *
     * @param milliseconds
     *            time in milliseconds
     */
    @android.view.RemotableViewMethod
    public void setFlipInterval(@IntRange(from = 0) int milliseconds) {
        mFlipInterval = milliseconds;
    }

    /**
     * Get the delay before flipping to the next view.
     *
     * @return delay time in milliseconds
     */
    @InspectableProperty
    @IntRange(from = 0)
    public int getFlipInterval() {
        return mFlipInterval;
    }

    /**
     * Start a timer to cycle through child views
     */
    public void startFlipping() {
        mStarted = true;
        updateRunning();
    }

    /**
     * No more flips
     */
    public void stopFlipping() {
        mStarted = false;
        updateRunning();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return ViewFlipper.class.getName();
    }

    /**
     * Internal method to start or stop dispatching flip {@link Message} based
     * on {@link #mRunning} and {@link #mVisible} state.
     */
    private void updateRunning() {
        updateRunning(true);
    }

    /**
     * Internal method to start or stop dispatching flip {@link Message} based
     * on {@link #mRunning} and {@link #mVisible} state.
     *
     * @param flipNow Determines whether or not to execute the animation now, in
     *            addition to queuing future flips. If omitted, defaults to
     *            true.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void updateRunning(boolean flipNow) {
        boolean running = mVisible && mStarted;
        if (running != mRunning) {
            if (running) {
                showOnly(mWhichChild, flipNow);
                postDelayed(mFlipRunnable, mFlipInterval);
            } else {
                removeCallbacks(mFlipRunnable);
            }
            mRunning = running;
        }
        if (LOGD) {
            Log.d(TAG, "updateRunning() mVisible=" + mVisible + ", mStarted=" + mStarted
                    + ", mRunning=" + mRunning);
        }
    }

    /**
     * Returns true if the child views are flipping.
     */
    @InspectableProperty(hasAttributeId = false)
    public boolean isFlipping() {
        return mStarted;
    }

    /**
     * Set if this view automatically calls {@link #startFlipping()} when it
     * becomes attached to a window.
     */
    public void setAutoStart(boolean autoStart) {
        mAutoStart = autoStart;
    }

    /**
     * Returns true if this view automatically calls {@link #startFlipping()}
     * when it becomes attached to a window.
     */
    @InspectableProperty
    public boolean isAutoStart() {
        return mAutoStart;
    }

    private final Runnable mFlipRunnable = new Runnable() {
        @Override
        public void run() {
            if (mRunning) {
                showNext();
                postDelayed(mFlipRunnable, mFlipInterval);
            }
        }
    };
}
