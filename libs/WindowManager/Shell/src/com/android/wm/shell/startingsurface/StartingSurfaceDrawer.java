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
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Choreographer.CALLBACK_INSETS_ANIMATION;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT;

import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.TaskInfo;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.StartingWindowInfo;
import android.window.StartingWindowInfo.StartingWindowType;
import android.window.TaskSnapshot;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellSplashscreenThread;

import java.util.function.Supplier;

/**
 * A class which able to draw splash screen or snapshot as the starting window for a task.
 *
 * In order to speed up, there will use two threads to creating a splash screen in parallel.
 * Right now we are still using PhoneWindow to create splash screen window, so the view is added to
 * the ViewRootImpl, and those view won't be draw immediately because the ViewRootImpl will call
 * scheduleTraversal to register a callback from Choreographer, so the drawing result of the view
 * can synchronize on each frame.
 *
 * The bad thing is that we cannot decide when would Choreographer#doFrame happen, and drawing
 * the AdaptiveIconDrawable object can be time consuming, so we use the splash-screen background
 * thread to draw the AdaptiveIconDrawable object to a Bitmap and cache it to a BitmapShader after
 * the SplashScreenView just created, once we get the BitmapShader then the #draw call can be very
 * quickly.
 *
 * So basically we are using the spare time to prepare the SplashScreenView while splash screen
 * thread is waiting for
 * 1. WindowManager#addView(binder call to WM),
 * 2. Choreographer#doFrame happen(uncertain time for next frame, depends on device),
 * 3. Session#relayout(another binder call to WM which under Choreographer#doFrame, but will
 * always happen before #draw).
 * Because above steps are running on splash-screen thread, so pre-draw the BitmapShader on
 * splash-screen background tread can make they execute in parallel, which ensure it is faster then
 * to draw the AdaptiveIconDrawable when receive callback from Choreographer#doFrame.
 *
 * Here is the sequence to compare the difference between using single and two thread.
 *
 * Single thread:
 * => makeSplashScreenContentView -> WM#addView .. waiting for Choreographer#doFrame -> relayout
 * -> draw -> AdaptiveIconDrawable#draw
 *
 * Two threads:
 * => makeSplashScreenContentView -> cachePaint(=AdaptiveIconDrawable#draw)
 * => WM#addView -> .. waiting for Choreographer#doFrame -> relayout -> draw -> (draw the Paint
 * directly).
 */
@ShellSplashscreenThread
public class StartingSurfaceDrawer {
    static final String TAG = StartingSurfaceDrawer.class.getSimpleName();
    static final boolean DEBUG_SPLASH_SCREEN = StartingWindowController.DEBUG_SPLASH_SCREEN;
    static final boolean DEBUG_TASK_SNAPSHOT = StartingWindowController.DEBUG_TASK_SNAPSHOT;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final ShellExecutor mSplashScreenExecutor;
    @VisibleForTesting
    final SplashscreenContentDrawer mSplashscreenContentDrawer;
    private Choreographer mChoreographer;
    private final WindowManagerGlobal mWindowManagerGlobal;
    private StartingSurface.SysuiProxy mSysuiProxy;

