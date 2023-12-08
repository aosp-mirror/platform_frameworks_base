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
import com.android.systemui.Flags;
import com.android.systemui.InitController;
import com.android.systemui.SystemUIAppComponentFactoryBase;
import com.android.systemui.dagger.qualifiers.PerUser;
import com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionCli;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.people.PeopleProvider;
import com.android.systemui.statusbar.NotificationInsetsModule;
import com.android.systemui.statusbar.QsFrameTranslateModule;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.unfold.FoldStateLogger;
import com.android.systemui.unfold.FoldStateLoggingProvider;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.unfold.dagger.UnfoldBg;
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.displayareahelper.DisplayAreaHelper;
import com.android.wm.shell.keyguard.KeyguardTransitions;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.sysui.ShellInterface;
import com.android.wm.shell.taskview.TaskViewFactory;
import com.android.wm.shell.transition.ShellTransitions;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.util.Map;
import java.util.Optional;

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
        Builder setTransitions(ShellTransitions t);

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
     * Initializes all the SysUI components.
     */
    default void init() {
        // Initialize components that have no direct tie to the dagger dependency graph,
        // but are critical to this component's operation
        getSysUIUnfoldComponent()
                .ifPresent(
                        c -> {
                            c.getUnfoldLightRevealOverlayAnimation().init();
                            c.getUnfoldTransitionWallpaperController().init();
                            c.getUnfoldHapticsPlayer();
                            c.getNaturalRotationUnfoldProgressProvider().init();
                            c.getUnfoldLatencyTracker().init();
                        });
        // No init method needed, just needs to be gotten so that it's created.
        getMediaMuteAwaitConnectionCli();
        getNearbyMediaDevicesManager();
        getConnectingDisplayViewModel().init();
        getFoldStateLoggingProvider().ifPresent(FoldStateLoggingProvider::init);
        getFoldStateLogger().ifPresent(FoldStateLogger::init);

        Optional<UnfoldTransitionProgressProvider> unfoldTransitionProgressProvider;

        if (Flags.unfoldAnimationBackgroundProgress()) {
            unfoldTransitionProgressProvider = getBgUnfoldTransitionProgressProvider();
        } else {
            unfoldTransitionProgressProvider = getUnfoldTransitionProgressProvider();
        }
        unfoldTransitionProgressProvider
                .ifPresent(
                        (progressProvider) ->
                                getUnfoldTransitionProgressForwarder()
                                        .ifPresent(progressProvider::addCallback));
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
     * Creates a UnfoldTransitionProgressProvider that calculates progress in the background.
     */
    @SysUISingleton
    @UnfoldBg
    Optional<UnfoldTransitionProgressProvider> getBgUnfoldTransitionProgressProvider();

    /**
     * Creates a UnfoldTransitionProgressProvider that calculates progress in the main thread.
     */
    @SysUISingleton
    Optional<UnfoldTransitionProgressProvider> getUnfoldTransitionProgressProvider();

    /**
     * Creates a UnfoldTransitionProgressForwarder.
     */
    @SysUISingleton
    Optional<UnfoldTransitionProgressForwarder> getUnfoldTransitionProgressForwarder();

    /**
     * Creates a FoldStateLoggingProvider.
     */
    @SysUISingleton
    Optional<FoldStateLoggingProvider> getFoldStateLoggingProvider();

    /**
     * Creates a FoldStateLogger.
     */
    @SysUISingleton
    Optional<FoldStateLogger> getFoldStateLogger();

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
     * For devices with a hinge: access objects within this component
     */
    Optional<SysUIUnfoldComponent> getSysUIUnfoldComponent();

    /** */
    MediaMuteAwaitConnectionCli getMediaMuteAwaitConnectionCli();

    /** */
    NearbyMediaDevicesManager getNearbyMediaDevicesManager();

    /**
     * Creates a ConnectingDisplayViewModel
     */
    ConnectingDisplayViewModel getConnectingDisplayViewModel();

    /**
     * Returns {@link CoreStartable}s that should be started with the application.
     */
    Map<Class<?>, Provider<CoreStartable>> getStartables();

    /**
     * Returns {@link CoreStartable}s that should be started for every user.
     */
    @PerUser Map<Class<?>, Provider<CoreStartable>> getPerUserStartables();

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
