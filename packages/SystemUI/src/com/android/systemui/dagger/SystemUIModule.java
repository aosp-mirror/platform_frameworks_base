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
import android.app.Service;
import android.app.backup.BackupManager;
import android.content.Context;
import android.service.dreams.IDreamManager;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.dagger.ClockRegistryModule;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.CameraProtectionModule;
import com.android.systemui.CoreStartable;
import com.android.systemui.SystemUISecondaryUserService;
import com.android.systemui.accessibility.AccessibilityModule;
import com.android.systemui.accessibility.data.repository.AccessibilityRepositoryModule;
import com.android.systemui.ambient.dagger.AmbientModule;
import com.android.systemui.appops.dagger.AppOpsModule;
import com.android.systemui.assist.AssistModule;
import com.android.systemui.authentication.AuthenticationModule;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.biometrics.FingerprintReEnrollNotification;
import com.android.systemui.biometrics.UdfpsDisplayModeProvider;
import com.android.systemui.biometrics.dagger.BiometricsModule;
import com.android.systemui.biometrics.domain.BiometricsDomainLayerModule;
import com.android.systemui.bouncer.data.repository.BouncerRepositoryModule;
import com.android.systemui.bouncer.domain.interactor.BouncerInteractorModule;
import com.android.systemui.bouncer.ui.BouncerViewModule;
import com.android.systemui.brightness.dagger.ScreenBrightnessModule;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule;
import com.android.systemui.common.data.CommonDataLayerModule;
import com.android.systemui.communal.dagger.CommunalModule;
import com.android.systemui.complication.dagger.ComplicationComponent;
import com.android.systemui.controls.dagger.ControlsModule;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.SystemUser;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.demomode.dagger.DemoModeModule;
import com.android.systemui.deviceentry.DeviceEntryModule;
import com.android.systemui.display.DisplayModule;
import com.android.systemui.doze.dagger.DozeComponent;
import com.android.systemui.dreams.dagger.DreamModule;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.FlagDependenciesModule;
import com.android.systemui.flags.FlagsModule;
import com.android.systemui.inputmethod.InputMethodModule;
import com.android.systemui.keyboard.KeyboardModule;
import com.android.systemui.keyevent.data.repository.KeyEventRepositoryModule;
import com.android.systemui.keyguard.ui.composable.LockscreenContent;
import com.android.systemui.log.dagger.LogModule;
import com.android.systemui.log.dagger.MonitorLog;
import com.android.systemui.log.table.TableLogBuffer;
import com.android.systemui.mediaprojection.appselector.MediaProjectionModule;
import com.android.systemui.mediaprojection.taskswitcher.MediaProjectionTaskSwitcherModule;
import com.android.systemui.model.SceneContainerPlugin;
import com.android.systemui.model.SysUiState;
import com.android.systemui.motiontool.MotionToolModule;
import com.android.systemui.navigationbar.NavigationBarComponent;
import com.android.systemui.notetask.NoteTaskModule;
import com.android.systemui.people.PeopleModule;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.privacy.PrivacyModule;
import com.android.systemui.process.condition.SystemProcessCondition;
import com.android.systemui.qrcodescanner.dagger.QRCodeScannerModule;
import com.android.systemui.qs.FgsManagerController;
import com.android.systemui.qs.FgsManagerControllerImpl;
import com.android.systemui.qs.QSFragmentStartableModule;
import com.android.systemui.qs.footer.dagger.FooterActionsModule;
import com.android.systemui.recents.Recents;
import com.android.systemui.recordissue.RecordIssueModule;
import com.android.systemui.retail.dagger.RetailModeModule;
import com.android.systemui.scene.shared.model.SceneContainerConfig;
import com.android.systemui.scene.shared.model.SceneDataSource;
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator;
import com.android.systemui.scene.ui.view.WindowRootViewComponent;
import com.android.systemui.screenrecord.ScreenRecordModule;
import com.android.systemui.screenshot.dagger.ScreenshotModule;
import com.android.systemui.security.data.repository.SecurityRepositoryModule;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolatorImpl;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.smartspace.dagger.SmartspaceModule;
import com.android.systemui.startable.Dependencies;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.connectivity.ConnectivityModule;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.disableflags.dagger.DisableFlagsModule;
import com.android.systemui.statusbar.events.StatusBarEventsModule;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider;
import com.android.systemui.statusbar.notification.people.PeopleHubModule;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.ConfigurationControllerModule;
import com.android.systemui.statusbar.phone.LetterboxModule;
import com.android.systemui.statusbar.phone.NotificationIconAreaControllerModule;
import com.android.systemui.statusbar.pipeline.dagger.StatusBarPipelineModule;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.PolicyModule;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.dagger.SmartRepliesInflationModule;
import com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule;
import com.android.systemui.statusbar.ui.binder.StatusBarViewBinderModule;
import com.android.systemui.statusbar.window.StatusBarWindowModule;
import com.android.systemui.telephony.data.repository.TelephonyRepositoryModule;
import com.android.systemui.temporarydisplay.dagger.TemporaryDisplayModule;
import com.android.systemui.tuner.dagger.TunerModule;
import com.android.systemui.unfold.SysUIUnfoldModule;
import com.android.systemui.user.UserModule;
import com.android.systemui.user.domain.UserDomainLayerModule;
import com.android.systemui.util.EventLogModule;
import com.android.systemui.util.concurrency.SysUIConcurrencyModule;
import com.android.systemui.util.dagger.UtilModule;
import com.android.systemui.util.kotlin.SysUICoroutinesModule;
import com.android.systemui.util.reference.ReferenceModule;
import com.android.systemui.util.sensors.SensorModule;
import com.android.systemui.util.settings.SettingsUtilModule;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.time.SystemClockImpl;
import com.android.systemui.wallet.dagger.WalletModule;
import com.android.systemui.wmshell.BubblesManager;
import com.android.wm.shell.bubbles.Bubbles;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;

