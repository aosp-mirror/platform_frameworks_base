/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.Chronometer.OnChronometerTickListener;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.RemoteViews.RemoteView;

/**
 * Container that links together a {@link ProgressBar} and {@link Chronometer}
 * as children. It subscribes to {@link Chronometer#OnChronometerTickListener}
 * and updates the {@link ProgressBar} based on a preset finishing time.
 * <p>
 * This widget expects to contain two children with specific ids
 * {@link android.R.id.progress} and {@link android.R.id.text1}.
 * <p>
 * If the {@link Chronometer} {@link android.R.attr#layout_width} is
 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}, then the
 * {@link android.R.attr#gravity} will be used to automatically move it with
 * respect to the {@link ProgressBar} position. For example, if
 * {@link android.view.Gravity#LEFT} then the {@link Chronometer} will be placed
 * just ahead of the leading edge of the {@link ProgressBar} position.
 */
@RemoteView
public class TextProgressBar extends RelativeLayout implements OnChronometerTickListener {
    public static final String TAG = "TextProgressBar"; 
    
    static final int CHRONOMETER_ID = android.R.id.text1;
    static final int PROGRESSBAR_ID = android.R.id.progress;
    
    Chronometer mChronometer = null;
    ProgressBar mProgressBar = null;
    
    long mDurationBase = -1;
    int mDuration = -1;

    boolean mChronometerFollow = false;
    int mChronometerGravity = Gravity.NO_GRAVITY;

    public TextProgressBar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    
    public TextProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TextProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TextProgressBar(Context context) {
        super(context);
    }

    /**
     * Catch any interesting children when they are added.
     */
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        
        int childId = child.getId();
        if (childId == CHRONOMETER_ID && child instanceof Chronometer) {
            mChronometer = (Chronometer) child;
            mChronometer.setOnChronometerTickListener(this);
            
            // Check if Chronometer should move with with ProgressBar 
            mChronometerFollow = (params.width == ViewGroup.LayoutParams.WRAP_CONTENT);
            mChronometerGravity = (mChronometer.getGravity() &
                    Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            
        } else if (childId == PROGRESSBAR_ID && child instanceof ProgressBar) {
            mProgressBar = (ProgressBar) child;
        }
    }

    /**
     * Set the expected termination time of the running {@link Chronometer}.
     * This value is used to adjust the {@link ProgressBar} against the elapsed
     * time.
     * <p>
     * Call this <b>after</b> adjusting the {@link Chronometer} base, if
     * necessary.
     * 
     * @param durationBase Use the {@link SystemClock#elapsedRealtime} time
     *            base.
     */
    @android.view.RemotableViewMethod
    public void setDurationBase(long durationBase) {
        mDurationBase = durationBase;
        
        if (mProgressBar == null || mChronometer == null) {
            throw new RuntimeException("Expecting child ProgressBar with id " +
                    "'android.R.id.progress' and Chronometer id 'android.R.id.text1'");
        }
        
        // Update the ProgressBar maximum relative to Chronometer base
        mDuration = (int) (durationBase - mChronometer.getBase());
        if (mDuration <= 0) {
            mDuration = 1;
        }
        mProgressBar.setMax(mDuration);
    }
    
    /**
     * Callback when {@link Chronometer} changes, indicating that we should
     * update the {@link ProgressBar} and change the layout if necessary.
     */
    public void onChronometerTick(Chronometer chronometer) {
        if (mProgressBar == null) {
            throw new RuntimeException(
                "Expecting child ProgressBar with id 'android.R.id.progress'");
        }
        
        // Stop Chronometer if we're past duration
        long now = SystemClock.elapsedRealtime();
        if (now >= mDurationBase) {
            mChronometer.stop();
        }

        // Update the ProgressBar status
        int remaining = (int) (mDurationBase - now);
        mProgressBar.setProgress(mDuration - remaining);
        
        // Move the Chronometer if gravity is set correctly
        if (mChronometerFollow) {
            RelativeLayout.LayoutParams params;
            
            // Calculate estimate of ProgressBar leading edge position
            params = (RelativeLayout.LayoutParams) mProgressBar.getLayoutParams();
            int contentWidth = mProgressBar.getWidth() - (params.leftMargin + params.rightMargin);
            int leadingEdge = ((contentWidth * mProgressBar.getProgress()) /
                    mProgressBar.getMax()) + params.leftMargin;
            
            // Calculate any adjustment based on gravity
            int adjustLeft = 0;
            int textWidth = mChronometer.getWidth();
            if (mChronometerGravity == Gravity.END) {
                adjustLeft = -textWidth;
            } else if (mChronometerGravity == Gravity.CENTER_HORIZONTAL) {
                adjustLeft = -(textWidth / 2);
            }
            
            // Limit margin to keep text inside ProgressBar bounds
            leadingEdge += adjustLeft;
            int rightLimit = contentWidth - params.rightMargin - textWidth;
            if (leadingEdge < params.leftMargin) {
                leadingEdge = params.leftMargin;
            } else if (leadingEdge > rightLimit) {
                leadingEdge = rightLimit;
            }
            
            params = (RelativeLayout.LayoutParams) mChronometer.getLayoutParams();
            params.leftMargin = leadingEdge;
            
            // Request layout to move Chronometer
            mChronometer.requestLayout();
            
        }
    }
}
