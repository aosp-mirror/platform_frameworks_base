/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.dagger.pip;

import android.content.Context;
import android.os.Handler;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TabletopModeController;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.common.pip.PhoneSizeSpecSource;
import com.android.wm.shell.common.pip.PipAppOpsListener;
import com.android.wm.shell.common.pip.SizeSpecSource;
import com.android.wm.shell.dagger.WMShellBaseModule;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
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
import com.android.wm.shell.pip.PipUtils;
import com.android.wm.shell.pip.phone.PhonePipKeepClearAlgorithm;
import com.android.wm.shell.pip.phone.PhonePipMenuController;
import com.android.wm.shell.pip.phone.PipController;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.pip.phone.PipTouchHandler;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import dagger.Module;
import dagger.Provides;

import java.util.Optional;

/**
 * Provides dependencies from {@link com.android.wm.shell.pip}, this implementation is meant to be
 * replaced by the sibling {@link Pip2Module}.
 */
@Module(includes = {
        Pip1SharedModule.class,
        WMShellBaseModule.class
})
public abstract class Pip1Module {
    @WMSingleton
    @Provides
    static Optional<Pip> providePip1(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            PipAnimationController pipAnimationController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PhonePipKeepClearAlgorithm pipKeepClearAlgorithm,
            PipBoundsState pipBoundsState,
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
        if (PipUtils.isPip2ExperimentEnabled()) {
            return Optional.empty();
        } else {
            return Optional.ofNullable(PipController.create(
                    context, shellInit, shellCommandHandler, shellController,
                    displayController, pipAnimationController, pipAppOpsListener,
                    pipBoundsAlgorithm,
                    pipKeepClearAlgorithm, pipBoundsState, pipDisplayLayoutState,
                    pipMotionHelper, pipMediaController, phonePipMenuController, pipTaskOrganizer,
                    pipTransitionState, pipTouchHandler, pipTransitionController,
                    windowManagerShellWrapper, taskStackListener, pipParamsChangedForwarder,
                    displayInsetsController, pipTabletopController, oneHandedController,
                    mainExecutor));
        }
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context,
            SizeSpecSource sizeSpecSource, PipDisplayLayoutState pipDisplayLayoutState) {
        return new PipBoundsState(context, sizeSpecSource, pipDisplayLayoutState);
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
    static PipBoundsAlgorithm providesPipBoundsAlgorithm(Context context,
            PipBoundsState pipBoundsState, PipSnapAlgorithm pipSnapAlgorithm,
            PhonePipKeepClearAlgorithm pipKeepClearAlgorithm,
            PipDisplayLayoutState pipDisplayLayoutState, SizeSpecSource sizeSpecSource) {
        return new PipBoundsAlgorithm(context, pipBoundsState, pipSnapAlgorithm,
                pipKeepClearAlgorithm, pipDisplayLayoutState, sizeSpecSource);
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
            SizeSpecSource sizeSpecSource,
            PipTaskOrganizer pipTaskOrganizer,
            PipMotionHelper pipMotionHelper,
            FloatingContentCoordinator floatingContentCoordinator,
            PipUiEventLogger pipUiEventLogger,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTouchHandler(context, shellInit, menuPhoneController, pipBoundsAlgorithm,
                pipBoundsState, sizeSpecSource, pipTaskOrganizer, pipMotionHelper,
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
    static PipTransition providePipTransition(Context context,
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
    static SizeSpecSource provideSizeSpecSource(Context context,
            PipDisplayLayoutState pipDisplayLayoutState) {
        return new PhoneSizeSpecSource(context, pipDisplayLayoutState);
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
}
