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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeStatus;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link CaptionWindowDecorViewModel}. The caption bar contains maximize and close buttons.
 *
 * {@link CaptionWindowDecorViewModel} can change the color of the caption bar based on the foremost
 * app's request through {@link #setCaptionColor(int)}, in which it changes the foreground color of
 * caption buttons according to the luminance of the background.
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
    private static final int RESIZE_HANDLE_IN_DIP = 30;

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
                DECOR_CAPTION_HEIGHT_IN_DIP, outset, shadowRadiusDp, startT, finishT, wct, mResult);
        taskInfo = null; // Clear it just in case we use it accidentally

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            setupRootView();
        }

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

        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, (int) (mResult.mDensity * RESIZE_HANDLE_IN_DIP));
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        View maximize = caption.findViewById(R.id.maximize_window);
        if (DesktopModeStatus.IS_SUPPORTED) {
            // Hide maximize button when desktop mode is available
            maximize.setVisibility(View.GONE);
        } else {
            maximize.setVisibility(View.VISIBLE);
            maximize.setOnClickListener(mOnCaptionButtonClickListener);
        }
        View close = caption.findViewById(R.id.close_window);
        close.setOnClickListener(mOnCaptionButtonClickListener);
        View minimize = caption.findViewById(R.id.minimize_window);
        minimize.setOnClickListener(mOnCaptionButtonClickListener);
    }

    void setCaptionColor(int captionColor) {
        if (mResult.mRootView == null) {
            return;
        }

        View caption = mResult.mRootView.findViewById(R.id.caption);
        GradientDrawable captionDrawable = (GradientDrawable) caption.getBackground();
        captionDrawable.setColor(captionColor);

        int buttonTintColorRes =
                Color.valueOf(captionColor).luminance() < 0.5
                        ? R.color.decor_button_light_color
                        : R.color.decor_button_dark_color;
        ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);
        View maximize = caption.findViewById(R.id.maximize_window);
        VectorDrawable maximizeBackground = (VectorDrawable) maximize.getBackground();
        maximizeBackground.setTintList(buttonTintColor);

        View close = caption.findViewById(R.id.close_window);
        VectorDrawable closeBackground = (VectorDrawable) close.getBackground();
        closeBackground.setTintList(buttonTintColor);
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
