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

package com.android.systemui.wmshell;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;

import android.animation.AnimationHandler;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.dagger.WMSingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.FullscreenTaskListener;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellCommandHandler;
import com.android.wm.shell.ShellCommandHandlerImpl;
import com.android.wm.shell.ShellInit;
import com.android.wm.shell.ShellInitImpl;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewFactory;
import com.android.wm.shell.TaskViewFactoryController;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ChoreographerSfVsync;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * Provides basic dependencies from {@link com.android.wm.shell}, the dependencies declared here
 * should be shared among different branches of SystemUI.
 */
@Module
public abstract class WMShellBaseModule {

    private static final boolean ENABLE_SHELL_MAIN_THREAD = false;

    //
    // Shell Concurrency - Components used for managing threading in the Shell and SysUI
    //

    /**
     * Provide a SysUI main-thread Executor.
     */
    @WMSingleton
    @Provides
    @Main
    public static ShellExecutor provideSysUIMainExecutor(@Main Handler sysuiMainHandler) {
        return new HandlerExecutor(sysuiMainHandler);
    }

    /**
     * Shell main-thread Handler, don't use this unless really necessary (ie. need to dedupe
     * multiple types of messages, etc.)
     */
    @WMSingleton
    @Provides
    @ShellMainThread
    public static Handler provideShellMainHandler(@Main Handler sysuiMainHandler) {
        if (ENABLE_SHELL_MAIN_THREAD) {
             HandlerThread mainThread = new HandlerThread("wmshell.main");
             mainThread.start();
             return mainThread.getThreadHandler();
        }
        return sysuiMainHandler;
    }

    /**
     * Provide a Shell main-thread Executor.
     */
    @WMSingleton
    @Provides
    @ShellMainThread
    public static ShellExecutor provideShellMainExecutor(@ShellMainThread Handler mainHandler,
            @Main ShellExecutor sysuiMainExecutor) {
        if (ENABLE_SHELL_MAIN_THREAD) {
            return new HandlerExecutor(mainHandler);
        }
        return sysuiMainExecutor;
    }

    /**
     * Provide a Shell animation-thread Executor.
     */
    @WMSingleton
    @Provides
    @ShellAnimationThread
    public static ShellExecutor provideShellAnimationExecutor() {
         HandlerThread shellAnimationThread = new HandlerThread("wmshell.anim",
                 THREAD_PRIORITY_DISPLAY);
         shellAnimationThread.start();
         return new HandlerExecutor(shellAnimationThread.getThreadHandler());
    }

    /**
     * Provide a Shell main-thread AnimationHandler.  The AnimationHandler can be set on
     * {@link android.animation.ValueAnimator}s and will ensure that the animation will run on
     * the Shell main-thread with the SF vsync.
     */
    @WMSingleton
    @Provides
    @ChoreographerSfVsync
    public static AnimationHandler provideShellMainExecutorSfVsyncAnimationHandler(
            @ShellMainThread ShellExecutor mainExecutor) {
        try {
            AnimationHandler handler = new AnimationHandler();
            mainExecutor.executeBlocking(() -> {
                // This is called on the animation thread since it calls
                // Choreographer.getSfInstance() which returns a thread-local Choreographer instance
                // that uses the SF vsync
                handler.setProvider(new SfVsyncFrameCallbackProvider());
            });
            return handler;
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to initialize SfVsync animation handler in 1s", e);
        }
    }

    @WMSingleton
    @Provides
    static ShellInit provideShellInit(DisplayImeController displayImeController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreen> legacySplitScreenOptional,
            Optional<SplitScreen> splitScreenOptional,
            Optional<AppPairs> appPairsOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor) {
        return ShellInitImpl.create(displayImeController,
                dragAndDropController,
                shellTaskOrganizer,
                legacySplitScreenOptional,
                splitScreenOptional,
                appPairsOptional,
                fullscreenTaskListener,
                transitions,
                mainExecutor);
    }

    /**
     * Note, this is only optional because we currently pass this to the SysUI component scope and
     * for non-primary users, we may inject a null-optional for that dependency.
     */
    @WMSingleton
    @Provides
    static Optional<ShellCommandHandler> provideShellCommandHandler(
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreen> legacySplitScreenOptional,
            Optional<SplitScreen> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHanded> oneHandedOptional,
            Optional<HideDisplayCutout> hideDisplayCutout,
            Optional<AppPairs> appPairsOptional,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.of(ShellCommandHandlerImpl.create(shellTaskOrganizer,
                legacySplitScreenOptional, splitScreenOptional, pipOptional, oneHandedOptional,
                hideDisplayCutout, appPairsOptional, mainExecutor));
    }

