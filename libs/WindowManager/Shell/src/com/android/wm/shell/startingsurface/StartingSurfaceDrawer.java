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
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.StartingWindowInfo;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.policy.PhoneWindow;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

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
    private final ShellExecutor mSplashScreenExecutor;
    private final SplashscreenContentDrawer mSplashscreenContentDrawer;

    public StartingSurfaceDrawer(Context context, ShellExecutor splashScreenExecutor,
            TransactionPool pool) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mSplashScreenExecutor = splashScreenExecutor;
        final int maxAnimatableIconDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.max_starting_window_intro_icon_anim_duration);
        final int iconExitAnimDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.starting_window_icon_exit_anim_duration);
        final int appRevealAnimDuration = context.getResources().getInteger(
                com.android.wm.shell.R.integer.starting_window_app_reveal_anim_duration);
        mSplashscreenContentDrawer = new SplashscreenContentDrawer(mContext,
                maxAnimatableIconDuration, iconExitAnimDuration, appRevealAnimDuration, pool);
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
                context = context.createPackageContextAsUser(activityInfo.packageName,
                        CONTEXT_RESTRICTED, UserHandle.of(taskInfo.userId));
                context.setTheme(theme);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Failed creating package context with package name "
                        + activityInfo.packageName + " for user " + taskInfo.userId, e);
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
        windowFlags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

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
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
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
        if (displayId == DEFAULT_DISPLAY && windowInfo.isKeyguardOccluded) {
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
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        params.format = PixelFormat.RGBA_8888;

        final Resources res = context.getResources();
        final boolean supportsScreen = res != null && (res.getCompatibilityInfo() != null
                && res.getCompatibilityInfo().supportsScreen());
        if (!supportsScreen) {
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
        }

        params.setTitle("Splash Screen " + activityInfo.packageName);

        // TODO(b/173975965) tracking performance
        final int taskId = taskInfo.taskId;
        SplashScreenView sView = null;
        try {
            final View view = win.getDecorView();
            final WindowManager wm = mContext.getSystemService(WindowManager.class);
            // splash screen content will be deprecated after S.
            sView = SplashscreenContentDrawer.makeSplashscreenContent(
                    context, splashscreenContentResId[0]);
            final boolean splashscreenContentCompatible = sView != null;
            if (splashscreenContentCompatible) {
                win.setContentView(sView);
            } else {
                sView = mSplashscreenContentDrawer.makeSplashScreenContentView(context, iconRes);
                win.setContentView(sView);
                sView.cacheRootWindow(win);
            }
            postAddWindow(taskId, appToken, view, wm, params);
        } catch (RuntimeException e) {
            // don't crash if something else bad happens, for example a
            // failure loading resources because we are loading from an app
            // on external storage that has been unmounted.
            Slog.w(TAG, " failed creating starting window at taskId: " + taskId, e);
            sView = null;
        } finally {
            final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
            if (record != null) {
                record.setSplashScreenView(sView);
            }
        }
    }

    /**
     * Called when a task need a snapshot starting window.
     */
    void makeTaskSnapshotWindow(StartingWindowInfo startingWindowInfo, IBinder appToken,
            TaskSnapshot snapshot) {
        final int taskId = startingWindowInfo.taskInfo.taskId;
        final TaskSnapshotWindow surface = TaskSnapshotWindow.create(startingWindowInfo, appToken,
                snapshot, mSplashScreenExecutor, () -> removeWindowNoAnimate(taskId));
        final StartingWindowRecord tView = new StartingWindowRecord(null/* decorView */, surface);
        mStartingWindowRecords.put(taskId, tView);
    }

    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
            Slog.d(TAG, "Task start finish, remove starting surface for task " + taskId);
        }
        removeWindowSynced(taskId, leash, frame, playRevealAnimation);
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

    protected void postAddWindow(int taskId, IBinder appToken, View view, WindowManager wm,
            WindowManager.LayoutParams params) {
        boolean shouldSaveView = true;
        try {
            wm.addView(view, params);
        } catch (WindowManager.BadTokenException e) {
            // ignore
            Slog.w(TAG, appToken + " already running, starting window not displayed. "
                    + e.getMessage());
            shouldSaveView = false;
        } finally {
            if (view != null && view.getParent() == null) {
                Slog.w(TAG, "view not successfully added to wm, removing view");
                wm.removeViewImmediate(view);
                shouldSaveView = false;
            }
        }
        if (shouldSaveView) {
            removeWindowNoAnimate(taskId);
            saveSplashScreenRecord(taskId, view);
        }
    }

    private void saveSplashScreenRecord(int taskId, View view) {
        final StartingWindowRecord tView = new StartingWindowRecord(view,
                null/* TaskSnapshotWindow */);
        mStartingWindowRecords.put(taskId, tView);
    }

    private void removeWindowNoAnimate(int taskId) {
        removeWindowSynced(taskId, null, null, false);
    }

    protected void removeWindowSynced(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
        if (record != null) {
            if (record.mDecorView != null) {
                if (DEBUG_SPLASH_SCREEN) {
                    Slog.v(TAG, "Removing splash screen window for task: " + taskId);
                }
                if (record.mContentView != null) {
                    final HandleExitFinish exitFinish = new HandleExitFinish(record.mDecorView);
                    if (leash != null || playRevealAnimation) {
                        mSplashscreenContentDrawer.applyExitAnimation(record.mContentView,
                                leash, frame, record.isEarlyExit(), exitFinish);
                    } else {
                        // the SplashScreenView has been copied to client, skip default exit
                        // animation
                        exitFinish.run();
                    }
                }
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

    private static class HandleExitFinish implements Runnable {
        private View mDecorView;

        HandleExitFinish(View decorView) {
            mDecorView = decorView;
        }

        @Override
        public void run() {
            if (mDecorView == null) {
                return;
            }
            final WindowManager wm = mDecorView.getContext().getSystemService(WindowManager.class);
            if (wm != null) {
                wm.removeView(mDecorView);
            }
            mDecorView = null;
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
        private static final long EARLY_START_MINIMUM_TIME_MS = 250;
        private final View mDecorView;
        private final TaskSnapshotWindow mTaskSnapshotWindow;
        private SplashScreenView mContentView;
        private boolean mSetSplashScreen;
        private long mContentCreateTime;

        StartingWindowRecord(View decorView, TaskSnapshotWindow taskSnapshotWindow) {
            mDecorView = decorView;
            mTaskSnapshotWindow = taskSnapshotWindow;
        }

        private void setSplashScreenView(SplashScreenView splashScreenView) {
            if (mSetSplashScreen) {
                return;
            }
            mContentView = splashScreenView;
            mContentCreateTime = SystemClock.uptimeMillis();
            mSetSplashScreen = true;
        }

        boolean isEarlyExit() {
            return SystemClock.uptimeMillis() - mContentCreateTime < EARLY_START_MINIMUM_TIME_MS;
        }
    }
}