import kotlinx.coroutines.CoroutineScope;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Named;

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
        AccessibilityModule.class,
        AccessibilityRepositoryModule.class,
        AmbientModule.class,
        AppOpsModule.class,
        AssistModule.class,
        AuthenticationModule.class,
        BiometricsModule.class,
        BiometricsDomainLayerModule.class,
        BouncerInteractorModule.class,
        BouncerRepositoryModule.class,
        BouncerViewModule.class,
        CameraProtectionModule.class,
        ClipboardOverlayModule.class,
        ClockRegistryModule.class,
        CommunalModule.class,
        CommonDataLayerModule.class,
        ConfigurationControllerModule.class,
        ConnectivityModule.class,
        ControlsModule.class,
        DemoModeModule.class,
        DeviceEntryModule.class,
        DisableFlagsModule.class,
        DisplayModule.class,
        DreamModule.class,
        EventLogModule.class,
        FalsingModule.class,
        FlagsModule.class,
        FlagDependenciesModule.class,
        FooterActionsModule.class,
        InputMethodModule.class,
        KeyEventRepositoryModule.class,
        KeyboardModule.class,
        LetterboxModule.class,
        LogModule.class,
        MediaProjectionModule.class,
        MediaProjectionTaskSwitcherModule.class,
        MotionToolModule.class,
        NotificationIconAreaControllerModule.class,
        PeopleHubModule.class,
        PeopleModule.class,
        PluginModule.class,
        PolicyModule.class,
        PrivacyModule.class,
        QRCodeScannerModule.class,
        QSFragmentStartableModule.class,
        RecordIssueModule.class,
        ReferenceModule.class,
        RetailModeModule.class,
        ScreenBrightnessModule.class,
        ScreenshotModule.class,
        SensorModule.class,
        SecurityRepositoryModule.class,
        ScreenRecordModule.class,
        SettingsUtilModule.class,
        SmartRepliesInflationModule.class,
        SmartspaceModule.class,
        StatusBarEventsModule.class,
        StatusBarModule.class,
        StatusBarPipelineModule.class,
        StatusBarPolicyModule.class,
        StatusBarViewBinderModule.class,
        StatusBarWindowModule.class,
        SystemPropertiesFlagsModule.class,
        SysUIConcurrencyModule.class,
        SysUICoroutinesModule.class,
        SysUIUnfoldModule.class,
        TelephonyRepositoryModule.class,
        TemporaryDisplayModule.class,
        TunerModule.class,
        UserDomainLayerModule.class,
        UserModule.class,
        UtilModule.class,
        NoteTaskModule.class,
        WalletModule.class
        },
        subcomponents = {
            ComplicationComponent.class,
            DozeComponent.class,
            ExpandableNotificationRowComponent.class,
            KeyguardBouncerComponent.class,
            NavigationBarComponent.class,
            NotificationRowComponent.class,
            WindowRootViewComponent.class,
        })
public abstract class SystemUIModule {

