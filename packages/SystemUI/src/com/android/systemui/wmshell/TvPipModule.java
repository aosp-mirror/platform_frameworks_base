/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;

import com.android.systemui.dagger.WMSingleton;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.tv.TvPipController;
import com.android.wm.shell.pip.tv.TvPipMenuController;
import com.android.wm.shell.pip.tv.TvPipNotificationController;
import com.android.wm.shell.pip.tv.TvPipTransition;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/**
 * Provides TV specific dependencies for Pip.
 */
@Module(includes = {WMShellBaseModule.class})
public abstract class TvPipModule {
    @WMSingleton
    @Provides
    static Optional<Pip> providePip(
            Context context,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipTaskOrganizer pipTaskOrganizer,
            TvPipMenuController tvPipMenuController,
            PipMediaController pipMediaController,
            PipTransitionController pipTransitionController,
            TvPipNotificationController tvPipNotificationController,
            TaskStackListenerImpl taskStackListener,
            WindowManagerShellWrapper windowManagerShellWrapper,
            @ShellMainThread ShellExecutor mainExecutor) {
        return Optional.of(
                TvPipController.create(
                        context,
                        pipBoundsState,
                        pipBoundsAlgorithm,
                        pipTaskOrganizer,
                        pipTransitionController,
                        tvPipMenuController,
                        pipMediaController,
                        tvPipNotificationController,
                        taskStackListener,
                        windowManagerShellWrapper,
                        mainExecutor));
    }

    @WMSingleton
    @Provides
    static PipSnapAlgorithm providePipSnapAlgorithm() {
        return new PipSnapAlgorithm();
    }

    @WMSingleton
    @Provides
    static PipBoundsAlgorithm providePipBoundsAlgorithm(Context context,
            PipBoundsState pipBoundsState, PipSnapAlgorithm pipSnapAlgorithm) {
        return new PipBoundsAlgorithm(context, pipBoundsState, pipSnapAlgorithm);
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState(Context context) {
        return new PipBoundsState(context);
    }

    // Handler needed for loadDrawableAsync() in PipControlsViewController
    @WMSingleton
    @Provides
    static PipTransitionController provideTvPipTransition(
            Transitions transitions, ShellTaskOrganizer shellTaskOrganizer,
            PipAnimationController pipAnimationController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, TvPipMenuController pipMenuController) {
        return new TvPipTransition(pipBoundsState, pipMenuController,
                pipBoundsAlgorithm, pipAnimationController, transitions, shellTaskOrganizer);
    }

    @WMSingleton
    @Provides
    static TvPipMenuController providesTvPipMenuController(
            Context context,
            PipBoundsState pipBoundsState,
            SystemWindows systemWindows,
            PipMediaController pipMediaController,
            @ShellMainThread Handler mainHandler) {
        return new TvPipMenuController(context, pipBoundsState, systemWindows, pipMediaController,
                mainHandler);
    }

    // Handler needed for registerReceiverForAllUsers()
    @WMSingleton
    @Provides
    static TvPipNotificationController provideTvPipNotificationController(Context context,
            PipMediaController pipMediaController,
            @ShellMainThread Handler mainHandler) {
        return new TvPipNotificationController(context, pipMediaController, mainHandler);
    }

    @WMSingleton
    @Provides
    static PipAnimationController providePipAnimationController(PipSurfaceTransactionHelper
            pipSurfaceTransactionHelper) {
        return new PipAnimationController(pipSurfaceTransactionHelper);
    }

    @WMSingleton
    @Provides
    static PipTransitionState providePipTransitionState() {
        return new PipTransitionState();
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            TvPipMenuController tvPipMenuController,
            SyncTransactionQueue syncTransactionQueue,
            PipBoundsState pipBoundsState,
            PipTransitionState pipTransitionState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            PipTransitionController pipTransitionController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<LegacySplitScreenController> splitScreenOptional,
            DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        return new PipTaskOrganizer(context,
                syncTransactionQueue, pipTransitionState, pipBoundsState, pipBoundsAlgorithm,
                tvPipMenuController, pipAnimationController, pipSurfaceTransactionHelper,
                pipTransitionController, splitScreenOptional, displayController, pipUiEventLogger,
                shellTaskOrganizer, mainExecutor);
    }
}
