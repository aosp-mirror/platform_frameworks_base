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
import static android.content.Context.CONTEXT_RESTRICTED;
import static android.content.res.Configuration.EMPTY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.StartingWindowInfo;
import android.window.TaskOrganizer;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;
import com.android.wm.shell.common.ShellExecutor;

import java.util.function.Consumer;

/**
 * Implementation to draw the starting window to an application, and remove the starting window
 * until the application displays its own window.
 *
 * When receive {@link TaskOrganizer#addStartingWindow} callback, use this class to create a
 * starting window and attached to the Task, then when the Task want to remove the starting window,
 * the TaskOrganizer will receive {@link TaskOrganizer#removeStartingWindow} callback then use this
 * class to remove the starting window of the Task.
 * @hide
 */
public class StartingSurfaceDrawer {
    static final String TAG = StartingSurfaceDrawer.class.getSimpleName();
    static final boolean DEBUG_SPLASH_SCREEN = false;
    static final boolean DEBUG_TASK_SNAPSHOT = false;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    final ShellExecutor mMainExecutor;
    private final SplashscreenContentDrawer mSplashscreenContentDrawer;
    private final IconAnimationFinishListener mIconAnimationFinishListener =
            new IconAnimationFinishListener();

    // TODO(b/131727939) remove this when clearing ActivityRecord
    private static final int REMOVE_WHEN_TIMEOUT = 2000;

    public StartingSurfaceDrawer(Context context, ShellExecutor mainExecutor) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mMainExecutor = mainExecutor;

