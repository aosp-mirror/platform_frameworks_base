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

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.dagger.WMComponent;
import com.android.systemui.dagger.WMSingleton;
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
import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.common.annotations.ShellSplashscreenThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipAppOpsListener;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.sizecompatui.SizeCompatUIController;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.transition.ShellTransitions;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * Provides basic dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines *common* dependencies across various SystemUI implementations,
 * dependencies that are device/form factor SystemUI implementation specific should go into their
 * respective modules (ie. {@link WMShellModule} for handheld, {@link TvWMShellModule} for tv, etc.)
 */
@Module(includes = WMShellConcurrencyModule.class)
public abstract class WMShellBaseModule {

    //
    // Internal common - Components used internally by multiple shell features
    //

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
    static ShellTaskOrganizer provideShellTaskOrganizer(@ShellMainThread ShellExecutor mainExecutor,
            Context context, SizeCompatUIController sizeCompatUI) {
        return new ShellTaskOrganizer(mainExecutor, context, sizeCompatUI);
    }

    @WMSingleton
    @Provides
    static SizeCompatUIController provideSizeCompatUIController(Context context,
            DisplayController displayController, DisplayImeController imeController,
            SyncTransactionQueue syncQueue) {
        return new SizeCompatUIController(context, displayController, imeController, syncQueue);
    }

    @WMSingleton
    @Provides
    static SyncTransactionQueue provideSyncTransactionQueue(TransactionPool pool,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new SyncTransactionQueue(pool, mainExecutor);
    }

    @WMSingleton
    @Provides
    static SystemWindows provideSystemWindows(DisplayController displayController,
            IWindowManager wmService) {
        return new SystemWindows(displayController, wmService);
    }

    // We currently dedupe multiple messages, so we use the shell main handler directly
    @WMSingleton
    @Provides
    static TaskStackListenerImpl providerTaskStackListenerImpl(
            @ShellMainThread Handler mainHandler) {
        return new TaskStackListenerImpl(mainHandler);
    }

    @WMSingleton
    @Provides
    static TransactionPool provideTransactionPool() {
        return new TransactionPool();
    }

    @WMSingleton
    @Provides
    static WindowManagerShellWrapper provideWindowManagerShellWrapper(
            @ShellMainThread ShellExecutor mainExecutor) {
        return new WindowManagerShellWrapper(mainExecutor);
    }

    //
    // Bubbles
    //

    @WMSingleton
    @Provides
    static Optional<Bubbles> provideBubbles(Optional<BubbleController> bubbleController) {
        return bubbleController.map((controller) -> controller.asBubbles());
    }

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static Optional<BubbleController> provideBubbleController(Context context,
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

    //
    // Fullscreen
    //

    @WMSingleton
    @Provides
    static FullscreenTaskListener provideFullscreenTaskListener(SyncTransactionQueue syncQueue) {
        return new FullscreenTaskListener(syncQueue);
    }

    //
    // Hide display cutout
    //

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutout> provideHideDisplayCutout(
            Optional<HideDisplayCutoutController> hideDisplayCutoutController) {
        return hideDisplayCutoutController.map((controller) -> controller.asHideDisplayCutout());
    }

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutoutController> provideHideDisplayCutoutController(Context context,
            DisplayController displayController, @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(
                HideDisplayCutoutController.create(context, displayController, mainExecutor));
    }

    //
    // One handed mode (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<OneHanded> provideOneHanded(Optional<OneHandedController> oneHandedController) {
        return oneHandedController.map((controller) -> controller.asOneHanded());
    }

    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    static Optional<OneHandedController> provideOneHandedController(Context context,
            WindowManager windowManager, DisplayController displayController,
            TaskStackListenerImpl taskStackListener, UiEventLogger uiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return Optional.ofNullable(OneHandedController.create(context, windowManager,
                displayController, taskStackListener, uiEventLogger, mainExecutor, mainHandler));
    }

    //
    // Pip (optional feature)
    //

    @WMSingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
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
    static PipSurfaceTransactionHelper providePipSurfaceTransactionHelper(Context context) {
        return new PipSurfaceTransactionHelper(context);
    }

    @WMSingleton
    @Provides
    static PipUiEventLogger providePipUiEventLogger(UiEventLogger uiEventLogger,
            PackageManager packageManager) {
        return new PipUiEventLogger(uiEventLogger, packageManager);
    }

    @BindsOptionalOf
    abstract PipTouchHandler optionalPipTouchHandler();

    //
    // Shell transitions
    //

    @WMSingleton
    @Provides
    static ShellTransitions provideRemoteTransitions(Transitions transitions) {
        return transitions.asRemoteTransitions();
    }