    @Multibinds
    @Dependencies
    abstract Map<Class<?>, Set<Class<? extends CoreStartable>>> startableDependencyMap();

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
    static SysUiState provideSysUiState(
            DisplayTracker displayTracker,
            DumpManager dumpManager,
            SceneContainerPlugin sceneContainerPlugin) {
        final SysUiState state = new SysUiState(displayTracker, sceneContainerPlugin);
        dumpManager.registerDumpable(state);
        return state;
    }

    /**
     * Provides the monitor for SystemUI that requires the process running as the system user.
     */
    @SysUISingleton
    @Provides
    @SystemUser
    static Monitor provideSystemUserMonitor(@Main Executor executor,
            SystemProcessCondition systemProcessCondition, @MonitorLog TableLogBuffer logBuffer) {
        return new Monitor(executor, Collections.singleton(systemProcessCondition), logBuffer);
    }

    /** Provides the package name for SystemUI. */
    @SysUISingleton
    @Provides
    static BackupManager provideBackupManager(@Application Context context) {
        return new BackupManager(context);
    }

    @BindsOptionalOf
    abstract CommandQueue optionalCommandQueue();

    @BindsOptionalOf
    abstract HeadsUpManager optionalHeadsUpManager();

    @BindsOptionalOf
    abstract BcSmartspaceDataPlugin optionalBcSmartspaceDataPlugin();

    @BindsOptionalOf
    abstract BcSmartspaceConfigPlugin optionalBcSmartspaceConfigPlugin();

    @BindsOptionalOf
    @Named(SmartspaceModule.DATE_SMARTSPACE_DATA_PLUGIN)
    abstract BcSmartspaceDataPlugin optionalDateSmartspaceConfigPlugin();

    @BindsOptionalOf
    @Named(SmartspaceModule.WEATHER_SMARTSPACE_DATA_PLUGIN)
    abstract BcSmartspaceDataPlugin optionalWeatherSmartspaceConfigPlugin();

    @BindsOptionalOf
    abstract Recents optionalRecents();

    @BindsOptionalOf
    abstract CentralSurfaces optionalCentralSurfaces();

    @BindsOptionalOf
    abstract UdfpsDisplayModeProvider optionalUdfpsDisplayModeProvider();

    @BindsOptionalOf
    abstract FingerprintInteractiveToAuthProvider optionalFingerprintInteractiveToAuthProvider();

    @BindsOptionalOf
    abstract SystemStatusAnimationScheduler optionalSystemStatusAnimationScheduler();

    @BindsOptionalOf
    abstract FingerprintReEnrollNotification optionalFingerprintReEnrollNotification();

    @BindsOptionalOf
    abstract LockscreenContent optionalLockscreenContent();

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
            VisualInterruptionDecisionProvider visualInterruptionDecisionProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            SensitiveNotificationProtectionController sensitiveNotificationProtectionController,
            CommonNotifCollection notifCollection,
            NotifPipeline notifPipeline,
            SysUiState sysUiState,
            FeatureFlags featureFlags,
            NotifPipelineFlags notifPipelineFlags,
            @Main Executor sysuiMainExecutor,
            @UiBackground Executor sysuiUiBgExecutor) {
        return Optional.ofNullable(BubblesManager.create(context,
                bubblesOptional,
                notificationShadeWindowController,
                keyguardStateController,
                shadeController,
                statusBarService,
                notificationManager,
                dreamManager,
                visibilityProvider,
                visualInterruptionDecisionProvider,
                zenModeController,
                notifUserManager,
                sensitiveNotificationProtectionController,
                notifCollection,
                notifPipeline,
                sysUiState,
                featureFlags,
                notifPipelineFlags,
                sysuiMainExecutor,
                sysuiUiBgExecutor));
    }

    @Binds
    abstract FgsManagerController bindFgsManagerController(FgsManagerControllerImpl impl);

    @Binds
    abstract LargeScreenShadeInterpolator largeScreensShadeInterpolator(
            LargeScreenShadeInterpolatorImpl impl);

    @Binds
    @IntoMap
    @ClassKey(SystemUISecondaryUserService.class)
    abstract Service bindsSystemUISecondaryUserService(SystemUISecondaryUserService service);

    @Provides
    @SysUISingleton
    static SceneDataSourceDelegator providesSceneDataSourceDelegator(
            @Application CoroutineScope applicationScope, SceneContainerConfig config) {
        return new SceneDataSourceDelegator(applicationScope, config);
    }

    @Binds
    abstract SceneDataSource bindSceneDataSource(SceneDataSourceDelegator delegator);
}
