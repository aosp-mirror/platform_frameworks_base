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

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.TaskSnapshot;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.os.Environment;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.WindowManager.LayoutParams;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;
import com.android.server.wm.TaskSnapshotSurface.SystemBarBackgroundPainter;
import com.android.server.wm.utils.InsetUtils;

import com.google.android.collect.Sets;

import java.io.PrintWriter;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) of the corresponding task and
 * put it into our cache. Internally we use gralloc buffers to be able to draw them wherever we
 * like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of a task, and draw
 * them in their own process.
 * <p>
 * When we task becomes visible again, we show a starting window with the snapshot as the content to
 * make app transitions more responsive.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class TaskSnapshotController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskSnapshotController" : TAG_WM;

    /**
     * Return value for {@link #getSnapshotMode}: We are allowed to take a real screenshot to be
     * used as the snapshot.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_REAL = 0;

    /**
     * Return value for {@link #getSnapshotMode}: We are not allowed to take a real screenshot but
     * we should try to use the app theme to create a dummy representation of the app.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_APP_THEME = 1;

    /**
     * Return value for {@link #getSnapshotMode}: We aren't allowed to take any snapshot.
     */
    @VisibleForTesting
    static final int SNAPSHOT_MODE_NONE = 2;

    private final WindowManagerService mService;

    private final TaskSnapshotCache mCache;
    private final TaskSnapshotPersister mPersister;
    private final TaskSnapshotLoader mLoader;
    private final ArraySet<Task> mSkipClosingAppSnapshotTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();
    private final float mHighResTaskSnapshotScale;

    private final Rect mTmpRect = new Rect();

    /**
     * Flag indicating whether we are running on an Android TV device.
     */
    private final boolean mIsRunningOnTv;

    /**
     * Flag indicating whether we are running on an IoT device.
     */
    private final boolean mIsRunningOnIoT;

    /**
     * Flag indicating whether we are running on an Android Wear device.
     */
    private final boolean mIsRunningOnWear;

    TaskSnapshotController(WindowManagerService service) {
        mService = service;
        mPersister = new TaskSnapshotPersister(mService, Environment::getDataSystemCeDirectory);
        mLoader = new TaskSnapshotLoader(mPersister);
        mCache = new TaskSnapshotCache(mService, mLoader);
        mIsRunningOnTv = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        mIsRunningOnIoT = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_EMBEDDED);
        mIsRunningOnWear = mService.mContext.getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_WATCH);
        mHighResTaskSnapshotScale = mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
    }

    void systemReady() {
        mPersister.start();
    }

    void onTransitionStarting(DisplayContent displayContent) {
        handleClosingApps(displayContent.mClosingApps);
    }

    /**
     * Called when the visibility of an app changes outside of the regular app transition flow.
     */
    void notifyAppVisibilityChanged(ActivityRecord appWindowToken, boolean visible) {
        if (!visible) {
            handleClosingApps(Sets.newArraySet(appWindowToken));
        }
    }

    private void handleClosingApps(ArraySet<ActivityRecord> closingApps) {
        if (shouldDisableSnapshots()) {
            return;
        }

        // We need to take a snapshot of the task if and only if all activities of the task are
        // either closing or hidden.
        getClosingTasks(closingApps, mTmpTasks);
        snapshotTasks(mTmpTasks);
        mSkipClosingAppSnapshotTasks.clear();
    }

    /**
     * Adds the given {@param tasks} to the list of tasks which should not have their snapshots
     * taken upon the next processing of the set of closing apps. The caller is responsible for
     * calling {@link #snapshotTasks} to ensure that the task has an up-to-date snapshot.
     */
    @VisibleForTesting
    void addSkipClosingAppSnapshotTasks(ArraySet<Task> tasks) {
        mSkipClosingAppSnapshotTasks.addAll(tasks);
    }

    void snapshotTasks(ArraySet<Task> tasks) {
        snapshotTasks(tasks, false /* allowSnapshotHome */);
    }

    private void snapshotTasks(ArraySet<Task> tasks, boolean allowSnapshotHome) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task task = tasks.valueAt(i);
            final TaskSnapshot snapshot;
            final boolean snapshotHome = allowSnapshotHome && task.isActivityTypeHome();
            if (snapshotHome) {
                snapshot = snapshotTask(task);
            } else {
                switch (getSnapshotMode(task)) {
                    case SNAPSHOT_MODE_NONE:
                        continue;
                    case SNAPSHOT_MODE_APP_THEME:
                        snapshot = drawAppThemeSnapshot(task);
                        break;
                    case SNAPSHOT_MODE_REAL:
                        snapshot = snapshotTask(task);
                        break;
                    default:
                        snapshot = null;
                        break;
                }
            }
            if (snapshot != null) {
                final HardwareBuffer buffer = snapshot.getHardwareBuffer();
                if (buffer.getWidth() == 0 || buffer.getHeight() == 0) {
                    buffer.close();
                    Slog.e(TAG, "Invalid task snapshot dimensions " + buffer.getWidth() + "x"
                            + buffer.getHeight());
                } else {
                    mCache.putSnapshot(task, snapshot);
                    // Don't persist or notify the change for the temporal snapshot.
                    if (!snapshotHome) {
                        mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
                        task.onSnapshotChanged(snapshot);
                    }
                }
            }
        }
    }

    /**
     * Retrieves a snapshot. If {@param restoreFromDisk} equals {@code true}, DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean isLowResolution) {
        return mCache.getSnapshot(taskId, userId, restoreFromDisk, isLowResolution
                && mPersister.enableLowResSnapshots());
    }

    /**
     * @see WindowManagerInternal#clearSnapshotCache
     */
    public void clearSnapshotCache() {
        mCache.clearRunningCache();
    }

    /**
     * Creates a starting surface for {@param token} with {@param snapshot}. DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    StartingSurface createStartingSurface(ActivityRecord activity,
            TaskSnapshot snapshot) {
        return TaskSnapshotSurface.create(mService, activity, snapshot);
    }

    /**
     * Find the window for a given task to take a snapshot. Top child of the task is usually the one
     * we're looking for, but during app transitions, trampoline activities can appear in the
     * children, which should be ignored.
     */
    @Nullable private ActivityRecord findAppTokenForSnapshot(Task task) {
        return task.getActivity((r) -> {
            if (r == null || !r.isSurfaceShowing() || r.findMainWindow() == null) {
                return false;
            }
            return r.forAllWindows(
                    // Ensure at least one window for the top app is visible before attempting to
                    // take a screenshot. Visible here means that the WSA surface is shown and has
                    // an alpha greater than 0.
                    ws -> ws.mWinAnimator != null && ws.mWinAnimator.getShown()
                            && ws.mWinAnimator.mLastAlpha > 0f, true  /* traverseTopToBottom */);

        });
    }

    /**
     * Validates the state of the Task is appropriate to capture a snapshot, collects
     * information from the task and populates the builder.
     *
     * @param task the task to capture
     * @param pixelFormat the desired pixel format, or {@link PixelFormat#UNKNOWN} to
     *                    automatically select
     * @param builder the snapshot builder to populate
     *
     * @return true if the state of the task is ok to proceed
     */
    @VisibleForTesting
    boolean prepareTaskSnapshot(Task task, int pixelFormat, TaskSnapshot.Builder builder) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return false;
        }
        final ActivityRecord activity = findAppTokenForSnapshot(task);
        if (activity == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No visible windows for " + task);
            }
            return false;
        }
        if (activity.hasCommittedReparentToAnimationLeash()) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. App is animating " + activity);
            }
            return false;
        }

        final WindowState mainWindow = activity.findMainWindow();
        if (mainWindow == null) {
            Slog.w(TAG_WM, "Failed to take screenshot. No main window for " + task);
            return false;
        }

        builder.setIsRealSnapshot(true);
        builder.setId(System.currentTimeMillis());
        builder.setContentInsets(getInsets(mainWindow));

        final boolean isWindowTranslucent = mainWindow.getAttrs().format != PixelFormat.OPAQUE;
        final boolean isShowWallpaper = (mainWindow.getAttrs().flags & FLAG_SHOW_WALLPAPER) != 0;

        if (pixelFormat == PixelFormat.UNKNOWN) {
            pixelFormat = mPersister.use16BitFormat() && activity.fillsParent()
                    && !(isWindowTranslucent && isShowWallpaper)
                    ? PixelFormat.RGB_565
                    : PixelFormat.RGBA_8888;
        }

        final boolean isTranslucent = PixelFormat.formatHasAlpha(pixelFormat)
                && (!activity.fillsParent() || isWindowTranslucent);

        builder.setTopActivityComponent(activity.mActivityComponent);
        builder.setPixelFormat(pixelFormat);
        builder.setIsTranslucent(isTranslucent);
        builder.setOrientation(activity.getTask().getConfiguration().orientation);
        builder.setRotation(activity.getTask().getDisplayContent().getRotation());
        builder.setWindowingMode(task.getWindowingMode());
        builder.setSystemUiVisibility(getSystemUiVisibility(task));
        return true;
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            TaskSnapshot.Builder builder) {
        Point taskSize = new Point();
        final SurfaceControl.ScreenshotHardwareBuffer taskSnapshot = createTaskSnapshot(task,
                mHighResTaskSnapshotScale, builder.getPixelFormat(), taskSize);
        builder.setTaskSize(taskSize);
        return taskSnapshot;
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            float scaleFraction) {
        return createTaskSnapshot(task, scaleFraction, PixelFormat.RGBA_8888, null);
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            float scaleFraction, int pixelFormat, Point outTaskSize) {
        if (task.getSurfaceControl() == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No surface control for " + task);
            }
            return null;
        }
        task.getBounds(mTmpRect);
        mTmpRect.offsetTo(0, 0);

        SurfaceControl[] excludeLayers;
        final WindowState imeWindow = task.getDisplayContent().mInputMethodWindow;
        if (imeWindow != null) {
            excludeLayers = new SurfaceControl[1];
            excludeLayers[0] = imeWindow.getSurfaceControl();
        } else {
            excludeLayers = new SurfaceControl[0];
        }
        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                SurfaceControl.captureLayersExcluding(
                        task.getSurfaceControl(), mTmpRect, scaleFraction,
                        pixelFormat, excludeLayers);
        if (outTaskSize != null) {
            outTaskSize.x = mTmpRect.width();
            outTaskSize.y = mTmpRect.height();
        }
        final HardwareBuffer buffer = screenshotBuffer == null ? null
                : screenshotBuffer.getHardwareBuffer();
        if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
            return null;
        }
        return screenshotBuffer;
    }

    @Nullable
    TaskSnapshot snapshotTask(Task task) {
        return snapshotTask(task, PixelFormat.UNKNOWN);
    }

    @Nullable
    TaskSnapshot snapshotTask(Task task, int pixelFormat) {
        TaskSnapshot.Builder builder = new TaskSnapshot.Builder();

        if (!prepareTaskSnapshot(task, pixelFormat, builder)) {
            // Failed some pre-req. Has been logged.
            return null;
        }

        final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                createTaskSnapshot(task, builder);

        if (screenshotBuffer == null) {
            // Failed to acquire image. Has been logged.
            return null;
        }
        builder.setSnapshot(screenshotBuffer.getHardwareBuffer());
        builder.setColorSpace(screenshotBuffer.getColorSpace());
        return builder.build();
    }

    private boolean shouldDisableSnapshots() {
        return mIsRunningOnWear || mIsRunningOnTv || mIsRunningOnIoT;
    }

    private Rect getInsets(WindowState state) {
        // XXX(b/72757033): These are insets relative to the window frame, but we're really
        // interested in the insets relative to the task bounds.
        final Rect insets = minRect(state.getContentInsets(), state.getStableInsets());
        InsetUtils.addInsets(insets, state.mActivityRecord.getLetterboxInsets());
        return insets;
    }

    private Rect minRect(Rect rect1, Rect rect2) {
        return new Rect(Math.min(rect1.left, rect2.left),
                Math.min(rect1.top, rect2.top),
                Math.min(rect1.right, rect2.right),
                Math.min(rect1.bottom, rect2.bottom));
    }

    /**
     * Retrieves all closing tasks based on the list of closing apps during an app transition.
     */
    @VisibleForTesting
    void getClosingTasks(ArraySet<ActivityRecord> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = closingApps.valueAt(i);
            final Task task = activity.getTask();

            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (task != null && !task.isVisible() && !mSkipClosingAppSnapshotTasks.contains(task)) {
                outClosingTasks.add(task);
            }
        }
    }

    @VisibleForTesting
    int getSnapshotMode(Task task) {
        final ActivityRecord topChild = task.getTopMostActivity();
        if (!task.isActivityTypeStandardOrUndefined() && !task.isActivityTypeAssistant()) {
            return SNAPSHOT_MODE_NONE;
        } else if (topChild != null && topChild.shouldUseAppThemeSnapshot()) {
            return SNAPSHOT_MODE_APP_THEME;
        } else {
            return SNAPSHOT_MODE_REAL;
        }
    }

    /**
     * If we are not allowed to take a real screenshot, this attempts to represent the app as best
     * as possible by using the theme's window background.
     */
    private TaskSnapshot drawAppThemeSnapshot(Task task) {
        final ActivityRecord topChild = task.getTopMostActivity();
        if (topChild == null) {
            return null;
        }
        final WindowState mainWindow = topChild.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        final int color = ColorUtils.setAlphaComponent(
                task.getTaskDescription().getBackgroundColor(), 255);
        final LayoutParams attrs = mainWindow.getAttrs();
        final SystemBarBackgroundPainter decorPainter = new SystemBarBackgroundPainter(attrs.flags,
                attrs.privateFlags, attrs.systemUiVisibility, task.getTaskDescription(),
                mHighResTaskSnapshotScale, mainWindow.getRequestedInsetsState());
        final int taskWidth = task.getBounds().width();
        final int taskHeight = task.getBounds().height();
        final int width = (int) (taskWidth * mHighResTaskSnapshotScale);
        final int height = (int) (taskHeight * mHighResTaskSnapshotScale);

        final RenderNode node = RenderNode.create("TaskSnapshotController", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        final RecordingCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(mainWindow.getContentInsets(), mainWindow.getStableInsets());
        decorPainter.drawDecors(c, null /* statusBarExcludeFrame */);
        node.end(c);
        final Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }

        // Note, the app theme snapshot is never translucent because we enforce a non-translucent
        // color above
        return new TaskSnapshot(
                System.currentTimeMillis() /* id */,
                topChild.mActivityComponent, hwBitmap.getHardwareBuffer(),
                hwBitmap.getColorSpace(), mainWindow.getConfiguration().orientation,
                mainWindow.getWindowConfiguration().getRotation(), new Point(taskWidth, taskHeight),
                getInsets(mainWindow), false /* isLowResolution */,
                false /* isRealSnapshot */, task.getWindowingMode(),
                getSystemUiVisibility(task), false);
    }

    /**
     * Called when an {@link ActivityRecord} has been removed.
     */
    void onAppRemoved(ActivityRecord activity) {
        mCache.onAppRemoved(activity);
    }

    /**
     * Called when the process of an {@link ActivityRecord} has died.
     */
    void onAppDied(ActivityRecord activity) {
        mCache.onAppDied(activity);
    }

    void notifyTaskRemovedFromRecents(int taskId, int userId) {
        mCache.onTaskRemoved(taskId);
        mPersister.onTaskRemovedFromRecents(taskId, userId);
    }

    void removeSnapshotCache(int taskId) {
        mCache.removeRunningEntry(taskId);
    }

    /**
     * See {@link TaskSnapshotPersister#removeObsoleteFiles}
     */
    void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        mPersister.removeObsoleteFiles(persistentTaskIds, runningUserIds);
    }

    /**
     * Temporarily pauses/unpauses persisting of task snapshots.
     *
     * @param paused Whether task snapshot persisting should be paused.
     */
    void setPersisterPaused(boolean paused) {
        mPersister.setPaused(paused);
    }

    /**
     * Called when screen is being turned off.
     */
    void screenTurningOff(ScreenOffListener listener) {
        if (shouldDisableSnapshots()) {
            listener.onScreenOff();
            return;
        }

        // We can't take a snapshot when screen is off, so take a snapshot now!
        mHandler.post(() -> {
            try {
                synchronized (mService.mGlobalLock) {
                    mTmpTasks.clear();
                    mService.mRoot.forAllTasks(task -> {
                        if (task.isVisible()) {
                            mTmpTasks.add(task);
                        }
                    });
                    // Allow taking snapshot of home when turning screen off to reduce the delay of
                    // waking from secure lock to home.
                    final boolean allowSnapshotHome =
                            mService.mPolicy.isKeyguardSecure(mService.mCurrentUserId);
                    snapshotTasks(mTmpTasks, allowSnapshotHome);
                }
            } finally {
                listener.onScreenOff();
            }
        });
    }

    /**
     * @return The SystemUI visibility flags for the top fullscreen opaque window in the given
     *         {@param task}.
     */
    private int getSystemUiVisibility(Task task) {
        final ActivityRecord topFullscreenActivity = task.getTopFullscreenActivity();
        final WindowState topFullscreenOpaqueWindow = topFullscreenActivity != null
                ? topFullscreenActivity.getTopFullscreenOpaqueWindow()
                : null;
        if (topFullscreenOpaqueWindow != null) {
            return topFullscreenOpaqueWindow.getSystemUiVisibility();
        }
        return 0;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mHighResTaskSnapshotScale=" + mHighResTaskSnapshotScale);
        mCache.dump(pw, prefix);
    }
}
