/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager.TaskSnapshot;
import android.app.WindowConfiguration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.google.android.collect.Sets;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls a single instance of the remote driven recents animation. In particular, this allows
 * the calling SystemUI to animate the visible task windows as a part of the transition. The remote
 * runner is provided an animation controller which allows it to take screenshots and to notify
 * window manager when the animation is completed. In addition, window manager may also notify the
 * app if it requires the animation to be canceled at any time (ie. due to timeout, etc.)
 */
public class RecentsAnimationController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RecentsAnimationController" : TAG_WM;
    private static final boolean DEBUG = false;

    private final WindowManagerService mService;
    private final IRecentsAnimationRunner mRunner;
    private final RecentsAnimationCallbacks mCallbacks;
    private final ArrayList<TaskAnimationAdapter> mPendingAnimations = new ArrayList<>();
    private final int mDisplayId;

    // The recents component app token that is shown behind the visibile tasks
    private AppWindowToken mHomeAppToken;
    private Rect mMinimizedHomeBounds = new Rect();

    // We start the RecentsAnimationController in a pending-start state since we need to wait for
    // the wallpaper/activity to draw before we can give control to the handler to start animating
    // the visible task surfaces
    private boolean mPendingStart = true;

    // Set when the animation has been canceled
    private boolean mCanceled = false;

    // Whether or not the input consumer is enabled. The input consumer must be both registered and
    // enabled for it to start intercepting touch events.
    private boolean mInputConsumerEnabled;

    private Rect mTmpRect = new Rect();

    public interface RecentsAnimationCallbacks {
        void onAnimationFinished(boolean moveHomeToTop);
    }

    private final IRecentsAnimationController mController =
            new IRecentsAnimationController.Stub() {

        @Override
        public TaskSnapshot screenshotTask(int taskId) {
            if (DEBUG) Log.d(TAG, "screenshotTask(" + taskId + "): mCanceled=" + mCanceled);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return null;
                    }
                    for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                        final TaskAnimationAdapter adapter = mPendingAnimations.get(i);
                        final Task task = adapter.mTask;
                        if (task.mTaskId == taskId) {
                            final TaskSnapshotController snapshotController =
                                    mService.mTaskSnapshotController;
                            final ArraySet<Task> tasks = Sets.newArraySet(task);
                            snapshotController.snapshotTasks(tasks);
                            snapshotController.addSkipClosingAppSnapshotTasks(tasks);
                            return snapshotController.getSnapshot(taskId, 0 /* userId */,
                                    false /* restoreFromDisk */, false /* reducedResolution */);
                        }
                    }
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void finish(boolean moveHomeToTop) {
            if (DEBUG) Log.d(TAG, "finish(" + moveHomeToTop + "): mCanceled=" + mCanceled);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return;
                    }
                }

                // Note, the callback will handle its own synchronization, do not lock on WM lock
                // prior to calling the callback
                mCallbacks.onAnimationFinished(moveHomeToTop);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setInputConsumerEnabled(boolean enabled) {
            if (DEBUG) Log.d(TAG, "setInputConsumerEnabled(" + enabled + "): mCanceled="
                    + mCanceled);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mService.getWindowManagerLock()) {
                    if (mCanceled) {
                        return;
                    }

                    mInputConsumerEnabled = enabled;
                    mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
                    mService.scheduleAnimationLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    /**
     * @param remoteAnimationRunner The remote runner which should be notified when the animation is
     *                              ready to start or has been canceled
     * @param callbacks Callbacks to be made when the animation finishes
     */
    RecentsAnimationController(WindowManagerService service,
            IRecentsAnimationRunner remoteAnimationRunner, RecentsAnimationCallbacks callbacks,
            int displayId) {
        mService = service;
        mRunner = remoteAnimationRunner;
        mCallbacks = callbacks;
        mDisplayId = displayId;
    }

    /**
     * Initializes the recents animation controller. This is a separate call from the constructor
     * because it may call cancelAnimation() which needs to properly clean up the controller
     * in the window manager.
     */
    public void initialize() {
        // Make leashes for each of the visible tasks and add it to the recents animation to be
        // started
        final DisplayContent dc = mService.mRoot.getDisplayContent(mDisplayId);
        final ArrayList<Task> visibleTasks = dc.getVisibleTasks();
        final int taskCount = visibleTasks.size();
        for (int i = 0; i < taskCount; i++) {
            final Task task = visibleTasks.get(i);
            final WindowConfiguration config = task.getWindowConfiguration();
            if (config.tasksAreFloating()
                    || config.getWindowingMode() == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    || config.getActivityType() == ACTIVITY_TYPE_HOME) {
                continue;
            }
            addAnimation(task);
        }

        // Skip the animation if there is nothing to animate
        if (mPendingAnimations.isEmpty()) {
            cancelAnimation();
            return;
        }

        // Adjust the wallpaper visibility for the showing home activity
        final AppWindowToken recentsComponentAppToken =
                dc.getHomeStack().getTopChild().getTopFullscreenAppToken();
        if (recentsComponentAppToken != null) {
            if (DEBUG) Log.d(TAG, "setHomeApp(" + recentsComponentAppToken.getName() + ")");
            mHomeAppToken = recentsComponentAppToken;
            if (recentsComponentAppToken.windowsCanBeWallpaperTarget()) {
                dc.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
                dc.setLayoutNeeded();
            }
        }

        // Save the minimized home height
        dc.getDockedDividerController().getHomeStackBoundsInDockedMode(mMinimizedHomeBounds);

        mService.mWindowPlacerLocked.performSurfacePlacement();
    }

    private void addAnimation(Task task) {
        if (DEBUG) Log.d(TAG, "addAnimation(" + task.getName() + ")");
        final SurfaceAnimator anim = new SurfaceAnimator(task, null /* animationFinishedCallback */,
                mService.mAnimator::addAfterPrepareSurfacesRunnable, mService);
        final TaskAnimationAdapter taskAdapter = new TaskAnimationAdapter(task);
        anim.startAnimation(task.getPendingTransaction(), taskAdapter, false /* hidden */);
        task.commitPendingTransaction();
        mPendingAnimations.add(taskAdapter);
    }

    void startAnimation() {
        if (DEBUG) Log.d(TAG, "startAnimation(): mPendingStart=" + mPendingStart
                + " mCanceled=" + mCanceled);
        if (!mPendingStart || mCanceled) {
            // Skip starting if we've already started or canceled the animation
            return;
        }
        try {
            final RemoteAnimationTarget[] appAnimations =
                    new RemoteAnimationTarget[mPendingAnimations.size()];
            for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                appAnimations[i] = mPendingAnimations.get(i).createRemoteAnimationApp();
            }
            mPendingStart = false;

            final Rect minimizedHomeBounds =
                    mHomeAppToken != null && mHomeAppToken.inSplitScreenSecondaryWindowingMode()
                            ? mMinimizedHomeBounds : null;
            final Rect contentInsets =
                    mHomeAppToken != null && mHomeAppToken.findMainWindow() != null
                            ? mHomeAppToken.findMainWindow().mContentInsets : null;
            mRunner.onAnimationStart_New(mController, appAnimations, contentInsets,
                    minimizedHomeBounds);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start recents animation", e);
        }
    }

    void cancelAnimation() {
        if (DEBUG) Log.d(TAG, "cancelAnimation()");
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                // We've already canceled the animation
                return;
            }
            mCanceled = true;
            try {
                mRunner.onAnimationCanceled();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to cancel recents animation", e);
            }
        }
        // Clean up and return to the previous app
        // Don't hold the WM lock here as it calls back to AM/RecentsAnimation
        mCallbacks.onAnimationFinished(false /* moveHomeToTop */);
    }

    void cleanupAnimation() {
        if (DEBUG) Log.d(TAG, "cleanupAnimation(): mPendingAnimations="
                + mPendingAnimations.size());
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final TaskAnimationAdapter adapter = mPendingAnimations.get(i);
            adapter.mCapturedFinishCallback.onAnimationFinished(adapter);
        }
        mPendingAnimations.clear();

        mService.mInputMonitor.updateInputWindowsLw(true /*force*/);
        mService.destroyInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);
    }

    void checkAnimationReady(WallpaperController wallpaperController) {
        if (mPendingStart) {
            final boolean wallpaperReady = !isHomeAppOverWallpaper()
                    || (wallpaperController.getWallpaperTarget() != null
                            && wallpaperController.wallpaperTransitionReady());
            if (wallpaperReady) {
                mService.getRecentsAnimationController().startAnimation();
            }
        }
    }

    boolean isWallpaperVisible(WindowState w) {
        return w != null && w.mAppToken != null && mHomeAppToken == w.mAppToken
                && isHomeAppOverWallpaper();
    }

    boolean hasInputConsumerForApp(AppWindowToken appToken) {
        return mInputConsumerEnabled && isAnimatingApp(appToken);
    }

    boolean updateInputConsumerForApp(InputConsumerImpl recentsAnimationInputConsumer,
            boolean hasFocus) {
        // Update the input consumer touchable region to match the home app main window
        final WindowState homeAppMainWindow = mHomeAppToken != null
                ? mHomeAppToken.findMainWindow()
                : null;
        if (homeAppMainWindow != null) {
            homeAppMainWindow.getBounds(mTmpRect);
            recentsAnimationInputConsumer.mWindowHandle.hasFocus = hasFocus;
            recentsAnimationInputConsumer.mWindowHandle.touchableRegion.set(mTmpRect);
            return true;
        }
        return false;
    }

    private boolean isHomeAppOverWallpaper() {
        if (mHomeAppToken == null) {
            return false;
        }
        return mHomeAppToken.windowsCanBeWallpaperTarget();
    }

    private boolean isAnimatingApp(AppWindowToken appToken) {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final Task task = mPendingAnimations.get(i).mTask;
            for (int j = task.getChildCount() - 1; j >= 0; j--) {
                final AppWindowToken app = task.getChildAt(j);
                if (app == appToken) {
                    return true;
                }
            }
        }
        return false;
    }

    private class TaskAnimationAdapter implements AnimationAdapter {

        private Task mTask;
        private SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;

        TaskAnimationAdapter(Task task) {
            mTask = task;
        }

        RemoteAnimationTarget createRemoteAnimationApp() {
            final Point position = new Point();
            final Rect bounds = new Rect();
            final WindowContainer container = mTask.getParent();
            container.getRelativePosition(position);
            container.getBounds(bounds);
            final WindowState mainWindow = mTask.getTopVisibleAppMainWindow();
            return new RemoteAnimationTarget(mTask.mTaskId, MODE_CLOSING, mCapturedLeash,
                    !mTask.fillsParent(), mainWindow.mWinAnimator.mLastClipRect,
                    mainWindow.mContentInsets, mTask.getPrefixOrderIndex(), position, bounds,
                    mTask.getWindowConfiguration());
        }

        @Override
        public boolean getDetachWallpaper() {
            return false;
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public int getBackgroundColor() {
            return 0;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            cancelAnimation();
        }

        @Override
        public long getDurationHint() {
            return 0;
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis();
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println(RecentsAnimationController.class.getSimpleName() + ":");
        pw.print(innerPrefix); pw.println("mPendingStart=" + mPendingStart);
        pw.print(innerPrefix); pw.println("mHomeAppToken=" + mHomeAppToken);
    }
}
