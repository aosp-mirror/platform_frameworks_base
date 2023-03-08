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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.window.ScreenCapture;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;

import com.google.android.collect.Sets;

import java.util.Set;

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
class TaskSnapshotController extends AbsAppSnapshotController<Task, TaskSnapshotCache> {
    static final String SNAPSHOTS_DIRNAME = "snapshots";

    private final TaskSnapshotPersister mPersister;
    private final ArraySet<Task> mSkipClosingAppSnapshotTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();

    private final PersistInfoProvider mPersistInfoProvider;

    TaskSnapshotController(WindowManagerService service, SnapshotPersistQueue persistQueue) {
        super(service);
        mPersistInfoProvider = createPersistInfoProvider(service,
                Environment::getDataSystemCeDirectory);
        mPersister = new TaskSnapshotPersister(persistQueue, mPersistInfoProvider);

        initialize(new TaskSnapshotCache(service, new AppSnapshotLoader(mPersistInfoProvider)));
        final boolean snapshotEnabled =
                !service.mContext
                        .getResources()
                        .getBoolean(com.android.internal.R.bool.config_disableTaskSnapshots);
        setSnapshotEnabled(snapshotEnabled);
    }

    static PersistInfoProvider createPersistInfoProvider(WindowManagerService service,
            BaseAppSnapshotPersister.DirectoryResolver resolver) {
        final float highResTaskSnapshotScale = service.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
        final float lowResTaskSnapshotScale = service.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_lowResTaskSnapshotScale);

        if (lowResTaskSnapshotScale < 0 || 1 <= lowResTaskSnapshotScale) {
            throw new RuntimeException("Low-res scale must be between 0 and 1");
        }
        if (highResTaskSnapshotScale <= 0 || 1 < highResTaskSnapshotScale) {
            throw new RuntimeException("High-res scale must be between 0 and 1");
        }
        if (highResTaskSnapshotScale <= lowResTaskSnapshotScale) {
            throw new RuntimeException("High-res scale must be greater than low-res scale");
        }

