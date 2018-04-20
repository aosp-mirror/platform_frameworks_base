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

import static com.android.server.wm.TaskSnapshotPersister.DISABLE_FULL_SIZED_BITMAPS;
import static com.android.server.wm.TaskSnapshotPersister.REDUCED_SCALE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.TaskSnapshot;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import android.view.DisplayListCanvas;
import android.view.RenderNode;
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
    private final TaskSnapshotPersister mPersister = new TaskSnapshotPersister(
            Environment::getDataSystemCeDirectory);
    private final TaskSnapshotLoader mLoader = new TaskSnapshotLoader(mPersister);
    private final ArraySet<Task> mSkipClosingAppSnapshotTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();

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
        mCache = new TaskSnapshotCache(mService, mLoader);
        mIsRunningOnTv = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        mIsRunningOnIoT = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_EMBEDDED);
        mIsRunningOnWear = mService.mContext.getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_WATCH);
    }

    void systemReady() {
        mPersister.start();
    }

    void onTransitionStarting() {
        handleClosingApps(mService.mClosingApps);
    }

    /**
     * Called when the visibility of an app changes outside of the regular app transition flow.
     */
    void notifyAppVisibilityChanged(AppWindowToken appWindowToken, boolean visible) {
        if (!visible) {
            handleClosingApps(Sets.newArraySet(appWindowToken));
        }
    }

    private void handleClosingApps(ArraySet<AppWindowToken> closingApps) {
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
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task task = tasks.valueAt(i);
            final int mode = getSnapshotMode(task);
            final TaskSnapshot snapshot;
            switch (mode) {
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
            if (snapshot != null) {
                final GraphicBuffer buffer = snapshot.getSnapshot();
                if (buffer.getWidth() == 0 || buffer.getHeight() == 0) {
                    buffer.destroy();
                    Slog.e(TAG, "Invalid task snapshot dimensions " + buffer.getWidth() + "x"
                            + buffer.getHeight());
                } else {
                    mCache.putSnapshot(task, snapshot);
                    mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
                    if (task.getController() != null) {
                        task.getController().reportSnapshotChanged(snapshot);
                    }
                }
            }
        }
    }

    /**
     * Retrieves a snapshot. If {@param restoreFromDisk} equals {@code true}, DO HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean reducedResolution) {
        return mCache.getSnapshot(taskId, userId, restoreFromDisk, reducedResolution
                || DISABLE_FULL_SIZED_BITMAPS);
    }

    /**
     * Creates a starting surface for {@param token} with {@param snapshot}. DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    StartingSurface createStartingSurface(AppWindowToken token,
            TaskSnapshot snapshot) {
        return TaskSnapshotSurface.create(mService, token, snapshot);
    }

    private TaskSnapshot snapshotTask(Task task) {
        final AppWindowToken top = task.getTopChild();
        if (top == null) {
            return null;
        }
        final WindowState mainWindow = top.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        if (task.getSurfaceControl() == null) {
            return null;
        }

        if (top.hasCommittedReparentToAnimationLeash()) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. App is animating " + top);
            }
            return null;
        }

        final boolean hasVisibleChild = top.forAllWindows(
                // Ensure at least one window for the top app is visible before attempting to take
                // a screenshot. Visible here means that the WSA surface is shown and has an alpha
                // greater than 0.
                ws -> (ws.mAppToken == null || ws.mAppToken.isSurfaceShowing())
                        && ws.mWinAnimator != null && ws.mWinAnimator.getShown()
                        && ws.mWinAnimator.mLastAlpha > 0f, true);

        if (!hasVisibleChild) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No visible windows for " + task);
            }
            return null;
        }

        final boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
        final float scaleFraction = isLowRamDevice ? REDUCED_SCALE : 1f;
        task.getBounds(mTmpRect);
        mTmpRect.offsetTo(0, 0);

        final GraphicBuffer buffer = SurfaceControl.captureLayers(
                task.getSurfaceControl().getHandle(), mTmpRect, scaleFraction);

        if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot for " + task);
            }
            return null;
        }
        return new TaskSnapshot(buffer, top.getConfiguration().orientation,
                getInsets(mainWindow), isLowRamDevice /* reduced */, scaleFraction /* scale */,
                true /* isRealSnapshot */, task.getWindowingMode());
    }

    private boolean shouldDisableSnapshots() {
        return mIsRunningOnWear || mIsRunningOnTv || mIsRunningOnIoT;
    }

    private Rect getInsets(WindowState state) {
        // XXX(b/72757033): These are insets relative to the window frame, but we're really
        // interested in the insets relative to the task bounds.
        final Rect insets = minRect(state.mContentInsets, state.mStableInsets);
        InsetUtils.addInsets(insets, state.mAppToken.getLetterboxInsets());
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
    void getClosingTasks(ArraySet<AppWindowToken> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final AppWindowToken atoken = closingApps.valueAt(i);
            final Task task = atoken.getTask();

            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (task != null && !task.isVisible() && !mSkipClosingAppSnapshotTasks.contains(task)) {
                outClosingTasks.add(task);
            }
        }
    }

    @VisibleForTesting
    int getSnapshotMode(Task task) {
        final AppWindowToken topChild = task.getTopChild();
        if (!task.isActivityTypeStandardOrUndefined()) {
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
        final AppWindowToken topChild = task.getTopChild();
        if (topChild == null) {
            return null;
        }
        final WindowState mainWindow = topChild.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        final int color = ColorUtils.setAlphaComponent(
                task.getTaskDescription().getBackgroundColor(), 255);
        final int statusBarColor = task.getTaskDescription().getStatusBarColor();
        final int navigationBarColor = task.getTaskDescription().getNavigationBarColor();
        final LayoutParams attrs = mainWindow.getAttrs();
        final SystemBarBackgroundPainter decorPainter = new SystemBarBackgroundPainter(attrs.flags,
                attrs.privateFlags, attrs.systemUiVisibility, statusBarColor, navigationBarColor);
        final int width = mainWindow.getFrameLw().width();
        final int height = mainWindow.getFrameLw().height();

        final RenderNode node = RenderNode.create("TaskSnapshotController", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        final DisplayListCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(mainWindow.mContentInsets, mainWindow.mStableInsets);
        decorPainter.drawDecors(c, null /* statusBarExcludeFrame */);
        node.end(c);
        final Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }
        return new TaskSnapshot(hwBitmap.createGraphicBufferHandle(),
                topChild.getConfiguration().orientation, mainWindow.mStableInsets,
                ActivityManager.isLowRamDeviceStatic() /* reduced */, 1.0f /* scale */,
                false /* isRealSnapshot */, task.getWindowingMode());
    }

    /**
     * Called when an {@link AppWindowToken} has been removed.
     */
    void onAppRemoved(AppWindowToken wtoken) {
        mCache.onAppRemoved(wtoken);
    }

    /**
     * Called when the process of an {@link AppWindowToken} has died.
     */
    void onAppDied(AppWindowToken wtoken) {
        mCache.onAppDied(wtoken);
    }

    void notifyTaskRemovedFromRecents(int taskId, int userId) {
        mCache.onTaskRemoved(taskId);
        mPersister.onTaskRemovedFromRecents(taskId, userId);
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
                synchronized (mService.mWindowMap) {
                    mTmpTasks.clear();
                    mService.mRoot.forAllTasks(task -> {
                        if (task.isVisible()) {
                            mTmpTasks.add(task);
                        }
                    });
                    snapshotTasks(mTmpTasks);
                }
            } finally {
                listener.onScreenOff();
            }
        });
    }

    void dump(PrintWriter pw, String prefix) {
        mCache.dump(pw, prefix);
    }
}
