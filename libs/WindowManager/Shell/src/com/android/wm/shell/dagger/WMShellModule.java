/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.dagger;

import android.animation.AnimationHandler;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.view.WindowManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.apppairs.AppPairsController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ChoreographerSfVsync;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.fullscreen.FullscreenUnfoldController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransition;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PhonePipMenuController;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.splitscreen.StageTaskUnfoldController;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.UnfoldBackgroundController;

import java.util.Optional;

import javax.inject.Provider;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines Shell dependencies for handheld SystemUI implementation.  Common
 * dependencies should go into {@link WMShellBaseModule}.
 */
@Module(includes = WMShellBaseModule.class)
public class WMShellModule {

    //
    // Bubbles
    //

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static BubbleController provideBubbleController(Context context,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            LauncherApps launcherApps,
            TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            ShellTaskOrganizer organizer,
            DisplayController displayController,
            @DynamicOverride Optional<OneHandedController> oneHandedOptional,
            DragAndDropController dragAndDropController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            TaskViewTransitions taskViewTransitions,
            SyncTransactionQueue syncQueue) {
        return BubbleController.create(context, null /* synchronizer */,
                floatingContentCoordinator, statusBarService, windowManager,
                windowManagerShellWrapper, launcherApps, taskStackListener,
                uiEventLogger, organizer, displayController, oneHandedOptional,
                dragAndDropController, mainExecutor, mainHandler, bgExecutor,
                taskViewTransitions, syncQueue);
    }

    //
    // Freeform
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static FreeformTaskListener provideFreeformTaskListener(
            SyncTransactionQueue syncQueue) {
        return new FreeformTaskListener(syncQueue);
    }

    //
    // One handed mode
    //


    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    @DynamicOverride
    static OneHandedController provideOneHandedController(Context context,
            WindowManager windowManager, DisplayController displayController,
            DisplayLayout displayLayout, TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger, InteractionJankMonitor jankMonitor,
            @ShellMainThread ShellExecutor mainExecutor, @ShellMainThread Handler mainHandler) {
        return OneHandedController.create(context, windowManager, displayController, displayLayout,
                taskStackListener, jankMonitor, uiEventLogger, mainExecutor, mainHandler);
    }

    //
    // Splitscreen
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static SplitScreenController provideSplitScreenController(
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, Context context,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor,
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController, Transitions transitions,
            TransactionPool transactionPool, IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            Provider<Optional<StageTaskUnfoldController>> stageTaskUnfoldControllerProvider) {
        return new SplitScreenController(shellTaskOrganizer, syncQueue, context,
                rootTaskDisplayAreaOrganizer, mainExecutor, displayController, displayImeController,
                displayInsetsController, transitions, transactionPool, iconProvider,
                recentTasks, stageTaskUnfoldControllerProvider);
    }

    @WMSingleton
    @Provides
    static LegacySplitScreenController provideLegacySplitScreen(Context context,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController displayImeController, TransactionPool transactionPool,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue,
            TaskStackListenerImpl taskStackListener, Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor,
            @ChoreographerSfVsync AnimationHandler sfVsyncAnimationHandler) {
        return new LegacySplitScreenController(context, displayController, systemWindows,
                displayImeController, transactionPool, shellTaskOrganizer, syncQueue,
                taskStackListener, transitions, mainExecutor, sfVsyncAnimationHandler);
    }

    @WMSingleton
    @Provides
    static AppPairsController provideAppPairs(ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue, DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController) {
        return new AppPairsController(shellTaskOrganizer, syncQueue, displayController,
                mainExecutor, displayImeController, displayInsetsController);
    }

