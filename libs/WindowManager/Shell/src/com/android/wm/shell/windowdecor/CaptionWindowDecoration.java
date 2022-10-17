/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeStatus;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link CaptionWindowDecorViewModel}. The caption bar contains a handle, back button, and close button.
 *
 * The shadow's thickness is 20dp when the window is in focus and 5dp when the window isn't.
 */
public class CaptionWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    // The thickness of shadows of a window that has focus in DIP.
    private static final int DECOR_SHADOW_FOCUSED_THICKNESS_IN_DIP = 20;
    // The thickness of shadows of a window that doesn't have focus in DIP.
    private static final int DECOR_SHADOW_UNFOCUSED_THICKNESS_IN_DIP = 5;

    // Height of button (32dp)  + 2 * margin (5dp each)
    private static final int DECOR_CAPTION_HEIGHT_IN_DIP = 42;
    // Width of buttons (64dp) + handle (128dp) + padding (24dp total)
    private static final int DECOR_CAPTION_WIDTH_IN_DIP = 216;
    private static final int RESIZE_HANDLE_IN_DIP = 30;
    private static final int RESIZE_CORNER_IN_DIP = 44;

    private static final Rect EMPTY_OUTSET = new Rect();
    private static final Rect RESIZE_HANDLE_OUTSET = new Rect(
            RESIZE_HANDLE_IN_DIP, RESIZE_HANDLE_IN_DIP, RESIZE_HANDLE_IN_DIP, RESIZE_HANDLE_IN_DIP);

    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;

    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private DragResizeCallback mDragResizeCallback;

    private DragResizeInputListener mDragResizeListener;

    private final WindowDecoration.RelayoutResult<WindowDecorLinearLayout> mResult =
            new WindowDecoration.RelayoutResult<>();

    private boolean mDesktopActive;

    CaptionWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue) {
        super(context, displayController, taskOrganizer, taskInfo, taskSurface);

        mHandler = handler;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
        mDesktopActive = DesktopModeStatus.isActive(mContext);
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setDragResizeCallback(DragResizeCallback dragResizeCallback) {
        mDragResizeCallback = dragResizeCallback;
    }

    @Override
    void relayout(ActivityManager.RunningTaskInfo taskInfo) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        relayout(taskInfo, t, t);
        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
    }

    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT) {
        final int shadowRadiusDp = taskInfo.isFocused
                ? DECOR_SHADOW_FOCUSED_THICKNESS_IN_DIP : DECOR_SHADOW_UNFOCUSED_THICKNESS_IN_DIP;
        final boolean isFreeform = mTaskInfo.configuration.windowConfiguration.getWindowingMode()
                == WindowConfiguration.WINDOWING_MODE_FREEFORM;
        final boolean isDragResizeable = isFreeform && mTaskInfo.isResizeable;
        final Rect outset = isDragResizeable ? RESIZE_HANDLE_OUTSET : EMPTY_OUTSET;

        WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        relayout(taskInfo, R.layout.caption_window_decoration, oldRootView,
                DECOR_CAPTION_HEIGHT_IN_DIP, DECOR_CAPTION_WIDTH_IN_DIP, outset, shadowRadiusDp,
                startT, finishT, wct, mResult);

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            setupRootView();
        }

        // If this task is not focused, do not show caption.
        setCaptionVisibility(taskInfo.isFocused);

        // Only handle should show if Desktop Mode is inactive.
        boolean desktopCurrentStatus = DesktopModeStatus.isActive(mContext);
        if (mDesktopActive != desktopCurrentStatus && taskInfo.isFocused) {
            mDesktopActive = desktopCurrentStatus;
            setButtonVisibility();
        }
        taskInfo = null; // Clear it just in case we use it accidentally

        if (!isDragResizeable) {
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            mDragResizeListener = new DragResizeInputListener(
                        mContext,
                        mHandler,
                        mChoreographer,
                        mDisplay.getDisplayId(),
                        mDecorationContainerSurface,
                        mDragResizeCallback);
        }

        int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext()).getScaledTouchSlop();

        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, (int) (mResult.mDensity * RESIZE_HANDLE_IN_DIP),
                (int) (mResult.mDensity * RESIZE_CORNER_IN_DIP), touchSlop);
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        View close = caption.findViewById(R.id.close_window);
        close.setOnClickListener(mOnCaptionButtonClickListener);
        View back = caption.findViewById(R.id.back_button);
        back.setOnClickListener(mOnCaptionButtonClickListener);
        View handle = caption.findViewById(R.id.caption_handle);
        handle.setOnTouchListener(mOnCaptionTouchListener);
        setButtonVisibility();
    }

    /**
     * Sets caption visibility based on task focus.
     *
     * @param visible whether or not the caption should be visible
     */
    private void setCaptionVisibility(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setVisibility(v);
    }

    /**
     * Sets the visibility of buttons and color of caption based on desktop mode status
     *
     */
    public void setButtonVisibility() {
        int v = mDesktopActive ? View.VISIBLE : View.GONE;
        View caption = mResult.mRootView.findViewById(R.id.caption);
        View back = caption.findViewById(R.id.back_button);
        View close = caption.findViewById(R.id.close_window);
        back.setVisibility(v);
        close.setVisibility(v);
        int buttonTintColorRes =
                mDesktopActive ? R.color.decor_button_dark_color
                        : R.color.decor_button_light_color;
        ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);
        View handle = caption.findViewById(R.id.caption_handle);
        VectorDrawable handleBackground = (VectorDrawable) handle.getBackground();
        handleBackground.setTintList(buttonTintColor);
        caption.getBackground().setTint(v == View.VISIBLE ? Color.WHITE : Color.TRANSPARENT);
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    @Override
    public void close() {
        closeDragResizeListener();
        super.close();
    }
}