        final float lowResScaleFactor;
        final boolean enableLowResSnapshots;
        if (lowResTaskSnapshotScale > 0) {
            lowResScaleFactor = lowResTaskSnapshotScale / highResTaskSnapshotScale;
            enableLowResSnapshots = true;
        } else {
            lowResScaleFactor = 0;
            enableLowResSnapshots = false;
        }
        final boolean use16BitFormat = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_use16BitTaskSnapshotPixelFormat);
        return new PersistInfoProvider(resolver, SNAPSHOTS_DIRNAME,
                enableLowResSnapshots, lowResScaleFactor, use16BitFormat);
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
    void addSkipClosingAppSnapshotTasks(Set<Task> tasks) {
        if (shouldDisableSnapshots()) {
            return;
        }
        mSkipClosingAppSnapshotTasks.addAll(tasks);
    }

    void snapshotTasks(ArraySet<Task> tasks) {
        snapshotTasks(tasks, false /* allowSnapshotHome */);
    }

    void recordSnapshot(Task task, boolean allowSnapshotHome) {
        final boolean snapshotHome = allowSnapshotHome && task.isActivityTypeHome();
        final TaskSnapshot snapshot = recordSnapshotInner(task, allowSnapshotHome);
        if (!snapshotHome && snapshot != null) {
            mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
            task.onSnapshotChanged(snapshot);
        }
    }

    private void snapshotTasks(ArraySet<Task> tasks, boolean allowSnapshotHome) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            recordSnapshot(tasks.valueAt(i), allowSnapshotHome);
        }
    }

    /**
     * Retrieves a snapshot. If {@param restoreFromDisk} equals {@code true}, DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    @Nullable
    TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean isLowResolution) {
        return mCache.getSnapshot(taskId, userId, restoreFromDisk, isLowResolution
                && mPersistInfoProvider.enableLowResSnapshots());
    }

    /**
     * @see WindowManagerInternal#clearSnapshotCache
     */
    public void clearSnapshotCache() {
        mCache.clearRunningCache();
    }

    /**
     * Find the window for a given task to take a snapshot. Top child of the task is usually the one
     * we're looking for, but during app transitions, trampoline activities can appear in the
     * children, which should be ignored.
     */
    @Nullable protected ActivityRecord findAppTokenForSnapshot(Task task) {
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


    @Override
    protected boolean use16BitFormat() {
        return mPersistInfoProvider.use16BitFormat();
    }

    @Nullable
    private ScreenCapture.ScreenshotHardwareBuffer createImeSnapshot(@NonNull Task task,
            int pixelFormat) {
        if (task.getSurfaceControl() == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No surface control for " + task);
            }
            return null;
        }
        final WindowState imeWindow = task.getDisplayContent().mInputMethodWindow;
        ScreenCapture.ScreenshotHardwareBuffer imeBuffer = null;
        if (imeWindow != null && imeWindow.isVisible()) {
            final Rect bounds = imeWindow.getParentFrame();
            bounds.offsetTo(0, 0);
            imeBuffer = ScreenCapture.captureLayersExcluding(imeWindow.getSurfaceControl(),
                    bounds, 1.0f, pixelFormat, null);
        }
        return imeBuffer;
    }

    /**
     * Create the snapshot of the IME surface on the task which used for placing on the closing
     * task to keep IME visibility while app transitioning.
     */
    @Nullable
    ScreenCapture.ScreenshotHardwareBuffer snapshotImeFromAttachedTask(@NonNull Task task) {
        // Check if the IME targets task ready to take the corresponding IME snapshot, if not,
        // means the task is not yet visible for some reasons and no need to snapshot IME surface.
        if (checkIfReadyToSnapshot(task) == null) {
            return null;
        }
        final int pixelFormat = mPersistInfoProvider.use16BitFormat()
                    ? PixelFormat.RGB_565
                    : PixelFormat.RGBA_8888;
        return createImeSnapshot(task, pixelFormat);
    }

    @Override
    ActivityRecord getTopActivity(Task source) {
        return source.getTopMostActivity();
    }

    @Override
    ActivityRecord getTopFullscreenActivity(Task source) {
        return source.getTopFullscreenActivity();
    }

    @Override
    ActivityManager.TaskDescription getTaskDescription(Task source) {
        return source.getTaskDescription();
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
            if (task == null) continue;

            // Since RecentsAnimation will handle task snapshot while switching apps with the
            // best capture timing (e.g. IME window capture),
            // No need additional task capture while task is controlled by RecentsAnimation.
            if (isAnimatingByRecents(task)) {
                mSkipClosingAppSnapshotTasks.add(task);
            }
            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (!task.isVisible() && !mSkipClosingAppSnapshotTasks.contains(task)) {
                outClosingTasks.add(task);
            }
        }
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
        mCache.onIdRemoved(taskId);
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
     * Called when screen is being turned off.
     */
    void screenTurningOff(int displayId, ScreenOffListener listener) {
        if (shouldDisableSnapshots()) {
            listener.onScreenOff();
            return;
        }

        // We can't take a snapshot when screen is off, so take a snapshot now!
        mHandler.post(() -> {
            try {
                synchronized (mService.mGlobalLock) {
                    snapshotForSleeping(displayId);
                }
            } finally {
                listener.onScreenOff();
            }
        });
    }

    /** Called when the device is going to sleep (e.g. screen off, AOD without screen off). */
    void snapshotForSleeping(int displayId) {
        if (shouldDisableSnapshots() || !mService.mDisplayEnabled) {
            return;
        }
        final DisplayContent displayContent = mService.mRoot.getDisplayContent(displayId);
        if (displayContent == null) {
            return;
        }
        mTmpTasks.clear();
        displayContent.forAllTasks(task -> {
            // Since RecentsAnimation will handle task snapshot while switching apps with the best
            // capture timing (e.g. IME window capture), No need additional task capture while task
            // is controlled by RecentsAnimation.
            if (task.isVisible() && !isAnimatingByRecents(task)) {
                mTmpTasks.add(task);
            }
        });
        // Allow taking snapshot of home when turning screen off to reduce the delay of waking from
        // secure lock to home.
        final boolean allowSnapshotHome = displayId == Display.DEFAULT_DISPLAY
                && mService.mPolicy.isKeyguardSecure(mService.mCurrentUserId);
        snapshotTasks(mTmpTasks, allowSnapshotHome);
    }

    private boolean isAnimatingByRecents(@NonNull Task task) {
        return task.isAnimatingByRecents()
                || mService.mAtmService.getTransitionController().inRecentsTransition(task);
    }
}
