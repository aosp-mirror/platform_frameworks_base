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
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.TaskConstants;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;

import java.util.function.Supplier;

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
public abstract class WindowDecoration<T extends View & TaskFocusStateConsumer>
        implements AutoCloseable {
    private static final int[] CAPTION_INSETS_TYPES = { InsetsState.ITYPE_CAPTION_BAR };

    /**
     * System-wide context. Only used to create context with overridden configurations.
     */
    final Context mContext;
    final DisplayController mDisplayController;
    final ShellTaskOrganizer mTaskOrganizer;
    final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    final Supplier<WindowContainerTransaction> mWindowContainerTransactionSupplier;
    final SurfaceControlViewHostFactory mSurfaceControlViewHostFactory;
    private final DisplayController.OnDisplaysChangedListener mOnDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (mTaskInfo.displayId != displayId) {
                        return;
                    }

                    mDisplayController.removeDisplayWindowListener(this);
                    relayout(mTaskInfo);
                }
            };

    RunningTaskInfo mTaskInfo;
    final SurfaceControl mTaskSurface;

    Display mDisplay;
    Context mDecorWindowContext;
    SurfaceControl mDecorationContainerSurface;
    SurfaceControl mTaskBackgroundSurface;

    SurfaceControl mCaptionContainerSurface;
    private WindowlessWindowManager mCaptionWindowManager;
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
        this(context, displayController, taskOrganizer, taskInfo, taskSurface,
                SurfaceControl.Builder::new, SurfaceControl.Transaction::new,
                WindowContainerTransaction::new, new SurfaceControlViewHostFactory() {});
    }

    WindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
            Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
            SurfaceControlViewHostFactory surfaceControlViewHostFactory) {
        mContext = context;
        mDisplayController = displayController;
        mTaskOrganizer = taskOrganizer;
        mTaskInfo = taskInfo;
        mTaskSurface = taskSurface;
        mSurfaceControlBuilderSupplier = surfaceControlBuilderSupplier;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mWindowContainerTransactionSupplier = windowContainerTransactionSupplier;
        mSurfaceControlViewHostFactory = surfaceControlViewHostFactory;

        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        mDecorWindowContext = mContext.createConfigurationContext(mTaskInfo.getConfiguration());
    }

    /**
     * Used by {@link WindowDecoration} to trigger a new relayout because the requirements for a
     * relayout weren't satisfied are satisfied now.
     *
     * @param taskInfo The previous {@link RunningTaskInfo} passed into {@link #relayout} or the
     *                 constructor.
     */
    abstract void relayout(RunningTaskInfo taskInfo);

    void relayout(RelayoutParams params, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, WindowContainerTransaction wct, T rootView,
            RelayoutResult<T> outResult) {
        outResult.reset();

        final Configuration oldTaskConfig = mTaskInfo.getConfiguration();
        if (params.mRunningTaskInfo != null) {
            mTaskInfo = params.mRunningTaskInfo;
        }

        if (!mTaskInfo.isVisible) {
            releaseViews();
            finishT.hide(mTaskSurface);
            return;
        }

        if (rootView == null && params.mLayoutResId == 0) {
            throw new IllegalArgumentException("layoutResId and rootView can't both be invalid.");
        }

        outResult.mRootView = rootView;
        rootView = null; // Clear it just in case we use it accidentally
        final Configuration taskConfig = mTaskInfo.getConfiguration();
        if (oldTaskConfig.densityDpi != taskConfig.densityDpi
                || mDisplay == null
                || mDisplay.getDisplayId() != mTaskInfo.displayId) {
            releaseViews();

            if (!obtainDisplayOrRegisterListener()) {
                outResult.mRootView = null;
                return;
            }
            mDecorWindowContext = mContext.createConfigurationContext(taskConfig);
            if (params.mLayoutResId != 0) {
                outResult.mRootView = (T) LayoutInflater.from(mDecorWindowContext)
                                .inflate(params.mLayoutResId, null);
            }
        }

        if (outResult.mRootView == null) {
            outResult.mRootView = (T) LayoutInflater.from(mDecorWindowContext)
                            .inflate(params.mLayoutResId , null);
        }

        // DecorationContainerSurface
        if (mDecorationContainerSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mDecorationContainerSurface = builder
                    .setName("Decor container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mTaskSurface)
                    .build();

            startT.setTrustedOverlay(mDecorationContainerSurface, true)
                    .setLayer(mDecorationContainerSurface,
                            TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS);
        }

        final Rect taskBounds = taskConfig.windowConfiguration.getBounds();
        final Resources resources = mDecorWindowContext.getResources();
        outResult.mDecorContainerOffsetX = -loadDimensionPixelSize(resources, params.mOutsetLeftId);
        outResult.mDecorContainerOffsetY = -loadDimensionPixelSize(resources, params.mOutsetTopId);
        outResult.mWidth = taskBounds.width()
                + loadDimensionPixelSize(resources, params.mOutsetRightId)
                - outResult.mDecorContainerOffsetX;
        outResult.mHeight = taskBounds.height()
                + loadDimensionPixelSize(resources, params.mOutsetBottomId)
                - outResult.mDecorContainerOffsetY;
        startT.setPosition(
                        mDecorationContainerSurface,
                        outResult.mDecorContainerOffsetX, outResult.mDecorContainerOffsetY)
                .setWindowCrop(mDecorationContainerSurface,
                        outResult.mWidth, outResult.mHeight)
                .show(mDecorationContainerSurface);

        // TaskBackgroundSurface
        if (mTaskBackgroundSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mTaskBackgroundSurface = builder
                    .setName("Background of Task=" + mTaskInfo.taskId)
                    .setEffectLayer()
                    .setParent(mTaskSurface)
                    .build();

            startT.setLayer(mTaskBackgroundSurface, TaskConstants.TASK_CHILD_LAYER_TASK_BACKGROUND);
        }

        float shadowRadius = loadDimension(resources, params.mShadowRadiusId);
        int backgroundColorInt = mTaskInfo.taskDescription.getBackgroundColor();
        mTmpColor[0] = (float) Color.red(backgroundColorInt) / 255.f;
        mTmpColor[1] = (float) Color.green(backgroundColorInt) / 255.f;
        mTmpColor[2] = (float) Color.blue(backgroundColorInt) / 255.f;
        startT.setWindowCrop(mTaskBackgroundSurface, taskBounds.width(),
                        taskBounds.height())
                .setShadowRadius(mTaskBackgroundSurface, shadowRadius)
                .setColor(mTaskBackgroundSurface, mTmpColor)
                .show(mTaskBackgroundSurface);

        // CaptionContainerSurface, CaptionWindowManager
        if (mCaptionContainerSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mCaptionContainerSurface = builder
                    .setName("Caption container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mDecorationContainerSurface)
                    .build();
        }

        final int captionHeight = loadDimensionPixelSize(resources, params.mCaptionHeightId);
        final int captionWidth = params.mCaptionWidthId == Resources.ID_NULL
                ? taskBounds.width()
                : loadDimensionPixelSize(resources, params.mCaptionWidthId);

        startT.setPosition(
                        mCaptionContainerSurface,
                        -outResult.mDecorContainerOffsetX + params.mCaptionX,
                        -outResult.mDecorContainerOffsetY + params.mCaptionY)
                .setWindowCrop(mCaptionContainerSurface, captionWidth, captionHeight)
                .show(mCaptionContainerSurface);

        if (mCaptionWindowManager == null) {
            // Put caption under a container surface because ViewRootImpl sets the destination frame
            // of windowless window layers and BLASTBufferQueue#update() doesn't support offset.
            mCaptionWindowManager = new WindowlessWindowManager(
                    mTaskInfo.getConfiguration(), mCaptionContainerSurface,
                    null /* hostInputToken */);
        }

        // Caption view
        mCaptionWindowManager.setConfiguration(taskConfig);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(captionWidth, captionHeight,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Caption of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        if (mViewHost == null) {
            mViewHost = mSurfaceControlViewHostFactory.create(mDecorWindowContext, mDisplay,
                    mCaptionWindowManager);
            mViewHost.setView(outResult.mRootView, lp);
        } else {
            mViewHost.relayout(lp);
        }

        if (ViewRootImpl.CAPTION_ON_SHELL) {
            outResult.mRootView.setTaskFocusState(mTaskInfo.isFocused);

            // Caption insets
            mCaptionInsetsRect.set(taskBounds);
            mCaptionInsetsRect.bottom =
                    mCaptionInsetsRect.top + captionHeight + params.mCaptionY;
            wct.addRectInsetsProvider(mTaskInfo.token, mCaptionInsetsRect,
                    CAPTION_INSETS_TYPES);
        } else {
            startT.hide(mCaptionContainerSurface);
        }

        // Task surface itself
        Point taskPosition = mTaskInfo.positionInParent;
        mTaskSurfaceCrop.set(
                outResult.mDecorContainerOffsetX,
                outResult.mDecorContainerOffsetY,
                outResult.mWidth + outResult.mDecorContainerOffsetX,
                outResult.mHeight + outResult.mDecorContainerOffsetY);
        startT.show(mTaskSurface);
        finishT.setPosition(mTaskSurface, taskPosition.x, taskPosition.y)
                .setCrop(mTaskSurface, mTaskSurfaceCrop);
    }

    /**
     * Obtains the {@link Display} instance for the display ID in {@link #mTaskInfo} if it exists or
     * registers {@link #mOnDisplaysChangedListener} if it doesn't.
     *
     * @return {@code true} if the {@link Display} instance exists; or {@code false} otherwise
     */
    private boolean obtainDisplayOrRegisterListener() {
        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        if (mDisplay == null) {
            mDisplayController.addDisplayWindowListener(mOnDisplaysChangedListener);
            return false;
        }
        return true;
    }

    void releaseViews() {
        if (mViewHost != null) {
            mViewHost.release();
            mViewHost = null;
        }

        mCaptionWindowManager = null;

        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        boolean released = false;
        if (mCaptionContainerSurface != null) {
            t.remove(mCaptionContainerSurface);
            mCaptionContainerSurface = null;
            released = true;
        }

        if (mDecorationContainerSurface != null) {
            t.remove(mDecorationContainerSurface);
            mDecorationContainerSurface = null;
            released = true;
        }

        if (mTaskBackgroundSurface != null) {
            t.remove(mTaskBackgroundSurface);
            mTaskBackgroundSurface = null;
            released = true;
        }

        if (released) {
            t.apply();
        }

        final WindowContainerTransaction wct = mWindowContainerTransactionSupplier.get();
        wct.removeInsetsProvider(mTaskInfo.token, CAPTION_INSETS_TYPES);
        mTaskOrganizer.applyTransaction(wct);
    }

    @Override
    public void close() {
        mDisplayController.removeDisplayWindowListener(mOnDisplaysChangedListener);
        releaseViews();
    }

    static int loadDimensionPixelSize(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }

    static float loadDimension(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimension(resourceId);
    }

    /**
     * Create a window associated with this WindowDecoration.
     * Note that subclass must dispose of this when the task is hidden/closed.
     * @param layoutId layout to make the window from
     * @param t the transaction to apply
     * @param xPos x position of new window
     * @param yPos y position of new window
     * @param width width of new window
     * @param height height of new window
     * @return
     */
    AdditionalWindow addWindow(int layoutId, String namePrefix,
            SurfaceControl.Transaction t, int xPos, int yPos, int width, int height) {
        final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
        SurfaceControl windowSurfaceControl = builder
                .setName(namePrefix + " of Task=" + mTaskInfo.taskId)
                .setContainerLayer()
                .setParent(mDecorationContainerSurface)
                .build();
        View v = LayoutInflater.from(mDecorWindowContext).inflate(layoutId, null);

        t.setPosition(
                windowSurfaceControl, xPos, yPos)
                .setWindowCrop(windowSurfaceControl, width, height)
                .show(windowSurfaceControl);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSPARENT);
        lp.setTitle("Additional window of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        WindowlessWindowManager windowManager = new WindowlessWindowManager(mTaskInfo.configuration,
                windowSurfaceControl, null /* hostInputToken */);
        SurfaceControlViewHost viewHost = mSurfaceControlViewHostFactory
                .create(mDecorWindowContext, mDisplay, windowManager);
        viewHost.setView(v, lp);
        return new AdditionalWindow(windowSurfaceControl, viewHost,
                mSurfaceControlTransactionSupplier);
    }

    static class RelayoutParams{
        RunningTaskInfo mRunningTaskInfo;
        int mLayoutResId;
        int mCaptionHeightId;
        int mCaptionWidthId;
        int mShadowRadiusId;

        int mOutsetTopId;
        int mOutsetBottomId;
        int mOutsetLeftId;
        int mOutsetRightId;

        int mCaptionX;
        int mCaptionY;

        void setOutsets(int leftId, int topId, int rightId, int bottomId) {
            mOutsetLeftId = leftId;
            mOutsetTopId = topId;
            mOutsetRightId = rightId;
            mOutsetBottomId = bottomId;
        }

        void setCaptionPosition(int left, int top) {
            mCaptionX = left;
            mCaptionY = top;
        }

        void reset() {
            mLayoutResId = Resources.ID_NULL;
            mCaptionHeightId = Resources.ID_NULL;
            mCaptionWidthId = Resources.ID_NULL;
            mShadowRadiusId = Resources.ID_NULL;

            mOutsetTopId = Resources.ID_NULL;
            mOutsetBottomId = Resources.ID_NULL;
            mOutsetLeftId = Resources.ID_NULL;
            mOutsetRightId = Resources.ID_NULL;

            mCaptionX = 0;
            mCaptionY = 0;
        }
    }

    static class RelayoutResult<T extends View & TaskFocusStateConsumer> {
        int mWidth;
        int mHeight;
        T mRootView;
        int mDecorContainerOffsetX;
        int mDecorContainerOffsetY;

        void reset() {
            mWidth = 0;
            mHeight = 0;
            mDecorContainerOffsetX = 0;
            mDecorContainerOffsetY = 0;
            mRootView = null;
        }
    }

    interface SurfaceControlViewHostFactory {
        default SurfaceControlViewHost create(Context c, Display d, WindowlessWindowManager wmm) {
            return new SurfaceControlViewHost(c, d, wmm);
        }
    }

    /**
     * Subclass for additional windows associated with this WindowDecoration
     */
    static class AdditionalWindow {
        SurfaceControl mWindowSurface;
        SurfaceControlViewHost mWindowViewHost;
        Supplier<SurfaceControl.Transaction> mTransactionSupplier;

        private AdditionalWindow(SurfaceControl surfaceControl,
                SurfaceControlViewHost surfaceControlViewHost,
                Supplier<SurfaceControl.Transaction> transactionSupplier) {
            mWindowSurface = surfaceControl;
            mWindowViewHost = surfaceControlViewHost;
            mTransactionSupplier = transactionSupplier;
        }

        void releaseView() {
            WindowlessWindowManager windowManager = mWindowViewHost.getWindowlessWM();

            if (mWindowViewHost != null) {
                mWindowViewHost.release();
                mWindowViewHost = null;
            }
            windowManager = null;
            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            boolean released = false;
            if (mWindowSurface != null) {
                t.remove(mWindowSurface);
                mWindowSurface = null;
                released = true;
            }
            if (released) {
                t.apply();
            }
        }
    }
}