    /**
     * @param splashScreenExecutor The thread used to control add and remove starting window.
     */
    public StartingSurfaceDrawer(Context context, ShellExecutor splashScreenExecutor,
            TransactionPool pool) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mSplashScreenExecutor = splashScreenExecutor;
        mSplashscreenContentDrawer = new SplashscreenContentDrawer(mContext, pool);
        mSplashScreenExecutor.execute(() -> mChoreographer = Choreographer.getInstance());
        mWindowManagerGlobal = WindowManagerGlobal.getInstance();
        mDisplayManager.getDisplay(DEFAULT_DISPLAY);
    }

    private final SparseArray<StartingWindowRecord> mStartingWindowRecords = new SparseArray<>();

    /**
     * Records of {@link SurfaceControlViewHost} where the splash screen icon animation is
     * rendered and that have not yet been removed by their client.
     */
    private final SparseArray<SurfaceControlViewHost> mAnimatedSplashScreenSurfaceHosts =
            new SparseArray<>(1);

    private Display getDisplay(int displayId) {
        return mDisplayManager.getDisplay(displayId);
    }

    int getSplashScreenTheme(int splashScreenThemeResId, ActivityInfo activityInfo) {
        return splashScreenThemeResId != 0
                ? splashScreenThemeResId
                : activityInfo.getThemeResource() != 0 ? activityInfo.getThemeResource()
                        : com.android.internal.R.style.Theme_DeviceDefault_DayNight;
    }

    void setSysuiProxy(StartingSurface.SysuiProxy sysuiProxy) {
        mSysuiProxy = sysuiProxy;
    }

    /**
     * Called when a task need a splash screen starting window.
     *
     * @param suggestType The suggestion type to draw the splash screen.
     */
    void addSplashScreenStartingWindow(StartingWindowInfo windowInfo, IBinder appToken,
            @StartingWindowType int suggestType) {
        final RunningTaskInfo taskInfo = windowInfo.taskInfo;
        final ActivityInfo activityInfo = windowInfo.targetActivityInfo != null
                ? windowInfo.targetActivityInfo
                : taskInfo.topActivityInfo;
        if (activityInfo == null || activityInfo.packageName == null) {
            return;
        }

        final int displayId = taskInfo.displayId;
        final int taskId = taskInfo.taskId;

        // replace with the default theme if the application didn't set
        final int theme = getSplashScreenTheme(windowInfo.splashScreenThemeResId, activityInfo);
        if (DEBUG_SPLASH_SCREEN) {
            Slog.d(TAG, "addSplashScreen " + activityInfo.packageName
                    + " theme=" + Integer.toHexString(theme) + " task=" + taskInfo.taskId
                    + " suggestType=" + suggestType);
        }
        final Display display = getDisplay(displayId);
        if (display == null) {
            // Can't show splash screen on requested display, so skip showing at all.
            return;
        }
        Context context = displayId == DEFAULT_DISPLAY
                ? mContext : mContext.createDisplayContext(display);
        if (context == null) {
            return;
        }
        if (theme != context.getThemeResId()) {
            try {
                context = context.createPackageContextAsUser(activityInfo.packageName,
                        CONTEXT_RESTRICTED, UserHandle.of(taskInfo.userId));
                context.setTheme(theme);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Failed creating package context with package name "
                        + activityInfo.packageName + " for user " + taskInfo.userId, e);
                return;
            }
        }

        final Configuration taskConfig = taskInfo.getConfiguration();
        if (taskConfig.diffPublicOnly(context.getResources().getConfiguration()) != 0) {
            if (DEBUG_SPLASH_SCREEN) {
                Slog.d(TAG, "addSplashScreen: creating context based"
                        + " on task Configuration " + taskConfig + " for splash screen");
            }
            final Context overrideContext = context.createConfigurationContext(taskConfig);
            overrideContext.setTheme(theme);
            final TypedArray typedArray = overrideContext.obtainStyledAttributes(
                    com.android.internal.R.styleable.Window);
            final int resId = typedArray.getResourceId(R.styleable.Window_windowBackground, 0);
            try {
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
            } catch (Resources.NotFoundException e) {
                Slog.w(TAG, "failed creating starting window for overrideConfig at taskId: "
                        + taskId, e);
                return;
            }
            typedArray.recycle();
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
        params.setFitInsetsSides(0);
        params.setFitInsetsTypes(0);
        params.format = PixelFormat.TRANSLUCENT;
        int windowFlags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
        final TypedArray a = context.obtainStyledAttributes(R.styleable.Window);
        if (a.getBoolean(R.styleable.Window_windowShowWallpaper, false)) {
            windowFlags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
        }
        if (suggestType == STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN) {
            if (a.getBoolean(R.styleable.Window_windowDrawsSystemBarBackgrounds, false)) {
                windowFlags |= WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            }
        } else {
            windowFlags |= WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        }
        params.layoutInDisplayCutoutMode = a.getInt(
                R.styleable.Window_windowLayoutInDisplayCutoutMode,
                params.layoutInDisplayCutoutMode);
        params.windowAnimations = a.getResourceId(R.styleable.Window_windowAnimationStyle, 0);
        a.recycle();

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
        windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        params.flags = windowFlags;
        params.token = appToken;
        params.packageName = activityInfo.packageName;
        params.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

        if (!context.getResources().getCompatibilityInfo().supportsScreen()) {
            params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
        }

        params.setTitle("Splash Screen " + activityInfo.packageName);

        // TODO(b/173975965) tracking performance
        // Prepare the splash screen content view on splash screen worker thread in parallel, so the
        // content view won't be blocked by binder call like addWindow and relayout.
        // 1. Trigger splash screen worker thread to create SplashScreenView before/while
        // Session#addWindow.
        // 2. Synchronize the SplashscreenView to splash screen thread before Choreographer start
        // traversal, which will call Session#relayout on splash screen thread.
        // 3. Pre-draw the BitmapShader if the icon is immobile on splash screen worker thread, at
        // the same time the splash screen thread should be executing Session#relayout. Blocking the
        // traversal -> draw on splash screen thread until the BitmapShader of the icon is ready.

        // Record whether create splash screen view success, notify to current thread after
        // create splash screen view finished.
        final SplashScreenViewSupplier viewSupplier = new SplashScreenViewSupplier();
        final FrameLayout rootLayout = new FrameLayout(
                mSplashscreenContentDrawer.createViewContextWrapper(context));
        rootLayout.setPadding(0, 0, 0, 0);
        rootLayout.setFitsSystemWindows(false);
        final Runnable setViewSynchronized = () -> {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "addSplashScreenView");
            // waiting for setContentView before relayoutWindow
            SplashScreenView contentView = viewSupplier.get();
            final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
            // If record == null, either the starting window added fail or removed already.
            // Do not add this view if the token is mismatch.
            if (record != null && appToken == record.mAppToken) {
                // if view == null then creation of content view was failed.
                if (contentView != null) {
                    try {
                        rootLayout.addView(contentView);
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "failed set content view to starting window "
                                + "at taskId: " + taskId, e);
                        contentView = null;
                    }
                }
                record.setSplashScreenView(contentView);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        };
        if (mSysuiProxy != null) {
            mSysuiProxy.requestTopUi(true, TAG);
        }
        mSplashscreenContentDrawer.createContentView(context, suggestType, activityInfo, taskId,
                viewSupplier::setView, viewSupplier::setUiThreadInitTask);
        try {
            if (addWindow(taskId, appToken, rootLayout, display, params, suggestType)) {
                // We use the splash screen worker thread to create SplashScreenView while adding
                // the window, as otherwise Choreographer#doFrame might be delayed on this thread.
                // And since Choreographer#doFrame won't happen immediately after adding the window,
                // if the view is not added to the PhoneWindow on the first #doFrame, the view will
                // not be rendered on the first frame. So here we need to synchronize the view on
                // the window before first round relayoutWindow, which will happen after insets
                // animation.
                mChoreographer.postCallback(CALLBACK_INSETS_ANIMATION, setViewSynchronized, null);
                // Block until we get the background color.
                final StartingWindowRecord record = mStartingWindowRecords.get(taskId);
                final SplashScreenView contentView = viewSupplier.get();
                record.mBGColor = contentView.getInitBackgroundColor();
            }
        } catch (RuntimeException e) {
            // don't crash if something else bad happens, for example a
            // failure loading resources because we are loading from an app
            // on external storage that has been unmounted.
            Slog.w(TAG, "failed creating starting window at taskId: " + taskId, e);
        }
    }

    int getStartingWindowBackgroundColorForTask(int taskId) {
        final StartingWindowRecord startingWindowRecord = mStartingWindowRecords.get(taskId);
        if (startingWindowRecord == null) {
            return Color.TRANSPARENT;
        }
        return startingWindowRecord.mBGColor;
    }

    private static class SplashScreenViewSupplier implements Supplier<SplashScreenView> {
        private SplashScreenView mView;
        private boolean mIsViewSet;
        private Runnable mUiThreadInitTask;
        void setView(SplashScreenView view) {
            synchronized (this) {
                mView = view;
                mIsViewSet = true;
                notify();
            }
        }

        void setUiThreadInitTask(Runnable initTask) {
            synchronized (this) {
                mUiThreadInitTask = initTask;
            }
        }

        @Override
        public @Nullable SplashScreenView get() {
            synchronized (this) {
                while (!mIsViewSet) {
                    try {
                        wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                if (mUiThreadInitTask != null) {
                    mUiThreadInitTask.run();
                    mUiThreadInitTask = null;
                }
                return mView;
            }
        }
    }

    int estimateTaskBackgroundColor(TaskInfo taskInfo) {
        if (taskInfo.topActivityInfo == null) {
            return Color.TRANSPARENT;
        }
        final ActivityInfo activityInfo = taskInfo.topActivityInfo;
        final String packageName = activityInfo.packageName;
        final int userId = taskInfo.userId;
        final Context windowContext;
        try {
            windowContext = mContext.createPackageContextAsUser(
                    packageName, Context.CONTEXT_RESTRICTED, UserHandle.of(userId));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Failed creating package context with package name "
                    + packageName + " for user " + taskInfo.userId, e);
            return Color.TRANSPARENT;
        }
        try {
            final IPackageManager packageManager = ActivityThread.getPackageManager();
            final String splashScreenThemeName = packageManager.getSplashScreenTheme(packageName,
                    userId);
            final int splashScreenThemeId = splashScreenThemeName != null
                    ? windowContext.getResources().getIdentifier(splashScreenThemeName, null, null)
                    : 0;

            final int theme = getSplashScreenTheme(splashScreenThemeId, activityInfo);

            if (theme != windowContext.getThemeResId()) {
                windowContext.setTheme(theme);
            }
            return mSplashscreenContentDrawer.estimateTaskBackgroundColor(windowContext);
        } catch (RuntimeException | RemoteException e) {
            Slog.w(TAG, "failed get starting window background color at taskId: "
                    + taskInfo.taskId, e);
        }
        return Color.TRANSPARENT;
    }

    /**
     * Called when a task need a snapshot starting window.
     */
    void makeTaskSnapshotWindow(StartingWindowInfo startingWindowInfo, IBinder appToken,
            TaskSnapshot snapshot) {
        final int taskId = startingWindowInfo.taskInfo.taskId;
        // Remove any existing starting window for this task before adding.
        removeWindowNoAnimate(taskId);
        final TaskSnapshotWindow surface = TaskSnapshotWindow.create(startingWindowInfo, appToken,
                snapshot, mSplashScreenExecutor, () -> removeWindowNoAnimate(taskId));
        if (surface == null) {
            return;
        }
        final StartingWindowRecord tView = new StartingWindowRecord(appToken,
                null/* decorView */, surface, STARTING_WINDOW_TYPE_SNAPSHOT);
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
     */
    public void copySplashScreenView(int taskId) {
        final StartingWindowRecord preView = mStartingWindowRecords.get(taskId);
        SplashScreenViewParcelable parcelable;
        SplashScreenView splashScreenView = preView != null ? preView.mContentView : null;
        if (splashScreenView != null && splashScreenView.isCopyable()) {
            parcelable = new SplashScreenViewParcelable(splashScreenView);
            parcelable.setClientCallback(
                    new RemoteCallback((bundle) -> mSplashScreenExecutor.execute(
                            () -> onAppSplashScreenViewRemoved(taskId, false))));
            splashScreenView.onCopied();
            mAnimatedSplashScreenSurfaceHosts.append(taskId, splashScreenView.getSurfaceHost());
        } else {
            parcelable = null;
        }
        if (DEBUG_SPLASH_SCREEN) {
            Slog.v(TAG, "Copying splash screen window view for task: " + taskId
                    + " parcelable: " + parcelable);
        }
        ActivityTaskManager.getInstance().onSplashScreenViewCopyFinished(taskId, parcelable);
    }

    /**
     * Called when the {@link SplashScreenView} is removed from the client Activity view's hierarchy
     * or when the Activity is clean up.
     *
     * @param taskId The Task id on which the splash screen was attached
     */
    public void onAppSplashScreenViewRemoved(int taskId) {
        onAppSplashScreenViewRemoved(taskId, true /* fromServer */);
    }

    /**
     * @param fromServer If true, this means the removal was notified by the server. This is only
     *                   used for debugging purposes.
     * @see #onAppSplashScreenViewRemoved(int)
     */
    private void onAppSplashScreenViewRemoved(int taskId, boolean fromServer) {
        SurfaceControlViewHost viewHost =
                mAnimatedSplashScreenSurfaceHosts.get(taskId);
        if (viewHost == null) {
            return;
        }
        mAnimatedSplashScreenSurfaceHosts.remove(taskId);
        if (DEBUG_SPLASH_SCREEN) {
            String reason = fromServer ? "Server cleaned up" : "App removed";
            Slog.v(TAG, reason + "the splash screen. Releasing SurfaceControlViewHost for task:"
                    + taskId);
        }
        SplashScreenView.releaseIconHost(viewHost);
    }

    protected boolean addWindow(int taskId, IBinder appToken, View view, Display display,
            WindowManager.LayoutParams params, @StartingWindowType int suggestType) {
        boolean shouldSaveView = true;
        final Context context = view.getContext();
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "addRootView");
            mWindowManagerGlobal.addView(view, params, display,
                    null /* parentWindow */, context.getUserId());
        } catch (WindowManager.BadTokenException e) {
            // ignore
            Slog.w(TAG, appToken + " already running, starting window not displayed. "
                    + e.getMessage());
            shouldSaveView = false;
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (view.getParent() == null) {
                Slog.w(TAG, "view not successfully added to wm, removing view");
                mWindowManagerGlobal.removeView(view, true /* immediate */);
                shouldSaveView = false;
            }
        }
        if (shouldSaveView) {
            removeWindowNoAnimate(taskId);
            saveSplashScreenRecord(appToken, taskId, view, suggestType);
        }
        return shouldSaveView;
    }

    private void saveSplashScreenRecord(IBinder appToken, int taskId, View view,
            @StartingWindowType int suggestType) {
        final StartingWindowRecord tView = new StartingWindowRecord(appToken, view,
                null/* TaskSnapshotWindow */, suggestType);
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
                    if (record.mSuggestType == STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN) {
                        removeWindowInner(record.mDecorView, false);
                    } else {
                        if (playRevealAnimation) {
                            mSplashscreenContentDrawer.applyExitAnimation(record.mContentView,
                                    leash, frame,
                                    () -> removeWindowInner(record.mDecorView, true));
                        } else {
                            // the SplashScreenView has been copied to client, hide the view to skip
                            // default exit animation
                            removeWindowInner(record.mDecorView, true);
                        }
                    }
                } else {
                    // shouldn't happen
                    Slog.e(TAG, "Found empty splash screen, remove!");
                    removeWindowInner(record.mDecorView, false);
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

    private void removeWindowInner(View decorView, boolean hideView) {
        if (mSysuiProxy != null) {
            mSysuiProxy.requestTopUi(false, TAG);
        }
        if (hideView) {
            decorView.setVisibility(View.GONE);
        }
        mWindowManagerGlobal.removeView(decorView, false /* immediate */);
    }

    /**
     * Record the view or surface for a starting window.
     */
    private static class StartingWindowRecord {
        private final IBinder mAppToken;
        private final View mDecorView;
        private final TaskSnapshotWindow mTaskSnapshotWindow;
        private SplashScreenView mContentView;
        private boolean mSetSplashScreen;
        private @StartingWindowType int mSuggestType;
        private int mBGColor;

        StartingWindowRecord(IBinder appToken, View decorView,
                TaskSnapshotWindow taskSnapshotWindow, @StartingWindowType int suggestType) {
            mAppToken = appToken;
            mDecorView = decorView;
            mTaskSnapshotWindow = taskSnapshotWindow;
            if (mTaskSnapshotWindow != null) {
                mBGColor = mTaskSnapshotWindow.getBackgroundColor();
            }
            mSuggestType = suggestType;
        }

        private void setSplashScreenView(SplashScreenView splashScreenView) {
            if (mSetSplashScreen) {
                return;
            }
            mContentView = splashScreenView;
            mSetSplashScreen = true;
        }
    }
}