    @WMSingleton
    @Provides
    static Transitions provideTransitions(ShellTaskOrganizer organizer, TransactionPool pool,
            Context context, @ShellMainThread ShellExecutor mainExecutor,
            @ShellAnimationThread ShellExecutor animExecutor) {
        return new Transitions(organizer, pool, context, mainExecutor, animExecutor);
    }

    //
    // Split/multiwindow
    //

    @WMSingleton
    @Provides
    static RootTaskDisplayAreaOrganizer provideRootTaskDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor, Context context) {
        return new RootTaskDisplayAreaOrganizer(mainExecutor, context);
    }

    @WMSingleton
    @Provides
    static Optional<SplitScreen> provideSplitScreen(
            Optional<SplitScreenController> splitScreenController) {
        return splitScreenController.map((controller) -> controller.asSplitScreen());
    }

    @WMSingleton
    @Provides
    static Optional<SplitScreenController> provideSplitScreenController(
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor,
            DisplayImeController displayImeController) {
        if (ActivityTaskManager.supportsSplitScreenMultiWindow(context)) {
            return Optional.of(new SplitScreenController(shellTaskOrganizer, syncQueue, context,
                    rootTaskDisplayAreaOrganizer, mainExecutor, displayImeController));
        } else {
            return Optional.empty();
        }
    }

    // Legacy split (optional feature)

    @WMSingleton
    @Provides
    static Optional<LegacySplitScreen> provideLegacySplitScreen(
            Optional<LegacySplitScreenController> splitScreenController) {
        return splitScreenController.map((controller) -> controller.asLegacySplitScreen());
    }

    @BindsOptionalOf
    abstract LegacySplitScreenController optionalLegacySplitScreenController();

    // App Pairs (optional feature)

    @WMSingleton
    @Provides
    static Optional<AppPairs> provideAppPairs(Optional<AppPairsController> appPairsController) {
        return appPairsController.map((controller) -> controller.asAppPairs());
    }

    @BindsOptionalOf
    abstract AppPairsController optionalAppPairs();

    // Starting window

    @WMSingleton
    @Provides
    static Optional<StartingSurface> provideStartingSurface(
            StartingWindowController startingWindowController) {
        return Optional.of(startingWindowController.asStartingSurface());
    }

    @WMSingleton
    @Provides
    static StartingWindowController provideStartingWindowController(Context context,
            @ShellSplashscreenThread ShellExecutor executor, TransactionPool pool) {
        return new StartingWindowController(context, executor, pool);
    }

    //
    // Task view factory
    //

    @WMSingleton
    @Provides
    static Optional<TaskViewFactory> provideTaskViewFactory(
            TaskViewFactoryController taskViewFactoryController) {
        return Optional.of(taskViewFactoryController.asTaskViewFactory());
    }

    @WMSingleton
    @Provides
    static TaskViewFactoryController provideTaskViewFactoryController(
            ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new TaskViewFactoryController(shellTaskOrganizer, mainExecutor);
    }

    //
    // Misc
    //

    @WMSingleton
    @Provides
    static ShellInit provideShellInit(ShellInitImpl impl) {
        return impl.asShellInit();
    }

    @WMSingleton
    @Provides
    static ShellInitImpl provideShellInitImpl(DisplayImeController displayImeController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreenController> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<AppPairsController> appPairsOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Transitions transitions,
            StartingWindowController startingWindow,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new ShellInitImpl(displayImeController,
                dragAndDropController,
                shellTaskOrganizer,
                legacySplitScreenOptional,
                splitScreenOptional,
                appPairsOptional,
                pipTouchHandlerOptional,
                fullscreenTaskListener,
                transitions,
                startingWindow,
                mainExecutor);
    }

    /**
     * Note, this is only optional because we currently pass this to the SysUI component scope and
     * for non-primary users, we may inject a null-optional for that dependency.
     */
    @WMSingleton
    @Provides
    static Optional<ShellCommandHandler> provideShellCommandHandler(ShellCommandHandlerImpl impl) {
        return Optional.of(impl.asShellCommandHandler());
    }

    @WMSingleton
    @Provides
    static ShellCommandHandlerImpl provideShellCommandHandlerImpl(
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<LegacySplitScreenController> legacySplitScreenOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<OneHandedController> oneHandedOptional,
            Optional<HideDisplayCutoutController> hideDisplayCutout,
            Optional<AppPairsController> appPairsOptional,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new ShellCommandHandlerImpl(shellTaskOrganizer,
                legacySplitScreenOptional, splitScreenOptional, pipOptional, oneHandedOptional,
                hideDisplayCutout, appPairsOptional, mainExecutor);
    }
}
