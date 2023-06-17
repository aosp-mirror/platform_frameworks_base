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

import android.content.Context;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.UserManager;
import android.view.Choreographer;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.LaunchAdjacentController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TabletopModeController;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler;
import com.android.wm.shell.desktopmode.ExitDesktopTaskTransitionHandler;
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformComponents;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler;
import com.android.wm.shell.freeform.FreeformTaskTransitionObserver;
import com.android.wm.shell.keyguard.KeyguardTransitionHandler;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipDisplayLayoutState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransition;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PhonePipKeepClearAlgorithm;
import com.android.wm.shell.pip.phone.PhonePipMenuController;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.pip.phone.PipSizeSpecHandler;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.recents.RecentsTransitionHandler;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.DefaultMixedHandler;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.UnfoldAnimationController;
import com.android.wm.shell.unfold.UnfoldBackgroundController;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;
import com.android.wm.shell.unfold.animation.FullscreenUnfoldTaskAnimator;
import com.android.wm.shell.unfold.animation.SplitTaskUnfoldAnimator;
import com.android.wm.shell.unfold.animation.UnfoldTaskAnimator;
import com.android.wm.shell.unfold.qualifier.UnfoldShellTransition;
import com.android.wm.shell.unfold.qualifier.UnfoldTransition;
import com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel;
import com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Provides dependencies from {@link com.android.wm.shell}, these dependencies are only
 * accessible from components within the WM subcomponent (can be explicitly exposed to the
 * SysUIComponent, see {@link WMComponent}).
 *
 * This module only defines Shell dependencies for handheld SystemUI implementation.  Common
 * dependencies should go into {@link WMShellBaseModule}.
 */
@Module(includes = WMShellBaseModule.class)
public abstract class WMShellModule {

    //
    // Bubbles
    //

    @WMSingleton
    @Provides
    static BubbleLogger provideBubbleLogger(UiEventLogger uiEventLogger) {
        return new BubbleLogger(uiEventLogger);
    }

    @WMSingleton
    @Provides
    static BubblePositioner provideBubblePositioner(Context context,
            WindowManager windowManager) {
        return new BubblePositioner(context, windowManager);
    }

    @WMSingleton
    @Provides
    static BubbleData provideBubbleData(Context context,
            BubbleLogger logger,
            BubblePositioner positioner,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new BubbleData(context, logger, positioner, mainExecutor);
    }

