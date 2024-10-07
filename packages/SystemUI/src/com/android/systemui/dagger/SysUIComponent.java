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

import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.CoreStartable;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SystemUIAppComponentFactoryBase;
import com.android.systemui.common.ui.GlobalConfig;
import com.android.systemui.dagger.qualifiers.PerUser;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.people.PeopleProvider;
import com.android.systemui.startable.Dependencies;
import com.android.systemui.statusbar.NotificationInsetsModule;
import com.android.systemui.statusbar.QsFrameTranslateModule;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.keyguard.KeyguardTransitions;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.shared.ShellTransitions;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.taskview.TaskViewFactory;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Provider;

/**
 * An example Dagger Subcomponent for Core SysUI.
 * <p>
 * See {@link ReferenceSysUIComponent} for the one actually used by AOSP.
 */
@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        NotificationInsetsModule.class,
        QsFrameTranslateModule.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        SystemUICoreStartableModule.class,
        ReferenceSystemUIModule.class})
public interface SysUIComponent {

    /**
     * Builder for a SysUIComponent.
     */
    @SysUISingleton
    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        Builder setShell(ShellInterface s);

        @BindsInstance
        Builder setPip(Optional<Pip> p);

        @BindsInstance
        Builder setSplitScreen(Optional<SplitScreen> s);

        @BindsInstance
        Builder setOneHanded(Optional<OneHanded> o);

        @BindsInstance
        Builder setBubbles(Optional<Bubbles> b);

        @BindsInstance
        Builder setTaskViewFactory(Optional<TaskViewFactory> t);

        @BindsInstance
        Builder setShellTransitions(ShellTransitions t);

        @BindsInstance
        Builder setKeyguardTransitions(KeyguardTransitions k);

        @BindsInstance
        Builder setStartingSurface(Optional<StartingSurface> s);

        @BindsInstance
        Builder setDisplayAreaHelper(Optional<DisplayAreaHelper> h);

        @BindsInstance
        Builder setRecentTasks(Optional<RecentTasks> r);

        @BindsInstance
        Builder setBackAnimation(Optional<BackAnimation> b);

        @BindsInstance
        Builder setDesktopMode(Optional<DesktopMode> d);

        SysUIComponent build();
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
    @GlobalConfig
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
     * Returns {@link CoreStartable}s that should be started with the application.
     */
    Map<Class<?>, Provider<CoreStartable>> getStartables();

    /**
     * Returns {@link CoreStartable}s that should be started for every user.
     */
    @PerUser Map<Class<?>, Provider<CoreStartable>> getPerUserStartables();

    /**
     * Returns {@link CoreStartable} dependencies if there are any.
     */
    @Dependencies Map<Class<?>, Set<Class<? extends CoreStartable>>> getStartableDependencies();

    /**
     * Member injection into the supplied argument.
     */
    void inject(SystemUIAppComponentFactoryBase factory);

    /**
     * Member injection into the supplied argument.
     */
    void inject(KeyguardSliceProvider keyguardSliceProvider);

    /**
     * Member injection into the supplied argument.
     */
    void inject(PeopleProvider peopleProvider);
}
