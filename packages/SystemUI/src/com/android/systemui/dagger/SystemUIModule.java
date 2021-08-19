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

import android.app.INotificationManager;
import android.content.Context;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.clock.ClockModule;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.appops.dagger.AppOpsModule;
import com.android.systemui.assist.AssistModule;
import com.android.systemui.biometrics.UdfpsHbmProvider;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.controls.dagger.ControlsModule;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.demomode.dagger.DemoModeModule;
import com.android.systemui.doze.dagger.DozeComponent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.log.dagger.LogModule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.Recents;
import com.android.systemui.screenshot.dagger.ScreenshotModule;
import com.android.systemui.settings.dagger.SettingsModule;
import com.android.systemui.shared.system.smartspace.SmartspaceTransitionController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.people.PeopleHubModule;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationShelfComponent;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.dagger.SmartRepliesInflationModule;
import com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule;
import com.android.systemui.tuner.dagger.TunerModule;
import com.android.systemui.user.UserModule;
import com.android.systemui.util.concurrency.SysUIConcurrencyModule;
import com.android.systemui.util.dagger.UtilModule;
import com.android.systemui.util.sensors.SensorModule;
import com.android.systemui.util.settings.SettingsUtilModule;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.time.SystemClockImpl;
import com.android.systemui.wallet.dagger.WalletModule;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;
import java.util.concurrent.Executor;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * A dagger module for injecting components of System UI that are not overridden by the System UI
 * implementation.
 */
@Module(includes = {
            AppOpsModule.class,
            AssistModule.class,
            ClockModule.class,
            ControlsModule.class,
            DemoModeModule.class,
            FalsingModule.class,
            LogModule.class,
            PeopleHubModule.class,
            PluginModule.class,
            ScreenshotModule.class,
            SensorModule.class,
            SettingsModule.class,
            SettingsUtilModule.class,
            SmartRepliesInflationModule.class,
            StatusBarPolicyModule.class,
            SysUIConcurrencyModule.class,
            TunerModule.class,
            UserModule.class,
            UtilModule.class,
            WalletModule.class
        },
        subcomponents = {
            StatusBarComponent.class,
            NotificationRowComponent.class,
            DozeComponent.class,
            ExpandableNotificationRowComponent.class,
            KeyguardBouncerComponent.class,
            NotificationShelfComponent.class,
            FragmentService.FragmentCreator.class
        })
public abstract class SystemUIModule {

    @Binds
    abstract BootCompleteCache bindBootCompleteCache(BootCompleteCacheImpl bootCompleteCache);

    /** */
    @Binds
    public abstract ContextComponentHelper bindComponentHelper(
            ContextComponentResolver componentHelper);

    /** */
    @Binds
    public abstract NotificationRowBinder bindNotificationRowBinder(
            NotificationRowBinderImpl notificationRowBinder);

    @SysUISingleton
    @Provides
    static SysUiState provideSysUiState() {
        return new SysUiState();
    }

    @BindsOptionalOf
    abstract CommandQueue optionalCommandQueue();

    @BindsOptionalOf
    abstract HeadsUpManager optionalHeadsUpManager();

    @BindsOptionalOf
    abstract BcSmartspaceDataPlugin optionalBcSmartspaceDataPlugin();

    @BindsOptionalOf
    abstract Recents optionalRecents();

    @BindsOptionalOf
    abstract StatusBar optionalStatusBar();

    @BindsOptionalOf
    abstract UdfpsHbmProvider optionalUdfpsHbmProvider();

    @SysUISingleton
    @Binds
    abstract SystemClock bindSystemClock(SystemClockImpl systemClock);

    @Provides
    static SystemUIFactory getSystemUIFactory() {
        return SystemUIFactory.getInstance();
    }

    @SysUISingleton
    @Provides
    static SmartspaceTransitionController provideSmartspaceTransitionController() {
        return new SmartspaceTransitionController();
    }

    // TODO: This should provided by the WM component
    /** Provides Optional of BubbleManager */
    @SysUISingleton
    @Provides
    static Optional<BubblesManager> provideBubblesManager(Context context,
            Optional<Bubbles> bubblesOptional,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController, ShadeController shadeController,
            ConfigurationController configurationController,
            @Nullable IStatusBarService statusBarService, INotificationManager notificationManager,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController, NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManagerLegacy groupManager, NotificationEntryManager entryManager,
            NotifPipeline notifPipeline, SysUiState sysUiState, FeatureFlags featureFlags,
            DumpManager dumpManager, @Main Executor sysuiMainExecutor) {
        return Optional.ofNullable(BubblesManager.create(context, bubblesOptional,
                notificationShadeWindowController, statusBarStateController, shadeController,
                configurationController, statusBarService, notificationManager,
                interruptionStateProvider, zenModeController, notifUserManager,
                groupManager, entryManager, notifPipeline, sysUiState, featureFlags, dumpManager,
                sysuiMainExecutor));
    }
}