    @WMSingleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @WMSingleton
    @Provides
    static DisplayController provideDisplayController(Context context,
            IWindowManager wmService, @ShellMainThread ShellExecutor mainExecutor) {
        return new DisplayController(context, wmService, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DragAndDropController provideDragAndDropController(Context context,
            DisplayController displayController) {
        return new DragAndDropController(context, displayController);
    }

    @WMSingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
    }

    @WMSingleton
    @Provides
    static WindowManagerShellWrapper provideWindowManagerShellWrapper(
            @ShellMainThread ShellExecutor mainExecutor) {
        return new WindowManagerShellWrapper(mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            IActivityManager activityManager,
            PipTouchHandler pipTouchHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipAppOpsListener(context, pipTouchHandler.getMotionHelper(), mainExecutor);
    }

    // Needs handler for registering broadcast receivers
    @WMSingleton
    @Provides
    static PipMediaController providePipMediaController(Context context,
            @ShellMainThread Handler mainHandler) {
        return new PipMediaController(context, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipUiEventLogger providePipUiEventLogger(UiEventLogger uiEventLogger,
            PackageManager packageManager) {
        return new PipUiEventLogger(uiEventLogger, packageManager);
    }

    @WMSingleton
    @Provides
    static PipSurfaceTransactionHelper providePipSurfaceTransactionHelper(Context context) {
        return new PipSurfaceTransactionHelper(context);
    }

    @WMSingleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    @WMSingleton
    @Provides
    static SyncTransactionQueue provideSyncTransactionQueue(TransactionPool pool,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new SyncTransactionQueue(pool, mainExecutor);
    }

    @WMSingleton
    @Provides
    static ShellTaskOrganizer provideShellTaskOrganizer(@ShellMainThread ShellExecutor mainExecutor,
            Context context) {
        return new ShellTaskOrganizer(mainExecutor, context);
    }

    @WMSingleton
    @Provides
    static RootTaskDisplayAreaOrganizer provideRootTaskDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor, Context context) {
        return new RootTaskDisplayAreaOrganizer(mainExecutor, context);
    }

    // We currently dedupe multiple messages, so we use the shell main handler directly
    @WMSingleton
    @Provides
    static TaskStackListenerImpl providerTaskStackListenerImpl(
            @ShellMainThread Handler mainHandler) {
        return new TaskStackListenerImpl(mainHandler);
    }

    @BindsOptionalOf
    abstract LegacySplitScreen optionalLegacySplitScreen();

    @WMSingleton
    @Provides
    static Optional<SplitScreen> provideSplitScreen(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        if (ActivityTaskManager.supportsSplitScreenMultiWindow(context)) {
            return Optional.of(new SplitScreenController(shellTaskOrganizer, syncQueue, context,
                    rootTaskDisplayAreaOrganizer));
        } else {
            return Optional.empty();
        }
    }

    @BindsOptionalOf
    abstract AppPairs optionalAppPairs();

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static Optional<Bubbles> provideBubbles(Context context,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            UiEventLogger uiEventLogger,
            ShellTaskOrganizer organizer,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return Optional.of(BubbleController.create(context, null /* synchronizer */,
                floatingContentCoordinator, statusBarService, windowManager,
                windowManagerShellWrapper, launcherApps, uiEventLogger, organizer,
                mainExecutor, mainHandler));
    }

    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    static Optional<OneHanded> provideOneHandedController(Context context,
            DisplayController displayController, TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return Optional.ofNullable(OneHandedController.create(context, displayController,
                taskStackListener, uiEventLogger, mainExecutor, mainHandler));
    }

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutout> provideHideDisplayCutoutController(Context context,
            DisplayController displayController, @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(
                HideDisplayCutoutController.create(context, displayController, mainExecutor));
    }

    @WMSingleton
    @Provides
    static Optional<TaskViewFactory> provideTaskViewFactory(ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.of(new TaskViewFactoryController(shellTaskOrganizer, mainExecutor)
                .getTaskViewFactory());
    }

    @WMSingleton
    @Provides
    static FullscreenTaskListener provideFullscreenTaskListener(
            SyncTransactionQueue syncQueue) {
        return new FullscreenTaskListener(syncQueue);
    }

    @WMSingleton
    @Provides
    static Transitions provideTransitions(ShellTaskOrganizer organizer, TransactionPool pool,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellAnimationThread ShellExecutor animExecutor) {
        return new Transitions(organizer, pool, mainExecutor, animExecutor);
    }
}
