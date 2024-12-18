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

import static android.window.flags.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_TASK_LIMIT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.KeyguardManager;
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
import com.android.window.flags.Flags;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.activityembedding.ActivityEmbeddingController;
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser;
import com.android.wm.shell.apptoweb.AssistContentRequester;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleEducationController;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.properties.ProdBubbleProperties;
import com.android.wm.shell.bubbles.storage.BubblePersistentRepository;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.LaunchAdjacentController;
import com.android.wm.shell.common.MultiInstanceHelper;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.dagger.back.ShellBackAnimationModule;
import com.android.wm.shell.dagger.pip.PipModule;
import com.android.wm.shell.desktopmode.DefaultDragToDesktopTransitionHandler;
import com.android.wm.shell.desktopmode.DesktopActivityOrientationChangeHandler;
import com.android.wm.shell.desktopmode.DesktopModeDragAndDropTransitionHandler;
import com.android.wm.shell.desktopmode.DesktopModeEventLogger;
import com.android.wm.shell.desktopmode.DesktopModeLoggerTransitionObserver;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.desktopmode.DesktopTasksLimiter;
import com.android.wm.shell.desktopmode.DesktopTasksTransitionObserver;
import com.android.wm.shell.desktopmode.DragToDesktopTransitionHandler;
import com.android.wm.shell.desktopmode.EnterDesktopTaskTransitionHandler;
import com.android.wm.shell.desktopmode.ExitDesktopTaskTransitionHandler;
import com.android.wm.shell.desktopmode.ReturnToDragStartAnimator;
import com.android.wm.shell.desktopmode.SpringDragToDesktopTransitionHandler;
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler;
import com.android.wm.shell.desktopmode.WindowDecorCaptionHandleRepository;
import com.android.wm.shell.desktopmode.education.AppHandleEducationController;
import com.android.wm.shell.desktopmode.education.AppHandleEducationFilter;
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository;
import com.android.wm.shell.desktopmode.persistence.DesktopPersistentRepository;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.draganddrop.GlobalDragListener;
import com.android.wm.shell.freeform.FreeformComponents;
import com.android.wm.shell.freeform.FreeformTaskListener;
import com.android.wm.shell.freeform.FreeformTaskTransitionHandler;
import com.android.wm.shell.freeform.FreeformTaskTransitionObserver;
import com.android.wm.shell.keyguard.KeyguardTransitionHandler;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.recents.RecentsTransitionHandler;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.shared.annotations.ShellAnimationThread;
import com.android.wm.shell.shared.annotations.ShellBackgroundThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.taskview.TaskViewTransitions;
import com.android.wm.shell.transition.DefaultMixedHandler;
import com.android.wm.shell.transition.HomeTransitionObserver;
import com.android.wm.shell.transition.MixedTransitionHandler;
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
import com.android.wm.shell.windowdecor.viewhost.DefaultWindowDecorViewHostSupplier;
import com.android.wm.shell.windowdecor.viewhost.WindowDecorViewHostSupplier;

import dagger.Binds;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

import kotlinx.coroutines.CoroutineScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Provides dependencies from {@link com.android.wm.shell}, these dependencies are only accessible
 * from components within the WM subcomponent (can be explicitly exposed to the SysUIComponent, see
 * {@link WMComponent}).
 *
 * <p>This module only defines Shell dependencies for handheld SystemUI implementation. Common
 * dependencies should go into {@link WMShellBaseModule}.
 */
