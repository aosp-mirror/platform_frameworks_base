/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui;

import static com.android.systemui.Dependency.BG_HANDLER_NAME;
import static com.android.systemui.Dependency.BG_LOOPER_NAME;
import static com.android.systemui.Dependency.LEAK_REPORT_EMAIL_NAME;
import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;
import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.app.ColorDisplayController;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.appops.AppOpsControllerImpl;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.PluginInitializerImpl;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.power.PowerUI;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.statusbar.DisplayNavigationBarController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.phone.DarkIconDispatcherImpl;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileControllerImpl;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconControllerImpl;
import com.android.systemui.statusbar.phone.StatusBarRemoteInputCallback;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothControllerImpl;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedControllerImpl;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionControllerImpl;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightControllerImpl;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.HotspotControllerImpl;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.statusbar.policy.IconLoggerImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitorImpl;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationControllerImpl;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkControllerImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmControllerImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityControllerImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoControllerImpl;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeControllerImpl;
import com.android.systemui.tuner.TunablePadding;
import com.android.systemui.tuner.TunablePadding.TunablePaddingService;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerServiceImpl;
import com.android.systemui.util.AsyncSensorManager;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.leak.LeakReporter;
import com.android.systemui.volume.VolumeDialogControllerImpl;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies for the root component of sysui injection.
 * See SystemUI/docs/dagger.md
 */
@Module
public class DependencyProvider {

    @Singleton
    @Provides
    @Named(TIME_TICK_HANDLER_NAME)
    public Handler provideHandler() {
        HandlerThread thread = new HandlerThread("TimeTick");
        thread.start();
        return new Handler(thread.getLooper());
    }

    @Singleton
    @Provides
    @Named(BG_LOOPER_NAME)
    public Looper provideBgLooper() {
        HandlerThread thread = new HandlerThread("SysUiBg",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return thread.getLooper();
    }

    @Singleton
    @Provides
    @Named(BG_HANDLER_NAME)
    public Handler provideBgHandler(@Named(BG_LOOPER_NAME) Looper bgLooper) {
        return new Handler(bgLooper);
    }

    @Singleton
    @Provides
    @Named(MAIN_HANDLER_NAME)
    public Handler provideMainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    @Singleton
    @Provides
    public ActivityStarter provideActivityStarter() {
        return new ActivityStarterDelegate();
    }

    @Singleton
    @Provides
    public InitController provideInitController() {
        return new InitController();
    }

    @Singleton
    @Provides
    public ActivityStarterDelegate provideActivityStarterDelegate(ActivityStarter starter) {
        return (ActivityStarterDelegate) starter;
    }

    @Singleton
    @Provides
    public AsyncSensorManager provideAsyncSensorManager(Context context, PluginManager manager) {
        return new AsyncSensorManager(context.getSystemService(SensorManager.class),
                manager);

    }

    @Singleton
    @Provides
    public BluetoothController provideBluetoothController(Context context,
            @Named(BG_LOOPER_NAME) Looper looper) {
        return new BluetoothControllerImpl(context, looper);

    }

    @Singleton
    @Provides
    public LocationController provideLocationController(Context context,
            @Named(BG_LOOPER_NAME) Looper bgLooper) {
        return new LocationControllerImpl(context, bgLooper);

    }

    @Singleton
    @Provides
    public RotationLockController provideRotationLockController(Context context) {
        return new RotationLockControllerImpl(context);

    }

    @Singleton
    @Provides
    public NetworkController provideNetworkController(Context context,
            @Named(BG_LOOPER_NAME) Looper bgLooper, DeviceProvisionedController controller) {
        return new NetworkControllerImpl(context, bgLooper,
                controller);

    }

    @Singleton
    @Provides
    public ZenModeController provideZenModeController(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        return new ZenModeControllerImpl(context, mainHandler);

    }

    @Singleton
    @Provides
    public HotspotController provideHotspotController(Context context) {
        return new HotspotControllerImpl(context);

    }

    @Singleton
    @Provides
    public CastController provideCastController(Context context) {
        return new CastControllerImpl(context);

    }

    @Singleton
    @Provides
    public FlashlightController provideFlashlightController(Context context) {
        return new FlashlightControllerImpl(context);

    }

    @Singleton
    @Provides
    public KeyguardMonitor provideKeyguardMonitor(Context context) {
        return new KeyguardMonitorImpl(context);

    }

    @Singleton
    @Provides
    public UserSwitcherController provideUserSwitcherController(Context context,
            KeyguardMonitor keyguardMonitor, @Named(MAIN_HANDLER_NAME) Handler mainHandler,
            ActivityStarter activityStarter) {
        return new UserSwitcherController(context, keyguardMonitor, mainHandler, activityStarter);
    }

    @Singleton
    @Provides
    public UserInfoController provideUserInfoContrller(Context context) {
        return new UserInfoControllerImpl(context);

    }

    @Singleton
    @Provides
    public BatteryController provideBatteryController(Context context) {
        return new BatteryControllerImpl(context);

    }

    @Singleton
    @Provides
    public ColorDisplayController provideColorDisplayController(Context context) {
        return new ColorDisplayController(context);

    }

    @Singleton
    @Provides
    public ManagedProfileController provideManagedProfileController(Context context) {
        return new ManagedProfileControllerImpl(context);

    }

    @Singleton
    @Provides
    public NextAlarmController provideNextAlarmController(Context context) {
        return new NextAlarmControllerImpl(context);

    }

    @Singleton
    @Provides
    public DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
    }