        final int maxIconAnimDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.max_starting_window_intro_icon_anim_duration);
        mSplashscreenContentDrawer = new SplashscreenContentDrawer(mContext, maxIconAnimDuration);
    }

    private final SparseArray<StartingWindowRecord> mStartingWindowRecords = new SparseArray<>();

    /** Obtain proper context for showing splash screen on the provided display. */
    private Context getDisplayContext(Context context, int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            // The default context fits.
            return context;
        }

        final Display targetDisplay = mDisplayManager.getDisplay(displayId);
        if (targetDisplay == null) {
            // Failed to obtain the non-default display where splash screen should be shown,
            // lets not show at all.
            return null;
        }

        return context.createDisplayContext(targetDisplay);
    }

    private static class PreferredStartingTypeHelper {
        private static final int STARTING_TYPE_NO = 0;
        private static final int STARTING_TYPE_SPLASH_SCREEN = 1;
        private static final int STARTING_TYPE_SNAPSHOT = 2;

        TaskSnapshot mSnapshot;
        int mPreferredType;

        PreferredStartingTypeHelper(StartingWindowInfo taskInfo) {
            final int parameter = taskInfo.startingWindowTypeParameter;
            final boolean newTask = (parameter & TYPE_PARAMETER_NEW_TASK) != 0;
            final boolean taskSwitch = (parameter & TYPE_PARAMETER_TASK_SWITCH) != 0;
            final boolean processRunning = (parameter & TYPE_PARAMETER_PROCESS_RUNNING) != 0;
            final boolean allowTaskSnapshot = (parameter & TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT) != 0;
            final boolean activityCreated = (parameter & TYPE_PARAMETER_ACTIVITY_CREATED) != 0;
            mPreferredType = preferredStartingWindowType(taskInfo, newTask, taskSwitch,
                    processRunning, allowTaskSnapshot, activityCreated);
        }

        // reference from ActivityRecord#getStartingWindowType
        private int preferredStartingWindowType(StartingWindowInfo windowInfo,
                boolean newTask, boolean taskSwitch, boolean processRunning,
                boolean allowTaskSnapshot, boolean activityCreated) {
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "preferredStartingWindowType newTask " + newTask
                        + " taskSwitch " + taskSwitch
                        + " processRunning " + processRunning
                        + " allowTaskSnapshot " + allowTaskSnapshot
                        + " activityCreated " + activityCreated);
            }

            if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
                return STARTING_TYPE_SPLASH_SCREEN;
            } else if (taskSwitch && allowTaskSnapshot) {
                final TaskSnapshot snapshot = getTaskSnapshot(windowInfo.taskInfo.taskId);
                if (isSnapshotCompatible(windowInfo, snapshot)) {
                    return STARTING_TYPE_SNAPSHOT;
                }
                if (windowInfo.taskInfo.topActivityType != ACTIVITY_TYPE_HOME) {
                    return STARTING_TYPE_SPLASH_SCREEN;
                }
                return STARTING_TYPE_NO;
            } else {
                return STARTING_TYPE_NO;
            }
        }

        /**
         * Returns {@code true} if the task snapshot is compatible with this activity (at least the
         * rotation must be the same).
         */
        private boolean isSnapshotCompatible(StartingWindowInfo windowInfo, TaskSnapshot snapshot) {
            if (snapshot == null) {
                if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                    Slog.d(TAG, "isSnapshotCompatible no snapshot " + windowInfo.taskInfo.taskId);
                }
                return false;
            }

            final int taskRotation = windowInfo.taskInfo.configuration
                    .windowConfiguration.getRotation();
            final int snapshotRotation = snapshot.getRotation();
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "isSnapshotCompatible rotation " + taskRotation
                        + " snapshot " + snapshotRotation);
            }
            return taskRotation == snapshotRotation;
        }

        private TaskSnapshot getTaskSnapshot(int taskId) {
            if (mSnapshot != null) {
                return mSnapshot;
            }
            try {
                mSnapshot = ActivityTaskManager.getService().getTaskSnapshot(taskId,
                        false/* isLowResolution */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get snapshot for task: " + taskId + ", from: " + e);
                return null;
            }
            return mSnapshot;
        }
    }

    /**
     * Called when a task need a starting window.
     */
    public void addStartingWindow(StartingWindowInfo windowInfo, IBinder appToken) {
        final PreferredStartingTypeHelper helper =
                new PreferredStartingTypeHelper(windowInfo);
        final RunningTaskInfo runningTaskInfo = windowInfo.taskInfo;
        if (helper.mPreferredType == PreferredStartingTypeHelper.STARTING_TYPE_SPLASH_SCREEN) {
            addSplashScreenStartingWindow(runningTaskInfo, appToken);
        } else if (helper.mPreferredType == PreferredStartingTypeHelper.STARTING_TYPE_SNAPSHOT) {
            final TaskSnapshot snapshot = helper.mSnapshot;
            makeTaskSnapshotWindow(windowInfo, appToken, snapshot);
        }
        // If prefer don't show, then don't show!
    }

    private void addSplashScreenStartingWindow(RunningTaskInfo taskInfo, IBinder appToken) {
        final ActivityInfo activityInfo = taskInfo.topActivityInfo;
        if (activityInfo == null) {
            return;
        }
        final int displayId = taskInfo.displayId;
        if (activityInfo.packageName == null) {
            return;
        }

        CharSequence nonLocalizedLabel = activityInfo.nonLocalizedLabel;
        int labelRes = activityInfo.labelRes;
        if (activityInfo.nonLocalizedLabel == null && activityInfo.labelRes == 0) {
            ApplicationInfo app = activityInfo.applicationInfo;
            nonLocalizedLabel = app.nonLocalizedLabel;
            labelRes = app.labelRes;
        }

        Context context = mContext;
        int theme = activityInfo.getThemeResource();
        if (theme == 0) {
            // replace with the default theme if the application didn't set
            theme = com.android.internal.R.style.Theme_DeviceDefault_DayNight;
        }
        if (DEBUG_SPLASH_SCREEN) {
            Slog.d(TAG, "addSplashScreen " + activityInfo.packageName
                    + ": nonLocalizedLabel=" + nonLocalizedLabel + " theme="
                    + Integer.toHexString(theme) + " task= " + taskInfo.taskId);
        }

        // Obtain proper context to launch on the right display.
        final Context displayContext = getDisplayContext(context, displayId);
        if (displayContext == null) {
            // Can't show splash screen on requested display, so skip showing at all.
            return;
        }
        context = displayContext;
        if (theme != context.getThemeResId() || labelRes != 0) {
            try {
                context = context.createPackageContext(
                        activityInfo.packageName, CONTEXT_RESTRICTED);
                context.setTheme(theme);
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore
            }
        }

        final Configuration taskConfig = taskInfo.getConfiguration();
        if (taskConfig != null && !taskConfig.equals(EMPTY)) {
            if (DEBUG_SPLASH_SCREEN) {
                Slog.d(TAG, "addSplashScreen: creating context based"
                        + " on task Configuration " + taskConfig + " for splash screen");
            }
            final Context overrideContext = context.createConfigurationContext(taskConfig);
            overrideContext.setTheme(theme);
            final TypedArray typedArray = overrideContext.obtainStyledAttributes(
                    com.android.internal.R.styleable.Window);
            final int resId = typedArray.getResourceId(R.styleable.Window_windowBackground, 0);
            if (resId != 0 && overrideContext.getDrawable(resId) != null) {
                // We want to use the windowBackground for the override context if it is
                // available, otherwise we use the default one to make sure a themed starting
                // window is displayed for the app.
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.d(TAG, "addSplashScreen: apply overrideConfig"
                            + taskConfig + " to starting window resId=" + resId);
                }
                context = overrideContext;
            }
            typedArray.recycle();
        }

        int windowFlags = 0;
        if ((activityInfo.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
            windowFlags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }

        final boolean[] showWallpaper = new boolean[1];
        final int[] splashscreenContentResId = new int[1];
        getWindowResFromContext(context, a -> {
            splashscreenContentResId[0] =
                    a.getResourceId(R.styleable.Window_windowSplashscreenContent, 0);
            showWallpaper[0] = a.getBoolean(R.styleable.Window_windowShowWallpaper, false);
        });
        if (showWallpaper[0]) {
            windowFlags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }

        final PhoneWindow win = new PhoneWindow(context);
        win.setIsStartingWindow(true);

        CharSequence label = context.getResources().getText(labelRes, null);
        // Only change the accessibility title if the label is localized
        if (label != null) {
            win.setTitle(label, true);
        } else {
            win.setTitle(nonLocalizedLabel, false);
        }

        win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);

        // Assumes it's safe to show starting windows of launched apps while
        // the keyguard is being hidden. This is okay because starting windows never show
        // secret information.
        // TODO(b/113840485): Occluded may not only happen on default display
        if (displayId == DEFAULT_DISPLAY) {
            windowFlags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        }

        // Force the window flags: this is a fake window, so it is not really
        // touchable or focusable by the user.  We also add in the ALT_FOCUSABLE_IM
        // flag because we do know that the next window will take input
        // focus, so we want to get the IME window up on top of us right away.
        win.setFlags(windowFlags
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                windowFlags
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        final int iconRes = activityInfo.getIconResource();
        final int logoRes = activityInfo.getLogoResource();
        win.setDefaultIcon(iconRes);
        win.setDefaultLogo(logoRes);

        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);

        final WindowManager.LayoutParams params = win.getAttributes();
        params.token = appToken;
        params.packageName = activityInfo.packageName;
        params.windowAnimations = win.getWindowStyle().getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        params.privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED;
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        final Resources res = context.getResources();
        final boolean supportsScreen = res != null && (res.getCompatibilityInfo() != null
                && res.getCompatibilityInfo().supportsScreen());
        if (!supportsScreen) {
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
        }

        params.setTitle("Splash Screen " + activityInfo.packageName);
        final SplashScreenView splashScreenView =
                mSplashscreenContentDrawer.makeSplashScreenContentView(win, context, iconRes,
                        splashscreenContentResId[0]);
        if (splashScreenView == null) {
            Slog.w(TAG, "Adding splash screen window for " + activityInfo.packageName + " failed!");
            return;
        }

        final View view = win.getDecorView();

        if (DEBUG_SPLASH_SCREEN) {
            Slog.d(TAG, "Adding splash screen window for "
                    + activityInfo.packageName + " / " + appToken + ": " + view);
        }
        final WindowManager wm = context.getSystemService(WindowManager.class);
        postAddWindow(taskInfo.taskId, appToken, view, wm, params, splashScreenView);
    }

    /**
     * Called when a task need a snapshot starting window.
     */
    private void makeTaskSnapshotWindow(StartingWindowInfo startingWindowInfo,
            IBinder appToken, TaskSnapshot snapshot) {
        final int taskId = startingWindowInfo.taskInfo.taskId;
        final TaskSnapshotWindow surface = TaskSnapshotWindow.create(startingWindowInfo, appToken,
                snapshot, mMainExecutor, () -> removeWindowSynced(taskId) /* clearWindow */);
        mMainExecutor.executeDelayed(() -> removeWindowSynced(taskId), REMOVE_WHEN_TIMEOUT);
        final StartingWindowRecord tView =
                new StartingWindowRecord(null/* decorView */, surface, null /* splashScreenView */);
        mStartingWindowRecords.put(taskId, tView);
    }

    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    public void removeStartingWindow(int taskId) {
        if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
            Slog.d(TAG, "Task start finish, remove starting surface for task " + taskId);
        }
        removeWindowSynced(taskId);
    }

    /**
     * Called when the Task wants to copy the splash screen.
     * @param taskId
     */
    public void copySplashScreenView(int taskId) {
        final StartingWindowRecord preView = mStartingWindowRecords.get(taskId);
        SplashScreenViewParcelable parcelable;
        if (preView != null && preView.mContentView != null
                && preView.mContentView.isCopyable()) {
            if (preView.mContentView.isIconAnimating()) {
                // if animating, wait until animation finish
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.v(TAG, "Copying splash screen view but icon is animating " + taskId);
                }
                mIconAnimationFinishListener.waitingForCopyTask(taskId);
                return;
            } else {
                parcelable = new SplashScreenViewParcelable(preView.mContentView);
            }
        } else {
            parcelable = null;
        }
        if (DEBUG_SPLASH_SCREEN) {
            Slog.v(TAG, "Copying splash screen window view for task: " + taskId
                    + " parcelable? " + parcelable);
        }
        ActivityTaskManager.getInstance().onSplashScreenViewCopyFinished(taskId, parcelable);
    }

    protected void postAddWindow(int taskId, IBinder appToken,
            View view, WindowManager wm, WindowManager.LayoutParams params,
            SplashScreenView splashScreenView) {
        mMainExecutor.execute(() -> {
            boolean shouldSaveView = true;
            try {
                wm.addView(view, params);
            } catch (WindowManager.BadTokenException e) {
                // ignore
                Slog.w(TAG, appToken + " already running, starting window not displayed. "
                        + e.getMessage());
                shouldSaveView = false;
            } catch (RuntimeException e) {
                // don't crash if something else bad happens, for example a
                // failure loading resources because we are loading from an app
                // on external storage that has been unmounted.
                Slog.w(TAG, appToken + " failed creating starting window", e);
                shouldSaveView = false;
            } finally {
                if (view != null && view.getParent() == null) {
                    Slog.w(TAG, "view not successfully added to wm, removing view");
                    wm.removeViewImmediate(view);
                    shouldSaveView = false;
                }
            }
            if (shouldSaveView) {
                removeWindowSynced(taskId);
                mMainExecutor.executeDelayed(() -> removeWindowSynced(taskId), REMOVE_WHEN_TIMEOUT);
                final StartingWindowRecord tView = new StartingWindowRecord(view,
                        null /* TaskSnapshotWindow */, splashScreenView);
                splashScreenView.startIntroAnimation(
                        mIconAnimationFinishListener.makeListener(taskId));
                mStartingWindowRecords.put(taskId, tView);
            }
        });
    }

    private class IconAnimationFinishListener {
        private final SparseArray<TaskListener> mListeners = new SparseArray<>();

        private class TaskListener implements Runnable {
            private int mTargetTaskId;
            private boolean mWaitingForCopy;
            private boolean mWaitingForRemove;
            @Override
            public void run() {
                if (mWaitingForCopy) {
                    if (DEBUG_SPLASH_SCREEN) {
                        Slog.v(TAG, "Icon animation finish and waiting for copy at task "
                                + mTargetTaskId);
                    }
                    copySplashScreenView(mTargetTaskId);
                }
                if (mWaitingForRemove) {
                    if (DEBUG_SPLASH_SCREEN) {
                        Slog.v(TAG, "Icon animation finish and waiting for remove at task "
                                + mTargetTaskId);
                    }
                    mMainExecutor.execute(() -> removeWindowSynced(mTargetTaskId));
                }
                mListeners.remove(mTargetTaskId);
            }
        }

        private Runnable makeListener(int taskId) {
            final TaskListener listener = new TaskListener();
            listener.mTargetTaskId = taskId;
            mListeners.put(taskId, listener);
            return listener;
        }

        private void waitingForCopyTask(int taskId) {
            if (mListeners.contains(taskId)) {
                mListeners.get(taskId).mWaitingForCopy = true;
            }
        }

        private void waitingForRemove(int taskId) {
            if (mListeners.contains(taskId)) {
                mListeners.get(taskId).mWaitingForRemove = true;
            }
        }
    }

    protected void removeWindowSynced(int taskId) {
        final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
        if (record != null) {
            if (record.mDecorView != null) {
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.v(TAG, "Removing splash screen window for task: " + taskId);
                }
                if (record.mContentView != null && record.mContentView.isIconAnimating()) {
                    // do not remove until the animation is finish
                    mIconAnimationFinishListener.waitingForRemove(taskId);
                    return;
                }
                final WindowManager wm = record.mDecorView.getContext()
                        .getSystemService(WindowManager.class);
                wm.removeView(record.mDecorView);
            }
            if (record.mTaskSnapshotWindow != null) {
                if (DEBUG_TASK_SNAPSHOT) {
                    Slog.v(TAG, "Removing task snapshot window for " + taskId);
                }
                record.mTaskSnapshotWindow.remove();
            }
            mStartingWindowRecords.remove(taskId);
        }
    }

    private void getWindowResFromContext(Context ctx, Consumer<TypedArray> consumer) {
        final TypedArray a = ctx.obtainStyledAttributes(R.styleable.Window);
        consumer.accept(a);
        a.recycle();
    }

    /**
     * Record the view or surface for a starting window.
     */
    private static class StartingWindowRecord {
        private final View mDecorView;
        private final TaskSnapshotWindow mTaskSnapshotWindow;
        private final SplashScreenView mContentView;

        StartingWindowRecord(View decorView, TaskSnapshotWindow taskSnapshotWindow,
                SplashScreenView splashScreenView) {
            mDecorView = decorView;
            mTaskSnapshotWindow = taskSnapshotWindow;
            mContentView = splashScreenView;
        }
    }
}
