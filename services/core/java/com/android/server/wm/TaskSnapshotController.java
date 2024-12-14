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
import android.util.IntArray;
import android.util.Slog;
import android.view.Display;
import android.window.ScreenCapture;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy.ScreenOffListener;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;

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
    private final IntArray mSkipClosingAppSnapshotTasks = new IntArray();
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();
    private final Handler mHandler = new Handler();

    private final PersistInfoProvider mPersistInfoProvider;

    TaskSnapshotController(WindowManagerService service, SnapshotPersistQueue persistQueue) {
        super(service);
        mPersistInfoProvider = createPersistInfoProvider(service,
                Environment::getDataSystemCeDirectory);
        mPersister = new TaskSnapshotPersister(persistQueue, mPersistInfoProvider);

        initialize(new TaskSnapshotCache(new AppSnapshotLoader(mPersistInfoProvider)));
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

    // Still needed for legacy transition.(AppTransitionControllerTest)
    void handleClosingApps(ArraySet<ActivityRecord> closingApps) {
        if (shouldDisableSnapshots()) {
            return;
        }
        // We need to take a snapshot of the task if and only if all activities of the task are
        // either closing or hidden.
        mTmpTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = closingApps.valueAt(i);
            if (activity.isActivityTypeHome()) continue;
            final Task task = activity.getTask();
            if (task == null) continue;

            getClosingTasksInner(task, mTmpTasks);
        }
        snapshotTasks(mTmpTasks);
        mTmpTasks.clear();
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
        for (Task task : tasks) {
            mSkipClosingAppSnapshotTasks.add(task.mTaskId);
        }
    }

    void snapshotTasks(ArraySet<Task> tasks) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            recordSnapshot(tasks.valueAt(i));
        }
    }

    /**
     * The attributes of task snapshot are based on task configuration. But sometimes the
     * configuration may have been changed during a transition, so supply the ChangeInfo that
     * stored the previous appearance of the closing task.
     */
    void recordSnapshot(Task task, Transition.ChangeInfo changeInfo) {
        mCurrentChangeInfo = changeInfo;
        try {
            recordSnapshot(task);
        } finally {
            mCurrentChangeInfo = null;
        }
    }

    TaskSnapshot recordSnapshot(Task task) {
        final TaskSnapshot snapshot = recordSnapshotInner(task);
        if (snapshot != null && !task.isActivityTypeHome()) {
            mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
            task.onSnapshotChanged(snapshot);
        }
        return snapshot;
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
     * Returns the elapsed real time (in nanoseconds) at which a snapshot for the given task was
     * last taken, or -1 if no such snapshot exists for that task.
     */
    long getSnapshotCaptureTime(int taskId) {
        final TaskSnapshot snapshot = mCache.getSnapshot(taskId);
        if (snapshot != null) {
            return snapshot.getCaptureTime();
        }
        return -1;
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
        return task.getActivity(ActivityRecord::canCaptureSnapshot);
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
            ScreenCapture.LayerCaptureArgs captureArgs = new ScreenCapture.LayerCaptureArgs.Builder(
                    imeWindow.getSurfaceControl())
                    .setSourceCrop(bounds)
                    .setFrameScale(1.0f)
                    .setPixelFormat(pixelFormat)
                    .setCaptureSecureLayers(true)
                    .build();
            imeBuffer = ScreenCapture.captureLayers(captureArgs);
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
    ActivityManager.TaskDescription getTaskDescription(Task source) {
        return source.getTaskDescription();
    }

    @Override
    protected Rect getLetterboxInsets(ActivityRecord topActivity) {
        return topActivity.getLetterboxInsets();
    }

    void getClosingTasksInner(Task task, ArraySet<Task> outClosingTasks) {
        // Since RecentsAnimation will handle task snapshot while switching apps with the
        // best capture timing (e.g. IME window capture),
        // No need additional task capture while task is controlled by RecentsAnimation.
        if (isAnimatingByRecents(task)) {
            mSkipClosingAppSnapshotTasks.add(task.mTaskId);
        }
        // If the task of the app is not visible anymore, it means no other app in that task
        // is opening. Thus, the task is closing.
        if (!task.isVisible() && mSkipClosingAppSnapshotTasks.indexOf(task.mTaskId) < 0) {
            outClosingTasks.add(task);
        }
    }

    void removeAndDeleteSnapshot(int taskId, int userId) {
        mCache.onIdRemoved(taskId);
        mPersister.removeSnapshot(taskId, userId);
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
        // Allow taking snapshot of home when turning screen off to reduce the delay of waking from
        // secure lock to home.
        final boolean allowSnapshotHome = displayId == Display.DEFAULT_DISPLAY
                && mService.mPolicy.isKeyguardSecure(mService.mCurrentUserId);
        mTmpTasks.clear();
        displayContent.forAllLeafTasks(task -> {
            if (!allowSnapshotHome && task.isActivityTypeHome()) {
                return;
            }
            // Since RecentsAnimation will handle task snapshot while switching apps with the best
            // capture timing (e.g. IME window capture), No need additional task capture while task
            // is controlled by RecentsAnimation.
            if (task.isVisible() && !isAnimatingByRecents(task)) {
                mTmpTasks.add(task);
            }
        }, true /* traverseTopToBottom */);
        snapshotTasks(mTmpTasks);
    }
}