@Module(
        includes = {
                WMShellBaseModule.class,
                PipModule.class,
                ShellBackAnimationModule.class
        })
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
    static BubbleEducationController provideBubbleEducationProvider(Context context) {
        return new BubbleEducationController(context);
    }

    @WMSingleton
    @Provides
    static BubbleData provideBubbleData(Context context,
            BubbleLogger logger,
            BubblePositioner positioner,
            BubbleEducationController educationController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellBackgroundThread ShellExecutor bgExecutor) {
        return new BubbleData(context, logger, positioner, educationController, mainExecutor,
                bgExecutor);
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
            DragAndDropController dragAndDropController,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            TaskViewTransitions taskViewTransitions,
            Transitions transitions,
            SyncTransactionQueue syncQueue,
            IWindowManager wmService) {
        return new BubbleController(context, shellInit, shellCommandHandler, shellController, data,
                null /* synchronizer */, floatingContentCoordinator,
                new BubbleDataRepository(launcherApps, mainExecutor, bgExecutor,
                        new BubblePersistentRepository(context)),
                statusBarService, windowManager, windowManagerShellWrapper, userManager,
                launcherApps, logger, taskStackListener, organizer, positioner, displayController,
                oneHandedOptional, dragAndDropController, mainExecutor, mainHandler, bgExecutor,
                taskViewTransitions, transitions, syncQueue, wmService,
                ProdBubbleProperties.INSTANCE);
    }

    //
    // Window decoration
    //

    @WMSingleton
    @Provides
    static WindowDecorViewModel provideWindowDecorViewModel(
            Context context,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            @ShellMainThread Choreographer mainChoreographer,
            @ShellBackgroundThread ShellExecutor bgExecutor,
            ShellInit shellInit,
            IWindowManager windowManager,
            ShellCommandHandler shellCommandHandler,
            ShellTaskOrganizer taskOrganizer,
            DisplayController displayController,
            ShellController shellController,
            DisplayInsetsController displayInsetsController,
            SyncTransactionQueue syncQueue,
            Transitions transitions,
            Optional<DesktopTasksController> desktopTasksController,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            InteractionJankMonitor interactionJankMonitor,
            AppToWebGenericLinksParser genericLinksParser,
            AssistContentRequester assistContentRequester,
            MultiInstanceHelper multiInstanceHelper,
            Optional<DesktopTasksLimiter> desktopTasksLimiter,
            WindowDecorCaptionHandleRepository windowDecorCaptionHandleRepository,
            Optional<DesktopActivityOrientationChangeHandler> desktopActivityOrientationHandler,
            WindowDecorViewHostSupplier windowDecorViewHostSupplier) {
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            return new DesktopModeWindowDecorViewModel(
                    context,
                    mainExecutor,
                    mainHandler,
                    mainChoreographer,
                    bgExecutor,
                    shellInit,
                    shellCommandHandler,
                    windowManager,
                    taskOrganizer,
                    displayController,
                    shellController,
                    displayInsetsController,
                    syncQueue,
                    transitions,
                    desktopTasksController,
                    rootTaskDisplayAreaOrganizer,
                    interactionJankMonitor,
                    genericLinksParser,
                    assistContentRequester,
                    multiInstanceHelper,
                    desktopTasksLimiter,
                    windowDecorCaptionHandleRepository,
                    desktopActivityOrientationHandler,
                    windowDecorViewHostSupplier);
        }
        return new CaptionWindowDecorViewModel(
                context,
                mainHandler,
                mainExecutor,
                bgExecutor,
                mainChoreographer,
                windowManager,
                shellInit,
                taskOrganizer,
                displayController,
                rootTaskDisplayAreaOrganizer,
                syncQueue,
                transitions,
                windowDecorViewHostSupplier);
    }

    @WMSingleton
    @Provides
    static AppToWebGenericLinksParser provideGenericLinksParser(
            Context context,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        return new AppToWebGenericLinksParser(context, mainExecutor);
    }

    @Provides
    static AssistContentRequester provideAssistContentRequester(
            Context context,
            @ShellMainThread ShellExecutor shellExecutor,
            @ShellBackgroundThread ShellExecutor bgExecutor
    ) {
        return new AssistContentRequester(context, shellExecutor, bgExecutor);
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
            LaunchAdjacentController launchAdjacentController,
            WindowDecorViewModel windowDecorViewModel) {
        // TODO(b/238217847): Temporarily add this check here until we can remove the dynamic
        //                    override for this controller from the base module
        ShellInit init = FreeformComponents.isFreeformEnabled(context)
                ? shellInit
                : null;
        return new FreeformTaskListener(context, init, shellTaskOrganizer,
                desktopModeTaskRepository, launchAdjacentController, windowDecorViewModel);
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
            @ShellAnimationThread ShellExecutor animExecutor,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository,
            InteractionJankMonitor interactionJankMonitor,
            @ShellMainThread Handler handler) {
        return new FreeformTaskTransitionHandler(
                shellInit,
                transitions,
                context,
                windowDecorViewModel,
                displayController,
                mainExecutor,
                animExecutor,
                desktopModeTaskRepository,
                interactionJankMonitor,
                handler);
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

    @WMSingleton
    @Provides
    static WindowDecorViewHostSupplier provideWindowDecorViewHostSupplier(
            @ShellMainThread @NonNull CoroutineScope mainScope) {
        return new DefaultWindowDecorViewHostSupplier(mainScope);
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
            DragAndDropController dragAndDropController,
            Transitions transitions,
            TransactionPool transactionPool,
            IconProvider iconProvider,
            Optional<RecentTasksController> recentTasks,
            LaunchAdjacentController launchAdjacentController,
            Optional<WindowDecorViewModel> windowDecorViewModel,
            Optional<DesktopTasksController> desktopTasksController,
            MultiInstanceHelper multiInstanceHelper,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler) {
        return new SplitScreenController(context, shellInit, shellCommandHandler, shellController,
                shellTaskOrganizer, syncQueue, rootTaskDisplayAreaOrganizer, displayController,
                displayImeController, displayInsetsController, dragAndDropController, transitions,
                transactionPool, iconProvider, recentTasks, launchAdjacentController,
                windowDecorViewModel, desktopTasksController, null /* stageCoordinator */,
                multiInstanceHelper, mainExecutor, mainHandler);
    }

    //
    // Transitions
    //

    @WMSingleton
    @DynamicOverride
    @Provides
    static MixedTransitionHandler provideMixedTransitionHandler(
            ShellInit shellInit,
            Optional<SplitScreenController> splitScreenOptional,
            @Nullable PipTransitionController pipTransitionController,
            Optional<RecentsTransitionHandler> recentsTransitionHandler,
            KeyguardTransitionHandler keyguardTransitionHandler,
            Optional<DesktopTasksController> desktopTasksController,
            Optional<UnfoldTransitionHandler> unfoldHandler,
            Optional<ActivityEmbeddingController> activityEmbeddingController,
            Transitions transitions) {
        return new DefaultMixedHandler(shellInit, transitions, splitScreenOptional,
                pipTransitionController, recentsTransitionHandler,
                keyguardTransitionHandler, desktopTasksController,
                unfoldHandler, activityEmbeddingController);
    }

    @WMSingleton
    @Provides
    static RecentsTransitionHandler provideRecentsTransitionHandler(
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            Transitions transitions,
            Optional<RecentTasksController> recentTasksController,
            HomeTransitionObserver homeTransitionObserver) {
        return new RecentsTransitionHandler(shellInit, shellTaskOrganizer, transitions,
                recentTasksController.orElse(null), homeTransitionObserver);
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
    static DesktopTasksController provideDesktopTasksController(
            Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            ShellTaskOrganizer shellTaskOrganizer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            DragAndDropController dragAndDropController,
            Transitions transitions,
            KeyguardManager keyguardManager,
            ReturnToDragStartAnimator returnToDragStartAnimator,
            EnterDesktopTaskTransitionHandler enterDesktopTransitionHandler,
            ExitDesktopTaskTransitionHandler exitDesktopTransitionHandler,
            DesktopModeDragAndDropTransitionHandler desktopModeDragAndDropTransitionHandler,
            ToggleResizeDesktopTaskTransitionHandler toggleResizeDesktopTaskTransitionHandler,
            DragToDesktopTransitionHandler dragToDesktopTransitionHandler,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository,
            DesktopModeLoggerTransitionObserver desktopModeLoggerTransitionObserver,
            LaunchAdjacentController launchAdjacentController,
            RecentsTransitionHandler recentsTransitionHandler,
            MultiInstanceHelper multiInstanceHelper,
            @ShellMainThread ShellExecutor mainExecutor,
            @ShellMainThread Handler mainHandler,
            Optional<DesktopTasksLimiter> desktopTasksLimiter,
            Optional<RecentTasksController> recentTasksController,
            InteractionJankMonitor interactionJankMonitor) {
        return new DesktopTasksController(context, shellInit, shellCommandHandler, shellController,
                displayController, shellTaskOrganizer, syncQueue, rootTaskDisplayAreaOrganizer,
                dragAndDropController, transitions, keyguardManager,
                returnToDragStartAnimator, enterDesktopTransitionHandler,
                exitDesktopTransitionHandler, desktopModeDragAndDropTransitionHandler,
                toggleResizeDesktopTaskTransitionHandler,
                dragToDesktopTransitionHandler, desktopModeTaskRepository,
                desktopModeLoggerTransitionObserver, launchAdjacentController,
                recentsTransitionHandler, multiInstanceHelper, mainExecutor, desktopTasksLimiter,
                recentTasksController.orElse(null), interactionJankMonitor, mainHandler);
    }

    @WMSingleton
    @Provides
    static Optional<DesktopTasksLimiter> provideDesktopTasksLimiter(
            Context context,
            Transitions transitions,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository,
            ShellTaskOrganizer shellTaskOrganizer,
            InteractionJankMonitor interactionJankMonitor,
            @ShellMainThread Handler handler) {
        int maxTaskLimit = DesktopModeStatus.getMaxTaskLimit(context);
        if (!DesktopModeStatus.canEnterDesktopMode(context)
                || !ENABLE_DESKTOP_WINDOWING_TASK_LIMIT.isTrue()
                || maxTaskLimit <= 0) {
            return Optional.empty();
        }
        return Optional.of(
                new DesktopTasksLimiter(
                        transitions,
                        desktopModeTaskRepository,
                        shellTaskOrganizer,
                        maxTaskLimit,
                        interactionJankMonitor,
                        context,
                        handler)
        );
    }

    @WMSingleton
    @Provides
    static ReturnToDragStartAnimator provideReturnToDragStartAnimator(
            Context context, InteractionJankMonitor interactionJankMonitor) {
        return new ReturnToDragStartAnimator(context, interactionJankMonitor);
    }


    @WMSingleton
    @Provides
    static DragToDesktopTransitionHandler provideDragToDesktopTransitionHandler(
            Context context,
            Transitions transitions,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            InteractionJankMonitor interactionJankMonitor) {
        return Flags.enableDesktopWindowingTransitions()
                ? new SpringDragToDesktopTransitionHandler(context, transitions,
                        rootTaskDisplayAreaOrganizer, interactionJankMonitor)
                : new DefaultDragToDesktopTransitionHandler(context, transitions,
                        rootTaskDisplayAreaOrganizer, interactionJankMonitor);
    }

    @WMSingleton
    @Provides
    static EnterDesktopTaskTransitionHandler provideEnterDesktopModeTaskTransitionHandler(
            Transitions transitions,
            Optional<DesktopTasksLimiter> desktopTasksLimiter,
            InteractionJankMonitor interactionJankMonitor) {
        return new EnterDesktopTaskTransitionHandler(transitions, interactionJankMonitor);
    }

    @WMSingleton
    @Provides
    static ToggleResizeDesktopTaskTransitionHandler provideToggleResizeDesktopTaskTransitionHandler(
            Transitions transitions, InteractionJankMonitor interactionJankMonitor) {
        return new ToggleResizeDesktopTaskTransitionHandler(transitions, interactionJankMonitor);
    }

    @WMSingleton
    @Provides
    static ExitDesktopTaskTransitionHandler provideExitDesktopTaskTransitionHandler(
            Transitions transitions,
            Context context,
            InteractionJankMonitor interactionJankMonitor,
            @ShellMainThread Handler handler) {
        return new ExitDesktopTaskTransitionHandler(
            transitions, context, interactionJankMonitor, handler);
    }

    @WMSingleton
    @Provides
    static DesktopModeDragAndDropTransitionHandler provideDesktopModeDragAndDropTransitionHandler(
            Transitions transitions
    ) {
        return new DesktopModeDragAndDropTransitionHandler(transitions);
    }

    @WMSingleton
    @Provides
    @DynamicOverride
    static DesktopModeTaskRepository provideDesktopModeTaskRepository(
            Context context,
            ShellInit shellInit,
            DesktopPersistentRepository desktopPersistentRepository,
            @ShellMainThread CoroutineScope mainScope
    ) {
        return new DesktopModeTaskRepository(context, shellInit, desktopPersistentRepository,
                mainScope);
    }

    @WMSingleton
    @Provides
    static Optional<DesktopActivityOrientationChangeHandler> provideActivityOrientationHandler(
            Context context,
            ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            TaskStackListenerImpl taskStackListener,
            ToggleResizeDesktopTaskTransitionHandler toggleResizeDesktopTaskTransitionHandler,
            @DynamicOverride DesktopModeTaskRepository desktopModeTaskRepository
    ) {
        if (DesktopModeStatus.canEnterDesktopMode(context)) {
            return Optional.of(new DesktopActivityOrientationChangeHandler(
                    context, shellInit, shellTaskOrganizer, taskStackListener,
                    toggleResizeDesktopTaskTransitionHandler, desktopModeTaskRepository));
        }
        return Optional.empty();
    }

    @WMSingleton
    @Provides
    static Optional<DesktopTasksTransitionObserver> provideDesktopTasksTransitionObserver(
            Context context,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            Transitions transitions,
            ShellTaskOrganizer shellTaskOrganizer,
            ShellInit shellInit
    ) {
        return desktopModeTaskRepository.flatMap(repository ->
                Optional.of(new DesktopTasksTransitionObserver(
                        context, repository, transitions, shellTaskOrganizer, shellInit))
        );
    }

    @WMSingleton
    @Provides
    static DesktopModeLoggerTransitionObserver provideDesktopModeLoggerTransitionObserver(
            Context context,
            ShellInit shellInit,
            Transitions transitions,
            DesktopModeEventLogger desktopModeEventLogger) {
        return new DesktopModeLoggerTransitionObserver(
                context, shellInit, transitions, desktopModeEventLogger);
    }

    @WMSingleton
    @Provides
    static DesktopModeEventLogger provideDesktopModeEventLogger() {
        return new DesktopModeEventLogger();
    }

    @WMSingleton
    @Provides
    static AppHandleEducationDatastoreRepository provideAppHandleEducationDatastoreRepository(
            Context context) {
        return new AppHandleEducationDatastoreRepository(context);
    }

    @WMSingleton
    @Provides
    static AppHandleEducationFilter provideAppHandleEducationFilter(
            Context context,
            AppHandleEducationDatastoreRepository appHandleEducationDatastoreRepository) {
        return new AppHandleEducationFilter(context, appHandleEducationDatastoreRepository);
    }

    @WMSingleton
    @Provides
    static WindowDecorCaptionHandleRepository provideAppHandleRepository() {
        return new WindowDecorCaptionHandleRepository();
    }

    @WMSingleton
    @Provides
    static AppHandleEducationController provideAppHandleEducationController(
            AppHandleEducationFilter appHandleEducationFilter,
            ShellTaskOrganizer shellTaskOrganizer,
            AppHandleEducationDatastoreRepository appHandleEducationDatastoreRepository,
            @ShellMainThread CoroutineScope applicationScope) {
        return new AppHandleEducationController(appHandleEducationFilter,
                shellTaskOrganizer, appHandleEducationDatastoreRepository, applicationScope);
    }

    @WMSingleton
    @Provides
    static DesktopPersistentRepository provideDesktopPersistentRepository(
            Context context,
            @ShellBackgroundThread CoroutineScope bgScope) {
        return new DesktopPersistentRepository(context, bgScope);
    }

    //
    // Drag and drop
    //

    @WMSingleton
    @Provides
    static GlobalDragListener provideGlobalDragListener(
            IWindowManager wmService,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new GlobalDragListener(wmService, mainExecutor);
    }

    @WMSingleton
    @Provides
    static DragAndDropController provideDragAndDropController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            ShellCommandHandler shellCommandHandler,
            ShellTaskOrganizer shellTaskOrganizer,
            DisplayController displayController,
            UiEventLogger uiEventLogger,
            IconProvider iconProvider,
            GlobalDragListener globalDragListener,
            Transitions transitions,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new DragAndDropController(context, shellInit, shellController, shellCommandHandler,
                shellTaskOrganizer, displayController, uiEventLogger, iconProvider,
                globalDragListener, transitions, mainExecutor);
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
            DragAndDropController dragAndDropController,
            Optional<DesktopTasksTransitionObserver> desktopTasksTransitionObserverOptional,
            AppHandleEducationController appHandleEducationController
    ) {
        return new Object();
    }
}
