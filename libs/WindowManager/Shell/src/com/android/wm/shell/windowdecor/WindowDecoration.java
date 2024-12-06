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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.content.res.Configuration.DENSITY_DPI_UNDEFINED;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Trace;
import android.view.Display;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.window.SurfaceSyncGroup;
import android.window.TaskConstants;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.windowdecor.WindowDecoration.RelayoutParams.OccludingCaptionElement;
import com.android.wm.shell.windowdecor.additionalviewcontainer.AdditionalViewHostViewContainer;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHost;
import com.android.wm.shell.windowdecor.common.viewhost.WindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.extension.InsetsStateKt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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

    /**
     * The Z-order of the caption surface.
     * <p>
     * We use {@link #mDecorationContainerSurface} to define input window for task resizing; by
     * layering it in front of the caption surface, we can allow it to handle input
     * prior to caption view itself, treating corner inputs as resize events rather than
     * repositioning.
     */
    static final int CAPTION_LAYER_Z_ORDER = -1;
    /**
     * The Z-order of the task input sink in {@link DragPositioningCallback}.
     * <p>
     * This task input sink is used to prevent undesired dispatching of motion events out of task
     * bounds; by layering it behind the caption surface, we allow captions to handle
     * input events first.
     */
    static final int INPUT_SINK_Z_ORDER = -2;

    /**
     * Invalid corner radius that signifies that corner radius should not be set.
     */
    static final int INVALID_CORNER_RADIUS = -1;
    /**
     * Invalid corner radius that signifies that shadow radius should not be set.
     */
    static final int INVALID_SHADOW_RADIUS = -1;

    /**
     * System-wide context. Only used to create context with overridden configurations.
     */
    final Context mContext;
    final @NonNull Context mUserContext;
    final @NonNull DisplayController mDisplayController;
    final @NonNull DesktopModeEventLogger mDesktopModeEventLogger;
    final ShellTaskOrganizer mTaskOrganizer;
    final Supplier<SurfaceControl.Builder> mSurfaceControlBuilderSupplier;
    final Supplier<SurfaceControl.Transaction> mSurfaceControlTransactionSupplier;
    final Supplier<WindowContainerTransaction> mWindowContainerTransactionSupplier;
    final SurfaceControlViewHostFactory mSurfaceControlViewHostFactory;
    @NonNull private final WindowDecorViewHostSupplier<WindowDecorViewHost>
            mWindowDecorViewHostSupplier;
    private final DisplayController.OnDisplaysChangedListener mOnDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (mTaskInfo.displayId != displayId) {
                        return;
                    }

                    mDisplayController.removeDisplayWindowListener(this);
                    relayout(mTaskInfo, mHasGlobalFocus, mExclusionRegion);
                }
            };

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public RunningTaskInfo mTaskInfo;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public Context mDecorWindowContext;
    int mLayoutResId;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public final SurfaceControl mTaskSurface;

    Display mDisplay;
    SurfaceControl mDecorationContainerSurface;

    private WindowDecorViewHost mViewHost;
    private Configuration mWindowDecorConfig;
    TaskDragResizer mTaskDragResizer;
    boolean mIsCaptionVisible;

    boolean mIsStatusBarVisible;
    boolean mIsKeyguardVisibleAndOccluded;
    boolean mHasGlobalFocus;
    final Region mExclusionRegion = Region.obtain();

    /** The most recent set of insets applied to this window decoration. */
    private WindowDecorationInsets mWindowDecorationInsets;
    private final Binder mOwner = new Binder();
    private final float[] mTmpColor = new float[3];

    WindowDecoration(
            Context context,
            @NonNull Context userContext,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier) {
        this(context, userContext, displayController, taskOrganizer, taskInfo,
                taskSurface, SurfaceControl.Builder::new, SurfaceControl.Transaction::new,
                WindowContainerTransaction::new, SurfaceControl::new,
                new SurfaceControlViewHostFactory() {}, windowDecorViewHostSupplier,
                new DesktopModeEventLogger());
    }

    WindowDecoration(
            Context context,
            @NonNull Context userContext,
            @NonNull DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            @NonNull SurfaceControl taskSurface,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
            Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
            Supplier<SurfaceControl> surfaceControlSupplier,
            SurfaceControlViewHostFactory surfaceControlViewHostFactory,
            @NonNull WindowDecorViewHostSupplier<WindowDecorViewHost> windowDecorViewHostSupplier,
            @NonNull DesktopModeEventLogger desktopModeEventLogger
    ) {
        mContext = context;
        mUserContext = userContext;
        mDisplayController = displayController;
        mTaskOrganizer = taskOrganizer;
        mTaskInfo = taskInfo;
        mTaskSurface = cloneSurfaceControl(taskSurface, surfaceControlSupplier);
        mDesktopModeEventLogger = desktopModeEventLogger;
        mSurfaceControlBuilderSupplier = surfaceControlBuilderSupplier;
        mSurfaceControlTransactionSupplier = surfaceControlTransactionSupplier;
        mWindowContainerTransactionSupplier = windowContainerTransactionSupplier;
        mSurfaceControlViewHostFactory = surfaceControlViewHostFactory;
        mWindowDecorViewHostSupplier = windowDecorViewHostSupplier;
        mDisplay = mDisplayController.getDisplay(mTaskInfo.displayId);
        final InsetsState insetsState = mDisplayController.getInsetsState(mTaskInfo.displayId);
        mIsStatusBarVisible = insetsState != null
                && InsetsStateKt.isVisible(insetsState, statusBars());
    }

    /**
     * Gets the decoration's task leash.
     * @return the decoration' task surface used to manipulate the task.
     */
    public SurfaceControl getLeash() {
        return mTaskSurface;
    }

    /**
     * Used by {@link WindowDecoration} to trigger a new relayout because the requirements for a
     * relayout weren't satisfied are satisfied now.
     *
     * @param taskInfo The previous {@link RunningTaskInfo} passed into {@link #relayout} or the
     *                 constructor.
     * @param hasGlobalFocus Whether the task is focused
     */
    abstract void relayout(RunningTaskInfo taskInfo, boolean hasGlobalFocus,
            @NonNull Region displayExclusionRegion);

    /**
     * Used by the {@link DragPositioningCallback} associated with the implementing class to
     * enforce drags ending in a valid position. A null result means no restriction.
     */
    @Nullable
    abstract Rect calculateValidDragArea();

    void relayout(RelayoutParams params, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, WindowContainerTransaction wct, T rootView,
            RelayoutResult<T> outResult) {
        Trace.beginSection("WindowDecoration#relayout");
        outResult.reset();
        if (params.mRunningTaskInfo != null) {
            mTaskInfo = params.mRunningTaskInfo;
        }
        mHasGlobalFocus = params.mHasGlobalFocus;
        mExclusionRegion.set(params.mDisplayExclusionRegion);
        final int oldLayoutResId = mLayoutResId;
        mLayoutResId = params.mLayoutResId;

        if (!mTaskInfo.isVisible) {
            releaseViews(wct);
            if (params.mSetTaskVisibilityPositionAndCrop) {
                finishT.hide(mTaskSurface);
            }
            Trace.endSection(); // WindowDecoration#relayout
            return;
        }
        Trace.beginSection("WindowDecoration#relayout-inflateIfNeeded");
        inflateIfNeeded(params, wct, rootView, oldLayoutResId, outResult);
        Trace.endSection();
        final boolean hasCaptionView = outResult.mRootView != null;
        if (!hasCaptionView) {
            Trace.endSection(); // WindowDecoration#relayout
            return;
        }

        Trace.beginSection("WindowDecoration#relayout-updateCaptionVisibility");
        updateCaptionVisibility(outResult.mRootView, params);
        Trace.endSection();

        final Rect taskBounds = mTaskInfo.getConfiguration().windowConfiguration.getBounds();
        outResult.mWidth = taskBounds.width();
        outResult.mHeight = taskBounds.height();
        outResult.mRootView.setTaskFocusState(mHasGlobalFocus);
        final Resources resources = mDecorWindowContext.getResources();
        outResult.mCaptionHeight = loadDimensionPixelSize(resources, params.mCaptionHeightId)
                + params.mCaptionTopPadding;
        outResult.mCaptionWidth = params.mCaptionWidthId != Resources.ID_NULL
                ? loadDimensionPixelSize(resources, params.mCaptionWidthId) : taskBounds.width();
        outResult.mCaptionX = (outResult.mWidth - outResult.mCaptionWidth) / 2;
        outResult.mCaptionY = 0;
        outResult.mCaptionTopPadding = params.mCaptionTopPadding;

        Trace.beginSection("relayout-createViewHostIfNeeded");
        createViewHostIfNeeded(mDecorWindowContext, mDisplay);
        Trace.endSection();

        Trace.beginSection("WindowDecoration#relayout-updateSurfacesAndInsets");
        final SurfaceControl captionSurface = mViewHost.getSurfaceControl();
        updateDecorationContainerSurface(startT, outResult);
        updateCaptionContainerSurface(captionSurface, startT, outResult);
        updateCaptionInsets(params, wct, outResult, taskBounds);
        updateTaskSurface(params, startT, finishT, outResult);
        Trace.endSection();

        Trace.beginSection("WindowDecoration#relayout-updateViewHost");
        outResult.mRootView.setPadding(0, params.mCaptionTopPadding, 0, 0);
        final Rect localCaptionBounds = new Rect(
                outResult.mCaptionX,
                outResult.mCaptionY,
                outResult.mCaptionX + outResult.mCaptionWidth,
                outResult.mCaptionY + outResult.mCaptionHeight);
        final Region touchableRegion = params.mLimitTouchRegionToSystemAreas
                ? calculateLimitedTouchableRegion(params, localCaptionBounds)
                : null;
        updateViewHierarchy(params, outResult, startT, touchableRegion);
        Trace.endSection();

        Trace.endSection(); // WindowDecoration#relayout
    }

    private void createViewHostIfNeeded(@NonNull Context context, @NonNull Display display) {
        if (mViewHost == null) {
            mViewHost = mWindowDecorViewHostSupplier.acquire(context, display);
        }
    }

    private void updateViewHierarchy(@NonNull RelayoutParams params,
            @NonNull RelayoutResult<T> outResult, @NonNull SurfaceControl.Transaction startT,
            @Nullable Region touchableRegion) {
        Trace.beginSection("WindowDecoration#updateViewHierarchy");
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        outResult.mCaptionWidth,
                        outResult.mCaptionHeight,
                        TYPE_APPLICATION,
                        FLAG_NOT_FOCUSABLE | FLAG_SPLIT_TOUCH,
                        PixelFormat.TRANSPARENT);
        lp.setTitle("Caption of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        lp.inputFeatures = params.mInputFeatures;
        if (params.mAsyncViewHost) {
            if (params.mApplyStartTransactionOnDraw) {
                throw new IllegalArgumentException("Cannot use sync draw tx with async relayout");
            }
            mViewHost.updateViewAsync(outResult.mRootView, lp, mTaskInfo.configuration,
                    touchableRegion);
        } else {
            mViewHost.updateView(outResult.mRootView, lp, mTaskInfo.configuration,
                    touchableRegion, params.mApplyStartTransactionOnDraw ? startT : null);
        }
        Trace.endSection();
    }

    private void inflateIfNeeded(RelayoutParams params, WindowContainerTransaction wct,
            T rootView, int oldLayoutResId, RelayoutResult<T> outResult) {
        if (rootView == null && params.mLayoutResId == 0) {
            throw new IllegalArgumentException("layoutResId and rootView can't both be invalid.");
        }

        outResult.mRootView = rootView;
        final int oldDensityDpi = mWindowDecorConfig != null
                ? mWindowDecorConfig.densityDpi : DENSITY_DPI_UNDEFINED;
        final int oldNightMode =  mWindowDecorConfig != null
                ? (mWindowDecorConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                : Configuration.UI_MODE_NIGHT_UNDEFINED;
        mWindowDecorConfig = params.mWindowDecorConfig != null ? params.mWindowDecorConfig
                : mTaskInfo.getConfiguration();
        final int newDensityDpi = mWindowDecorConfig.densityDpi;
        final int newNightMode =  mWindowDecorConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (oldDensityDpi != newDensityDpi
                || mDisplay == null
                || mDisplay.getDisplayId() != mTaskInfo.displayId
                || oldLayoutResId != mLayoutResId
                || oldNightMode != newNightMode
                || mDecorWindowContext == null) {
            releaseViews(wct);

            if (!obtainDisplayOrRegisterListener()) {
                outResult.mRootView = null;
                return;
            }
            mDecorWindowContext = mContext.createConfigurationContext(mWindowDecorConfig);
            mDecorWindowContext.setTheme(mContext.getThemeResId());
            if (params.mLayoutResId != 0) {
                outResult.mRootView = inflateLayout(mDecorWindowContext, params.mLayoutResId);
            }
        }

        if (outResult.mRootView == null) {
            outResult.mRootView = inflateLayout(mDecorWindowContext, params.mLayoutResId);
        }
    }

    @VisibleForTesting
    T inflateLayout(Context context, int layoutResId) {
        return (T) LayoutInflater.from(context).inflate(layoutResId, null);
    }

    private void updateDecorationContainerSurface(
            SurfaceControl.Transaction startT, RelayoutResult<T> outResult) {
        if (mDecorationContainerSurface == null) {
            final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
            mDecorationContainerSurface = builder
                    .setName("Decor container of Task=" + mTaskInfo.taskId)
                    .setContainerLayer()
                    .setParent(mTaskSurface)
                    .setCallsite("WindowDecoration.updateDecorationContainerSurface")
                    .build();

            startT.setTrustedOverlay(mDecorationContainerSurface, true)
                    .setLayer(mDecorationContainerSurface,
                            TaskConstants.TASK_CHILD_LAYER_WINDOW_DECORATIONS);
        }

        startT.setWindowCrop(mDecorationContainerSurface, outResult.mWidth, outResult.mHeight)
                .show(mDecorationContainerSurface);
    }

    private void updateCaptionContainerSurface(@NonNull SurfaceControl captionSurface,
            SurfaceControl.Transaction startT, RelayoutResult<T> outResult) {
        startT.reparent(captionSurface, mDecorationContainerSurface)
                .setWindowCrop(captionSurface, outResult.mCaptionWidth, outResult.mCaptionHeight)
                .setPosition(captionSurface, outResult.mCaptionX, 0 /* y */)
                .setLayer(captionSurface, CAPTION_LAYER_Z_ORDER)
                .show(captionSurface);
    }

    private void updateCaptionInsets(RelayoutParams params, WindowContainerTransaction wct,
            RelayoutResult<T> outResult, Rect taskBounds) {
        if (!mIsCaptionVisible || !params.mIsInsetSource) {
            if (mWindowDecorationInsets != null) {
                mWindowDecorationInsets.remove(wct);
                mWindowDecorationInsets = null;
            }
            return;
        }
        // Caption inset is the full width of the task with the |captionHeight| and
        // positioned at the top of the task bounds, also in absolute coordinates.
        // So just reuse the task bounds and adjust the bottom coordinate.
        final Rect captionInsetsRect = new Rect(taskBounds);
        captionInsetsRect.bottom = captionInsetsRect.top + outResult.mCaptionHeight;

        // Caption bounding rectangles: these are optional, and are used to present finer
        // insets than traditional |Insets| to apps about where their content is occluded.
        // These are also in absolute coordinates.
        final Rect[] boundingRects;
        final int numOfElements = params.mOccludingCaptionElements.size();
        if (numOfElements == 0) {
            boundingRects = null;
        } else {
            // The customizable region can at most be equal to the caption bar.
            if (params.hasInputFeatureSpy()) {
                outResult.mCustomizableCaptionRegion.set(captionInsetsRect);
            }
            final Resources resources = mDecorWindowContext.getResources();
            boundingRects = new Rect[numOfElements];
            for (int i = 0; i < numOfElements; i++) {
                final OccludingCaptionElement element =
                        params.mOccludingCaptionElements.get(i);
                final int elementWidthPx =
                        resources.getDimensionPixelSize(element.mWidthResId);
                boundingRects[i] =
                        calculateBoundingRectLocal(element, elementWidthPx, captionInsetsRect);
                // Subtract the regions used by the caption elements, the rest is
                // customizable.
                if (params.hasInputFeatureSpy()) {
                    outResult.mCustomizableCaptionRegion.op(boundingRects[i],
                            Region.Op.DIFFERENCE);
                }
            }
        }

        final WindowDecorationInsets newInsets = new WindowDecorationInsets(
                mTaskInfo.token, mOwner, captionInsetsRect, boundingRects,
                params.mInsetSourceFlags, params.mIsInsetSource);
        if (!newInsets.equals(mWindowDecorationInsets)) {
            // Add or update this caption as an insets source.
            mWindowDecorationInsets = newInsets;
            mWindowDecorationInsets.update(wct);
        }
    }

    private void updateTaskSurface(RelayoutParams params, SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT, RelayoutResult<T> outResult) {
        if (params.mSetTaskVisibilityPositionAndCrop) {
            final Point taskPosition = mTaskInfo.positionInParent;
            startT.setWindowCrop(mTaskSurface, outResult.mWidth, outResult.mHeight);
            finishT.setWindowCrop(mTaskSurface, outResult.mWidth, outResult.mHeight)
                    .setPosition(mTaskSurface, taskPosition.x, taskPosition.y);
        }

        if (params.mShadowRadius != INVALID_SHADOW_RADIUS) {
            startT.setShadowRadius(mTaskSurface, params.mShadowRadius);
            finishT.setShadowRadius(mTaskSurface, params.mShadowRadius);
        }

        if (params.mSetTaskVisibilityPositionAndCrop) {
            startT.show(mTaskSurface);
        }

        if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && !DesktopModeStatus.isVeiledResizeEnabled()) {
            // When fluid resize is enabled, add a background to freeform tasks
            int backgroundColorInt = mTaskInfo.taskDescription.getBackgroundColor();
            mTmpColor[0] = (float) Color.red(backgroundColorInt) / 255.f;
            mTmpColor[1] = (float) Color.green(backgroundColorInt) / 255.f;
            mTmpColor[2] = (float) Color.blue(backgroundColorInt) / 255.f;
            startT.setColor(mTaskSurface, mTmpColor);
        } else if (!DesktopModeStatus.isVeiledResizeEnabled()) {
            startT.unsetColor(mTaskSurface);
        }

        if (params.mCornerRadius != INVALID_CORNER_RADIUS) {
            startT.setCornerRadius(mTaskSurface, params.mCornerRadius);
            finishT.setCornerRadius(mTaskSurface, params.mCornerRadius);
        }
    }

    @NonNull
    private Region calculateLimitedTouchableRegion(
            RelayoutParams params,
            @NonNull Rect localCaptionBounds) {
        // Make caption bounds relative to display to align with exclusion region.
        final Point positionInParent = params.mRunningTaskInfo.positionInParent;
        final Rect captionBoundsInDisplay = new Rect(localCaptionBounds);
        captionBoundsInDisplay.offsetTo(positionInParent.x, positionInParent.y);

        final Region boundingRects = calculateBoundingRectsRegion(params, captionBoundsInDisplay);

        final Region customizedRegion = Region.obtain();
        customizedRegion.set(captionBoundsInDisplay);
        customizedRegion.op(boundingRects, Region.Op.DIFFERENCE);
        customizedRegion.op(params.mDisplayExclusionRegion, Region.Op.INTERSECT);

        final Region touchableRegion = Region.obtain();
        touchableRegion.set(captionBoundsInDisplay);
        touchableRegion.op(customizedRegion, Region.Op.DIFFERENCE);
        // Return resulting region back to window coordinates.
        touchableRegion.translate(-positionInParent.x, -positionInParent.y);

        boundingRects.recycle();
        customizedRegion.recycle();
        return touchableRegion;
    }

    @NonNull
    private Region calculateBoundingRectsRegion(
            @NonNull RelayoutParams params,
            @NonNull Rect captionBoundsInDisplay) {
        final int numOfElements = params.mOccludingCaptionElements.size();
        final Region region = Region.obtain();
        if (numOfElements == 0) {
            // The entire caption is a bounding rect.
            region.set(captionBoundsInDisplay);
            return region;
        }
        final Resources resources = mDecorWindowContext.getResources();
        for (int i = 0; i < numOfElements; i++) {
            final OccludingCaptionElement element = params.mOccludingCaptionElements.get(i);
            final int elementWidthPx = resources.getDimensionPixelSize(element.mWidthResId);
            final Rect boundingRect = calculateBoundingRectLocal(element, elementWidthPx,
                    captionBoundsInDisplay);
            // Bounding rect is initially calculated relative to the caption, so offset it to make
            // it relative to the display.
            boundingRect.offset(captionBoundsInDisplay.left, captionBoundsInDisplay.top);
            region.union(boundingRect);
        }
        return region;
    }

    private Rect calculateBoundingRectLocal(@NonNull OccludingCaptionElement element,
            int elementWidthPx, @NonNull Rect captionRect) {
        final boolean isRtl =
                mDecorWindowContext.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL;
        switch (element.mAlignment) {
            case START -> {
                if (isRtl) {
                    return new Rect(captionRect.width() - elementWidthPx, 0,
                            captionRect.width(), captionRect.height());
                } else {
                    return new Rect(0, 0, elementWidthPx, captionRect.height());
                }
            }
            case END -> {
                if (isRtl) {
                    return new Rect(0, 0, elementWidthPx, captionRect.height());
                } else {
                    return new Rect(captionRect.width() - elementWidthPx, 0,
                            captionRect.width(), captionRect.height());
                }
            }
        }
        throw new IllegalArgumentException("Unexpected alignment " + element.mAlignment);
    }

    void onKeyguardStateChanged(boolean visible, boolean occluded) {
        final boolean prevVisAndOccluded = mIsKeyguardVisibleAndOccluded;
        mIsKeyguardVisibleAndOccluded = visible && occluded;
        final boolean changed = prevVisAndOccluded != mIsKeyguardVisibleAndOccluded;
        if (changed) {
            relayout(mTaskInfo, mHasGlobalFocus, mExclusionRegion);
        }
    }

    void onInsetsStateChanged(@NonNull InsetsState insetsState) {
        final boolean prevStatusBarVisibility = mIsStatusBarVisible;
        mIsStatusBarVisible = InsetsStateKt.isVisible(insetsState, statusBars());
        final boolean changed = prevStatusBarVisibility != mIsStatusBarVisible;

        if (changed) {
            relayout(mTaskInfo, mHasGlobalFocus, mExclusionRegion);
        }
    }

    void onExclusionRegionChanged(@NonNull Region exclusionRegion) {
        relayout(mTaskInfo, mHasGlobalFocus, exclusionRegion);
    }

    /**
     * Update caption visibility state and views.
     */
    private void updateCaptionVisibility(View rootView, @NonNull RelayoutParams params) {
        mIsCaptionVisible = params.mIsCaptionVisible;
        setCaptionVisibility(rootView, mIsCaptionVisible);
    }

    void setTaskDragResizer(TaskDragResizer taskDragResizer) {
        mTaskDragResizer = taskDragResizer;
    }

    // TODO(b/346441962): Move these three methods closer to implementing or View-level classes to
    //  keep implementation details more encapsulated.
    private void setCaptionVisibility(View rootView, boolean visible) {
        if (rootView == null) {
            return;
        }
        final int v = visible ? View.VISIBLE : View.GONE;
        final View captionView = rootView.findViewById(getCaptionViewId());
        captionView.setVisibility(v);
    }

    int getCaptionHeightId(@WindowingMode int windowingMode) {
        return Resources.ID_NULL;
    }

    int getCaptionViewId() {
        return Resources.ID_NULL;
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

    void releaseViews(WindowContainerTransaction wct) {
        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        boolean released = false;
        if (mViewHost != null) {
            mWindowDecorViewHostSupplier.release(mViewHost, t);
            mViewHost = null;
            released = true;
        }

        if (mDecorationContainerSurface != null) {
            t.remove(mDecorationContainerSurface);
            mDecorationContainerSurface = null;
            released = true;
        }

        if (released) {
            t.apply();
        }

        if (mWindowDecorationInsets != null) {
            mWindowDecorationInsets.remove(wct);
            mWindowDecorationInsets = null;
        }
    }

    @Override
    public void close() {
        Trace.beginSection("WindowDecoration#close");
        mDisplayController.removeDisplayWindowListener(mOnDisplaysChangedListener);
        final WindowContainerTransaction wct = mWindowContainerTransactionSupplier.get();
        releaseViews(wct);
        mTaskOrganizer.applyTransaction(wct);
        mTaskSurface.release();
        Trace.endSection();
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

    private static SurfaceControl cloneSurfaceControl(SurfaceControl sc,
            Supplier<SurfaceControl> surfaceControlSupplier) {
        final SurfaceControl copy = surfaceControlSupplier.get();
        copy.copyFrom(sc, "WindowDecoration");
        return copy;
    }

    /**
     * Create a window associated with this WindowDecoration.
     * Note that subclass must dispose of this when the task is hidden/closed.
     *
     * @param v            View to attach to the window
     * @param t            the transaction to apply
     * @param xPos         x position of new window
     * @param yPos         y position of new window
     * @param width        width of new window
     * @param height       height of new window
     * @return the {@link AdditionalViewHostViewContainer} that was added.
     */
    AdditionalViewHostViewContainer addWindow(@NonNull View v, @NonNull String namePrefix,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceSyncGroup ssg,
            int xPos, int yPos, int width, int height) {
        final SurfaceControl.Builder builder = mSurfaceControlBuilderSupplier.get();
        SurfaceControl windowSurfaceControl = builder
                .setName(namePrefix + " of Task=" + mTaskInfo.taskId)
                .setContainerLayer()
                .setParent(mDecorationContainerSurface)
                .setCallsite("WindowDecoration.addWindow")
                .build();
        t.setPosition(windowSurfaceControl, xPos, yPos)
                .setWindowCrop(windowSurfaceControl, width, height)
                .show(windowSurfaceControl);
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        width,
                        height,
                        TYPE_APPLICATION,
                        FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH,
                        PixelFormat.TRANSPARENT);
        lp.setTitle("Additional window of Task=" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        WindowlessWindowManager windowManager = new WindowlessWindowManager(mTaskInfo.configuration,
                windowSurfaceControl, null /* hostInputToken */);
        SurfaceControlViewHost viewHost = mSurfaceControlViewHostFactory
                .create(mDecorWindowContext, mDisplay, windowManager);
        ssg.add(viewHost.getSurfacePackage(), () -> viewHost.setView(v, lp));
        return new AdditionalViewHostViewContainer(windowSurfaceControl, viewHost,
                mSurfaceControlTransactionSupplier);
    }

    /**
     * Create a window associated with this WindowDecoration.
     * Note that subclass must dispose of this when the task is hidden/closed.
     *
     * @param layoutId     layout to make the window from
     * @param t            the transaction to apply
     * @param xPos         x position of new window
     * @param yPos         y position of new window
     * @param width        width of new window
     * @param height       height of new window
     * @return the {@link AdditionalViewHostViewContainer} that was added.
     */
    AdditionalViewHostViewContainer addWindow(int layoutId, String namePrefix,
            SurfaceControl.Transaction t, SurfaceSyncGroup ssg, int xPos, int yPos,
            int width, int height) {
        final View v = LayoutInflater.from(mDecorWindowContext).inflate(layoutId, null);
        return addWindow(v, namePrefix, t, ssg, xPos, yPos, width, height);
    }

    /**
     * Adds caption inset source to a WCT
     */
    public void addCaptionInset(WindowContainerTransaction wct) {
        final int captionHeightId = getCaptionHeightId(mTaskInfo.getWindowingMode());
        if (captionHeightId == Resources.ID_NULL || !mIsCaptionVisible) {
            return;
        }

        final int captionHeight = loadDimensionPixelSize(mContext.getResources(), captionHeightId);
        final Rect captionInsets = new Rect(0, 0, 0, captionHeight);
        final WindowDecorationInsets newInsets = new WindowDecorationInsets(mTaskInfo.token,
                mOwner, captionInsets, null /* boundingRets */, 0 /* flags */,
                true /* shouldAddCaptionInset */);
        if (!newInsets.equals(mWindowDecorationInsets)) {
            mWindowDecorationInsets = newInsets;
            mWindowDecorationInsets.update(wct);
        }
    }

    static class RelayoutParams {
        RunningTaskInfo mRunningTaskInfo;
        int mLayoutResId;
        int mCaptionHeightId;
        int mCaptionWidthId;
        final List<OccludingCaptionElement> mOccludingCaptionElements = new ArrayList<>();
        boolean mLimitTouchRegionToSystemAreas;
        int mInputFeatures;
        boolean mIsInsetSource = true;
        @InsetsSource.Flags int mInsetSourceFlags;
        final Region mDisplayExclusionRegion = Region.obtain();

        int mShadowRadius = INVALID_SHADOW_RADIUS;
        int mCornerRadius = INVALID_CORNER_RADIUS;

        int mCaptionTopPadding;
        boolean mIsCaptionVisible;

        Configuration mWindowDecorConfig;
        boolean mAsyncViewHost;

        boolean mApplyStartTransactionOnDraw;
        boolean mSetTaskVisibilityPositionAndCrop;
        boolean mHasGlobalFocus;

        void reset() {
            mLayoutResId = Resources.ID_NULL;
            mCaptionHeightId = Resources.ID_NULL;
            mCaptionWidthId = Resources.ID_NULL;
            mOccludingCaptionElements.clear();
            mLimitTouchRegionToSystemAreas = false;
            mInputFeatures = 0;
            mIsInsetSource = true;
            mInsetSourceFlags = 0;
            mDisplayExclusionRegion.setEmpty();

            mShadowRadius = INVALID_SHADOW_RADIUS;
            mCornerRadius = INVALID_SHADOW_RADIUS;

            mCaptionTopPadding = 0;
            mIsCaptionVisible = false;

            mApplyStartTransactionOnDraw = false;
            mSetTaskVisibilityPositionAndCrop = false;
            mWindowDecorConfig = null;
            mAsyncViewHost = false;
            mHasGlobalFocus = false;
        }

        boolean hasInputFeatureSpy() {
            return (mInputFeatures & WindowManager.LayoutParams.INPUT_FEATURE_SPY) != 0;
        }

        /**
         * Describes elements within the caption bar that could occlude app content, and should be
         * sent as bounding rectangles to the insets system.
         */
        static class OccludingCaptionElement {
            int mWidthResId;
            Alignment mAlignment;

            enum Alignment {
                START, END
            }
        }
    }

    static class RelayoutResult<T extends View & TaskFocusStateConsumer> {
        int mCaptionHeight;
        int mCaptionWidth;
        int mCaptionX;
        int mCaptionY;
        int mCaptionTopPadding;
        final Region mCustomizableCaptionRegion = Region.obtain();
        int mWidth;
        int mHeight;
        T mRootView;

        void reset() {
            mWidth = 0;
            mHeight = 0;
            mCaptionHeight = 0;
            mCaptionWidth = 0;
            mCaptionX = 0;
            mCaptionY = 0;
            mCaptionTopPadding = 0;
            mCustomizableCaptionRegion.setEmpty();
            mRootView = null;
        }
    }

    private static class CaptionWindowlessWindowManager extends WindowlessWindowManager {
        CaptionWindowlessWindowManager(
                @NonNull Configuration configuration,
                @NonNull SurfaceControl rootSurface) {
            super(configuration, rootSurface, /* hostInputToken= */ null);
        }

        /** Set the view host's touchable region. */
        void setTouchRegion(@NonNull SurfaceControlViewHost viewHost, @NonNull Region region) {
            setTouchRegion(viewHost.getWindowToken().asBinder(), region);
        }
    }

    @VisibleForTesting
    public interface SurfaceControlViewHostFactory {
        default SurfaceControlViewHost create(Context c, Display d, WindowlessWindowManager wmm) {
            return new SurfaceControlViewHost(c, d, wmm, "WindowDecoration");
        }
        default SurfaceControlViewHost create(Context c, Display d,
                WindowlessWindowManager wmm, String callsite) {
            return new SurfaceControlViewHost(c, d, wmm, callsite);
        }
    }

    private static class WindowDecorationInsets {
        private static final int INDEX = 0;
        private final WindowContainerToken mToken;
        private final Binder mOwner;
        private final Rect mFrame;
        private final Rect[] mBoundingRects;
        private final @InsetsSource.Flags int mFlags;
        private final boolean mShouldAddCaptionInset;

        private WindowDecorationInsets(WindowContainerToken token, Binder owner, Rect frame,
                Rect[] boundingRects, @InsetsSource.Flags int flags,
                boolean shouldAddCaptionInset) {
            mToken = token;
            mOwner = owner;
            mFrame = frame;
            mBoundingRects = boundingRects;
            mFlags = flags;
            mShouldAddCaptionInset = shouldAddCaptionInset;
        }

        void update(WindowContainerTransaction wct) {
            if (mShouldAddCaptionInset) {
                wct.addInsetsSource(mToken, mOwner, INDEX, captionBar(), mFrame, mBoundingRects,
                        mFlags);
                wct.addInsetsSource(mToken, mOwner, INDEX, mandatorySystemGestures(), mFrame,
                        mBoundingRects, 0 /* flags */);
            }
        }

        void remove(WindowContainerTransaction wct) {
            wct.removeInsetsSource(mToken, mOwner, INDEX, captionBar());
            wct.removeInsetsSource(mToken, mOwner, INDEX, mandatorySystemGestures());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WindowDecoration.WindowDecorationInsets that)) return false;
            return Objects.equals(mToken, that.mToken) && Objects.equals(mOwner,
                    that.mOwner) && Objects.equals(mFrame, that.mFrame)
                    && Objects.deepEquals(mBoundingRects, that.mBoundingRects)
                    && mFlags == that.mFlags
                    && mShouldAddCaptionInset == that.mShouldAddCaptionInset;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mToken, mOwner, mFrame, Arrays.hashCode(mBoundingRects), mFlags);
        }
    }
}