/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.DOCKED_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static com.android.server.wm.DimLayer.RESIZING_HINT_ALPHA;
import static com.android.server.wm.DimLayer.RESIZING_HINT_DURATION_MS;
import static com.android.server.wm.TaskPositioner.SIDE_MARGIN_DIP;
import static com.android.server.wm.TaskStack.DOCKED_BOTTOM;
import static com.android.server.wm.TaskStack.DOCKED_LEFT;
import static com.android.server.wm.TaskStack.DOCKED_RIGHT;
import static com.android.server.wm.TaskStack.DOCKED_TOP;
import static com.android.server.wm.WindowManagerService.dipToPixel;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

/**
 * Controls showing and hiding of a docked stack divider on the display.
 */
public class DockedStackDividerController implements View.OnTouchListener, DimLayer.DimLayerUser {
    private static final String TAG = "DockedStackDivider";
    private final Context mContext;
    private final int mDividerWidth;
    private final DisplayContent mDisplayContent;
    private final int mSideMargin;
    private final DimLayer mDimLayer;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private View mView;
    private Rect mTmpRect = new Rect();
    private Rect mLastResizeRect = new Rect();
    private int mStartX;
    private int mStartY;
    private TaskStack mTaskStack;
    private Rect mOriginalRect = new Rect();
    private int mDockSide;
    private boolean mDimLayerVisible;

    DockedStackDividerController(Context context, DisplayContent displayContent) {
        mContext = context;
        mDisplayContent = displayContent;
        updateDisplayInfo();
        mDividerWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mSideMargin = dipToPixel(SIDE_MARGIN_DIP, mDisplayContent.getDisplayMetrics());
        mDimLayer = new DimLayer(displayContent.mService, this, displayContent.getDisplayId());
    }

