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

package com.android.systemui.pip.tv.dagger;

import android.app.Activity;
import android.content.Context;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.pip.Pip;
import com.android.systemui.pip.PipBoundsHandler;
import com.android.systemui.pip.PipSurfaceTransactionHelper;
import com.android.systemui.pip.PipTaskOrganizer;
import com.android.systemui.pip.PipUiEventLogger;
import com.android.systemui.pip.tv.PipController;
import com.android.systemui.pip.tv.PipMenuActivity;
import com.android.systemui.pip.tv.PipNotification;
import com.android.systemui.wmshell.WindowManagerShellWrapper;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.util.Optional;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Dagger module for TV Pip.
 */
@Module(subcomponents = {TvPipComponent.class})
public abstract class TvPipModule {

    /** Inject into PipMenuActivity. */
    @Binds
    @IntoMap
    @ClassKey(PipMenuActivity.class)
    public abstract Activity providePipMenuActivity(PipMenuActivity activity);

    @SysUISingleton
    @Provides
    static Pip providePipController(Context context,
            PipBoundsHandler pipBoundsHandler,
            PipTaskOrganizer pipTaskOrganizer,
            WindowManagerShellWrapper windowManagerShellWrapper) {
        return new PipController(context, pipBoundsHandler, pipTaskOrganizer,
                windowManagerShellWrapper);
    }

    @SysUISingleton
    @Provides
    static PipNotification providePipNotification(Context context,
            PipController pipController) {
        return new PipNotification(context, pipController);
    }

    @SysUISingleton
    @Provides
    static PipBoundsHandler providesPipBoundsHandler(Context context) {
        return new PipBoundsHandler(context);
    }

    @SysUISingleton
    @Provides
    static PipTaskOrganizer providesPipTaskOrganizer(Context context,
            PipBoundsHandler pipBoundsHandler,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreen> splitScreenOptional, DisplayController displayController,
            PipUiEventLogger pipUiEventLogger, ShellTaskOrganizer shellTaskOrganizer) {
        return new PipTaskOrganizer(context, pipBoundsHandler,
                pipSurfaceTransactionHelper, splitScreenOptional, displayController,
                pipUiEventLogger, shellTaskOrganizer);
    }
}
