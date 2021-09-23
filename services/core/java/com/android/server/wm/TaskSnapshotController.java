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
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
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
import android.util.Pair;
import android.util.Slog;
import android.view.Display;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.WindowInsets.Type;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowManager.LayoutParams;
import android.window.TaskSnapshot;

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
     * we should try to use the app theme to create a fake representation of the app.
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
     * Flag indicating if task snapshot is enabled on this device.
     */
    private boolean mTaskSnapshotEnabled;

    TaskSnapshotController(WindowManagerService service) {
        mService = service;
        mPersister = new TaskSnapshotPersister(mService, Environment::getDataSystemCeDirectory);
        mLoader = new TaskSnapshotLoader(mPersister);
        mCache = new TaskSnapshotCache(mService, mLoader);
        mIsRunningOnTv = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        mIsRunningOnIoT = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_EMBEDDED);
        mHighResTaskSnapshotScale = mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
        mTaskSnapshotEnabled =
                !mService.mContext
                        .getResources()
                        .getBoolean(com.android.internal.R.bool.config_disableTaskSnapshots);
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
        if (shouldDisableSnapshots()) {
            return;
        }
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
    @Nullable
    TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
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
        final Pair<ActivityRecord, WindowState> result = checkIfReadyToSnapshot(task);
        if (result == null) {
            return false;
        }
        final ActivityRecord activity = result.first;
        final WindowState mainWindow = result.second;
        final Rect contentInsets = getSystemBarInsets(task.getBounds(),
                mainWindow.getInsetsStateWithVisibilityOverride());
        InsetUtils.addInsets(contentInsets, activity.getLetterboxInsets());

        builder.setIsRealSnapshot(true);
        builder.setId(System.currentTimeMillis());
        builder.setContentInsets(contentInsets);

        final boolean isWindowTranslucent = mainWindow.getAttrs().format != PixelFormat.OPAQUE;
        final boolean isShowWallpaper = mainWindow.hasWallpaper();

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
        builder.setAppearance(getAppearance(task));
        return true;
    }

    /**
     * Check if the state of the Task is appropriate to capture a snapshot, such like the task
     * snapshot or the associated IME surface snapshot.
     *
     * @param task the target task to capture the snapshot
     * @return Pair of (the top activity of the task, the main window of the task) if passed the
     * state checking. Returns {@code null} if the task state isn't ready to snapshot.
     */
    Pair<ActivityRecord, WindowState> checkIfReadyToSnapshot(Task task) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        final ActivityRecord activity = findAppTokenForSnapshot(task);
        if (activity == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No visible windows for " + task);
            }
            return null;
        }
        if (activity.hasCommittedReparentToAnimationLeash()) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. App is animating " + activity);
            }
            return null;
        }

        final WindowState mainWindow = activity.findMainWindow();
        if (mainWindow == null) {
            Slog.w(TAG_WM, "Failed to take screenshot. No main window for " + task);
            return null;
        }
        if (activity.hasFixedRotationTransform()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Skip taking screenshot. App has fixed rotation " + activity);
            }
            // The activity is in a temporal state that it has different rotation than the task.
            return null;
        }
        return new Pair<>(activity, mainWindow);
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            TaskSnapshot.Builder builder) {
        Point taskSize = new Point();
        final SurfaceControl.ScreenshotHardwareBuffer taskSnapshot = createTaskSnapshot(task,
                mHighResTaskSnapshotScale, builder.getPixelFormat(), taskSize, builder);
        builder.setTaskSize(taskSize);
        return taskSnapshot;
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            float scaleFraction, TaskSnapshot.Builder builder) {
        return createTaskSnapshot(task, scaleFraction, PixelFormat.RGBA_8888, null, builder);
    }

    @Nullable
    private SurfaceControl.ScreenshotHardwareBuffer createImeSnapshot(@NonNull Task task,
            int pixelFormat) {
        if (task.getSurfaceControl() == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No surface control for " + task);
            }
            return null;
        }
        final WindowState imeWindow = task.getDisplayContent().mInputMethodWindow;
        SurfaceControl.ScreenshotHardwareBuffer imeBuffer = null;
        if (imeWindow != null && imeWindow.isWinVisibleLw()) {
            final Rect bounds = imeWindow.getContainingFrame();
            bounds.offsetTo(0, 0);
            imeBuffer = SurfaceControl.captureLayersExcluding(imeWindow.getSurfaceControl(),
                    bounds, 1.0f, pixelFormat, null);
        }
        return imeBuffer;
    }

    /**
     * Create the snapshot of the IME surface on the task which used for placing on the closing
     * task to keep IME visibility while app transitioning.
     */
    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer snapshotImeFromAttachedTask(@NonNull Task task) {
        // Check if the IME targets task ready to take the corresponding IME snapshot, if not,
        // means the task is not yet visible for some reasons and no need to snapshot IME surface.
        if (checkIfReadyToSnapshot(task) == null) {
            return null;
        }
        final int pixelFormat = mPersister.use16BitFormat()
                    ? PixelFormat.RGB_565
                    : PixelFormat.RGBA_8888;
        return createImeSnapshot(task, pixelFormat);
    }

    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer createTaskSnapshot(@NonNull Task task,
            float scaleFraction, int pixelFormat, Point outTaskSize, TaskSnapshot.Builder builder) {
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
        // Exclude IME window snapshot when IME isn't proper to attach to app.
        final boolean excludeIme = imeWindow != null && imeWindow.getSurfaceControl() != null
                && !task.getDisplayContent().shouldImeAttachedToApp();
        final WindowState navWindow =
                task.getDisplayContent().getDisplayPolicy().getNavigationBar();
        // If config_attachNavBarToAppDuringTransition is true, the nav bar will be reparent to the
        // the swiped app when entering recent app, therefore the task will contain the navigation
        // bar and we should exclude it from snapshot.
        final boolean excludeNavBar = navWindow != null;
        if (excludeIme && excludeNavBar) {
            excludeLayers = new SurfaceControl[2];
            excludeLayers[0] = imeWindow.getSurfaceControl();
            excludeLayers[1] = navWindow.getSurfaceControl();
        } else if (excludeIme || excludeNavBar) {
            excludeLayers = new SurfaceControl[1];
            excludeLayers[0] =
                    excludeIme ? imeWindow.getSurfaceControl() : navWindow.getSurfaceControl();
        } else {
            excludeLayers = new SurfaceControl[0];
        }
        builder.setHasImeSurface(!excludeIme && imeWindow != null && imeWindow.isVisible());

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

    void setTaskSnapshotEnabled(boolean enabled) {
        mTaskSnapshotEnabled = enabled;
    }

    boolean shouldDisableSnapshots() {
        return mIsRunningOnTv || mIsRunningOnIoT || !mTaskSnapshotEnabled;
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
            if (task.isAnimatingByRecents()) {
                mSkipClosingAppSnapshotTasks.add(task);
            }
            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (!task.isVisible() && !mSkipClosingAppSnapshotTasks.contains(task)) {
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
        final Rect taskBounds = task.getBounds();
        final InsetsState insetsState = mainWindow.getInsetsStateWithVisibilityOverride();
        final Rect systemBarInsets = getSystemBarInsets(taskBounds, insetsState);
        final SystemBarBackgroundPainter decorPainter = new SystemBarBackgroundPainter(attrs.flags,
                attrs.privateFlags, attrs.insetsFlags.appearance, task.getTaskDescription(),
                mHighResTaskSnapshotScale, insetsState);
        final int taskWidth = taskBounds.width();
        final int taskHeight = taskBounds.height();
        final int width = (int) (taskWidth * mHighResTaskSnapshotScale);
        final int height = (int) (taskHeight * mHighResTaskSnapshotScale);

        final RenderNode node = RenderNode.create("TaskSnapshotController", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        final RecordingCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(systemBarInsets);
        decorPainter.drawDecors(c, null /* statusBarExcludeFrame */);
        node.end(c);
        final Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }
        final Rect contentInsets = new Rect(systemBarInsets);
        InsetUtils.addInsets(contentInsets, topChild.getLetterboxInsets());

        // Note, the app theme snapshot is never translucent because we enforce a non-translucent
        // color above
        return new TaskSnapshot(
                System.currentTimeMillis() /* id */,
                topChild.mActivityComponent, hwBitmap.getHardwareBuffer(),
                hwBitmap.getColorSpace(), mainWindow.getConfiguration().orientation,
                mainWindow.getWindowConfiguration().getRotation(), new Point(taskWidth, taskHeight),
                contentInsets, false /* isLowResolution */, false /* isRealSnapshot */,
                task.getWindowingMode(), getAppearance(task), false /* isTranslucent */,
                false /* hasImeSurface */);
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
            if (task.isVisible() && !task.isAnimatingByRecents()) {
                mTmpTasks.add(task);
            }
        });
        // Allow taking snapshot of home when turning screen off to reduce the delay of waking from
        // secure lock to home.
        final boolean allowSnapshotHome = displayId == Display.DEFAULT_DISPLAY
                && mService.mPolicy.isKeyguardSecure(mService.mCurrentUserId);
        snapshotTasks(mTmpTasks, allowSnapshotHome);
    }

    /**
     * @return The {@link Appearance} flags for the top fullscreen opaque window in the given
     *         {@param task}.
     */
    private @Appearance int getAppearance(Task task) {
        final ActivityRecord topFullscreenActivity = task.getTopFullscreenActivity();
        final WindowState topFullscreenOpaqueWindow = topFullscreenActivity != null
                ? topFullscreenActivity.getTopFullscreenOpaqueWindow()
                : null;
        if (topFullscreenOpaqueWindow != null) {
            return topFullscreenOpaqueWindow.mAttrs.insetsFlags.appearance;
        }
        return 0;
    }

    static Rect getSystemBarInsets(Rect frame, InsetsState state) {
        return state.calculateInsets(
                frame, Type.systemBars(), false /* ignoreVisibility */).toRect();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mHighResTaskSnapshotScale=" + mHighResTaskSnapshotScale);
        pw.println(prefix + "mTaskSnapshotEnabled=" + mTaskSnapshotEnabled);
        mCache.dump(pw, prefix);
    }
}
