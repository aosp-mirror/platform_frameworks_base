/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.graphics.Color.WHITE;
import static android.graphics.Color.alpha;
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
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static com.android.internal.policy.DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.getColorViewLeftInset;
import static com.android.internal.policy.DecorView.getColorViewTopInset;
import static com.android.internal.policy.DecorView.getNavigationBarRect;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.DisplayCutout;
import android.view.IWindowSession;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;
import com.android.internal.view.BaseIWindow;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * This class represents a starting window that shows a snapshot.
 * <p>
 * DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING METHODS OF THIS CLASS!
 */
class TaskSnapshotSurface implements StartingSurface {

    private static final long SIZE_MISMATCH_MINIMUM_TIME_MS = 450;

    /**
     * When creating the starting window, we use the exact same layout flags such that we end up
     * with a window with the exact same dimensions etc. However, these flags are not used in layout
     * and might cause other side effects so we exclude them.
     */
    private static final int FLAG_INHERIT_EXCLUDES = FLAG_NOT_FOCUSABLE
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

    private static final int PRIVATE_FLAG_INHERITS = PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "SnapshotStartingWindow" : TAG_WM;
    private static final int MSG_REPORT_DRAW = 0;
    private static final String TITLE_FORMAT = "SnapshotStartingWindow for taskId=%s";
    private final Window mWindow;
    private final Surface mSurface;
    private SurfaceControl mChildSurfaceControl;
    private final IWindowSession mSession;
    private final WindowManagerService mService;
    private final Rect mTaskBounds;
    private final Rect mStableInsets = new Rect();
    private final Rect mContentInsets = new Rect();
    private final Rect mFrame = new Rect();
    private TaskSnapshot mSnapshot;
    private final CharSequence mTitle;
    private boolean mHasDrawn;
    private long mShownTime;
    private final Handler mHandler;
    private boolean mSizeMismatch;
    private final Paint mBackgroundPaint = new Paint();
    private final int mStatusBarColor;
    @VisibleForTesting final SystemBarBackgroundPainter mSystemBarBackgroundPainter;
    private final int mOrientationOnCreation;

