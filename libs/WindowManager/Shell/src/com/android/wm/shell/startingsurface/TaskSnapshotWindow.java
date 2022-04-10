/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.startingsurface;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.graphics.Color.WHITE;
import static android.graphics.Color.alpha;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.ViewRootImpl.LOCAL_LAYOUT;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowLayout.UNSPECIFIED_LENGTH;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;
import static android.view.WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import static com.android.internal.policy.DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.getNavigationBarRect;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityThread;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowLayout;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.ClientWindowFrames;
import android.window.StartingWindowInfo;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.view.BaseIWindow;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.lang.ref.WeakReference;

/**
 * This class represents a starting window that shows a snapshot.
 *
 * @hide
 */
public class TaskSnapshotWindow {
    /**
     * When creating the starting window, we use the exact same layout flags such that we end up
     * with a window with the exact same dimensions etc. However, these flags are not used in layout
     * and might cause other side effects so we exclude them.
     */
    static final int FLAG_INHERIT_EXCLUDES = FLAG_NOT_FOCUSABLE
            | FLAG_NOT_TOUCHABLE
            | FLAG_NOT_TOUCH_MODAL
            | FLAG_ALT_FOCUSABLE_IM
            | FLAG_NOT_FOCUSABLE
            | FLAG_HARDWARE_ACCELERATED
            | FLAG_IGNORE_CHEEK_PRESSES
            | FLAG_LOCAL_FOCUS_MODE
            | FLAG_SLIPPERY
            | FLAG_WATCH_OUTSIDE_TOUCH
            | FLAG_SPLIT_TOUCH
            | FLAG_SCALED
            | FLAG_SECURE;

    private static final String TAG = StartingWindowController.TAG;
    private static final String TITLE_FORMAT = "SnapshotStartingWindow for taskId=%s";

    private static final long DELAY_REMOVAL_TIME_GENERAL = 100;
    /**
     * The max delay time in milliseconds for removing the task snapshot window with IME visible.
     * Ideally the delay time will be shorter when receiving
     * {@link StartingSurfaceDrawer#onImeDrawnOnTask(int)}.
     */
    private static final long MAX_DELAY_REMOVAL_TIME_IME_VISIBLE = 600;

    private final Window mWindow;
    private final Runnable mClearWindowHandler;
    private final ShellExecutor mSplashScreenExecutor;
    private final SurfaceControl mSurfaceControl;
    private final IWindowSession mSession;
    private final Rect mTaskBounds;
    private final Rect mFrame = new Rect();
    private final Rect mSystemBarInsets = new Rect();
    private TaskSnapshot mSnapshot;
    private final RectF mTmpSnapshotSize = new RectF();
    private final RectF mTmpDstFrame = new RectF();
    private final CharSequence mTitle;
    private boolean mHasDrawn;
    private boolean mSizeMismatch;
    private final Paint mBackgroundPaint = new Paint();
    private final int mActivityType;
    private final int mStatusBarColor;
    private final SystemBarBackgroundPainter mSystemBarBackgroundPainter;
    private final int mOrientationOnCreation;
    private final SurfaceControl.Transaction mTransaction;
    private final Matrix mSnapshotMatrix = new Matrix();
    private final float[] mTmpFloat9 = new float[9];
    private final Runnable mScheduledRunnable = this::removeImmediately;
    private final boolean mHasImeSurface;

    static TaskSnapshotWindow create(StartingWindowInfo info, IBinder appToken,
            TaskSnapshot snapshot, ShellExecutor splashScreenExecutor,
            @NonNull Runnable clearWindowHandler) {
        final ActivityManager.RunningTaskInfo runningTaskInfo = info.taskInfo;
        final int taskId = runningTaskInfo.taskId;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_STARTING_WINDOW,
                "create taskSnapshot surface for task: %d", taskId);

        final WindowManager.LayoutParams attrs = info.topOpaqueWindowLayoutParams;
        final WindowManager.LayoutParams mainWindowParams = info.mainWindowLayoutParams;
        final InsetsState topWindowInsetsState = info.topOpaqueWindowInsetsState;
        if (attrs == null || mainWindowParams == null || topWindowInsetsState == null) {
            Slog.w(TAG, "unable to create taskSnapshot surface for task: " + taskId);
            return null;
        }
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();

