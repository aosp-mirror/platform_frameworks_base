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

package com.android.systemui.dagger;

import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.classifier.FalsingManagerProxy;
import com.android.systemui.globalactions.GlobalActionsComponent;
import com.android.systemui.globalactions.GlobalActionsImpl;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.phone.DarkIconDispatcherImpl;
import com.android.systemui.volume.VolumeDialogControllerImpl;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Module for binding Plugin implementations.
 *
 * TODO(b/166258224): Many of these should be moved closer to their implementations.
 */
@Module
public abstract class PluginModule {

    /** */
    @Provides
    static ActivityStarter provideActivityStarter(ActivityStarterDelegate delegate,
            PluginDependencyProvider dependencyProvider) {
        dependencyProvider.allowPluginDependency(ActivityStarter.class, delegate);
        return delegate;
    }

    /** */
    @Binds
    abstract DarkIconDispatcher provideDarkIconDispatcher(DarkIconDispatcherImpl controllerImpl);

    /** */
    @Binds
    abstract FalsingManager provideFalsingManager(FalsingManagerProxy falsingManagerImpl);

    /** */
    @Binds
    abstract GlobalActions provideGlobalActions(GlobalActionsImpl controllerImpl);

    /** */
    @Binds
    abstract GlobalActions.GlobalActionsManager provideGlobalActionsManager(
            GlobalActionsComponent controllerImpl);

    /** */
    @Binds
    abstract StatusBarStateController provideStatusBarStateController(
            StatusBarStateControllerImpl controllerImpl);

    /** */
    @Binds
    abstract VolumeDialogController provideVolumeDialogController(
            VolumeDialogControllerImpl controllerImpl);
}