    // Note: Handler needed for LauncherApps.register
    @WMSingleton
    @Provides
    static BubbleController provideBubbleController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            BubbleData data,
            FloatingContentCoordinator floatingContentCoordinator,
            IStatusBarService statusBarService,
            WindowManager windowManager,
            WindowManagerShellWrapper windowManagerShellWrapper,
            UserManager userManager,
            LauncherApps launcherApps,
            TaskStackListenerImpl taskStackListener,
            BubbleLogger logger,
            ShellTaskOrganizer organizer,
            BubblePositioner positioner,
            DisplayController displayController,
            @DynamicOverride Optional<OneHandedController> oneHandedOptional,
            Optional<DragAndDropController> dragAndDropController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            TaskViewTransitions taskViewTransitions,
            SyncTransactionQueue syncQueue,
            IWindowManager wmService) {
        return new BubbleController(context, shellInit, shellCommandHandler, shellController, data,
                null /* synchronizer */, floatingContentCoordinator,
                new BubbleDataRepository(context, launcherApps, mainExecutor),
                statusBarService, windowManager, windowManagerShellWrapper, userManager,
                launcherApps, logger, taskStackListener, organizer, positioner, displayController,
                oneHandedOptional, dragAndDropController, mainExecutor, mainHandler, bgExecutor,
                taskViewTransitions, syncQueue, wmService);
    }

    //
    // Window decoration
    //

    @WMSingleton
    @Provides
    static WindowDecorViewModel provideWindowDecorViewModel(
            Context context,
            @ShellMainThread Handler mainHandler,
            @ShellMainThread Choreographer mainChoreographer,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController) {
        if (DesktopModeStatus.isAnyEnabled()) {
            return new DesktopModeWindowDecorViewModel(
                    context,
                    mainHandler,
                    mainChoreographer,
                    taskOrganizer,
                    displayController,
                    syncQueue,
                    transitions,
                    desktopModeController,
                    desktopTasksController);
        }
        return new CaptionWindowDecorViewModel(
                    context,
                    mainHandler,
                    mainChoreographer,
                    taskOrganizer,
                    displayController,
                    syncQueue);
    }

    //
    // Freeform
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static FreeformComponents provideFreeformComponents(
            FreeformTaskListener taskListener,
            FreeformTaskTransitionHandler transitionHandler,
            FreeformTaskTransitionObserver transitionObserver) {
        return new FreeformComponents(
                taskListener, Optional.of(transitionHandler), Optional.of(transitionObserver));
    }

    @WMSingleton
    @Provides
    static FreeformTaskListener provideFreeformTaskListener(
            Context context,
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            WindowDecorViewModel windowDecorViewModel) {
        // TODO(b/238217847): Temporarily add this check here until we can remove the dynamic
        //                    override for this controller from the base module
        ShellInit init = FreeformComponents.isFreeformEnabled(context)
                ? shellInit
                : null;
        return new FreeformTaskListener(init, shellTaskOrganizer, desktopModeTaskRepository,
                windowDecorViewModel);
    }

    @WMSingleton
    @Provides
    static FreeformTaskTransitionHandler provideFreeformTaskTransitionHandler(
            ShellInit shellInit,
            Transitions transitions,
            Context context,
            WindowDecorViewModel windowDecorViewModel,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellAnimationThread ShellExecutor animExecutor) {
        return new FreeformTaskTransitionHandler(shellInit, transitions, context,
                windowDecorViewModel, displayController, mainExecutor, animExecutor);
    }

    @WMSingleton
    @Provides
    static FreeformTaskTransitionObserver provideFreeformTaskTransitionObserver(
            Context context,
            ShellInit shellInit,
            Transitions transitions,
            WindowDecorViewModel windowDecorViewModel) {
        return new FreeformTaskTransitionObserver(
                context, shellInit, transitions, windowDecorViewModel);
    }

    //
    // One handed mode
    //


    // Needs the shell main handler for ContentObserver callbacks
    @WMSingleton
    @Provides
    @DynamicOverride
    static OneHandedController provideOneHandedController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            WindowManager windowManager,
            DisplayController displayController,
            DisplayLayout displayLayout,
            TaskStackListenerImpl taskStackListener,
            UiEventLogger uiEventLogger,
            InteractionJankMonitor jankMonitor,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return OneHandedController.create(context, shellInit, shellCommandHandler, shellController,
                windowManager, displayController, displayLayout, taskStackListener, jankMonitor,
                uiEventLogger, mainExecutor, mainHandler);
    }

    //
    // Splitscreen
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static SplitScreenController provideSplitScreenController(
            Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            Optional<DragAndDropController> dragAndDropController,
            Transitions transitions,
            TransactionPool transactionPool,
            IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            LaunchAdjacentController launchAdjacentController,
            Optional<WindowDecorViewModel> windowDecorViewModel,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new SplitScreenController(context, shellInit, shellCommandHandler, shellController,
                shellTaskOrganizer, syncQueue, rootTaskDisplayAreaOrganizer, displayController,
                displayImeController, displayInsetsController, dragAndDropController, transitions,
                transactionPool, iconProvider, recentTasks, launchAdjacentController,
                windowDecorViewModel, mainExecutor);
    }

    //
    // Pip
    //

    @WMSingleton
    @Provides
    static Optional<Pip> providePip(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            PipAnimationController pipAnimationController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipKeepClearAlgorithm pipKeepClearAlgorithm,
            PipBoundsState pipBoundsState,
            PipSizeSpecHandler pipSizeSpecHandler,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipMotionHelper pipMotionHelper,
            PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionState pipTransitionState,
            PipTouchHandler pipTouchHandler,
            PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayInsetsController displayInsetsController,
            TabletopModeController pipTabletopController,
            Optional<OneHandedController> oneHandedController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(PipController.create(
                context, shellInit, shellCommandHandler, shellController,
                displayController, pipAnimationController, pipAppOpsListener, pipBoundsAlgorithm,
                pipKeepClearAlgorithm, pipBoundsState, pipSizeSpecHandler, pipDisplayLayoutState,
                pipMotionHelper, pipMediaController, phonePipMenuController, pipTaskOrganizer,
                pipTransitionState, pipTouchHandler, pipTransitionController,
                windowManagerShellWrapper, taskStackListener, pipParamsChangedForwarder,
                displayInsetsController, pipTabletopController, oneHandedController, mainExecutor));
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context,
            PipSizeSpecHandler pipSizeSpecHandler, PipDisplayLayoutState pipDisplayLayoutState) {
        return new PipBoundsState(context, pipSizeSpecHandler, pipDisplayLayoutState);
    }

    @WMSingleton
    @Provides
    static PipSnapAlgorithm providePipSnapAlgorithm() {
        return new PipSnapAlgorithm();
    }

    @WMSingleton
    @Provides
    static PhonePipKeepClearAlgorithm providePhonePipKeepClearAlgorithm(Context context) {
        return new PhonePipKeepClearAlgorithm(context);
    }

    @WMSingleton
    @Provides
    static PipSizeSpecHandler providePipSizeSpecHelper(Context context,
            PipDisplayLayoutState pipDisplayLayoutState) {
        return new PipSizeSpecHandler(context, pipDisplayLayoutState);
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providesPipBoundsAlgorithm(Context context,
            PipBoundsState pipBoundsState, PipSnapAlgorithm pipSnapAlgorithm,
            PhonePipKeepClearAlgorithm pipKeepClearAlgorithm,
            PipSizeSpecHandler pipSizeSpecHandler) {
        return new PipBoundsAlgorithm(context, pipBoundsState, pipSnapAlgorithm,
                pipKeepClearAlgorithm, pipSizeSpecHandler);
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
            ShellInit shellInit,
            PhonePipMenuController menuPhoneController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState,
            PipSizeSpecHandler pipSizeSpecHandler,
            PipTaskOrganizer pipTaskOrganizer,
            PipMotionHelper pipMotionHelper,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTouchHandler(context, shellInit, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, pipSizeSpecHandler, pipTaskOrganizer, pipMotionHelper,
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
            PipDisplayLayoutState pipDisplayLayoutState,
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
                syncTransactionQueue, pipTransitionState, pipBoundsState, pipDisplayLayoutState,
                pipBoundsAlgorithm, menuPhoneController, pipAnimationController,
                pipSurfaceTransactionHelper, pipTransitionController, pipParamsChangedForwarder,
                splitScreenControllerOptional, displayController, pipUiEventLogger,
                shellTaskOrganizer, mainExecutor);
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
            ShellInit shellInit, ShellTaskOrganizer shellTaskOrganizer, Transitions transitions,
            PipAnimationController pipAnimationController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipDisplayLayoutState pipDisplayLayoutState,
            PipTransitionState pipTransitionState, PhonePipMenuController pipMenuController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenOptional) {
        return new PipTransition(context, shellInit, shellTaskOrganizer, transitions,
                pipBoundsState, pipDisplayLayoutState, pipTransitionState, pipMenuController,
                pipBoundsAlgorithm, pipAnimationController, pipSurfaceTransactionHelper,
                splitScreenOptional);
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

    @WMSingleton
    @Provides
    static PipParamsChangedForwarder providePipParamsChangedForwarder() {
        return new PipParamsChangedForwarder();
    }

    //
    // Transitions
    //

    @WMSingleton
    @Provides
    static DefaultMixedHandler provideDefaultMixedHandler(
            ShellInit shellInit,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            Optional<RecentsTransitionHandler> recentsTransitionHandler,
            KeyguardTransitionHandler keyguardTransitionHandler,
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController,
            Optional<UnfoldTransitionHandler> unfoldHandler,
            Transitions transitions) {
        return new DefaultMixedHandler(shellInit, transitions, splitScreenOptional,
                pipTouchHandlerOptional, recentsTransitionHandler, keyguardTransitionHandler,
                desktopModeController, desktopTasksController, unfoldHandler);
    }

    @WMSingleton
    @Provides
    static RecentsTransitionHandler provideRecentsTransitionHandler(
            ShellInit shellInit,
            Transitions transitions,
            Optional<RecentTasksController> recentTasksController) {
        return new RecentsTransitionHandler(shellInit, transitions,
                recentTasksController.orElse(null));
    }

    //
    // Unfold transition
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static UnfoldAnimationController provideUnfoldAnimationController(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            TransactionPool transactionPool,
            @UnfoldTransition SplitTaskUnfoldAnimator splitAnimator,
            FullscreenUnfoldTaskAnimator fullscreenAnimator,
            Lazy<Optional<UnfoldTransitionHandler>> unfoldTransitionHandler,
            ShellInit shellInit,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        final List<UnfoldTaskAnimator> animators = new ArrayList<>();
        animators.add(splitAnimator);
        animators.add(fullscreenAnimator);

        return new UnfoldAnimationController(
                        shellInit,
                        transactionPool,
                        progressProvider.get(),
                        animators,
                        unfoldTransitionHandler,
                        mainExecutor
                );
    }

    @Provides
    static FullscreenUnfoldTaskAnimator provideFullscreenUnfoldTaskAnimator(
            Context context,
            UnfoldBackgroundController unfoldBackgroundController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController
    ) {
        return new FullscreenUnfoldTaskAnimator(context, unfoldBackgroundController,
                shellController, displayInsetsController);
    }

    @Provides
    static SplitTaskUnfoldAnimator provideSplitTaskUnfoldAnimatorBase(
            Context context,
            UnfoldBackgroundController backgroundController,
            ShellController shellController,
            @ShellMainThread ShellExecutor executor,
            Lazy<Optional<SplitScreenController>> splitScreenOptional,
            DisplayInsetsController displayInsetsController
    ) {
        // TODO(b/238217847): The lazy reference here causes some dependency issues since it
        // immediately registers a listener on that controller on init.  We should reference the
        // controller directly once we refactor ShellTaskOrganizer to not depend on the unfold
        // animation controller directly.
        return new SplitTaskUnfoldAnimator(context, executor, splitScreenOptional,
                shellController, backgroundController, displayInsetsController);
    }

    @WMSingleton
    @UnfoldShellTransition
    @Binds
    abstract SplitTaskUnfoldAnimator provideShellSplitTaskUnfoldAnimator(
            SplitTaskUnfoldAnimator splitTaskUnfoldAnimator);

    @WMSingleton
    @UnfoldTransition
    @Binds
    abstract SplitTaskUnfoldAnimator provideSplitTaskUnfoldAnimator(
            SplitTaskUnfoldAnimator splitTaskUnfoldAnimator);

    @WMSingleton
    @Provides
    @DynamicOverride
    static UnfoldTransitionHandler provideUnfoldTransitionHandler(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            FullscreenUnfoldTaskAnimator animator,
            @UnfoldShellTransition SplitTaskUnfoldAnimator unfoldAnimator,
            TransactionPool transactionPool,
            Transitions transitions,
            @ShellMainThread ShellExecutor executor,
            ShellInit shellInit) {
        return new UnfoldTransitionHandler(shellInit, progressProvider.get(), animator,
                unfoldAnimator, transactionPool, executor, transitions);
    }

    @WMSingleton
    @Provides
    static UnfoldBackgroundController provideUnfoldBackgroundController(Context context) {
        return new UnfoldBackgroundController(context);
    }

    //
    // Desktop mode (optional feature)
    //

    @WMSingleton
    @Provides
    @DynamicOverride
    static DesktopModeController provideDesktopModeController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Transitions transitions,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository,
            @ShellMainThread Handler mainHandler,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return new DesktopModeController(context, shellInit, shellController, shellTaskOrganizer,
                rootTaskDisplayAreaOrganizer, transitions, desktopModeTaskRepository, mainHandler,
                mainExecutor);
    }

    @WMSingleton
    @Provides
    @DynamicOverride
    static DesktopTasksController provideDesktopTasksController(
            Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Transitions transitions,
            EnterDesktopTaskTransitionHandler enterDesktopTransitionHandler,
            ExitDesktopTaskTransitionHandler exitDesktopTransitionHandler,
            ToggleResizeDesktopTaskTransitionHandler toggleResizeDesktopTaskTransitionHandler,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository,
            LaunchAdjacentController launchAdjacentController,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return new DesktopTasksController(context, shellInit, shellCommandHandler, shellController,
                displayController, shellTaskOrganizer, syncQueue, rootTaskDisplayAreaOrganizer,
                transitions, enterDesktopTransitionHandler, exitDesktopTransitionHandler,
                toggleResizeDesktopTaskTransitionHandler, desktopModeTaskRepository,
                launchAdjacentController, mainExecutor);
    }

    @WMSingleton
    @Provides
    static EnterDesktopTaskTransitionHandler provideEnterDesktopModeTaskTransitionHandler(
            Transitions transitions) {
        return new EnterDesktopTaskTransitionHandler(transitions);
    }

    @WMSingleton
    @Provides
    static ToggleResizeDesktopTaskTransitionHandler provideToggleResizeDesktopTaskTransitionHandler(
            Transitions transitions) {
        return new ToggleResizeDesktopTaskTransitionHandler(transitions);
    }

    @WMSingleton
    @Provides
    static ExitDesktopTaskTransitionHandler provideExitDesktopTaskTransitionHandler(
            Transitions transitions,
            Context context
    ) {
        return new ExitDesktopTaskTransitionHandler(transitions, context);
    }

    @WMSingleton
    @Provides
    @DynamicOverride
    static DesktopModeTaskRepository provideDesktopModeTaskRepository() {
        return new DesktopModeTaskRepository();
    }

    //
    // Misc
    //

    // TODO: Temporarily move dependencies to this instead of ShellInit since that is needed to add
    // the callback. We will be moving to a different explicit startup mechanism in a follow- up CL.
    @WMSingleton
    @ShellCreateTriggerOverride
    @Provides
    static Object provideIndependentShellComponentsToCreate(
            DefaultMixedHandler defaultMixedHandler,
            Optional<DesktopModeController> desktopModeController) {
        return new Object();
    }
}