    @Singleton
    @Provides
    public AccessibilityController provideAccessibilityController(Context context) {
        return new AccessibilityController(context);

    }

    @Singleton
    @Provides
    public DeviceProvisionedController provideDeviceProvisionedController(Context context) {
        return new DeviceProvisionedControllerImpl(context);

    }

    @Singleton
    @Provides
    public PluginManager providePluginManager(Context context) {
        return new PluginManagerImpl(context, new PluginInitializerImpl());

    }

    @Singleton
    @Provides
    public SecurityController provideSecurityController(Context context) {
        return new SecurityControllerImpl(context);

    }

    @Singleton
    @Provides
    public LeakDetector provideLeakDetector() {
        return LeakDetector.create();

    }

    @Singleton
    @Provides
    public LeakReporter provideLeakReporter(Context context, LeakDetector detector,
            @Nullable @Named(LEAK_REPORT_EMAIL_NAME) String email) {
        return new LeakReporter(context, detector, email);
    }

    @Singleton
    @Provides
    public GarbageMonitor provideGarbageMonitor(Context context,
            @Named(BG_LOOPER_NAME) Looper bgLooper, LeakDetector detector, LeakReporter reporter) {
        return new GarbageMonitor(context, bgLooper, detector, reporter);
    }

    @Singleton
    @Provides
    public TunerService provideTunerService(Context context) {
        return new TunerServiceImpl(context);

    }

    @Singleton
    @Provides
    public StatusBarWindowController provideStatusBarWindowController(Context context) {
        return new StatusBarWindowController(context);

    }

    @Singleton
    @Provides
    public DarkIconDispatcher provideDarkIconDispatcher(Context context) {
        return new DarkIconDispatcherImpl(context);
    }

    @Singleton
    @Provides
    public ConfigurationController provideConfigurationController(Context context) {
        return new ConfigurationControllerImpl(context);

    }

    @Singleton
    @Provides
    public StatusBarIconController provideStatusBarIconController(Context context) {
        return new StatusBarIconControllerImpl(context);

    }

    @Singleton
    @Provides
    public ScreenLifecycle provideScreenLifecycle() {
        return new ScreenLifecycle();
    }

    @Singleton
    @Provides
    public WakefulnessLifecycle provideWakefulnessLifecycle() {
        return new WakefulnessLifecycle();
    }