    private void addDivider(Configuration configuration) {
        View view = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.docked_stack_divider, null);
        view.setOnTouchListener(this);
        WindowManagerGlobal manager = WindowManagerGlobal.getInstance();
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? mDividerWidth : MATCH_PARENT;
        final int height = landscape ? MATCH_PARENT : mDividerWidth;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, TYPE_DOCK_DIVIDER,
                FLAG_TOUCHABLE_WHEN_WAKING | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                        | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
        params.setTitle(TAG);
        manager.addView(view, params, mDisplayContent.getDisplay(), null);
        mView = view;
    }

    private void removeDivider() {
        mView.setOnTouchListener(null);
        WindowManagerGlobal manager = WindowManagerGlobal.getInstance();
        manager.removeView(mView, true /* immediate */);
        mView = null;
    }

    boolean hasDivider() {
        return mView != null;
    }

    void updateDisplayInfo() {
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
    }

    void update(Configuration configuration, boolean forceUpdate) {
        if (forceUpdate && mView != null) {
            removeDivider();
        }
        TaskStack stack = mDisplayContent.getDockedStackLocked();
        if (stack != null && mView == null) {
            addDivider(configuration);
        } else if (stack == null && mView != null) {
            removeDivider();
        }
    }

    int getWidth() {
        return mDividerWidth;
    }

    void positionDockedStackedDivider(Rect frame) {
        TaskStack stack = mDisplayContent.getDockedStackLocked();
        if (stack == null) {
            // Unfortunately we might end up with still having a divider, even though the underlying
            // stack was already removed. This is because we are on AM thread and the removal of the
            // divider was deferred to WM thread and hasn't happened yet.
            return;
        }
        final @TaskStack.DockSide int side = stack.getDockSide();
        stack.getBounds(mTmpRect);
        switch (side) {
            case DOCKED_LEFT:
                frame.set(mTmpRect.right, frame.top, mTmpRect.right + frame.width(), frame.bottom);
                break;
            case DOCKED_TOP:
                frame.set(frame.left, mTmpRect.bottom, mTmpRect.right,
                        mTmpRect.bottom + frame.height());
                break;
            case DOCKED_RIGHT:
                frame.set(mTmpRect.left - frame.width(), frame.top, mTmpRect.left, frame.bottom);
                break;
            case DOCKED_BOTTOM:
                frame.set(frame.left, mTmpRect.top - frame.height(), frame.right, mTmpRect.top);
                break;
        }
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
                synchronized (mDisplayContent.mService.mWindowMap) {
                    mTaskStack = mDisplayContent.getDockedStackLocked();
                    if (mTaskStack != null) {
                        mTaskStack.getBounds(mOriginalRect);
                        mDockSide = mTaskStack.getDockSide();
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mTaskStack != null) {
                    final int x = (int) event.getRawX();
                    final int y = (int) event.getRawY();
                    resizeStack(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mTaskStack != null) {
                    final int x = (int) event.getRawX();
                    final int y = (int) event.getRawY();
                    // At most one of these will be executed, the other one will exit early.
                    maybeDismissTaskStack(x, y);
                    maybeMaximizeTaskStack(x, y);
                    mTaskStack = null;
                }
                setDimLayerVisible(false);
                mDockSide = TaskStack.DOCKED_INVALID;
                break;
        }
        return true;
    }

    private void maybeMaximizeTaskStack(int x, int y) {
        final int distance = distanceFromFullScreen(mDockSide, x, y);
        if (distance == -1) {
            Slog.wtf(TAG, "maybeMaximizeTaskStack: Unknown dock side=" + mDockSide);
            return;
        }
        if (distance <= mSideMargin) {
            try {
                mDisplayContent.mService.mActivityManager.resizeStack(
                        mTaskStack.mStackId, null, true);
            } catch (RemoteException e) {
                // This can't happen because we are in the same process.
            }
        }
    }

    private void maybeDismissTaskStack(int x, int y) {
        final int distance = distanceFromDockSide(mDockSide, mOriginalRect, x, y);
        if (distance == -1) {
            Slog.wtf(TAG, "maybeDismissTaskStack: Unknown dock side=" + mDockSide);
            return;
        }
        if (distance <= mSideMargin) {
            try {
                mDisplayContent.mService.mActivityManager.removeStack(mTaskStack.mStackId);
            } catch (RemoteException e) {
                // This can't happen because we are in the same process.
            }
        }
    }

    private void updateDimLayer(int x, int y) {
        final int dismissDistance = distanceFromDockSide(mDockSide, mOriginalRect, x, y);
        final int maximizeDistance = distanceFromFullScreen(mDockSide, x, y);
        if (dismissDistance == -1 || maximizeDistance == -1) {
            Slog.wtf(TAG, "updateDimLayer: Unknown dock side=" + mDockSide);
            return;
        }
        if (dismissDistance <= mSideMargin && maximizeDistance <= mSideMargin) {
            Slog.wtf(TAG, "Both dismiss and maximize distances would trigger dim layer.");
            return;
        }
        if (dismissDistance <= mSideMargin) {
            setDismissDimLayerVisible(x, y);
        } else if (maximizeDistance <= mSideMargin) {
            setMaximizeDimLayerVisible(x, y);
        } else {
            setDimLayerVisible(false);
        }
    }

    /**
     * Provides the distance from the point to the docked side of a rectangle.
     *
     * @return non negative distance or -1 on error
     */
    private static int distanceFromDockSide(int dockSide, Rect bounds, int x, int y) {
        switch (dockSide) {
            case DOCKED_LEFT:
                return x - bounds.left;
            case DOCKED_TOP:
                return y - bounds.top;
            case DOCKED_RIGHT:
                return bounds.right - x;
            case DOCKED_BOTTOM:
                return bounds.bottom - y;
        }
        return -1;
    }

    private int distanceFromFullScreen(int dockSide, int x, int y) {
        switch (dockSide) {
            case DOCKED_LEFT:
                return mDisplayWidth - x;
            case DOCKED_TOP:
                return mDisplayHeight - y;
            case DOCKED_RIGHT:
                return x;
            case DOCKED_BOTTOM:
                return y;
        }
        return -1;
    }

    private void setDismissDimLayerVisible(int x, int y) {
        mTmpRect.set(mOriginalRect);
        switch (mDockSide) {
            case DOCKED_LEFT:
                mTmpRect.right = x;
                break;
            case DOCKED_TOP:
                mTmpRect.bottom = y;
                break;
            case DOCKED_RIGHT:
                mTmpRect.left = x;
                break;
            case DOCKED_BOTTOM:
                mTmpRect.top = y;
                break;
            default:
                Slog.wtf(TAG, "setDismissDimLayerVisible: Unknown dock side when setting dim "
                        + "layer=" + mDockSide);
                return;
        }
        mDimLayer.setBounds(mTmpRect);
        setDimLayerVisible(true);
    }

    private void setMaximizeDimLayerVisible(int x, int y) {
        mTmpRect.set(0, 0, mDisplayWidth, mDisplayHeight);
        switch (mDockSide) {
            case DOCKED_LEFT:
                mTmpRect.left = x;
                break;
            case DOCKED_TOP:
                mTmpRect.top = y;
                break;
            case DOCKED_RIGHT:
                mTmpRect.right = x;
                break;
            case DOCKED_BOTTOM:
                mTmpRect.top = y;
                break;
            default:
                Slog.wtf(TAG, "setMaximizeDimLayerVisible: Unknown dock side when setting dim "
                        + "layer=" + mDockSide);
        }
        mDimLayer.setBounds(mTmpRect);
        setDimLayerVisible(true);
    }

    private void setDimLayerVisible(boolean visible) {
        if (mDimLayerVisible == visible) {
            return;
        }
        mDimLayerVisible = visible;
        if (mDimLayerVisible) {
            mDimLayer.show(mDisplayContent.mService.getDragLayerLocked(), RESIZING_HINT_ALPHA,
                    RESIZING_HINT_DURATION_MS);
        } else {
            mDimLayer.hide();
        }
    }

    private void resizeStack(int x, int y) {
        mTmpRect.set(mOriginalRect);
        final int deltaX = x - mStartX;
        final int deltaY = y - mStartY;
        switch (mDockSide) {
            case DOCKED_LEFT:
                mTmpRect.right += deltaX;
                break;
            case DOCKED_TOP:
                mTmpRect.bottom += deltaY;
                break;
            case DOCKED_RIGHT:
                mTmpRect.left += deltaX;
                break;
            case DOCKED_BOTTOM:
                mTmpRect.top += deltaY;
                break;
        }
        if (mTmpRect.equals(mLastResizeRect)) {
            return;
        }
        mLastResizeRect.set(mTmpRect);
        try {
            mDisplayContent.mService.mActivityManager.resizeStack(DOCKED_STACK_ID, mTmpRect, true);
        } catch (RemoteException e) {
            // This can't happen because we are in the same process.
        }
        updateDimLayer(x, y);
    }

    boolean isResizing() {
        return mTaskStack != null;
    }

    int getWidthAdjustment() {
        return getWidth() / 2;
    }

    @Override
    public boolean isFullscreen() {
        return false;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    @Override
    public void getBounds(Rect outBounds) {
        // This dim layer user doesn't need this.
    }

    @Override
    public String toShortString() {
        return TAG;
    }
}
