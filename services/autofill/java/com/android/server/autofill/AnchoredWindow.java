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

import android.content.Context;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import java.io.PrintWriter;
/**
 * A window above the application that is smartly anchored to a rectangular region.
 */
final class AnchoredWindow {
    private static final String TAG = "AutoFill";

    private final WindowManager mWm;
    private final View mRootView;
    private final View mView;
    private final int mWidth;
    private final int mHeight;
    private boolean mIsShowing = false;

    /**
     * Constructor.
     *
     * @param wm window manager that draws the view on a window
     * @param view singleton view in the window
     * @param width requested width of the view
     * @param height requested height of the view
     */
    AnchoredWindow(WindowManager wm, View view, int width, int height) {
        mWm = wm;
        mRootView = wrapView(view, width, height);
        mView = view;
        mWidth = width;
        mHeight = height;
    }

    /**
     * Shows the window.
     *
     * @param bounds the rectangular region this window should be anchored to
     */
    void show(Rect bounds) {
        LayoutParams params = createBaseLayoutParams();
        params.x = bounds.left;
        params.y = bounds.bottom;

        if (!mIsShowing) {
            if (DEBUG) Slog.d(TAG, "adding view " + mView);
            mWm.addView(mRootView, params);
        } else {
            if (DEBUG) Slog.d(TAG, "updating view " + mView);
            mWm.updateViewLayout(mRootView, params);
        }
        mIsShowing = true;
    }

    /**
     * Hides the window.
     */
    void hide() {
        if (DEBUG) Slog.d(TAG, "removing view " + mView);
        if (mIsShowing) {
            mWm.removeView(mRootView);
        }
        mIsShowing = false;
    }

    /**
     * Wraps a view with a SelfRemovingView and sets its requested width and height.
     */
    private View wrapView(View view, int width, int height) {
        ViewGroup viewGroup = new SelfRemovingView(view.getContext());
        viewGroup.addView(view, new ViewGroup.LayoutParams(width, height));
        return viewGroup;
    }

    private static LayoutParams createBaseLayoutParams() {
        LayoutParams params = new LayoutParams();
        // TODO(b/33197203): LayoutParams.TYPE_AUTOFILL
        params.type = LayoutParams.TYPE_SYSTEM_ALERT;
        params.flags =
                LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                | LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.width = LayoutParams.WRAP_CONTENT;
        params.height = LayoutParams.WRAP_CONTENT;
        return params;
    }

    void dump(PrintWriter pw) {
        pw.println("Anchored Window");
        final String prefix = "  ";
        pw.print(prefix); pw.print("width: "); pw.println(mWidth);
        pw.print(prefix); pw.print("height: "); pw.println(mHeight);
        pw.print(prefix); pw.print("visible: "); pw.println(mIsShowing);
    }

    /** FrameLayout that listens for touch events removes itself if the touch event is outside. */
    private final class SelfRemovingView extends FrameLayout {
        public SelfRemovingView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                hide();
                return true;
            } else {
                return super.onTouchEvent(event);
            }
        }
    }
}
