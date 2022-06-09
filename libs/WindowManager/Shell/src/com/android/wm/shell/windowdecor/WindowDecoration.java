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

import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;

/**
 * Manages a container surface and a windowless window to show window decoration. Responsible to
 * update window decoration window state and layout parameters on task info changes and so that
 * window decoration is in correct state and bounds.
 *
 * The container surface is a child of the task display area in the same display, so that window
 * decorations can be drawn out of the task bounds and receive input events from out of the task
 * bounds to support drag resizing.
 *
 * The windowless window that hosts window decoration is positioned in front of all activities, to
 * allow the foreground activity to draw its own background behind window decorations, such as
 * the window captions.
 *
 * @param <T> The type of the root view
 */
public class WindowDecoration<T extends View & TaskFocusStateConsumer> implements AutoCloseable {
    private static final int[] CAPTION_INSETS_TYPES = { InsetsState.ITYPE_CAPTION_BAR };

    /**
     * System-wide context. Only used to create context with overridden configurations.
     */
    final Context mContext;
    final DisplayController mDisplayController;
    final ShellTaskOrganizer mTaskOrganizer;

    RunningTaskInfo mTaskInfo;
    final SurfaceControl mTaskSurface;

    Display mDisplay;
    Context mDecorWindowContext;
    SurfaceControl mDecorationContainerSurface;
    SurfaceControl mTaskBackgroundSurface;

    private CaptionWindowManager mCaptionWindowManager;
    private SurfaceControlViewHost mViewHost;

    private final Rect mCaptionInsetsRect = new Rect();
    private final Rect mTaskSurfaceCrop = new Rect();
    private final float[] mTmpColor = new float[3];

    WindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface) {
        mContext = context;
        mDisplayController = displayController;
        mTaskOrganizer = taskOrganizer;
        mTaskInfo = taskInfo;
        mTaskSurface = taskSurface;

        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        mDecorWindowContext = mContext.createConfigurationContext(mTaskInfo.getConfiguration());

        // Put caption under task surface because ViewRootImpl sets the destination frame of
        // windowless window layers and BLASTBufferQueue#update() doesn't support offset.
        mCaptionWindowManager =
                new CaptionWindowManager(mTaskInfo.getConfiguration(), mTaskSurface);
    }

    void relayout(RunningTaskInfo taskInfo, int layoutResId, T rootView, float captionHeightDp,
            Rect outsetsDp, float shadowRadiusDp, SurfaceControl.Transaction t,
            WindowContainerTransaction wct, RelayoutResult<T> outResult) {
        outResult.reset();

        final Configuration oldTaskConfig = mTaskInfo.getConfiguration();
        if (taskInfo != null) {
            mTaskInfo = taskInfo;
        }

        if (!mTaskInfo.isVisible) {
            close();
            t.hide(mTaskSurface);
            return;
        }

        if (rootView == null && layoutResId == 0) {
            throw new IllegalArgumentException("layoutResId and rootView can't both be invalid.");
        }

        outResult.mRootView = rootView;
        rootView = null; // Clear it just in case we use it accidentally
        final Configuration taskConfig = mTaskInfo.getConfiguration();
        if (oldTaskConfig.densityDpi != taskConfig.densityDpi
                || mDisplay.getDisplayId() != mTaskInfo.displayId) {
            close();

            mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
            mDecorWindowContext = mContext.createConfigurationContext(taskConfig);
            if (layoutResId != 0) {
                outResult.mRootView =
                        (T) LayoutInflater.from(mDecorWindowContext).inflate(layoutResId, null);
            }
        }

        if (outResult.mRootView == null) {
            outResult.mRootView =
                    (T) LayoutInflater.from(mDecorWindowContext).inflate(layoutResId, null);
        }

        // DecorationContainerSurface
        if (mDecorationContainerSurface == null) {
            final SurfaceControl.Builder builder = new SurfaceControl.Builder();
            mDecorationContainerSurface = builder
                    .setName("Decor container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mTaskSurface)
                    .build();

            t.setTrustedOverlay(mDecorationContainerSurface, true);
        }

        final Rect taskBounds = taskConfig.windowConfiguration.getBounds();
        outResult.mDensity = taskConfig.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        final int decorContainerOffsetX = -(int) (outsetsDp.left * outResult.mDensity);
        final int decorContainerOffsetY = -(int) (outsetsDp.top * outResult.mDensity);
        outResult.mWidth = taskBounds.width()
                + (int) (outsetsDp.right * outResult.mDensity)
                - decorContainerOffsetX;
        outResult.mHeight = taskBounds.height()
                + (int) (outsetsDp.bottom * outResult.mDensity)
                - decorContainerOffsetY;
        t.setPosition(mDecorationContainerSurface, decorContainerOffsetX, decorContainerOffsetY)
                .setWindowCrop(mDecorationContainerSurface, outResult.mWidth, outResult.mHeight)
                .setLayer(mDecorationContainerSurface, mTaskInfo.numActivities + 1)
                .show(mDecorationContainerSurface);

        // TaskBackgroundSurface
        if (mTaskBackgroundSurface == null) {
            final SurfaceControl.Builder builder = new SurfaceControl.Builder();
            mTaskBackgroundSurface = builder
                    .setName("Background of Task=" + mTaskInfo.taskId)
                    .setEffectLayer()
                    .setParent(mTaskSurface)
                    .build();
        }

        float shadowRadius = outResult.mDensity * shadowRadiusDp;
        int backgroundColorInt = mTaskInfo.taskDescription.getBackgroundColor();
        mTmpColor[0] = Color.red(backgroundColorInt);
        mTmpColor[1] = Color.green(backgroundColorInt);
        mTmpColor[2] = Color.blue(backgroundColorInt);
        t.setCrop(mTaskBackgroundSurface, taskBounds)
                .setShadowRadius(mTaskBackgroundSurface, shadowRadius)
                .setColor(mTaskBackgroundSurface, mTmpColor);

        // Caption view
        mCaptionWindowManager.setConfiguration(taskConfig);
        final int captionHeight = (int) Math.ceil(captionHeightDp * outResult.mDensity);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(taskBounds.width(), captionHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Caption of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        if (mViewHost == null) {
            mViewHost = new SurfaceControlViewHost(mDecorWindowContext, mDisplay,
                    mCaptionWindowManager, true);
            mViewHost.setView(outResult.mRootView, lp);
        } else {
            mViewHost.relayout(lp);
        }

        if (ViewRootImpl.CAPTION_ON_SHELL) {
            outResult.mRootView.setTaskFocusState(mTaskInfo.isFocused);

            // Caption insets
            mCaptionInsetsRect.set(taskBounds);
            mCaptionInsetsRect.bottom = mCaptionInsetsRect.top + captionHeight;
            wct.addRectInsetsProvider(mTaskInfo.token, mCaptionInsetsRect, CAPTION_INSETS_TYPES);
        } else {
            outResult.mRootView.setVisibility(View.GONE);
        }

        // Task surface itself
        Point taskPosition = mTaskInfo.positionInParent;
        mTaskSurfaceCrop.set(
                decorContainerOffsetX,
                decorContainerOffsetY,
                outResult.mWidth + decorContainerOffsetX,
                outResult.mHeight + decorContainerOffsetY);
        t.setPosition(mTaskSurface, taskPosition.x, taskPosition.y)
                .setCrop(mTaskSurface, mTaskSurfaceCrop)
                .show(mTaskSurface);
    }

    @Override
    public void close() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        if (mDecorationContainerSurface != null) {
            mDecorationContainerSurface.release();
            mDecorationContainerSurface = null;
        }

        if (mTaskBackgroundSurface != null) {
            mTaskBackgroundSurface.release();
            mTaskBackgroundSurface = null;
        }
    }

    static class RelayoutResult<T extends View & TaskFocusStateConsumer> {
        int mWidth;
        int mHeight;
        float mDensity;
        T mRootView;

        void reset() {
            mWidth = 0;
            mHeight = 0;
            mDensity = 0;
            mRootView = null;
        }
    }

    private static class CaptionWindowManager extends WindowlessWindowManager {
        CaptionWindowManager(Configuration config, SurfaceControl rootSurface) {
            super(config, rootSurface, null /* hostInputToken */);
        }

        @Override
        public void setConfiguration(Configuration configuration) {
            super.setConfiguration(configuration);
        }
    }
}
