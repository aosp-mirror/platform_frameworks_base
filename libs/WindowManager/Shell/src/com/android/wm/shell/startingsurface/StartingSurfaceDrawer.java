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

import static android.content.Context.CONTEXT_RESTRICTED;
import static android.content.res.Configuration.EMPTY;
import static android.view.Display.DEFAULT_DISPLAY;

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
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.StartingWindowInfo;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;
import com.android.wm.shell.common.ShellExecutor;

import java.util.function.Consumer;

/**
 * A class which able to draw splash screen or snapshot as the starting window for a task.
 * @hide
 */
public class StartingSurfaceDrawer {
    static final String TAG = StartingSurfaceDrawer.class.getSimpleName();
    static final boolean DEBUG_SPLASH_SCREEN = StartingWindowController.DEBUG_SPLASH_SCREEN;
    static final boolean DEBUG_TASK_SNAPSHOT = StartingWindowController.DEBUG_TASK_SNAPSHOT;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    final ShellExecutor mMainExecutor;
    private final SplashscreenContentDrawer mSplashscreenContentDrawer;

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

    /**
     * Called when a task need a splash screen starting window.
     */
    public void addSplashScreenStartingWindow(StartingWindowInfo windowInfo, IBinder appToken) {
        final RunningTaskInfo taskInfo = windowInfo.taskInfo;
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
        // replace with the default theme if the application didn't set
        final int theme = windowInfo.splashScreenThemeResId != 0
                ? windowInfo.splashScreenThemeResId
                : activityInfo.getThemeResource() != 0 ? activityInfo.getThemeResource()
                        : com.android.internal.R.style.Theme_DeviceDefault_DayNight;
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
    void makeTaskSnapshotWindow(StartingWindowInfo startingWindowInfo, IBinder appToken,
            TaskSnapshot snapshot) {
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
            parcelable = new SplashScreenViewParcelable(preView.mContentView);
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
                splashScreenView.startIntroAnimation();
                mStartingWindowRecords.put(taskId, tView);
            }
        });
    }

    protected void removeWindowSynced(int taskId) {
        final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
        if (record != null) {
            if (record.mDecorView != null) {
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.v(TAG, "Removing splash screen window for task: " + taskId);
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
