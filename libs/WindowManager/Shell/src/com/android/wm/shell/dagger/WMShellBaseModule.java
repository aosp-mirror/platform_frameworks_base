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

import static com.android.wm.shell.onehanded.OneHandedController.SUPPORT_ONE_HANDED_MODE;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.view.IWindowManager;

import com.android.internal.logging.UiEventLogger;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ProtoLogController;
import com.android.wm.shell.R;
import com.android.wm.shell.RootDisplayAreaOrganizer;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskViewFactory;
import com.android.wm.shell.TaskViewFactoryController;
import com.android.wm.shell.TaskViewTransitions;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.activityembedding.ActivityEmbeddingController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.back.BackAnimationController;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.DockStateReader;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellAnimationThread;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.common.annotations.ShellSplashscreenThread;
import com.android.wm.shell.compatui.CompatUIController;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.desktopmode.DesktopModeController;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.displayareahelper.DisplayAreaHelperController;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.freeform.FreeformComponents;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutController;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.StartingWindowTypeAlgorithm;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.transition.ShellTransitions;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.unfold.ShellUnfoldProgressProvider;
import com.android.wm.shell.unfold.UnfoldAnimationController;
import com.android.wm.shell.unfold.UnfoldTransitionHandler;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.util.Optional;

import dagger.BindsOptionalOf;
import dagger.Lazy;
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
            IWindowManager wmService,
            ShellInit shellInit,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new DisplayController(context, wmService, shellInit, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DisplayInsetsController provideDisplayInsetsController(IWindowManager wmService,
            ShellInit shellInit,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new DisplayInsetsController(wmService, shellInit, displayController,
                mainExecutor);
    }

    @WMSingleton
    @Provides
    static DisplayImeController provideDisplayImeController(
            IWindowManager wmService,
            ShellInit shellInit,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            TransactionPool transactionPool,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return new DisplayImeController(wmService, shellInit, displayController,
                displayInsetsController, transactionPool, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DisplayLayout provideDisplayLayout() {
        return new DisplayLayout();
    }

    @WMSingleton
    @Provides
    static DragAndDropController provideDragAndDropController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            UiEventLogger uiEventLogger,
            IconProvider iconProvider,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new DragAndDropController(context, shellInit, shellController, displayController,
                uiEventLogger, iconProvider, mainExecutor);
    }

    @WMSingleton
    @Provides
    static ShellTaskOrganizer provideShellTaskOrganizer(
            Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            CompatUIController compatUI,
            Optional<UnfoldAnimationController> unfoldAnimationController,
            Optional<RecentTasksController> recentTasksOptional,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        if (!context.getResources().getBoolean(R.bool.config_registerShellTaskOrganizerOnInit)) {
            // TODO(b/238217847): Force override shell init if registration is disabled
            shellInit = new ShellInit(mainExecutor);
        }
        return new ShellTaskOrganizer(shellInit, shellCommandHandler, compatUI,
                unfoldAnimationController, recentTasksOptional, mainExecutor);
    }

    @WMSingleton
    @Provides
    static CompatUIController provideCompatUIController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController, DisplayInsetsController displayInsetsController,
            DisplayImeController imeController, SyncTransactionQueue syncQueue,
            @ShellMainThread ShellExecutor mainExecutor, Lazy<Transitions> transitionsLazy,
            DockStateReader dockStateReader) {
        return new CompatUIController(context, shellInit, shellController, displayController,
                displayInsetsController, imeController, syncQueue, mainExecutor, transitionsLazy,
                dockStateReader);
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

    @WMSingleton
    @Provides
    static IconProvider provideIconProvider(Context context) {
        return new IconProvider(context);
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
    // Back animation
    //

    @WMSingleton
    @Provides
    static Optional<BackAnimation> provideBackAnimation(
            Optional<BackAnimationController> backAnimationController) {
        return backAnimationController.map(BackAnimationController::getBackAnimationImpl);
    }

    @WMSingleton
    @Provides
    static Optional<BackAnimationController> provideBackAnimationController(
            Context context,
            ShellInit shellInit,
            ShellController shellController,
            @ShellMainThread ShellExecutor shellExecutor,
            @ShellBackgroundThread Handler backgroundHandler
    ) {
        if (BackAnimationController.IS_ENABLED) {
            return Optional.of(
                    new BackAnimationController(shellInit, shellController, shellExecutor,
                            backgroundHandler, context));
        }
        return Optional.empty();
    }

    //
    // Bubbles (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<Bubbles> provideBubbles(Optional<BubbleController> bubbleController) {
        return bubbleController.map((controller) -> controller.asBubbles());
    }

    @BindsOptionalOf
    abstract BubbleController optionalBubblesController();

    //
    // Fullscreen
    //

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract FullscreenTaskListener optionalFullscreenTaskListener();

    @WMSingleton
    @Provides
    static FullscreenTaskListener provideFullscreenTaskListener(
            @DynamicOverride Optional<FullscreenTaskListener> fullscreenTaskListener,
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            Optional<RecentTasksController> recentTasksOptional,
            Optional<WindowDecorViewModel> windowDecorViewModelOptional) {
        if (fullscreenTaskListener.isPresent()) {
            return fullscreenTaskListener.get();
        } else {
            return new FullscreenTaskListener(shellInit, shellTaskOrganizer, syncQueue,
                    recentTasksOptional, windowDecorViewModelOptional);
        }
    }

    //
    // Window Decoration
    //

    @BindsOptionalOf
    abstract WindowDecorViewModel optionalWindowDecorViewModel();

    //
    // Unfold transition
    //

    @BindsOptionalOf
    abstract ShellUnfoldProgressProvider optionalShellUnfoldProgressProvider();

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract UnfoldAnimationController optionalUnfoldController();

    @WMSingleton
    @Provides
    static Optional<UnfoldAnimationController> provideUnfoldController(
            @DynamicOverride Lazy<Optional<UnfoldAnimationController>>
                    fullscreenUnfoldController,
            Optional<ShellUnfoldProgressProvider> progressProvider) {
        if (progressProvider.isPresent()
                && progressProvider.get() != ShellUnfoldProgressProvider.NO_PROVIDER) {
            return fullscreenUnfoldController.get();
        }
        return Optional.empty();
    }

    @BindsOptionalOf
    @DynamicOverride
    abstract UnfoldTransitionHandler optionalUnfoldTransitionHandler();

    @WMSingleton
    @Provides
    static Optional<UnfoldTransitionHandler> provideUnfoldTransitionHandler(
            Optional<ShellUnfoldProgressProvider> progressProvider,
            @DynamicOverride Lazy<Optional<UnfoldTransitionHandler>> handler) {
        if (progressProvider.isPresent()
                && progressProvider.get() != ShellUnfoldProgressProvider.NO_PROVIDER) {
            return handler.get();
        }
        return Optional.empty();
    }

    //
    // Freeform (optional feature)
    //

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract FreeformComponents optionalFreeformComponents();

    @WMSingleton
    @Provides
    static Optional<FreeformComponents> provideFreeformComponents(
            @DynamicOverride Optional<FreeformComponents> freeformComponents,
            Context context) {
        if (FreeformComponents.isFreeformEnabled(context)) {
            return freeformComponents;
        }
        return Optional.empty();
    }

    //
    // Hide display cutout
    //

    @WMSingleton
    @Provides
    static Optional<HideDisplayCutoutController> provideHideDisplayCutoutController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.ofNullable(
                HideDisplayCutoutController.create(context, shellInit, shellCommandHandler,
                        shellController, displayController, mainExecutor));
    }

    //
    // One handed mode (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<OneHanded> provideOneHanded(Optional<OneHandedController> oneHandedController) {
        return oneHandedController.map((controller) -> controller.asOneHanded());
    }

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract OneHandedController optionalOneHandedController();

    @WMSingleton
    @Provides
    static Optional<OneHandedController> providesOneHandedController(
            @DynamicOverride Optional<OneHandedController> oneHandedController) {
        if (SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            return oneHandedController;
        }
        return Optional.empty();
    }

    //
    // Pip (optional feature)
    //

    @WMSingleton
    @Provides
    static FloatingContentCoordinator provideFloatingContentCoordinator() {
        return new FloatingContentCoordinator();
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
    // Recent tasks
    //

    @WMSingleton
    @Provides
    static Optional<RecentTasks> provideRecentTasks(
            Optional<RecentTasksController> recentTasksController) {
        return recentTasksController.map((controller) -> controller.asRecentTasks());
    }

    @WMSingleton
    @Provides
    static Optional<RecentTasksController> provideRecentTasksController(
            Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellCommandHandler shellCommandHandler,
            TaskStackListenerImpl taskStackListener,
            ActivityTaskManager activityTaskManager,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return Optional.ofNullable(
                RecentTasksController.create(context, shellInit, shellController,
                        shellCommandHandler, taskStackListener, activityTaskManager,
                        desktopModeTaskRepository, mainExecutor));
    }

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
    static Transitions provideTransitions(Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellTaskOrganizer organizer,
            TransactionPool pool,
            DisplayController displayController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellAnimationThread ShellExecutor animExecutor) {
        if (!context.getResources().getBoolean(R.bool.config_registerShellTransitionsOnInit)) {
            // TODO(b/238217847): Force override shell init if registration is disabled
            shellInit = new ShellInit(mainExecutor);
        }
        return new Transitions(context, shellInit, shellController, organizer, pool,
                displayController, mainExecutor, mainHandler, animExecutor);
    }

    @WMSingleton
    @Provides
    static TaskViewTransitions provideTaskViewTransitions(Transitions transitions) {
        return new TaskViewTransitions(transitions);
    }

    //
    // Display areas
    //

    @WMSingleton
    @Provides
    static RootTaskDisplayAreaOrganizer provideRootTaskDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor, Context context) {
        return new RootTaskDisplayAreaOrganizer(mainExecutor, context);
    }

    @WMSingleton
    @Provides
    static RootDisplayAreaOrganizer provideRootDisplayAreaOrganizer(
            @ShellMainThread ShellExecutor mainExecutor) {
        return new RootDisplayAreaOrganizer(mainExecutor);
    }

    @WMSingleton
    @Provides
    static Optional<DisplayAreaHelper> provideDisplayAreaHelper(
            @ShellMainThread ShellExecutor mainExecutor,
            RootDisplayAreaOrganizer rootDisplayAreaOrganizer) {
        return Optional.of(new DisplayAreaHelperController(mainExecutor,
                rootDisplayAreaOrganizer));
    }

    //
    // Splitscreen (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<SplitScreen> provideSplitScreen(
            Optional<SplitScreenController> splitScreenController) {
        return splitScreenController.map((controller) -> controller.asSplitScreen());
    }

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract SplitScreenController optionalSplitScreenController();

    @WMSingleton
    @Provides
    static Optional<SplitScreenController> providesSplitScreenController(
            @DynamicOverride Optional<SplitScreenController> splitscreenController,
            Context context) {
        if (ActivityTaskManager.supportsSplitScreenMultiWindow(context)) {
            return splitscreenController;
        }
        return Optional.empty();
    }

    //
    // Starting window
    //

    @WMSingleton
    @Provides
    static Optional<StartingSurface> provideStartingSurface(
            StartingWindowController startingWindowController) {
        return Optional.of(startingWindowController.asStartingSurface());
    }

    @WMSingleton
    @Provides
    static StartingWindowController provideStartingWindowController(
            Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellTaskOrganizer shellTaskOrganizer,
            @ShellSplashscreenThread ShellExecutor splashScreenExecutor,
            StartingWindowTypeAlgorithm startingWindowTypeAlgorithm, IconProvider iconProvider,
            TransactionPool pool) {
        return new StartingWindowController(context, shellInit, shellController, shellTaskOrganizer,
                splashScreenExecutor, startingWindowTypeAlgorithm, iconProvider, pool);
    }

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @DynamicOverride
    abstract StartingWindowTypeAlgorithm optionalStartingWindowTypeAlgorithm();

    @WMSingleton
    @Provides
    static StartingWindowTypeAlgorithm provideStartingWindowTypeAlgorithm(
            @DynamicOverride Optional<StartingWindowTypeAlgorithm> startingWindowTypeAlgorithm
    ) {
        if (startingWindowTypeAlgorithm.isPresent()) {
            return startingWindowTypeAlgorithm.get();
        }
        // Default to phone starting window type
        return new PhoneStartingWindowTypeAlgorithm();
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
            @ShellMainThread ShellExecutor mainExecutor,
            SyncTransactionQueue syncQueue,
            TaskViewTransitions taskViewTransitions) {
        return new TaskViewFactoryController(shellTaskOrganizer, mainExecutor, syncQueue,
                taskViewTransitions);
    }


    //
    // ActivityEmbedding
    //

    @WMSingleton
    @Provides
    static Optional<ActivityEmbeddingController> provideActivityEmbeddingController(
            Context context,
            ShellInit shellInit,
            Transitions transitions) {
        return Optional.ofNullable(
                ActivityEmbeddingController.create(context, shellInit, transitions));
    }

    //
    // SysUI -> Shell interface
    //

    @WMSingleton
    @Provides
    static ShellInterface provideShellSysuiCallbacks(
            @ShellCreateTrigger Object createTrigger,
            ShellController shellController) {
        return shellController.asShell();
    }

    @WMSingleton
    @Provides
    static ShellController provideShellController(ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new ShellController(shellInit, shellCommandHandler, mainExecutor);
    }

    //
    // Desktop mode (optional feature)
    //

    @WMSingleton
    @Provides
    static Optional<DesktopMode> provideDesktopMode(
            Optional<DesktopModeController> desktopModeController,
            Optional<DesktopTasksController> desktopTasksController) {
        if (DesktopModeStatus.isProto2Enabled()) {
            return desktopTasksController.map(DesktopTasksController::asDesktopMode);
        }
        return desktopModeController.map(DesktopModeController::asDesktopMode);
    }

    @BindsOptionalOf
    @DynamicOverride
    abstract DesktopModeController optionalDesktopModeController();

    @WMSingleton
    @Provides
    static Optional<DesktopModeController> provideDesktopModeController(
            @DynamicOverride Optional<Lazy<DesktopModeController>> desktopModeController) {
        // Use optional-of-lazy for the dependency that this provider relies on.
        // Lazy ensures that this provider will not be the cause the dependency is created
        // when it will not be returned due to the condition below.
        if (DesktopModeStatus.isProto1Enabled()) {
            return desktopModeController.map(Lazy::get);
        }
        return Optional.empty();
    }

    @BindsOptionalOf
    @DynamicOverride
    abstract DesktopTasksController optionalDesktopTasksController();

    @WMSingleton
    @Provides
    static Optional<DesktopTasksController> providesDesktopTasksController(
            @DynamicOverride Optional<Lazy<DesktopTasksController>> desktopTasksController) {
        // Use optional-of-lazy for the dependency that this provider relies on.
        // Lazy ensures that this provider will not be the cause the dependency is created
        // when it will not be returned due to the condition below.
        if (DesktopModeStatus.isProto2Enabled()) {
            return desktopTasksController.map(Lazy::get);
        }
        return Optional.empty();
    }

    @BindsOptionalOf
    @DynamicOverride
    abstract DesktopModeTaskRepository optionalDesktopModeTaskRepository();

    @WMSingleton
    @Provides
    static Optional<DesktopModeTaskRepository> provideDesktopTaskRepository(
            @DynamicOverride Optional<Lazy<DesktopModeTaskRepository>> desktopModeTaskRepository) {
        // Use optional-of-lazy for the dependency that this provider relies on.
        // Lazy ensures that this provider will not be the cause the dependency is created
        // when it will not be returned due to the condition below.
        if (DesktopModeStatus.isAnyEnabled()) {
            return desktopModeTaskRepository.map(Lazy::get);
        }
        return Optional.empty();
    }

    //
    // Misc
    //

    // Workaround for dynamic overriding with a default implementation, see {@link DynamicOverride}
    @BindsOptionalOf
    @ShellCreateTriggerOverride
    abstract Object provideIndependentShellComponentsToCreateOverride();

    // TODO: Temporarily move dependencies to this instead of ShellInit since that is needed to add
    // the callback. We will be moving to a different explicit startup mechanism in a follow- up CL.
    @WMSingleton
    @ShellCreateTrigger
    @Provides
    static Object provideIndependentShellComponentsToCreate(
            DisplayController displayController,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController,
            DragAndDropController dragAndDropController,
            ShellTaskOrganizer shellTaskOrganizer,
            Optional<BubbleController> bubblesOptional,
            Optional<SplitScreenController> splitScreenOptional,
            Optional<Pip> pipOptional,
            Optional<PipTouchHandler> pipTouchHandlerOptional,
            FullscreenTaskListener fullscreenTaskListener,
            Optional<UnfoldAnimationController> unfoldAnimationController,
            Optional<UnfoldTransitionHandler> unfoldTransitionHandler,
            Optional<FreeformComponents> freeformComponents,
            Optional<RecentTasksController> recentTasksOptional,
            Optional<OneHandedController> oneHandedControllerOptional,
            Optional<HideDisplayCutoutController> hideDisplayCutoutControllerOptional,
            Optional<ActivityEmbeddingController> activityEmbeddingOptional,
            Transitions transitions,
            StartingWindowController startingWindow,
            ProtoLogController protoLogController,
            @ShellCreateTriggerOverride Optional<Object> overriddenCreateTrigger) {
        return new Object();
    }

    @WMSingleton
    @Provides
    static ShellInit provideShellInit(@ShellMainThread ShellExecutor mainExecutor) {
        return new ShellInit(mainExecutor);
    }

    @WMSingleton
    @Provides
    static ShellCommandHandler provideShellCommandHandler() {
        return new ShellCommandHandler();
    }

    @WMSingleton
    @Provides
    static ProtoLogController provideProtoLogController(
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler) {
        return new ProtoLogController(shellInit, shellCommandHandler);
    }
}
