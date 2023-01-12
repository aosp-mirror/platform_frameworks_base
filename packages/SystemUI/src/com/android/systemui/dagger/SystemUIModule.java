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
import android.service.dreams.IDreamManager;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.clock.ClockInfoModule;
import com.android.keyguard.dagger.ClockRegistryModule;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.appops.dagger.AppOpsModule;
import com.android.systemui.assist.AssistModule;
import com.android.systemui.biometrics.AlternateUdfpsTouchProvider;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.biometrics.UdfpsDisplayModeProvider;
import com.android.systemui.biometrics.dagger.BiometricsModule;
import com.android.systemui.biometrics.dagger.UdfpsModule;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule;
import com.android.systemui.controls.dagger.ControlsModule;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.demomode.dagger.DemoModeModule;
import com.android.systemui.doze.dagger.DozeComponent;
import com.android.systemui.dreams.dagger.DreamModule;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.FlagsModule;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.data.BouncerViewModule;
import com.android.systemui.log.dagger.LogModule;
import com.android.systemui.mediaprojection.appselector.MediaProjectionModule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.motiontool.MotionToolModule;
import com.android.systemui.navigationbar.NavigationBarComponent;
import com.android.systemui.notetask.NoteTaskModule;
import com.android.systemui.people.PeopleModule;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.privacy.PrivacyModule;
import com.android.systemui.qs.FgsManagerController;
import com.android.systemui.qs.FgsManagerControllerImpl;
import com.android.systemui.qs.footer.dagger.FooterActionsModule;
import com.android.systemui.recents.Recents;
import com.android.systemui.screenshot.dagger.ScreenshotModule;
import com.android.systemui.security.data.repository.SecurityRepositoryModule;
import com.android.systemui.settings.dagger.MultiUserUtilsModule;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.smartspace.dagger.SmartspaceModule;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.people.PeopleHubModule;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationShelfComponent;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.pipeline.dagger.StatusBarPipelineModule;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.dagger.SmartRepliesInflationModule;
import com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule;
import com.android.systemui.statusbar.window.StatusBarWindowModule;
import com.android.systemui.telephony.data.repository.TelephonyRepositoryModule;
import com.android.systemui.temporarydisplay.dagger.TemporaryDisplayModule;
import com.android.systemui.tuner.dagger.TunerModule;
import com.android.systemui.unfold.SysUIUnfoldModule;
import com.android.systemui.user.UserModule;
import com.android.systemui.util.concurrency.SysUIConcurrencyModule;
import com.android.systemui.util.dagger.UtilModule;
import com.android.systemui.util.kotlin.CoroutinesModule;
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
 * A dagger module for injecting components of System UI that are required by System UI.
 *
 * If your feature can be excluded, subclassed, or re-implemented by a variant of SystemUI, put
 * your Dagger Module in {@link ReferenceSystemUIModule} and/or any variant modules that
 * rely on the feature.
 *
 * Adding an entry in this file means that _all_ variants of SystemUI will receive that code. They
 * may not appreciate that.
 */
@Module(includes = {
            AppOpsModule.class,
            AssistModule.class,
            BiometricsModule.class,
            BouncerViewModule.class,
            ClipboardOverlayModule.class,
            ClockInfoModule.class,
            ClockRegistryModule.class,
            CoroutinesModule.class,
            DreamModule.class,
            ControlsModule.class,
            DemoModeModule.class,
            FalsingModule.class,
            FlagsModule.class,
            FooterActionsModule.class,
            LogModule.class,
            MediaProjectionModule.class,
            MotionToolModule.class,
            PeopleHubModule.class,
            PeopleModule.class,
            PluginModule.class,
            PrivacyModule.class,
            ScreenshotModule.class,
            SensorModule.class,
            MultiUserUtilsModule.class,
            SecurityRepositoryModule.class,
            SettingsUtilModule.class,
            SmartRepliesInflationModule.class,
            SmartspaceModule.class,
            StatusBarPipelineModule.class,
            StatusBarPolicyModule.class,
            StatusBarWindowModule.class,
            SysUIConcurrencyModule.class,
            SysUIUnfoldModule.class,
            TelephonyRepositoryModule.class,
            TemporaryDisplayModule.class,
            TunerModule.class,
            UdfpsModule.class,
            UserModule.class,
            UtilModule.class,
            NoteTaskModule.class,
            WalletModule.class
        },
        subcomponents = {
            CentralSurfacesComponent.class,
            NavigationBarComponent.class,
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

    /**
     *
     */
    @Binds
    public abstract ContextComponentHelper bindComponentHelper(
            ContextComponentResolver componentHelper);

    /**
     *
     */
    @Binds
    public abstract NotificationRowBinder bindNotificationRowBinder(
            NotificationRowBinderImpl notificationRowBinder);

    @SysUISingleton
    @Provides
    static SysUiState provideSysUiState(DumpManager dumpManager) {
        final SysUiState state = new SysUiState();
        dumpManager.registerDumpable(state);
        return state;
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
    abstract CentralSurfaces optionalCentralSurfaces();

    @BindsOptionalOf
    abstract UdfpsDisplayModeProvider optionalUdfpsDisplayModeProvider();

    @BindsOptionalOf
    abstract AlternateUdfpsTouchProvider optionalUdfpsTouchProvider();

    @BindsOptionalOf
    abstract FingerprintInteractiveToAuthProvider optionalFingerprintInteractiveToAuthProvider();

    @SysUISingleton
    @Binds
    abstract SystemClock bindSystemClock(SystemClockImpl systemClock);

    // TODO: This should provided by the WM component

    /** Provides Optional of BubbleManager */
    @SysUISingleton
    @Provides
    static Optional<BubblesManager> provideBubblesManager(Context context,
            Optional<Bubbles> bubblesOptional,
            NotificationShadeWindowController notificationShadeWindowController,
            KeyguardStateController keyguardStateController,
            ShadeController shadeController,
            @Nullable IStatusBarService statusBarService,
            INotificationManager notificationManager,
            IDreamManager dreamManager,
            NotificationVisibilityProvider visibilityProvider,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            CommonNotifCollection notifCollection,
            NotifPipeline notifPipeline,
            SysUiState sysUiState,
            FeatureFlags featureFlags,
            @Main Executor sysuiMainExecutor) {
        return Optional.ofNullable(BubblesManager.create(context,
                bubblesOptional,
                notificationShadeWindowController,
                keyguardStateController,
                shadeController,
                statusBarService,
                notificationManager,
                dreamManager,
                visibilityProvider,
                interruptionStateProvider,
                zenModeController,
                notifUserManager,
                notifCollection,
                notifPipeline,
                sysUiState,
                featureFlags,
                sysuiMainExecutor));
    }

    @Binds
    abstract FgsManagerController bindFgsManagerController(FgsManagerControllerImpl impl);
}
