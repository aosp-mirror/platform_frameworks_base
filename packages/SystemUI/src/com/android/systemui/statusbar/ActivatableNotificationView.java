/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.internal.R;

/**
 * Base class for both {@link ExpandableNotificationRow} and {@link NotificationOverflowContainer}
 * to implement dimming/activating on Keyguard for the double-tap gesture
 */
public abstract class ActivatableNotificationView extends ExpandableOutlineView {

    private static final long DOUBLETAP_TIMEOUT_MS = 1000;

    private boolean mDimmed;
    private boolean mLocked;

    private int mBgResId = R.drawable.notification_quantum_bg;
    private int mDimmedBgResId = R.drawable.notification_quantum_bg_dim;

    /**
     * Flag to indicate that the notification has been touched once and the second touch will
     * click it.
     */
    private boolean mActivated;

    private float mDownX;
    private float mDownY;
    private final float mTouchSlop;

    private OnActivatedListener mOnActivatedListener;

    public ActivatableNotificationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        updateBackgroundResource();
    }


    private final Runnable mTapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            makeInactive();
        }
    };

    @Override
    public void setOnClickListener(OnClickListener l) {
        super.setOnClickListener(l);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mLocked) {
            return handleTouchEventLocked(event);
        } else {
            return super.onTouchEvent(event);
        }
    }

    private boolean handleTouchEventLocked(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                if (mDownY > getActualHeight()) {
                    return false;
                }

                // Call the listener tentatively directly, even if we don't know whether the user
                // will stay within the touch slop, as the listener is implemented as a scale
                // animation, which is cancellable without jarring effects when swiping away
                // notifications.
                if (mOnActivatedListener != null) {
                    mOnActivatedListener.onActivated(this);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isWithinTouchSlop(event)) {
                    makeInactive();
                    return false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isWithinTouchSlop(event)) {
                    if (!mActivated) {
                        makeActive(event.getX(), event.getY());
                        postDelayed(mTapTimeoutRunnable, DOUBLETAP_TIMEOUT_MS);
                    } else {
                        performClick();
                        makeInactive();
                    }
                } else {
                    makeInactive();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                makeInactive();
                break;
            default:
                break;
        }
        return true;
    }

    private void makeActive(float x, float y) {
        mCustomBackground.setHotspot(0, x, y);
        mActivated = true;
    }

    /**
     * Cancels the hotspot and makes the notification inactive.
     */
    private void makeInactive() {
        if (mActivated) {
            // Make sure that we clear the hotspot from the center.
            mCustomBackground.setHotspot(0, getWidth() / 2, getActualHeight() / 2);
            mCustomBackground.removeHotspot(0);
            mActivated = false;
        }
        if (mOnActivatedListener != null) {
            mOnActivatedListener.onReset(this);
        }
        removeCallbacks(mTapTimeoutRunnable);
    }

    private boolean isWithinTouchSlop(MotionEvent event) {
        return Math.abs(event.getX() - mDownX) < mTouchSlop
                && Math.abs(event.getY() - mDownY) < mTouchSlop;
    }

    /**
     * Sets the notification as dimmed, meaning that it will appear in a more gray variant.
     */
    public void setDimmed(boolean dimmed) {
        if (mDimmed != dimmed) {
            mDimmed = dimmed;
            updateBackgroundResource();
        }
    }

    /**
     * Sets the notification as locked. In the locked state, the first tap will produce a quantum
     * ripple to make the notification brighter and only the second tap will cause a click.
     */
    public void setLocked(boolean locked) {
        mLocked = locked;
    }

    /**
     * Sets the resource id for the background of this notification.
     *
     * @param bgResId The background resource to use in normal state.
     * @param dimmedBgResId The background resource to use in dimmed state.
     */
    public void setBackgroundResourceIds(int bgResId, int dimmedBgResId) {
        mBgResId = bgResId;
        mDimmedBgResId = dimmedBgResId;
        updateBackgroundResource();
    }

    private void updateBackgroundResource() {
        setCustomBackgroundResource(mDimmed ? mDimmedBgResId : mBgResId);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        setPivotX(getWidth()/2);
    }

    @Override
    public void setActualHeight(int actualHeight) {
        super.setActualHeight(actualHeight);
        setPivotY(actualHeight/2);
    }

    public void setOnActivatedListener(OnActivatedListener onActivatedListener) {
        mOnActivatedListener = onActivatedListener;
    }

    public interface OnActivatedListener {
        void onActivated(View view);
        void onReset(View view);
    }
}
