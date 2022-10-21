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

            startT.setTrustedOverlay(mDecorationContainerSurface, true);
        }

        final Rect taskBounds = taskConfig.windowConfiguration.getBounds();
        final Resources resources = mDecorWindowContext.getResources();
        final int decorContainerOffsetX = -loadDimensionPixelSize(resources, params.mOutsetLeftId);
        final int decorContainerOffsetY = -loadDimensionPixelSize(resources, params.mOutsetTopId);
        outResult.mWidth = taskBounds.width()
                + loadDimensionPixelSize(resources, params.mOutsetRightId)
                - decorContainerOffsetX;
        outResult.mHeight = taskBounds.height()
                + loadDimensionPixelSize(resources, params.mOutsetBottomId)
                - decorContainerOffsetY;
        startT.setPosition(
                        mDecorationContainerSurface, decorContainerOffsetX, decorContainerOffsetY)
                .setWindowCrop(mDecorationContainerSurface,
                        outResult.mWidth, outResult.mHeight)
                // TODO(b/244455401): Change the z-order when it's better organized
                .setLayer(mDecorationContainerSurface, mTaskInfo.numActivities + 1)
                .show(mDecorationContainerSurface);

        // TaskBackgroundSurface
        if (mTaskBackgroundSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mTaskBackgroundSurface = builder
                    .setName("Background of Task=" + mTaskInfo.taskId)
                    .setEffectLayer()
                    .setParent(mTaskSurface)
                    .build();
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
                // TODO(b/244455401): Change the z-order when it's better organized
                .setLayer(mTaskBackgroundSurface, -1)
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
        final int captionWidth = loadDimensionPixelSize(resources, params.mCaptionWidthId);

        //Prevent caption from going offscreen if task is too high up
        final int captionYPos = taskBounds.top <= captionHeight / 2 ? 0 : captionHeight / 2;

        startT.setPosition(
                        mCaptionContainerSurface, -decorContainerOffsetX
                                + taskBounds.width() / 2 - captionWidth / 2,
                        -decorContainerOffsetY - captionYPos)
                .setWindowCrop(mCaptionContainerSurface, taskBounds.width(), captionHeight)
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
                    mCaptionInsetsRect.top + captionHeight - captionYPos;
            wct.addRectInsetsProvider(mTaskInfo.token, mCaptionInsetsRect,
                    CAPTION_INSETS_TYPES);
        } else {
            startT.hide(mCaptionContainerSurface);
        }

        // Task surface itself
        Point taskPosition = mTaskInfo.positionInParent;
        mTaskSurfaceCrop.set(
                decorContainerOffsetX,
                decorContainerOffsetY,
                outResult.mWidth + decorContainerOffsetX,
                outResult.mHeight + decorContainerOffsetY);
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

    private void releaseViews() {
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

    private static int loadDimensionPixelSize(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }

    private static float loadDimension(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimension(resourceId);
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

        void setOutsets(int leftId, int topId, int rightId, int bottomId) {
            mOutsetLeftId = leftId;
            mOutsetTopId = topId;
            mOutsetRightId = rightId;
            mOutsetBottomId = bottomId;
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
        }
    }

    static class RelayoutResult<T extends View & TaskFocusStateConsumer> {
        int mWidth;
        int mHeight;
        T mRootView;

        void reset() {
            mWidth = 0;
            mHeight = 0;
            mRootView = null;
        }
    }

    interface SurfaceControlViewHostFactory {
        default SurfaceControlViewHost create(Context c, Display d, WindowlessWindowManager wmm) {
            return new SurfaceControlViewHost(c, d, wmm);
        }
    }
}
