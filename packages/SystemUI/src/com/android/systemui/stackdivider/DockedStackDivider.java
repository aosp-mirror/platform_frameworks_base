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

package com.android.systemui.stackdivider;

import android.app.ActivityManagerNative;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.R;
import com.android.systemui.SystemUI;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

/**
 * Controls showing and hiding of a docked stack divider on the display.
 */
public class DockedStackDivider extends SystemUI implements View.OnTouchListener {
    private static final String TAG = "DockedStackDivider";
    private int mDividerWidth;
    private int mSideMargin;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mDisplayOrientation;
    private View mView;
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRectBackground = new Rect();
    private final Rect mLastResizeRect = new Rect();

    @GuardedBy("mResizeRect")
    private final Rect mResizeRect = new Rect();

    private int mStartX;
    private int mStartY;
    private int mStartPosition;
    private int mDockSide;
    private WindowManager mWindowManager;
    private final int[] mTempInt2 = new int[2];
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final Runnable mResizeRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mResizeRect) {
                mTmpRectBackground.set(mResizeRect);
            }
            try {
                ActivityManagerNative.getDefault().resizeStack(DOCKED_STACK_ID,
                        mTmpRectBackground, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    @Override
    public void start() {
        mWindowManager = mContext.getSystemService(WindowManager.class);
        updateDisplayInfo();
        mDividerWidth = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mSideMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_dismiss_distance);
        update(mContext.getResources().getConfiguration());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDisplayInfo();
        update(newConfig);
    }

    private void addDivider(Configuration configuration) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.docked_stack_divider, null);
        view.setOnTouchListener(this);
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? mDividerWidth : MATCH_PARENT;
        final int height = landscape ? MATCH_PARENT : mDividerWidth;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                        | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.setTitle(TAG);
        mWindowManager.addView(view, params);
        mView = view;
    }

    private void removeDivider() {
        if (mView == null) return;
        mView.setOnTouchListener(null);
        mWindowManager.removeView(mView);
        mView = null;
    }


    private void updateDisplayInfo() {
        DisplayMetrics info = mContext.getResources().getDisplayMetrics();
        mDisplayWidth = info.widthPixels;
        mDisplayHeight = info.heightPixels;
        mDisplayOrientation = mContext.getResources().getConfiguration().orientation;
    }

    private void update(Configuration configuration) {
        removeDivider();
        addDivider(configuration);
    }

    int getWidth() {
        return mDividerWidth;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // We use raw values, because getX/Y() would give us results relative to the
                // dock divider bounds.
                mStartX = (int) event.getRawX();
                mStartY = (int) event.getRawY();
                mView.getLocationOnScreen(mTempInt2);
                if (mDisplayOrientation == ORIENTATION_LANDSCAPE) {
                    mStartPosition = mTempInt2[0];
                } else {
                    mStartPosition = mTempInt2[1];
                }
                mDockSide = getDockSide();
                if (mDockSide != WindowManager.DOCKED_INVALID) {
                    setResizing(true);
                    return true;
                } else {
                    return false;
                }
            case MotionEvent.ACTION_MOVE:
                int x = (int) event.getRawX();
                int y = (int) event.getRawY();
                if (mDockSide != WindowManager.DOCKED_INVALID) {
                    resizeStack(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                x = (int) event.getRawX();
                y = (int) event.getRawY();
                // At most one of these will be executed, the other one will exit early.
                maybeDismissTaskStack(x, y);
                maybeMaximizeTaskStack(x, y);
                mDockSide = WindowManager.DOCKED_INVALID;
                setResizing(false);
                break;
        }
        return true;
    }

    private void setResizing(boolean resizing) {
        try {
            WindowManagerGlobal.getWindowManagerService().setDockedStackResizing(resizing);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling setDockedStackResizing: " + e);
        }
    }

    private int getDockSide() {
        try {
            return WindowManagerGlobal.getWindowManagerService().getDockedStackSide();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get dock side: " + e);
        }
        return WindowManager.DOCKED_INVALID;
    }

    private void maybeMaximizeTaskStack(int x, int y) {
        final int distance = distanceFromFullScreen(mDockSide, x, y);
        if (distance == -1) {
            Log.wtf(TAG, "maybeMaximizeTaskStack: Unknown dock side=" + mDockSide);
            return;
        }
        if (distance <= mSideMargin) {
            try {
                ActivityManagerNative.getDefault().resizeStack(
                        DOCKED_STACK_ID, null, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    }

    private void maybeDismissTaskStack(int x, int y) {
        final int distance = distanceFromDockSide(mDockSide, x, y);
        if (distance == -1) {
            Log.wtf(TAG, "maybeDismissTaskStack: Unknown dock side=" + mDockSide);
            return;
        }
        if (distance <= mSideMargin) {
            try {
                ActivityManagerNative.getDefault().removeStack(DOCKED_STACK_ID);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove stack: " + e);
            }
        }
    }

    private int distanceFromFullScreen(int dockSide, int x, int y) {
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                return mDisplayWidth - x;
            case WindowManager.DOCKED_TOP:
                return mDisplayHeight - y;
            case WindowManager.DOCKED_RIGHT:
                return x;
            case WindowManager.DOCKED_BOTTOM:
                return y;
        }
        return -1;
    }

    private int distanceFromDockSide(int dockSide, int x, int y) {
        switch (dockSide) {
            case WindowManager.DOCKED_LEFT:
                return x;
            case WindowManager.DOCKED_TOP:
                return y;
            case WindowManager.DOCKED_RIGHT:
                return mDisplayWidth - x;
            case WindowManager.DOCKED_BOTTOM:
                return mDisplayHeight - y;
        }
        return -1;
    }

    private void resizeStack(int x, int y) {
        int deltaX = x - mStartX;
        int deltaY = y - mStartY;
        mTmpRect.set(0, 0, mDisplayWidth, mDisplayHeight);
        switch (mDockSide) {
            case WindowManager.DOCKED_LEFT:
                mTmpRect.right = mStartPosition + deltaX;
                break;
            case WindowManager.DOCKED_TOP:
                mTmpRect.bottom = mStartPosition + deltaY;
                break;
            case WindowManager.DOCKED_RIGHT:
                mTmpRect.left = mStartPosition + deltaX + getWidth();
                break;
            case WindowManager.DOCKED_BOTTOM:
                mTmpRect.top = mStartPosition + deltaY + getWidth();
                break;
        }
        if (mTmpRect.equals(mLastResizeRect)) {
            return;
        }
        mLastResizeRect.set(mTmpRect);
        synchronized (mResizeRect) {
            mResizeRect.set(mTmpRect);
        }
        mExecutor.execute(mResizeRunnable);

    }
}
