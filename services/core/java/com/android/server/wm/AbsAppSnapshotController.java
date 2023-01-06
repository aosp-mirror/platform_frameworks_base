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
package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_SCREENSHOT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.os.Trace;
import android.util.Pair;
import android.util.Slog;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.ScreenCapture;
import android.window.SnapshotDrawerUtils;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;

/**
 * Base class for a Snapshot controller
 * @param <TYPE> The basic type, either Task or ActivityRecord
 * @param <CACHE> The basic cache for either Task or ActivityRecord
 */
abstract class AbsAppSnapshotController<TYPE extends WindowContainer,
        CACHE extends AbsAppSnapshotCache<TYPE>> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "SnapshotController" : TAG_WM;
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

    protected final WindowManagerService mService;
    protected final float mHighResTaskSnapshotScale;
    private final Rect mTmpRect = new Rect();
    /**
     * Flag indicating whether we are running on an Android TV device.
     */
    protected final boolean mIsRunningOnTv;
    /**
     * Flag indicating whether we are running on an IoT device.
     */
    protected final boolean mIsRunningOnIoT;

    protected CACHE mCache;
    /**
     * Flag indicating if task snapshot is enabled on this device.
     */
    private boolean mSnapshotEnabled;

    AbsAppSnapshotController(WindowManagerService service) {
        mService = service;
        mIsRunningOnTv = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);
        mIsRunningOnIoT = mService.mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_EMBEDDED);
        mHighResTaskSnapshotScale = initSnapshotScale();
    }

    protected float initSnapshotScale() {
        return mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_highResTaskSnapshotScale);
    }

    /**
     * Set basic cache to the controller.
     */
    protected void initialize(CACHE cache) {
        mCache = cache;
    }

    void setSnapshotEnabled(boolean enabled) {
        mSnapshotEnabled = enabled;
    }

    boolean shouldDisableSnapshots() {
        return mIsRunningOnTv || mIsRunningOnIoT || !mSnapshotEnabled;
    }

    abstract ActivityRecord getTopActivity(TYPE source);
    abstract ActivityRecord getTopFullscreenActivity(TYPE source);
    abstract ActivityManager.TaskDescription getTaskDescription(TYPE source);
    /**
     * Find the window for a given task to take a snapshot. Top child of the task is usually the one
     * we're looking for, but during app transitions, trampoline activities can appear in the
     * children, which should be ignored.
     */
    @Nullable
    protected abstract ActivityRecord findAppTokenForSnapshot(TYPE source);
    protected abstract boolean use16BitFormat();

    /**
     * This is different than {@link #recordSnapshotInner(TYPE, boolean)} because it doesn't store
     * the snapshot to the cache and returns the TaskSnapshot immediately.
     *
     * This is only used for testing so the snapshot content can be verified.
     */
    // TODO(b/264551777): clean up the "snapshotHome" argument
    @VisibleForTesting
    TaskSnapshot captureSnapshot(TYPE source, boolean snapshotHome) {
        final TaskSnapshot snapshot;
        if (snapshotHome) {
            snapshot = snapshot(source);
        } else {
            switch (getSnapshotMode(source)) {
                case SNAPSHOT_MODE_NONE:
                    return null;
                case SNAPSHOT_MODE_APP_THEME:
                    snapshot = drawAppThemeSnapshot(source);
                    break;
                case SNAPSHOT_MODE_REAL:
                    snapshot = snapshot(source);
                    break;
                default:
                    snapshot = null;
                    break;
            }
        }
        return snapshot;
    }

    final TaskSnapshot recordSnapshotInner(TYPE source, boolean allowSnapshotHome) {
        final boolean snapshotHome = allowSnapshotHome && source.isActivityTypeHome();
        final TaskSnapshot snapshot = captureSnapshot(source, snapshotHome);
        if (snapshot == null) {
            return null;
        }
        final HardwareBuffer buffer = snapshot.getHardwareBuffer();
        if (buffer.getWidth() == 0 || buffer.getHeight() == 0) {
            buffer.close();
            Slog.e(TAG, "Invalid task snapshot dimensions " + buffer.getWidth() + "x"
                    + buffer.getHeight());
            return null;
        } else {
            mCache.putSnapshot(source, snapshot);
            return snapshot;
        }
    }

    @VisibleForTesting
    int getSnapshotMode(TYPE source) {
        final ActivityRecord topChild = getTopActivity(source);
        if (!source.isActivityTypeStandardOrUndefined() && !source.isActivityTypeAssistant()) {
            return SNAPSHOT_MODE_NONE;
        } else if (topChild != null && topChild.shouldUseAppThemeSnapshot()) {
            return SNAPSHOT_MODE_APP_THEME;
        } else {
            return SNAPSHOT_MODE_REAL;
        }
    }

    @Nullable
    TaskSnapshot snapshot(TYPE source) {
        return snapshot(source, PixelFormat.UNKNOWN);
    }

    @Nullable
    TaskSnapshot snapshot(TYPE source, int pixelFormat) {
        TaskSnapshot.Builder builder = new TaskSnapshot.Builder();
        if (!prepareTaskSnapshot(source, pixelFormat, builder)) {
            // Failed some pre-req. Has been logged.
            return null;
        }
        final ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer =
                createSnapshot(source, builder);
        if (screenshotBuffer == null) {
            // Failed to acquire image. Has been logged.
            return null;
        }
        builder.setSnapshot(screenshotBuffer.getHardwareBuffer());
        builder.setColorSpace(screenshotBuffer.getColorSpace());
        return builder.build();
    }

    @Nullable
    ScreenCapture.ScreenshotHardwareBuffer createSnapshot(@NonNull TYPE source,
            TaskSnapshot.Builder builder) {
        Point taskSize = new Point();
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "createSnapshot");
        final ScreenCapture.ScreenshotHardwareBuffer taskSnapshot = createSnapshot(source,
                mHighResTaskSnapshotScale, builder.getPixelFormat(), taskSize, builder);
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        builder.setTaskSize(taskSize);
        return taskSnapshot;
    }

    @Nullable
    ScreenCapture.ScreenshotHardwareBuffer createSnapshot(@NonNull TYPE source,
            float scaleFraction, int pixelFormat, Point outTaskSize, TaskSnapshot.Builder builder) {
        if (source.getSurfaceControl() == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No surface control for " + source);
            }
            return null;
        }
        source.getBounds(mTmpRect);
        mTmpRect.offsetTo(0, 0);
        SurfaceControl[] excludeLayers;
        final WindowState imeWindow = source.getDisplayContent().mInputMethodWindow;
        // Exclude IME window snapshot when IME isn't proper to attach to app.
        final boolean excludeIme = imeWindow != null && imeWindow.getSurfaceControl() != null
                && !source.getDisplayContent().shouldImeAttachedToApp();
        final WindowState navWindow =
                source.getDisplayContent().getDisplayPolicy().getNavigationBar();
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
        final ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer =
                ScreenCapture.captureLayersExcluding(
                        source.getSurfaceControl(), mTmpRect, scaleFraction,
                        pixelFormat, excludeLayers);
        if (outTaskSize != null) {
            outTaskSize.x = mTmpRect.width();
            outTaskSize.y = mTmpRect.height();
        }
        final HardwareBuffer buffer = screenshotBuffer == null ? null
                : screenshotBuffer.getHardwareBuffer();
        if (isInvalidHardwareBuffer(buffer)) {
            return null;
        }
        return screenshotBuffer;
    }

    static boolean isInvalidHardwareBuffer(HardwareBuffer buffer) {
        return buffer == null || buffer.isClosed() // This must be checked before getting size.
                || buffer.getWidth() <= 1 || buffer.getHeight() <= 1;
    }

    /**
     * Validates the state of the Task is appropriate to capture a snapshot, collects
     * information from the task and populates the builder.
     *
     * @param source the window to capture
     * @param pixelFormat the desired pixel format, or {@link PixelFormat#UNKNOWN} to
     *                    automatically select
     * @param builder the snapshot builder to populate
     *
     * @return true if the state of the task is ok to proceed
     */
    @VisibleForTesting
    boolean prepareTaskSnapshot(TYPE source, int pixelFormat, TaskSnapshot.Builder builder) {
        final Pair<ActivityRecord, WindowState> result = checkIfReadyToSnapshot(source);
        if (result == null) {
            return false;
        }
        final ActivityRecord activity = result.first;
        final WindowState mainWindow = result.second;
        final Rect contentInsets = getSystemBarInsets(mainWindow.getFrame(),
                mainWindow.getInsetsStateWithVisibilityOverride());
        final Rect letterboxInsets = activity.getLetterboxInsets();
        InsetUtils.addInsets(contentInsets, letterboxInsets);
        builder.setIsRealSnapshot(true);
        builder.setId(System.currentTimeMillis());
        builder.setContentInsets(contentInsets);
        builder.setLetterboxInsets(letterboxInsets);
        final boolean isWindowTranslucent = mainWindow.getAttrs().format != PixelFormat.OPAQUE;
        final boolean isShowWallpaper = mainWindow.hasWallpaper();
        if (pixelFormat == PixelFormat.UNKNOWN) {
            pixelFormat = use16BitFormat() && activity.fillsParent()
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
        builder.setWindowingMode(source.getWindowingMode());
        builder.setAppearance(getAppearance(source));
        return true;
    }

    /**
     * Check if the state of the Task is appropriate to capture a snapshot, such like the task
     * snapshot or the associated IME surface snapshot.
     *
     * @param source the target object to capture the snapshot
     * @return Pair of (the top activity of the task, the main window of the task) if passed the
     * state checking. Returns {@code null} if the task state isn't ready to snapshot.
     */
    Pair<ActivityRecord, WindowState> checkIfReadyToSnapshot(TYPE source) {
        if (!mService.mPolicy.isScreenOn()) {
            if (DEBUG_SCREENSHOT) {
                Slog.i(TAG_WM, "Attempted to take screenshot while display was off.");
            }
            return null;
        }
        final ActivityRecord activity = findAppTokenForSnapshot(source);
        if (activity == null) {
            if (DEBUG_SCREENSHOT) {
                Slog.w(TAG_WM, "Failed to take screenshot. No visible windows for " + source);
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
            Slog.w(TAG_WM, "Failed to take screenshot. No main window for " + source);
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

    /**
     * If we are not allowed to take a real screenshot, this attempts to represent the app as best
     * as possible by using the theme's window background.
     */
    private TaskSnapshot drawAppThemeSnapshot(TYPE source) {
        final ActivityRecord topActivity = getTopActivity(source);
        if (topActivity == null) {
            return null;
        }
        final WindowState mainWindow = topActivity.findMainWindow();
        if (mainWindow == null) {
            return null;
        }
        final ActivityManager.TaskDescription taskDescription = getTaskDescription(source);
        final int color = ColorUtils.setAlphaComponent(
                taskDescription.getBackgroundColor(), 255);
        final WindowManager.LayoutParams attrs = mainWindow.getAttrs();
        final Rect taskBounds = source.getBounds();
        final InsetsState insetsState = mainWindow.getInsetsStateWithVisibilityOverride();
        final Rect systemBarInsets = getSystemBarInsets(mainWindow.getFrame(), insetsState);
        final SnapshotDrawerUtils.SystemBarBackgroundPainter
                decorPainter = new SnapshotDrawerUtils.SystemBarBackgroundPainter(attrs.flags,
                attrs.privateFlags, attrs.insetsFlags.appearance, taskDescription,
                mHighResTaskSnapshotScale, mainWindow.getRequestedVisibleTypes());
        final int taskWidth = taskBounds.width();
        final int taskHeight = taskBounds.height();
        final int width = (int) (taskWidth * mHighResTaskSnapshotScale);
        final int height = (int) (taskHeight * mHighResTaskSnapshotScale);
        final RenderNode node = RenderNode.create("SnapshotController", null);
        node.setLeftTopRightBottom(0, 0, width, height);
        node.setClipToBounds(false);
        final RecordingCanvas c = node.start(width, height);
        c.drawColor(color);
        decorPainter.setInsets(systemBarInsets);
        decorPainter.drawDecors(c /* statusBarExcludeFrame */, null /* alreadyDrawFrame */);
        node.end(c);
        final Bitmap hwBitmap = ThreadedRenderer.createHardwareBitmap(node, width, height);
        if (hwBitmap == null) {
            return null;
        }
        final Rect contentInsets = new Rect(systemBarInsets);
        final Rect letterboxInsets = topActivity.getLetterboxInsets();
        InsetUtils.addInsets(contentInsets, letterboxInsets);
        // Note, the app theme snapshot is never translucent because we enforce a non-translucent
        // color above
        return new TaskSnapshot(
                System.currentTimeMillis() /* id */,
                topActivity.mActivityComponent, hwBitmap.getHardwareBuffer(),
                hwBitmap.getColorSpace(), mainWindow.getConfiguration().orientation,
                mainWindow.getWindowConfiguration().getRotation(), new Point(taskWidth, taskHeight),
                contentInsets, letterboxInsets, false /* isLowResolution */,
                false /* isRealSnapshot */, source.getWindowingMode(),
                getAppearance(source), false /* isTranslucent */, false /* hasImeSurface */);
    }

    static Rect getSystemBarInsets(Rect frame, InsetsState state) {
        return state.calculateInsets(
                frame, WindowInsets.Type.systemBars(), false /* ignoreVisibility */).toRect();
    }

    /**
     * @return The {@link WindowInsetsController.Appearance} flags for the top fullscreen opaque
     * window in the given {@param TYPE}.
     */
    @WindowInsetsController.Appearance
    private int getAppearance(TYPE source) {
        final ActivityRecord topFullscreenActivity = getTopFullscreenActivity(source);
        final WindowState topFullscreenOpaqueWindow = topFullscreenActivity != null
                ? topFullscreenActivity.getTopFullscreenOpaqueWindow()
                : null;
        if (topFullscreenOpaqueWindow != null) {
            return topFullscreenOpaqueWindow.mAttrs.insetsFlags.appearance;
        }
        return 0;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mHighResTaskSnapshotScale=" + mHighResTaskSnapshotScale);
        pw.println(prefix + "mTaskSnapshotEnabled=" + mSnapshotEnabled);
        mCache.dump(pw, prefix);
    }
}