        final int appearance = attrs.insetsFlags.appearance;
        final int windowFlags = attrs.flags;
        final int windowPrivateFlags = attrs.privateFlags;

        layoutParams.packageName = mainWindowParams.packageName;
        layoutParams.windowAnimations = mainWindowParams.windowAnimations;
        layoutParams.dimAmount = mainWindowParams.dimAmount;
        layoutParams.type = TYPE_APPLICATION_STARTING;
        layoutParams.format = snapshot.getHardwareBuffer().getFormat();
        layoutParams.flags = (windowFlags & ~FLAG_INHERIT_EXCLUDES)
                | FLAG_NOT_FOCUSABLE
                | FLAG_NOT_TOUCHABLE;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        layoutParams.privateFlags = (windowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS)
                | PRIVATE_FLAG_TRUSTED_OVERLAY | PRIVATE_FLAG_USE_BLAST;
        layoutParams.token = appToken;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.insetsFlags.appearance = appearance;
        layoutParams.insetsFlags.behavior = attrs.insetsFlags.behavior;
        layoutParams.layoutInDisplayCutoutMode = attrs.layoutInDisplayCutoutMode;
        layoutParams.setFitInsetsTypes(attrs.getFitInsetsTypes());
        layoutParams.setFitInsetsSides(attrs.getFitInsetsSides());
        layoutParams.setFitInsetsIgnoringVisibility(attrs.isFitInsetsIgnoringVisibility());

        layoutParams.setTitle(String.format(TITLE_FORMAT, taskId));

        final Point taskSize = snapshot.getTaskSize();
        final Rect taskBounds = new Rect(0, 0, taskSize.x, taskSize.y);
        final int orientation = snapshot.getOrientation();
        final int activityType = runningTaskInfo.topActivityType;
        final int displayId = runningTaskInfo.displayId;

        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        final SurfaceControl surfaceControl = new SurfaceControl();
        final ClientWindowFrames tmpFrames = new ClientWindowFrames();
        final WindowLayout windowLayout = new WindowLayout();
        final Rect displayCutoutSafe = new Rect();

        final InsetsSourceControl[] tmpControls = new InsetsSourceControl[0];
        final MergedConfiguration tmpMergedConfiguration = new MergedConfiguration();

        final TaskDescription taskDescription;
        if (runningTaskInfo.taskDescription != null) {
            taskDescription = runningTaskInfo.taskDescription;
        } else {
            taskDescription = new TaskDescription();
            taskDescription.setBackgroundColor(WHITE);
        }

        final TaskSnapshotWindow snapshotSurface = new TaskSnapshotWindow(
                surfaceControl, snapshot, layoutParams.getTitle(), taskDescription, appearance,
                windowFlags, windowPrivateFlags, taskBounds, orientation, activityType,
                topWindowInsetsState, clearWindowHandler, splashScreenExecutor);
        final Window window = snapshotSurface.mWindow;