    static TaskSnapshotSurface create(WindowManagerService service, AppWindowToken token,
            TaskSnapshot snapshot) {

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        final Window window = new Window();
        final IWindowSession session = WindowManagerGlobal.getWindowSession();
        window.setSession(session);
        final Surface surface = new Surface();
        final Rect tmpRect = new Rect();
        final DisplayCutout.ParcelableWrapper tmpCutout = new DisplayCutout.ParcelableWrapper();
        final Rect tmpFrame = new Rect();
        final Rect taskBounds;
        final Rect tmpContentInsets = new Rect();
        final Rect tmpStableInsets = new Rect();
        final MergedConfiguration tmpMergedConfiguration = new MergedConfiguration();
        int backgroundColor = WHITE;
        int statusBarColor = 0;
        int navigationBarColor = 0;
        final int sysUiVis;
        final int windowFlags;
        final int windowPrivateFlags;
        final int currentOrientation;
        synchronized (service.mWindowMap) {
            final WindowState mainWindow = token.findMainWindow();
            final Task task = token.getTask();
            if (task == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find task for token="
                        + token);
                return null;
            }
            final AppWindowToken topFullscreenToken = token.getTask().getTopFullscreenAppToken();
            if (topFullscreenToken == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find top fullscreen for task="
                        + task);
                return null;
            }
            final WindowState topFullscreenWindow = topFullscreenToken.getTopFullscreenWindow();
            if (mainWindow == null || topFullscreenWindow == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find main window for token="
                        + token);
                return null;
            }
            sysUiVis = topFullscreenWindow.getSystemUiVisibility();
            windowFlags = topFullscreenWindow.getAttrs().flags;
            windowPrivateFlags = topFullscreenWindow.getAttrs().privateFlags;

            layoutParams.packageName = mainWindow.getAttrs().packageName;
            layoutParams.windowAnimations = mainWindow.getAttrs().windowAnimations;
            layoutParams.dimAmount = mainWindow.getAttrs().dimAmount;
            layoutParams.type = TYPE_APPLICATION_STARTING;
            layoutParams.format = snapshot.getSnapshot().getFormat();
            layoutParams.flags = (windowFlags & ~FLAG_INHERIT_EXCLUDES)
                    | FLAG_NOT_FOCUSABLE
                    | FLAG_NOT_TOUCHABLE;
            layoutParams.privateFlags = windowPrivateFlags & PRIVATE_FLAG_INHERITS;
            layoutParams.token = token.token;
            layoutParams.width = LayoutParams.MATCH_PARENT;
            layoutParams.height = LayoutParams.MATCH_PARENT;
            layoutParams.systemUiVisibility = sysUiVis;
            layoutParams.setTitle(String.format(TITLE_FORMAT, task.mTaskId));

            final TaskDescription taskDescription = task.getTaskDescription();
            if (taskDescription != null) {
                backgroundColor = taskDescription.getBackgroundColor();
                statusBarColor = taskDescription.getStatusBarColor();
                navigationBarColor = taskDescription.getNavigationBarColor();
            }
            taskBounds = new Rect();
            task.getBounds(taskBounds);
            currentOrientation = topFullscreenWindow.getConfiguration().orientation;
        }
        try {
            final int res = session.addToDisplay(window, window.mSeq, layoutParams,
                    View.GONE, token.getDisplayContent().getDisplayId(), tmpFrame, tmpRect, tmpRect,
                    tmpRect, tmpCutout, null);
            if (res < 0) {
                Slog.w(TAG, "Failed to add snapshot starting window res=" + res);
                return null;
            }
        } catch (RemoteException e) {
            // Local call.
        }
        final TaskSnapshotSurface snapshotSurface = new TaskSnapshotSurface(service, window,
                surface, snapshot, layoutParams.getTitle(), backgroundColor, statusBarColor,
                navigationBarColor, sysUiVis, windowFlags, windowPrivateFlags, taskBounds,
                currentOrientation);
        window.setOuter(snapshotSurface);
        try {
            session.relayout(window, window.mSeq, layoutParams, -1, -1, View.VISIBLE, 0, -1,
                    tmpFrame, tmpRect, tmpContentInsets, tmpRect, tmpStableInsets, tmpRect, tmpRect,
                    tmpCutout, tmpMergedConfiguration, surface);
        } catch (RemoteException e) {
            // Local call.
        }
        snapshotSurface.setFrames(tmpFrame, tmpContentInsets, tmpStableInsets);
        snapshotSurface.drawSnapshot();
        return snapshotSurface;
    }

    @VisibleForTesting
    TaskSnapshotSurface(WindowManagerService service, Window window, Surface surface,
            TaskSnapshot snapshot, CharSequence title, int backgroundColor, int statusBarColor,
            int navigationBarColor, int sysUiVis, int windowFlags, int windowPrivateFlags,
            Rect taskBounds, int currentOrientation) {
        mService = service;
        mHandler = new Handler(mService.mH.getLooper());
        mSession = WindowManagerGlobal.getWindowSession();
        mWindow = window;
        mSurface = surface;
        mSnapshot = snapshot;
        mTitle = title;
        mBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : WHITE);
        mTaskBounds = taskBounds;
        mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags,
                windowPrivateFlags, sysUiVis, statusBarColor, navigationBarColor);
        mStatusBarColor = statusBarColor;
        mOrientationOnCreation = currentOrientation;
    }

    @Override
    public void remove() {
        synchronized (mService.mWindowMap) {
            final long now = SystemClock.uptimeMillis();
            if (mSizeMismatch && now - mShownTime < SIZE_MISMATCH_MINIMUM_TIME_MS) {
                mHandler.postAtTime(this::remove, mShownTime + SIZE_MISMATCH_MINIMUM_TIME_MS);
                if (DEBUG_STARTING_WINDOW) {
                    Slog.v(TAG, "Defer removing snapshot surface in "  + (now - mShownTime) + "ms");
                }
                return;
            }
        }
        try {
            if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Removing snapshot surface");
            mSession.remove(mWindow);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    @VisibleForTesting
    void setFrames(Rect frame, Rect contentInsets, Rect stableInsets) {
        mFrame.set(frame);
        mContentInsets.set(contentInsets);
        mStableInsets.set(stableInsets);
        mSizeMismatch = (mFrame.width() != mSnapshot.getSnapshot().getWidth()
                || mFrame.height() != mSnapshot.getSnapshot().getHeight());
        mSystemBarBackgroundPainter.setInsets(contentInsets, stableInsets);
    }

    private void drawSnapshot() {
        final GraphicBuffer buffer = mSnapshot.getSnapshot();
        if (DEBUG_STARTING_WINDOW) Slog.v(TAG, "Drawing snapshot surface sizeMismatch="
                + mSizeMismatch);
        if (mSizeMismatch) {
            // The dimensions of the buffer and the window don't match, so attaching the buffer
            // will fail. Better create a child window with the exact dimensions and fill the parent
            // window with the background color!
            drawSizeMismatchSnapshot(buffer);
        } else {
            drawSizeMatchSnapshot(buffer);
        }
        synchronized (mService.mWindowMap) {
            mShownTime = SystemClock.uptimeMillis();
            mHasDrawn = true;
        }
        reportDrawn();

        // In case window manager leaks us, make sure we don't retain the snapshot.
        mSnapshot = null;
    }

    private void drawSizeMatchSnapshot(GraphicBuffer buffer) {
        mSurface.attachAndQueueBuffer(buffer);
        mSurface.release();
    }

    private void drawSizeMismatchSnapshot(GraphicBuffer buffer) {
        final SurfaceSession session = new SurfaceSession(mSurface);

        // Keep a reference to it such that it doesn't get destroyed when finalized.
        mChildSurfaceControl = new SurfaceControl.Builder(session)
                .setName(mTitle + " - task-snapshot-surface")
                .setSize(buffer.getWidth(), buffer.getHeight())
                .setFormat(buffer.getFormat())
                .build();
        Surface surface = new Surface();
        surface.copyFrom(mChildSurfaceControl);

        // Clip off ugly navigation bar.
        final Rect crop = calculateSnapshotCrop();
        final Rect frame = calculateSnapshotFrame(crop);
        SurfaceControl.openTransaction();
        try {
            // We can just show the surface here as it will still be hidden as the parent is
            // still hidden.
            mChildSurfaceControl.show();
            mChildSurfaceControl.setWindowCrop(crop);
            mChildSurfaceControl.setPosition(frame.left, frame.top);

            // Scale the mismatch dimensions to fill the task bounds
            final float scale = 1 / mSnapshot.getScale();
            mChildSurfaceControl.setMatrix(scale, 0, 0, scale);
        } finally {
            SurfaceControl.closeTransaction();
        }
        surface.attachAndQueueBuffer(buffer);
        surface.release();

        final Canvas c = mSurface.lockCanvas(null);
        drawBackgroundAndBars(c, frame);
        mSurface.unlockCanvasAndPost(c);
        mSurface.release();
    }

    /**
     * Calculates the snapshot crop in snapshot coordinate space.
     *
     * @return crop rect in snapshot coordinate space.
     */
    @VisibleForTesting
    Rect calculateSnapshotCrop() {
        final Rect rect = new Rect();
        rect.set(0, 0, mSnapshot.getSnapshot().getWidth(), mSnapshot.getSnapshot().getHeight());
        final Rect insets = mSnapshot.getContentInsets();

        // Let's remove all system decorations except the status bar, but only if the task is at the
        // very top of the screen.
        final boolean isTop = mTaskBounds.top == 0 && mFrame.top == 0;
        rect.inset((int) (insets.left * mSnapshot.getScale()),
                isTop ? 0 : (int) (insets.top * mSnapshot.getScale()),
                (int) (insets.right * mSnapshot.getScale()),
                (int) (insets.bottom * mSnapshot.getScale()));
        return rect;
    }

    /**
     * Calculates the snapshot frame in window coordinate space from crop.
     *
     * @param crop rect that is in snapshot coordinate space.
     */
    @VisibleForTesting
    Rect calculateSnapshotFrame(Rect crop) {
        final Rect frame = new Rect(crop);
        final float scale = mSnapshot.getScale();

        // Rescale the frame from snapshot to window coordinate space
        frame.scale(1 / scale);

        // By default, offset it to to top/left corner
        frame.offsetTo((int) (-crop.left / scale), (int) (-crop.top / scale));

        // However, we also need to make space for the navigation bar on the left side.
        final int colorViewLeftInset = getColorViewLeftInset(mStableInsets.left,
                mContentInsets.left);
        frame.offset(colorViewLeftInset, 0);
        return frame;
    }

    @VisibleForTesting
    void drawBackgroundAndBars(Canvas c, Rect frame) {
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

    private void reportDrawn() {
        try {
            mSession.finishDrawing(mWindow);
        } catch (RemoteException e) {
            // Local call.
        }
    }

    private static Handler sHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_DRAW:
                    final boolean hasDrawn;
                    final TaskSnapshotSurface surface = (TaskSnapshotSurface) msg.obj;
                    synchronized (surface.mService.mWindowMap) {
                        hasDrawn = surface.mHasDrawn;
                    }
                    if (hasDrawn) {
                        surface.reportDrawn();
                    }
                    break;
            }
        }
    };

    @VisibleForTesting
    static class Window extends BaseIWindow {

        private TaskSnapshotSurface mOuter;

        public void setOuter(TaskSnapshotSurface outer) {
            mOuter = outer;
        }

        @Override
        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets,
                Rect stableInsets, Rect outsets, boolean reportDraw,
                MergedConfiguration mergedConfiguration, Rect backDropFrame, boolean forceLayout,
                boolean alwaysConsumeNavBar, int displayId,
                DisplayCutout.ParcelableWrapper displayCutout) {
            if (mergedConfiguration != null && mOuter != null
                    && mOuter.mOrientationOnCreation
                            != mergedConfiguration.getMergedConfiguration().orientation) {

                // The orientation of the screen is changing. We better remove the snapshot ASAP as
                // we are going to wait on the new window in any case to unfreeze the screen, and
                // the starting window is not needed anymore.
                sHandler.post(mOuter::remove);
            }
            if (reportDraw) {
                sHandler.obtainMessage(MSG_REPORT_DRAW, mOuter).sendToTarget();
            }
        }
    }

    /**
     * Helper class to draw the background of the system bars in regions the task snapshot isn't
     * filling the window.
     */
    static class SystemBarBackgroundPainter {

        private final Rect mContentInsets = new Rect();
        private final Rect mStableInsets = new Rect();
        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();
        private final int mStatusBarColor;
        private final int mNavigationBarColor;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final int mSysUiVis;

        SystemBarBackgroundPainter( int windowFlags, int windowPrivateFlags, int sysUiVis,
                int statusBarColor, int navigationBarColor) {
            mWindowFlags = windowFlags;
            mWindowPrivateFlags = windowPrivateFlags;
            mSysUiVis = sysUiVis;
            final Context context = ActivityThread.currentActivityThread().getSystemUiContext();
            mStatusBarColor = DecorView.calculateStatusBarColor(windowFlags,
                    context.getColor(R.color.system_bar_background_semi_transparent),
                    statusBarColor);
            mNavigationBarColor = navigationBarColor;
            mStatusBarPaint.setColor(mStatusBarColor);
            mNavigationBarPaint.setColor(navigationBarColor);
        }

        void setInsets(Rect contentInsets, Rect stableInsets) {
            mContentInsets.set(contentInsets);
            mStableInsets.set(stableInsets);
        }

        int getStatusBarColorViewHeight() {
            final boolean forceStatusBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND) != 0;
            if (STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mSysUiVis, mStatusBarColor, mWindowFlags, forceStatusBarBackground)) {
                return getColorViewTopInset(mStableInsets.top, mContentInsets.top);
            } else {
                return 0;
            }
        }

        private boolean isNavigationBarColorViewVisible() {
            return NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mSysUiVis, mNavigationBarColor, mWindowFlags, false /* force */);
        }

        void drawDecors(Canvas c, @Nullable Rect alreadyDrawnFrame) {
            drawStatusBarBackground(c, alreadyDrawnFrame, getStatusBarColorViewHeight());
            drawNavigationBarBackground(c);
        }

        @VisibleForTesting
        void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame,
                int statusBarHeight) {
            if (statusBarHeight > 0 && Color.alpha(mStatusBarColor) != 0
                    && (alreadyDrawnFrame == null || c.getWidth() > alreadyDrawnFrame.right)) {
                final int rightInset = DecorView.getColorViewRightInset(mStableInsets.right,
                        mContentInsets.right);
                final int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                c.drawRect(left, 0, c.getWidth() - rightInset, statusBarHeight, mStatusBarPaint);
            }
        }

        @VisibleForTesting
        void drawNavigationBarBackground(Canvas c) {
            final Rect navigationBarRect = new Rect();
            getNavigationBarRect(c.getWidth(), c.getHeight(), mStableInsets, mContentInsets,
                    navigationBarRect);
            final boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(mNavigationBarColor) != 0 && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, mNavigationBarPaint);
            }
        }
    }
}
