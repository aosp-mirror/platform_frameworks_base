/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.android.internal.view.menu.ShowableListMenu;

/**
 * Abstract class that forwards touch events to a {@link ShowableListMenu}.
 *
 * @hide
 */
public abstract class ForwardingListener
        implements View.OnTouchListener, View.OnAttachStateChangeListener {

    /** Scaled touch slop, used for detecting movement outside bounds. */
    private final float mScaledTouchSlop;

    /** Timeout before disallowing intercept on the source's parent. */
    private final int mTapTimeout;

    /** Timeout before accepting a long-press to start forwarding. */
    private final int mLongPressTimeout;

    /** Source view from which events are forwarded. */
    private final View mSrc;

    /** Runnable used to prevent conflicts with scrolling parents. */
    private Runnable mDisallowIntercept;

    /** Runnable used to trigger forwarding on long-press. */
    private Runnable mTriggerLongPress;

    /** Whether this listener is currently forwarding touch events. */
    private boolean mForwarding;

    /** The id of the first pointer down in the current event stream. */
    private int mActivePointerId;

    public ForwardingListener(View src) {
        mSrc = src;
        src.setLongClickable(true);
        src.addOnAttachStateChangeListener(this);

        mScaledTouchSlop = ViewConfiguration.get(src.getContext()).getScaledTouchSlop();
        mTapTimeout = ViewConfiguration.getTapTimeout();

        // Use a medium-press timeout. Halfway between tap and long-press.
        mLongPressTimeout = (mTapTimeout + ViewConfiguration.getLongPressTimeout()) / 2;
    }

    /**
     * Returns the popup to which this listener is forwarding events.
     * <p>
     * Override this to return the correct popup. If the popup is displayed
     * asynchronously, you may also need to override
     * {@link #onForwardingStopped} to prevent premature cancellation of
     * forwarding.
     *
     * @return the popup to which this listener is forwarding events
     */
    public abstract ShowableListMenu getPopup();

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final boolean wasForwarding = mForwarding;
        final boolean forwarding;
        if (wasForwarding) {
            forwarding = onTouchForwarded(event) || !onForwardingStopped();
        } else {
            forwarding = onTouchObserved(event) && onForwardingStarted();

            if (forwarding) {
                // Make sure we cancel any ongoing source event stream.
                final long now = SystemClock.uptimeMillis();
                final MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL,
                        0.0f, 0.0f, 0);
                mSrc.onTouchEvent(e);
                e.recycle();
            }
        }

        mForwarding = forwarding;
        return forwarding || wasForwarding;
    }

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        mForwarding = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;

        if (mDisallowIntercept != null) {
            mSrc.removeCallbacks(mDisallowIntercept);
        }
    }

    /**
     * Called when forwarding would like to start.
     * <p>
     * By default, this will show the popup returned by {@link #getPopup()}.
     * It may be overridden to perform another action, like clicking the
     * source view or preparing the popup before showing it.
     *
     * @return true to start forwarding, false otherwise
     */
    protected boolean onForwardingStarted() {
        final ShowableListMenu popup = getPopup();
        if (popup != null && !popup.isShowing()) {
            popup.show();
        }
        return true;
    }

    /**
     * Called when forwarding would like to stop.
     * <p>
     * By default, this will dismiss the popup returned by
     * {@link #getPopup()}. It may be overridden to perform some other
     * action.
     *
     * @return true to stop forwarding, false otherwise
     */
    protected boolean onForwardingStopped() {
        final ShowableListMenu popup = getPopup();
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        }
        return true;
    }

    /**
     * Observes motion events and determines when to start forwarding.
     *
     * @param srcEvent motion event in source view coordinates
     * @return true to start forwarding motion events, false otherwise
     */
    private boolean onTouchObserved(MotionEvent srcEvent) {
        final View src = mSrc;
        if (!src.isEnabled()) {
            return false;
        }

        final int actionMasked = srcEvent.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = srcEvent.getPointerId(0);

                if (mDisallowIntercept == null) {
                    mDisallowIntercept = new DisallowIntercept();
                }
                src.postDelayed(mDisallowIntercept, mTapTimeout);

                if (mTriggerLongPress == null) {
                    mTriggerLongPress = new TriggerLongPress();
                }
                src.postDelayed(mTriggerLongPress, mLongPressTimeout);
                break;
            case MotionEvent.ACTION_MOVE:
                final int activePointerIndex = srcEvent.findPointerIndex(mActivePointerId);
                if (activePointerIndex >= 0) {
                    final float x = srcEvent.getX(activePointerIndex);
                    final float y = srcEvent.getY(activePointerIndex);

                    // Has the pointer moved outside of the view?
                    if (!src.pointInView(x, y, mScaledTouchSlop)) {
                        clearCallbacks();

                        // Don't let the parent intercept our events.
                        src.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                clearCallbacks();
                break;
        }

        return false;
    }

    private void clearCallbacks() {
        if (mTriggerLongPress != null) {
            mSrc.removeCallbacks(mTriggerLongPress);
        }

        if (mDisallowIntercept != null) {
            mSrc.removeCallbacks(mDisallowIntercept);
        }
    }

    private void onLongPress() {
        clearCallbacks();

        final View src = mSrc;
        if (!src.isEnabled() || src.isLongClickable()) {
            // Ignore long-press if the view is disabled or has its own
            // handler.
            return;
        }

        if (!onForwardingStarted()) {
            return;
        }

        // Don't let the parent intercept our events.
        src.getParent().requestDisallowInterceptTouchEvent(true);

        // Make sure we cancel any ongoing source event stream.
        final long now = SystemClock.uptimeMillis();
        final MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        src.onTouchEvent(e);
        e.recycle();

        mForwarding = true;
    }

    /**
     * Handles forwarded motion events and determines when to stop
     * forwarding.
     *
     * @param srcEvent motion event in source view coordinates
     * @return true to continue forwarding motion events, false to cancel
     */
    private boolean onTouchForwarded(MotionEvent srcEvent) {
        final View src = mSrc;
        final ShowableListMenu popup = getPopup();
        if (popup == null || !popup.isShowing()) {
            return false;
        }

        final DropDownListView dst = (DropDownListView) popup.getListView();
        if (dst == null || !dst.isShown()) {
            return false;
        }

        // Convert event to destination-local coordinates.
        final MotionEvent dstEvent = MotionEvent.obtainNoHistory(srcEvent);
        src.toGlobalMotionEvent(dstEvent);
        dst.toLocalMotionEvent(dstEvent);

        // Forward converted event to destination view, then recycle it.
        final boolean handled = dst.onForwardedEvent(dstEvent, mActivePointerId);
        dstEvent.recycle();

        // Always cancel forwarding when the touch stream ends.
        final int action = srcEvent.getActionMasked();
        final boolean keepForwarding = action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL;

        return handled && keepForwarding;
    }

    private class DisallowIntercept implements Runnable {
        @Override
        public void run() {
            final ViewParent parent = mSrc.getParent();
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private class TriggerLongPress implements Runnable {
        @Override
        public void run() {
            onLongPress();
        }
    }
}