        final InsetsState tmpInsetsState = new InsetsState();
        final InputChannel tmpInputChannel = new InputChannel();

        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "TaskSnapshot#addToDisplay");
            final int res = session.addToDisplay(window, layoutParams, View.GONE, displayId,
                    info.requestedVisibilities, tmpInputChannel, tmpInsetsState, tmpControls,
                    new Rect());
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (res < 0) {
                Slog.w(TAG, "Failed to add snapshot starting window res=" + res);
                return null;
            }
        } catch (RemoteException e) {
            snapshotSurface.clearWindowSynced();
        }
        window.setOuter(snapshotSurface);
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "TaskSnapshot#relayout");
            if (LOCAL_LAYOUT) {
                if (!surfaceControl.isValid()) {
                    session.updateVisibility(window, layoutParams, View.VISIBLE,
                            tmpMergedConfiguration, surfaceControl, tmpInsetsState, tmpControls);
                }
                tmpInsetsState.getDisplayCutoutSafe(displayCutoutSafe);
                final WindowConfiguration winConfig =
                        tmpMergedConfiguration.getMergedConfiguration().windowConfiguration;
                windowLayout.computeFrames(layoutParams, tmpInsetsState, displayCutoutSafe,
                        winConfig.getBounds(), winConfig.getWindowingMode(), UNSPECIFIED_LENGTH,
                        UNSPECIFIED_LENGTH, info.requestedVisibilities, 1f /* compatScale */,
                        tmpFrames);
                session.updateLayout(window, layoutParams, 0 /* flags */, tmpFrames,
                        UNSPECIFIED_LENGTH, UNSPECIFIED_LENGTH);
            } else {
                session.relayout(window, layoutParams, -1, -1, View.VISIBLE, 0,
                        tmpFrames, tmpMergedConfiguration, surfaceControl, tmpInsetsState,
                        tmpControls, new Bundle());
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        } catch (RemoteException e) {
            snapshotSurface.clearWindowSynced();
        }

        final Rect systemBarInsets = getSystemBarInsets(tmpFrames.frame, topWindowInsetsState);
        snapshotSurface.setFrames(tmpFrames.frame, systemBarInsets);
        snapshotSurface.drawSnapshot();
        return snapshotSurface;
    }

    public TaskSnapshotWindow(SurfaceControl surfaceControl,
            TaskSnapshot snapshot, CharSequence title, TaskDescription taskDescription,
            int appearance, int windowFlags, int windowPrivateFlags, Rect taskBounds,
            int currentOrientation, int activityType, InsetsState topWindowInsetsState,
            Runnable clearWindowHandler, ShellExecutor splashScreenExecutor) {
        mSplashScreenExecutor = splashScreenExecutor;
        mSession = WindowManagerGlobal.getWindowSession();
        mWindow = new Window();
        mWindow.setSession(mSession);
        mSurfaceControl = surfaceControl;
        mSnapshot = snapshot;
        mTitle = title;
        int backgroundColor = taskDescription.getBackgroundColor();
        mBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : WHITE);
        mTaskBounds = taskBounds;
        mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags,
                windowPrivateFlags, appearance, taskDescription, 1f, topWindowInsetsState);
        mStatusBarColor = taskDescription.getStatusBarColor();
        mOrientationOnCreation = currentOrientation;
        mActivityType = activityType;
        mTransaction = new SurfaceControl.Transaction();
        mClearWindowHandler = clearWindowHandler;
        mHasImeSurface = snapshot.hasImeSurface();
    }

    int getBackgroundColor() {
        return mBackgroundPaint.getColor();
    }

    boolean hasImeSurface() {
	return mHasImeSurface;
    }

    /**
     * Ask system bar background painter to draw status bar background.
     * @hide
     */
    public void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame) {
        mSystemBarBackgroundPainter.drawStatusBarBackground(c, alreadyDrawnFrame,
                mSystemBarBackgroundPainter.getStatusBarColorViewHeight());
    }

    /**
     * Ask system bar background painter to draw navigation bar background.
     * @hide
     */
    public void drawNavigationBarBackground(Canvas c) {
        mSystemBarBackgroundPainter.drawNavigationBarBackground(c);
    }

    void scheduleRemove(boolean deferRemoveForIme) {
        // Show the latest content as soon as possible for unlocking to home.
        if (mActivityType == ACTIVITY_TYPE_HOME) {
            removeImmediately();
            return;
        }
        mSplashScreenExecutor.removeCallbacks(mScheduledRunnable);
        final long delayRemovalTime = mHasImeSurface && deferRemoveForIme
                ? MAX_DELAY_REMOVAL_TIME_IME_VISIBLE
                : DELAY_REMOVAL_TIME_GENERAL;
        mSplashScreenExecutor.executeDelayed(mScheduledRunnable, delayRemovalTime);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_STARTING_WINDOW,
                "Defer removing snapshot surface in %d", delayRemovalTime);
    }

    void removeImmediately() {
        mSplashScreenExecutor.removeCallbacks(mScheduledRunnable);
        try {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_STARTING_WINDOW,
                    "Removing taskSnapshot surface, mHasDrawn=%b", mHasDrawn);
            mSession.remove(mWindow);
        } catch (RemoteException e) {
            // nothing
        }
    }

    /**
     * Set frame size.
     * @hide
     */
    public void setFrames(Rect frame, Rect systemBarInsets) {
        mFrame.set(frame);
        mSystemBarInsets.set(systemBarInsets);
        final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
        mSizeMismatch = (mFrame.width() != snapshot.getWidth()
                || mFrame.height() != snapshot.getHeight());
        mSystemBarBackgroundPainter.setInsets(systemBarInsets);
    }

    static Rect getSystemBarInsets(Rect frame, InsetsState state) {
        return state.calculateInsets(frame, WindowInsets.Type.systemBars(),
                false /* ignoreVisibility */).toRect();
    }

    private void drawSnapshot() {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_STARTING_WINDOW,
                "Drawing snapshot surface sizeMismatch=%b", mSizeMismatch);
        if (mSizeMismatch) {
            // The dimensions of the buffer and the window don't match, so attaching the buffer
            // will fail. Better create a child window with the exact dimensions and fill the parent
            // window with the background color!
            drawSizeMismatchSnapshot();
        } else {
            drawSizeMatchSnapshot();
        }
        mHasDrawn = true;
        reportDrawn();

        // In case window manager leaks us, make sure we don't retain the snapshot.
        mSnapshot = null;
        mSurfaceControl.release();
    }

    private void drawSizeMatchSnapshot() {
        mTransaction.setBuffer(mSurfaceControl, mSnapshot.getHardwareBuffer())
                .setColorSpace(mSurfaceControl, mSnapshot.getColorSpace())
                .apply();
    }

    private void drawSizeMismatchSnapshot() {
        final HardwareBuffer buffer = mSnapshot.getHardwareBuffer();
        final SurfaceSession session = new SurfaceSession();

        // We consider nearly matched dimensions as there can be rounding errors and the user won't
        // notice very minute differences from scaling one dimension more than the other
        final boolean aspectRatioMismatch = Math.abs(
                ((float) buffer.getWidth() / buffer.getHeight())
                - ((float) mFrame.width() / mFrame.height())) > 0.01f;

        // Keep a reference to it such that it doesn't get destroyed when finalized.
        SurfaceControl childSurfaceControl = new SurfaceControl.Builder(session)
                .setName(mTitle + " - task-snapshot-surface")
                .setBLASTLayer()
                .setFormat(buffer.getFormat())
                .setParent(mSurfaceControl)
                .setCallsite("TaskSnapshotWindow.drawSizeMismatchSnapshot")
                .build();

        final Rect frame;
        // We can just show the surface here as it will still be hidden as the parent is
        // still hidden.
        mTransaction.show(childSurfaceControl);
        if (aspectRatioMismatch) {
            // Clip off ugly navigation bar.
            final Rect crop = calculateSnapshotCrop();
            frame = calculateSnapshotFrame(crop);
            mTransaction.setWindowCrop(childSurfaceControl, crop);
            mTransaction.setPosition(childSurfaceControl, frame.left, frame.top);
            mTmpSnapshotSize.set(crop);
            mTmpDstFrame.set(frame);
        } else {
            frame = null;
            mTmpSnapshotSize.set(0, 0, buffer.getWidth(), buffer.getHeight());
            mTmpDstFrame.set(mFrame);
            mTmpDstFrame.offsetTo(0, 0);
        }

        // Scale the mismatch dimensions to fill the task bounds
        mSnapshotMatrix.setRectToRect(mTmpSnapshotSize, mTmpDstFrame, Matrix.ScaleToFit.FILL);
        mTransaction.setMatrix(childSurfaceControl, mSnapshotMatrix, mTmpFloat9);
        mTransaction.setColorSpace(childSurfaceControl, mSnapshot.getColorSpace());
        mTransaction.setBuffer(childSurfaceControl, mSnapshot.getHardwareBuffer());

        if (aspectRatioMismatch) {
            GraphicBuffer background = GraphicBuffer.create(mFrame.width(), mFrame.height(),
                    PixelFormat.RGBA_8888,
                    GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_HW_COMPOSER
                            | GraphicBuffer.USAGE_SW_WRITE_RARELY);
            // TODO: Support this on HardwareBuffer
            final Canvas c = background.lockCanvas();
            drawBackgroundAndBars(c, frame);
            background.unlockCanvasAndPost(c);
            mTransaction.setBuffer(mSurfaceControl,
                    HardwareBuffer.createFromGraphicBuffer(background));
        }
        mTransaction.apply();
        childSurfaceControl.release();
    }

    /**
     * Calculates the snapshot crop in snapshot coordinate space.
     *
     * @return crop rect in snapshot coordinate space.
     */
    public Rect calculateSnapshotCrop() {
        final Rect rect = new Rect();
        final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
        rect.set(0, 0, snapshot.getWidth(), snapshot.getHeight());
        final Rect insets = mSnapshot.getContentInsets();

        final float scaleX = (float) snapshot.getWidth() / mSnapshot.getTaskSize().x;
        final float scaleY = (float) snapshot.getHeight() / mSnapshot.getTaskSize().y;

        // Let's remove all system decorations except the status bar, but only if the task is at the
        // very top of the screen.
        final boolean isTop = mTaskBounds.top == 0 && mFrame.top == 0;
        rect.inset((int) (insets.left * scaleX),
                isTop ? 0 : (int) (insets.top * scaleY),
                (int) (insets.right * scaleX),
                (int) (insets.bottom * scaleY));
        return rect;
    }

    /**
     * Calculates the snapshot frame in window coordinate space from crop.
     *
     * @param crop rect that is in snapshot coordinate space.
     */
    public Rect calculateSnapshotFrame(Rect crop) {
        final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
        final float scaleX = (float) snapshot.getWidth() / mSnapshot.getTaskSize().x;
        final float scaleY = (float) snapshot.getHeight() / mSnapshot.getTaskSize().y;

        // Rescale the frame from snapshot to window coordinate space
        final Rect frame = new Rect(0, 0,
                (int) (crop.width() / scaleX + 0.5f),
                (int) (crop.height() / scaleY + 0.5f)
        );

        // However, we also need to make space for the navigation bar on the left side.
        frame.offset(mSystemBarInsets.left, 0);
        return frame;
    }

    /**
     * Draw status bar and navigation bar background.
     * @hide
     */
    public void drawBackgroundAndBars(Canvas c, Rect frame) {
        final int statusBarHeight = mSystemBarBackgroundPainter.getStatusBarColorViewHeight();
        final boolean fillHorizontally = c.getWidth() > frame.right;
        final boolean fillVertically = c.getHeight() > frame.bottom;
        if (fillHorizontally) {
            c.drawRect(frame.right, alpha(mStatusBarColor) == 0xFF ? statusBarHeight : 0,
                    c.getWidth(), fillVertically
                            ? frame.bottom
                            : c.getHeight(),
                    mBackgroundPaint);
        }
        if (fillVertically) {
            c.drawRect(0, frame.bottom, c.getWidth(), c.getHeight(), mBackgroundPaint);
        }
        mSystemBarBackgroundPainter.drawDecors(c, frame);
    }

    /**
     * Clear window from drawer, must be post on main executor.
     */
    private void clearWindowSynced() {
        mSplashScreenExecutor.executeDelayed(mClearWindowHandler, 0);
    }

    private void reportDrawn() {
        try {
            mSession.finishDrawing(mWindow, null /* postDrawTransaction */, Integer.MAX_VALUE);
        } catch (RemoteException e) {
            clearWindowSynced();
        }
    }

    static class Window extends BaseIWindow {
        private WeakReference<TaskSnapshotWindow> mOuter;

        public void setOuter(TaskSnapshotWindow outer) {
            mOuter = new WeakReference<>(outer);
        }

        @BinderThread
        @Override
        public void resized(ClientWindowFrames frames, boolean reportDraw,
                MergedConfiguration mergedConfiguration, InsetsState insetsState,
                boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId, int seqId,
                int resizeMode) {
            final TaskSnapshotWindow snapshot = mOuter.get();
            if (snapshot == null) {
                return;
            }
            snapshot.mSplashScreenExecutor.execute(() -> {
                if (mergedConfiguration != null
                        && snapshot.mOrientationOnCreation
                        != mergedConfiguration.getMergedConfiguration().orientation) {
                    // The orientation of the screen is changing. We better remove the snapshot
                    // ASAP as we are going to wait on the new window in any case to unfreeze
                    // the screen, and the starting window is not needed anymore.
                    snapshot.clearWindowSynced();
                } else if (reportDraw) {
                    if (snapshot.mHasDrawn) {
                        snapshot.reportDrawn();
                    }
                }
            });
        }
    }

    /**
     * Helper class to draw the background of the system bars in regions the task snapshot isn't
     * filling the window.
     */
    static class SystemBarBackgroundPainter {
        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();
        private final int mStatusBarColor;
        private final int mNavigationBarColor;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final float mScale;
        private final InsetsState mInsetsState;
        private final Rect mSystemBarInsets = new Rect();

        SystemBarBackgroundPainter(int windowFlags, int windowPrivateFlags, int appearance,
                TaskDescription taskDescription, float scale, InsetsState insetsState) {
            mWindowFlags = windowFlags;
            mWindowPrivateFlags = windowPrivateFlags;
            mScale = scale;
            final Context context = ActivityThread.currentActivityThread().getSystemUiContext();
            final int semiTransparent = context.getColor(
                    R.color.system_bar_background_semi_transparent);
            mStatusBarColor = DecorView.calculateBarColor(windowFlags, FLAG_TRANSLUCENT_STATUS,
                    semiTransparent, taskDescription.getStatusBarColor(), appearance,
                    APPEARANCE_LIGHT_STATUS_BARS,
                    taskDescription.getEnsureStatusBarContrastWhenTransparent());
            mNavigationBarColor = DecorView.calculateBarColor(windowFlags,
                    FLAG_TRANSLUCENT_NAVIGATION, semiTransparent,
                    taskDescription.getNavigationBarColor(), appearance,
                    APPEARANCE_LIGHT_NAVIGATION_BARS,
                    taskDescription.getEnsureNavigationBarContrastWhenTransparent()
                            && context.getResources().getBoolean(R.bool.config_navBarNeedsScrim));
            mStatusBarPaint.setColor(mStatusBarColor);
            mNavigationBarPaint.setColor(mNavigationBarColor);
            mInsetsState = insetsState;
        }

        void setInsets(Rect systemBarInsets) {
            mSystemBarInsets.set(systemBarInsets);
        }

        int getStatusBarColorViewHeight() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            if (STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mInsetsState, mStatusBarColor, mWindowFlags, forceBarBackground)) {
                return (int) (mSystemBarInsets.top * mScale);
            } else {
                return 0;
            }
        }

        private boolean isNavigationBarColorViewVisible() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            return NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mInsetsState, mNavigationBarColor, mWindowFlags, forceBarBackground);
        }

        void drawDecors(Canvas c, @Nullable Rect alreadyDrawnFrame) {
            drawStatusBarBackground(c, alreadyDrawnFrame, getStatusBarColorViewHeight());
            drawNavigationBarBackground(c);
        }

        void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame,
                int statusBarHeight) {
            if (statusBarHeight > 0 && Color.alpha(mStatusBarColor) != 0
                    && (alreadyDrawnFrame == null || c.getWidth() > alreadyDrawnFrame.right)) {
                final int rightInset = (int) (mSystemBarInsets.right * mScale);
                final int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                c.drawRect(left, 0, c.getWidth() - rightInset, statusBarHeight, mStatusBarPaint);
            }
        }

        @VisibleForTesting
        void drawNavigationBarBackground(Canvas c) {
            final Rect navigationBarRect = new Rect();
            getNavigationBarRect(c.getWidth(), c.getHeight(), mSystemBarInsets, navigationBarRect,
                    mScale);
            final boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(mNavigationBarColor) != 0 && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, mNavigationBarPaint);
            }
        }
    }
}