    //
    // Pip
    //

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context, DisplayController displayController,
            PipAppOpsListener pipAppOpsListener, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController, PipTaskOrganizer pipTaskOrganizer,
            PipTouchHandler pipTouchHandler, PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            Optional<OneHandedController> oneHandedController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(PipController.create(context, displayController,
                pipAppOpsListener, pipBoundsAlgorithm, pipBoundsState,
                pipMediaController, phonePipMenuController, pipTaskOrganizer,
                pipTouchHandler, pipTransitionController, windowManagerShellWrapper,
                taskStackListener, pipParamsChangedForwarder, oneHandedController, mainExecutor));
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context) {
        return new PipBoundsState(context);
    }

    @WMSingleton
    @Provides
    static PipSnapAlgorithm providePipSnapAlgorithm() {
        return new PipSnapAlgorithm();
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providesPipBoundsAlgorithm(Context context,
            PipBoundsState pipBoundsState, PipSnapAlgorithm pipSnapAlgorithm) {
        return new PipBoundsAlgorithm(context, pipBoundsState, pipSnapAlgorithm);
    }

    // Handler is used by Icon.loadDrawableAsync
    @WMSingleton
    @Provides
    static PhonePipMenuController providesPipPhoneMenuController(Context context,
            PipBoundsState pipBoundsState, PipMediaController pipMediaController,
            SystemWindows systemWindows,
            Optional<SplitScreenController> splitScreenOptional,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return new PhonePipMenuController(context, pipBoundsState, pipMediaController,
                systemWindows, splitScreenOptional, pipUiEventLogger, mainExecutor, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipTouchHandler providePipTouchHandler(Context context,
            PhonePipMenuController menuPhoneController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipTaskOrganizer pipTaskOrganizer,
            PipMotionHelper pipMotionHelper,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTouchHandler(context, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, pipTaskOrganizer, pipMotionHelper,
                floatingContentCoordinator, pipUiEventLogger, mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipTransitionState providePipTransitionState() {
        return new PipTransitionState();
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            SyncTransactionQueue syncTransactionQueue,
            PipTransitionState pipTransitionState,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipMenuController menuPhoneController,
            PipAnimationController pipAnimationController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            PipTransitionController pipTransitionController,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            Optional<SplitScreenController> splitScreenControllerOptional,
            DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTaskOrganizer(context,
                syncTransactionQueue, pipTransitionState, pipBoundsState, pipBoundsAlgorithm,
                menuPhoneController, pipAnimationController, pipSurfaceTransactionHelper,
                pipTransitionController, pipParamsChangedForwarder, splitScreenControllerOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer, mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipAnimationController providePipAnimationController(PipSurfaceTransactionHelper
            pipSurfaceTransactionHelper) {
        return new PipAnimationController(pipSurfaceTransactionHelper);
    }

    @WMSingleton
    @Provides
    static PipTransitionController providePipTransitionController(Context context,
            Transitions transitions, ShellTaskOrganizer shellTaskOrganizer,
            PipAnimationController pipAnimationController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipTransitionState pipTransitionState,
            PhonePipMenuController pipMenuController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenOptional) {
        return new PipTransition(context, pipBoundsState, pipTransitionState, pipMenuController,
                pipBoundsAlgorithm, pipAnimationController, transitions, shellTaskOrganizer,
                pipSurfaceTransactionHelper, splitScreenOptional);
    }

    @WMSingleton
    @Provides
    static PipAppOpsListener providePipAppOpsListener(Context context,
            PipTouchHandler pipTouchHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipAppOpsListener(context, pipTouchHandler.getMotionHelper(), mainExecutor);
    }

    @WMSingleton
    @Provides
    static PipMotionHelper providePipMotionHelper(Context context,
            PipBoundsState pipBoundsState, PipTaskOrganizer pipTaskOrganizer,
            PhonePipMenuController menuController, PipSnapAlgorithm pipSnapAlgorithm,
            PipTransitionController pipTransitionController,
            FloatingContentCoordinator floatingContentCoordinator) {
        return new PipMotionHelper(context, pipBoundsState, pipTaskOrganizer,
                menuController, pipSnapAlgorithm, pipTransitionController,
                floatingContentCoordinator);
    }

    //
    // Unfold transition
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static FullscreenUnfoldController provideFullscreenUnfoldController(
            Context context,
            Optional<ShellUnfoldProgressProvider> progressProvider,
            Lazy<UnfoldBackgroundController> unfoldBackgroundController,
            DisplayInsetsController displayInsetsController,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return new FullscreenUnfoldController(context, mainExecutor,
                unfoldBackgroundController.get(), progressProvider.get(),
                displayInsetsController);
    }

    @Provides
    static Optional<StageTaskUnfoldController> provideStageTaskUnfoldController(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            Context context,
            TransactionPool transactionPool,
            Lazy<UnfoldBackgroundController> unfoldBackgroundController,
            DisplayInsetsController displayInsetsController,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return progressProvider.map(shellUnfoldTransitionProgressProvider ->
                new StageTaskUnfoldController(
                        context,
                        transactionPool,
                        shellUnfoldTransitionProgressProvider,
                        displayInsetsController,
                        unfoldBackgroundController.get(),
                        mainExecutor
                ));
    }

    @WMSingleton
    @Provides
    static UnfoldBackgroundController provideUnfoldBackgroundController(
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Context context
    ) {
        return new UnfoldBackgroundController(
                context,
                rootTaskDisplayAreaOrganizer
        );
    }

    @WMSingleton
    @Provides
    static PipParamsChangedForwarder providePipParamsChangedForwarder() {
        return new PipParamsChangedForwarder();
    }
}
