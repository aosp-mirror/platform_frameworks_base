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

package com.android.systemui.dagger;

import com.android.keyguard.clock.ClockOptionsProvider;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SystemUIAppComponentFactory;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.people.PeopleProvider;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.InjectionInflationController;
import com.android.wm.shell.ShellCommandHandler;
import com.android.wm.shell.TaskViewFactory;
import com.android.wm.shell.apppairs.AppPairs;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.tasksurfacehelper.TaskSurfaceHelper;
import com.android.wm.shell.transition.ShellTransitions;

import java.util.Optional;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Dagger Subcomponent for Core SysUI.
 */
@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        SystemUIDefaultModule.class})
public interface SysUIComponent {

    /**
     * Builder for a SysUIComponent.
     */
    @SysUISingleton
    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        Builder setPip(Optional<Pip> p);

        @BindsInstance
        Builder setLegacySplitScreen(Optional<LegacySplitScreen> s);

        @BindsInstance
        Builder setSplitScreen(Optional<SplitScreen> s);

        @BindsInstance
        Builder setAppPairs(Optional<AppPairs> s);

        @BindsInstance
        Builder setOneHanded(Optional<OneHanded> o);

        @BindsInstance
        Builder setBubbles(Optional<Bubbles> b);

        @BindsInstance
        Builder setTaskViewFactory(Optional<TaskViewFactory> t);

        @BindsInstance
        Builder setHideDisplayCutout(Optional<HideDisplayCutout> h);

        @BindsInstance
        Builder setShellCommandHandler(Optional<ShellCommandHandler> shellDump);

        @BindsInstance
        Builder setTransitions(ShellTransitions t);

        @BindsInstance
        Builder setStartingSurface(Optional<StartingSurface> s);

        @BindsInstance
        Builder setTaskSurfaceHelper(Optional<TaskSurfaceHelper> t);

        SysUIComponent build();
    }

    /**
     * Initializes all the SysUI components.
     */
    default void init() {
        // Do nothing
    }

    /**
     * Provides a BootCompleteCache.
     */
    @SysUISingleton
    BootCompleteCacheImpl provideBootCacheImpl();

    /**
     * Creates a ContextComponentHelper.
     */
    @SysUISingleton
    ConfigurationController getConfigurationController();

    /**
     * Creates a ContextComponentHelper.
     */
    @SysUISingleton
    ContextComponentHelper getContextComponentHelper();

    /**
     * Main dependency providing module.
     */
    @SysUISingleton
    Dependency createDependency();

    /** */
    @SysUISingleton
    DumpManager createDumpManager();

    /**
     * Creates a InitController.
     */
    @SysUISingleton
    InitController getInitController();

    /**
     * ViewInstanceCreator generates all Views that need injection.
     */
    InjectionInflationController.ViewInstanceCreator.Factory createViewInstanceCreatorFactory();

    /**
     * Member injection into the supplied argument.
     */
    void inject(SystemUIAppComponentFactory factory);

    /**
     * Member injection into the supplied argument.
     */
    void inject(KeyguardSliceProvider keyguardSliceProvider);

    /**
     * Member injection into the supplied argument.
     */
    void inject(ClockOptionsProvider clockOptionsProvider);

    /**
     * Member injection into the supplied argument.
     */
    void inject(PeopleProvider peopleProvider);
}
