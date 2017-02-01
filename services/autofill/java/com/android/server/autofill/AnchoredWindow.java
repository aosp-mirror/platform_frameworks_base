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
 * limitations under the License.
 */
package com.android.server.autofill;

import static com.android.server.autofill.Helper.DEBUG;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

/**
 * A window above the application that is smartly anchored to a rectangular region.
 */
final class AnchoredWindow implements View.OnLayoutChangeListener, View.OnTouchListener {
    private static final String TAG = "AutoFill";

    private static final int NULL_HEIGHT = -1;

    private final WindowManager mWm;
    private final IBinder mAppToken;
    private final View mContentView;

    private final View mWindowSizeListenerView;
    private final int mMinMargin;

    private int mLastHeight = NULL_HEIGHT;
    @Nullable
    private Rect mLastBounds;
    @Nullable
    private Rect mLastDisplayBounds;

    /**
     * Constructor.
     *
     * @param wm window manager that draws the content on a window
     * @param appToken token to pass to window manager
     * @param contentView content of the window
     */
    AnchoredWindow(WindowManager wm, IBinder appToken, View contentView) {
        mWm = wm;
        mAppToken = appToken;
        mContentView = contentView;

        mContentView.addOnLayoutChangeListener(this);

        Context context = contentView.getContext();

        mWindowSizeListenerView = new FrameLayout(context);
        mWindowSizeListenerView.addOnLayoutChangeListener(this);

        mMinMargin = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.autofill_fill_min_margin);
    }

    /**
     * Shows the window.
     *
     * @param bounds the region the window should be anchored to
     */
    void show(Rect bounds) {
        if (DEBUG) Slog.d(TAG, "show bounds=" + bounds);

        if (!mWindowSizeListenerView.isAttachedToWindow()) {
            if (DEBUG) Slog.d(TAG, "adding mWindowSizeListenerView");
            LayoutParams params = createWindowLayoutParams(
                    mAppToken,
                    LayoutParams.FLAG_NOT_TOUCHABLE); // not touchable
            params.gravity = Gravity.LEFT | Gravity.TOP;
            params.x = 0;
            params.y = 0;
            params.width = LayoutParams.MATCH_PARENT;
            params.height = LayoutParams.MATCH_PARENT;
            mWm.addView(mWindowSizeListenerView, params);
        }

        updateBounds(bounds);
    }

    /**
     * Hides the window.
     */
    void hide() {
        if (DEBUG) Slog.d(TAG, "hide");

        mLastHeight = NULL_HEIGHT;
        mLastBounds = null;
        mLastDisplayBounds = null;

        if (mWindowSizeListenerView.isAttachedToWindow()) {
            if (DEBUG) Slog.d(TAG, "removing mWindowSizeListenerView");
            mWm.removeView(mWindowSizeListenerView);
        }

        if (mContentView.isAttachedToWindow()) {
            if (DEBUG) Slog.d(TAG, "removing mContentView");
            mContentView.setOnTouchListener(null);
            mWm.removeView(mContentView);
        }
    }

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (view == mWindowSizeListenerView) {
            if (DEBUG) Slog.d(TAG, "onLayoutChange() for mWindowSizeListenerView");
            // mWindowSizeListenerView layout changed, get the size of the display bounds and update
            // the window.
            final Rect displayBounds = new Rect();
            view.getBoundsOnScreen(displayBounds);
            updateDisplayBounds(displayBounds);
        } else if (view == mContentView) {
            // mContentView layout changed, update the window in case its height changed.
            if (DEBUG) Slog.d(TAG, "onLayoutChange() for mContentView");
            updateHeight();
        }
    }

    // When the window is touched outside, hide the window.
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (view == mContentView && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            hide();
            return true;
        }
        return false;
    }

    private boolean updateHeight() {
        final Rect displayBounds = mLastDisplayBounds;
        if (displayBounds == null) {
            return false;
        }

        mContentView.measure(
                MeasureSpec.makeMeasureSpec(displayBounds.width(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(displayBounds.height(), MeasureSpec.AT_MOST));
        int height = mContentView.getMeasuredHeight();
        if (height != mLastHeight) {
            if (DEBUG) Slog.d(TAG, "update height=" + height);
            mLastHeight = height;
            update(height, mLastBounds, displayBounds);
            return true;
        } else {
            return false;
        }
    }

    private void updateBounds(Rect bounds) {
        if (!bounds.equals(mLastBounds)) {
            if (DEBUG) Slog.d(TAG, "update bounds=" + bounds);
            mLastBounds = bounds;

            update(mLastHeight, bounds, mLastDisplayBounds);
        }
    }

    private void updateDisplayBounds(Rect displayBounds) {
        if (!displayBounds.equals(mLastDisplayBounds)) {
            if (DEBUG) Slog.d(TAG, "update displayBounds=" + displayBounds);
            mLastDisplayBounds = displayBounds;

            if (!updateHeight()) {
                update(mLastHeight, mLastBounds, displayBounds);
            }
        }
    }

    // Updates the window if height, bounds, and displayBounds are not null.
    // Caller should ensure that something changed before calling.
    private void update(int height, @Nullable Rect bounds, @Nullable Rect displayBounds) {
        if (height == NULL_HEIGHT || bounds == null || displayBounds == null) {
            return;
        }

        if (DEBUG) Slog.d(TAG, "update height=" + height + ", bounds=" + bounds
                + ", displayBounds=" + displayBounds);

        final LayoutParams params = createWindowLayoutParams(mAppToken,
                LayoutParams.FLAG_NOT_TOUCH_MODAL // outside touches go to windows behind us
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH); // outside touches trigger MotionEvent
        params.setTitle("AutoFill Fill"); // used for debugging
        updatePosition(params, height, mMinMargin, bounds, displayBounds);
        if (!mContentView.isAttachedToWindow()) {
            if (DEBUG) Slog.d(TAG, "adding mContentView");
            mWm.addView(mContentView, params);
            mContentView.setOnTouchListener(this);
        } else {
            if (DEBUG) Slog.d(TAG, "updating mContentView");
            mWm.updateViewLayout(mContentView, params);
        }
    }

    /**
     * Updates the position of the window by altering the {@link LayoutParams}.
     *
     * <p>The window can be anchored either above or below the bounds. Anchoring the window below
     * the bounds is preferred, if it fits. Otherwise, anchor the window on the side with more
     * space.
     *
     * @param params the params to update
     * @param height the requested height of the window
     * @param minMargin the minimum margin between the window and the display bounds
     * @param bounds the region the window should be anchored to
     * @param displayBounds the region in which the window may be displayed
     */
    private static void updatePosition(
            LayoutParams params,
            int height,
            int minMargin,
            Rect bounds,
            Rect displayBounds) {
        boolean below;
        int verticalSpace;
        final int verticalSpaceBelow = displayBounds.bottom - bounds.bottom - minMargin;
        if (height <= verticalSpaceBelow) {
            // Fits below bounds.
            below = true;
            verticalSpace = height;
        } else {
            final int verticalSpaceAbove = bounds.top - displayBounds.top - minMargin;
            if (height <= verticalSpaceAbove) {
                // Fits above bounds.
                below = false;
                verticalSpace = height;
            } else {
                // Pick above/below based on which has the most space.
                if (verticalSpaceBelow >= verticalSpaceAbove) {
                    below = true;
                    verticalSpace = verticalSpaceBelow;
                } else {
                    below = false;
                    verticalSpace = verticalSpaceAbove;
                }
            }
        }

        int gravity;
        int y;
        if (below) {
            if (DEBUG) Slog.d(TAG, "anchorBelow");
            gravity = Gravity.TOP | Gravity.LEFT;
            y = bounds.bottom - displayBounds.top;
        } else {
            if (DEBUG) Slog.d(TAG, "anchorAbove");
            gravity = Gravity.BOTTOM | Gravity.LEFT;
            y = displayBounds.bottom - bounds.top;
        }

        final int x = bounds.left - displayBounds.left;

        params.gravity = gravity;
        params.x = x;
        params.y = y;
        params.width = bounds.width();
        params.height = verticalSpace;
    }

    private static LayoutParams createWindowLayoutParams(IBinder appToken, int flags) {
        final LayoutParams params = new LayoutParams();
        params.token = appToken;
        params.type = LayoutParams.TYPE_PHONE;
        params.flags =
                flags
                | LayoutParams.FLAG_NOT_FOCUSABLE // don't receive input events
                | LayoutParams.FLAG_ALT_FOCUSABLE_IM; // resize for soft input
        params.format = PixelFormat.TRANSLUCENT;
        return params;
    }
}
