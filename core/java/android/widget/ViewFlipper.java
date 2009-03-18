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


import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.widget.RemoteViews.RemoteView;

/**
 * Simple {@link ViewAnimator} that will animate between two or more views
 * that have been added to it.  Only one child is shown at a time.  If
 * requested, can automatically flip between each child at a regular interval.
 *
 * @attr ref android.R.styleable#ViewFlipper_flipInterval
 */
public class ViewFlipper extends ViewAnimator {
    private int mFlipInterval = 3000;
    private boolean mKeepFlipping = false;

    public ViewFlipper(Context context) {
        super(context);
    }

    public ViewFlipper(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ViewFlipper);
        mFlipInterval = a.getInt(com.android.internal.R.styleable.ViewFlipper_flipInterval,
                3000);
        a.recycle();
    }

    /**
     * How long to wait before flipping to the next view
     * 
     * @param milliseconds
     *            time in milliseconds
     */
    @android.view.RemotableViewMethod
    public void setFlipInterval(int milliseconds) {
        mFlipInterval = milliseconds;
    }

    /**
     * Start a timer to cycle through child views
     */
    public void startFlipping() {
        if (!mKeepFlipping) {
            mKeepFlipping = true;
            showOnly(mWhichChild);
            Message msg = mHandler.obtainMessage(FLIP_MSG);
            mHandler.sendMessageDelayed(msg, mFlipInterval);
        }
    }

    /**
     * No more flips
     */
    public void stopFlipping() {
        mKeepFlipping = false;
    }

    /**
     * Returns true if the child views are flipping.
     */
    public boolean isFlipping() {
        return mKeepFlipping;
    }

    private final int FLIP_MSG = 1;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == FLIP_MSG) {
                if (mKeepFlipping) {
                    showNext();
                    msg = obtainMessage(FLIP_MSG);
                    sendMessageDelayed(msg, mFlipInterval);
                }
            }
        }
    };
}