    @Singleton
    @Provides
    public ExtensionController provideExtensionController(Context context) {
        return new ExtensionControllerImpl(context);
    }

    @Singleton
    @Provides
    public PluginDependencyProvider providePluginDependency(PluginManager pluginManager) {
        return new PluginDependencyProvider(pluginManager);
    }

    @Singleton
    @Provides
    @Nullable
    public LocalBluetoothManager provideLocalBluetoothController(Context context,
            @Named(BG_HANDLER_NAME) Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler,
                UserHandle.ALL);
    }

    @Singleton
    @Provides
    public VolumeDialogController provideVolumeDialogController(Context context) {
        return new VolumeDialogControllerImpl(context);

    }

    @Singleton
    @Provides
    public MetricsLogger provideMetricsLogger() {
        return new MetricsLogger();

    }

    @Singleton
    @Provides
    public AccessibilityManagerWrapper provideAccessibilityManagerWrapper(Context context) {
        return new AccessibilityManagerWrapper(context);

    }

    @Singleton
    @Provides
    // Creating a new instance will trigger color extraction.
    // Thankfully this only happens once - during boot - and WallpaperManagerService
    // loads colors from cache.
    public SysuiColorExtractor provideSysuiColorExtractor(Context context) {
        return new SysuiColorExtractor(context);

    }

    @Singleton
    @Provides
    public TunablePadding.TunablePaddingService provideTunablePaddingService() {
        return new TunablePaddingService();

    }

    @Singleton
    @Provides
    public ForegroundServiceController provideForegroundService(Context context) {
        return new ForegroundServiceControllerImpl(context);

    }

    @Singleton
    @Provides
    public UiOffloadThread provideUiOffloadThread() {
        return new UiOffloadThread();
    }

    @Singleton
    @Provides
    public PowerUI.WarningsUI provideWarningsUi(Context context) {
        return new PowerNotificationWarnings(context);
    }

    @Singleton
    @Provides
    public IconLogger provideIconLogger(Context context, @Named(BG_LOOPER_NAME) Looper bgLooper,
            MetricsLogger logger) {
        return new IconLoggerImpl(context, bgLooper, logger);
    }

    @Singleton
    @Provides
    public LightBarController provideLightBarController(Context context) {
        return new LightBarController(context);
    }

    @Singleton
    @Provides
    public IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
    }

    @Singleton
    @Provides
    public OverviewProxyService provideOverviewProxyService(Context context) {
        return new OverviewProxyService(context);
    }

    @Singleton
    @Provides
    public VibratorHelper provideVibratorHelper(Context context) {
        return new VibratorHelper(context);

    }

    @Singleton
    @Provides
    public IStatusBarService provideIStatusBarService() {
        return IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    @Singleton
    @Provides
    // Single instance of DisplayMetrics, gets updated by StatusBar, but can be used
// anywhere it is needed.
    public DisplayMetrics provideDisplayMetrics() {
        return new DisplayMetrics();

    }

    @Singleton
    @Provides
    public LockscreenGestureLogger provideLockscreenGestureLogger() {
        return new LockscreenGestureLogger();
    }

    @Singleton
    @Provides
    public ShadeController provideShadeController(Context context) {
        return SysUiServiceProvider.getComponent(context, StatusBar.class);
    }

    @Singleton
    @Provides
    public NotificationRemoteInputManager.Callback provideNotificationRemoteInputManager(
            Context context) {
        return new StatusBarRemoteInputCallback(context);

    }

    @Singleton
    @Provides
    public AppOpsController provideAppOpsController(Context context,
            @Named(BG_LOOPER_NAME) Looper bgLooper) {
        return new AppOpsControllerImpl(context, bgLooper);

    }

    @Singleton
    @Provides
    public DisplayNavigationBarController provideDisplayNavigationBarController(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        return new DisplayNavigationBarController(context, mainHandler);
    }

    @Singleton
    @Provides
    public SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
    }
}
