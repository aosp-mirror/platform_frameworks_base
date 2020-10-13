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
import android.view.LayoutInflater;

import com.android.systemui.dagger.WMSingleton;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SystemWindows;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipBoundsHandler;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipSurfaceTransactionHelper;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;
import com.android.wm.shell.pip.tv.PipController;
import com.android.wm.shell.pip.tv.PipControlsView;
import com.android.wm.shell.pip.tv.PipControlsViewController;
import com.android.wm.shell.pip.tv.PipNotification;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.Optional;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for TV Pip.
 */
@Module
public abstract class TvPipModule {
    @WMSingleton
    @Provides
    static Optional<Pip> providePip(
            Context context,
            PipBoundsState pipBoundsState,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        return Optional.of(
                new PipController(
                        context,
                        pipBoundsState,
                        pipBoundsHandler,
                        pipTaskOrganizer,
                        windowManagerShellWrapper));
    }

    @WMSingleton
    @Provides
    static PipControlsViewController providePipControlsViewController(
            PipControlsView pipControlsView, PipController pipController,
            LayoutInflater layoutInflater, Handler handler) {
        return new PipControlsViewController(pipControlsView, pipController, layoutInflater,
                handler);
    }

    @WMSingleton
    @Provides
    static PipControlsView providePipControlsView(Context context) {
        return new PipControlsView(context, null);
    }

    @WMSingleton
    @Provides
    static PipNotification providePipNotification(Context context,
            PipController pipController) {
        return new PipNotification(context, pipController);
    }

    @WMSingleton
    @Provides
    static PipBoundsHandler providePipBoundsHandler(Context context,
            PipBoundsState pipBoundsState) {
        return new PipBoundsHandler(context, pipBoundsState);
    }

    @WMSingleton
    @Provides
    static PipBoundsState providePipBoundsState() {
        return new PipBoundsState();
    }

    @WMSingleton
    @Provides
    static PipTaskOrganizer providePipTaskOrganizer(Context context,
            PipBoundsState pipBoundsState,
            PipBoundsHandler pipBoundsHandler,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer,
            SystemWindows systemWindows) {
        return new PipTaskOrganizer(context, pipBoundsState, pipBoundsHandler,
                null /* menuActivityController */, pipSurfaceTransactionHelper, splitScreenOptional,
                displayController, pipUiEventLogger, shellTaskOrganizer, systemWindows);
    }
}
