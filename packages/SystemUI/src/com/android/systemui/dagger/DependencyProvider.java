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

import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.INotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.om.OverlayManager;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.ColorDisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.view.Choreographer;
import android.view.IWindowManager;
import android.view.LayoutInflater;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.accessibility.ModeSwitchesController;
import com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.LifecycleScreenStatusProvider;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.theme.ThemeOverlayApplier;
import com.android.systemui.unfold.UnfoldTransitionFactory;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.unfold.config.UnfoldTransitionConfig;
import com.android.systemui.util.leak.LeakDetector;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies for the root component of sysui injection.
 *
 * Only SystemUI owned classes and instances should go in here. Other, framework-owned classes
 * should go in {@link FrameworkServicesModule}.
 *
 * See SystemUI/docs/dagger.md
 */
@Module(includes = {NightDisplayListenerModule.class})
public class DependencyProvider {

    /** */
    @Provides
    @SysUISingleton
    @Named(TIME_TICK_HANDLER_NAME)
    public Handler provideTimeTickHandler() {
        HandlerThread thread = new HandlerThread("TimeTick");
        thread.start();
        return new Handler(thread.getLooper());
    }

    /** */
    @Provides
    @Main
    public SharedPreferences provideSharePreferences(Context context) {
        return Prefs.get(context);
    }

    /** */
    @Provides
    public AmbientDisplayConfiguration provideAmbientDisplayConfiguration(Context context) {
        return new AmbientDisplayConfiguration(context);
    }

    /** */
    @Provides
    public Handler provideHandler() {
        return new Handler();
    }

    /** */
    @Provides
    @SysUISingleton
    public DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
    }

    @Provides
    @SysUISingleton
    public INotificationManager provideINotificationManager() {
        return INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    /** */
    @Provides
    @SysUISingleton
    public LayoutInflater providerLayoutInflater(Context context) {
        return LayoutInflater.from(context);
    }

    /** */
    @Provides
    @SysUISingleton
    public LeakDetector provideLeakDetector(DumpManager dumpManager) {
        return LeakDetector.create(dumpManager);
    }

    @SuppressLint("MissingPermission")
    @SysUISingleton
    @Provides
    @Nullable
    static LocalBluetoothManager provideLocalBluetoothController(Context context,
            @Background Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler, UserHandle.ALL);
    }

    /** */
    @Provides
    @SysUISingleton
    public MetricsLogger provideMetricsLogger() {
        return new MetricsLogger();
    }

    /** */
    @SysUISingleton
    @Provides
    static ThemeOverlayApplier provideThemeOverlayManager(Context context,
            @Background Executor bgExecutor,
            @Main Executor mainExecutor,
            OverlayManager overlayManager,
            DumpManager dumpManager) {
        return new ThemeOverlayApplier(overlayManager, bgExecutor, mainExecutor,
                context.getString(R.string.launcher_overlayable_package),
                context.getString(R.string.themepicker_overlayable_package), dumpManager);
    }

    /** */
    @Provides
    @SysUISingleton
    public AccessibilityFloatingMenuController provideAccessibilityFloatingMenuController(
            Context context, AccessibilityButtonTargetsObserver accessibilityButtonTargetsObserver,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        return new AccessibilityFloatingMenuController(context, accessibilityButtonTargetsObserver,
                accessibilityButtonModeObserver, keyguardUpdateMonitor);
    }

    /** */
    @Provides
    @SysUISingleton
    public ConfigurationController provideConfigurationController(Context context) {
        return new ConfigurationControllerImpl(context);
    }

    /** */
    @SysUISingleton
    @Provides
    public AutoHideController provideAutoHideController(Context context,
            @Main Handler mainHandler, IWindowManager iWindowManager) {
        return new AutoHideController(context, mainHandler, iWindowManager);
    }

    /** */
    @SysUISingleton
    @Provides
    public ReduceBrightColorsController provideReduceBrightColorsListener(
            @Background Handler bgHandler, UserTracker userTracker,
            ColorDisplayManager colorDisplayManager, SecureSettings secureSettings) {
        return new ReduceBrightColorsController(userTracker, bgHandler,
                colorDisplayManager, secureSettings);
    }

    @Provides
    @SysUISingleton
    public ActivityManagerWrapper provideActivityManagerWrapper() {
        return ActivityManagerWrapper.getInstance();
    }

    /** */
    @Provides
    @SysUISingleton
    public TaskStackChangeListeners provideTaskStackChangeListeners() {
        return TaskStackChangeListeners.getInstance();
    }

    /** Provides and initializes the {#link BroadcastDispatcher} for SystemUI */
    @Provides
    @SysUISingleton
    public BroadcastDispatcher providesBroadcastDispatcher(
            Context context,
            @Background Looper backgroundLooper,
            @Background Executor backgroundExecutor,
            DumpManager dumpManager,
            BroadcastDispatcherLogger logger,
            UserTracker userTracker
    ) {
        BroadcastDispatcher bD = new BroadcastDispatcher(context, backgroundLooper,
                backgroundExecutor, dumpManager, logger, userTracker);
        bD.initialize();
        return bD;
    }

    /** */
    @Provides
    @SysUISingleton
    public DevicePolicyManagerWrapper provideDevicePolicyManagerWrapper() {
        return DevicePolicyManagerWrapper.getInstance();
    }

    /** */
    @Provides
    @SysUISingleton
    public LockPatternUtils provideLockPatternUtils(Context context) {
        return new LockPatternUtils(context);
    }

    /** */
    @Provides
    @SysUISingleton
    public AlwaysOnDisplayPolicy provideAlwaysOnDisplayPolicy(Context context) {
        return new AlwaysOnDisplayPolicy(context);
    }

    /***/
    @Provides
    public NotificationMessagingUtil provideNotificationMessagingUtil(Context context) {
        return new NotificationMessagingUtil(context);
    }

    /** */
    @Provides
    public ViewMediatorCallback providesViewMediatorCallback(KeyguardViewMediator viewMediator) {
        return viewMediator.getViewMediatorCallback();
    }

    /** */
    @Provides
    public WindowManagerWrapper providesWindowManagerWrapper() {
        return WindowManagerWrapper.getInstance();
    }

    /** */
    @Provides
    @SysUISingleton
    public UnfoldTransitionProgressProvider provideUnfoldTransitionProgressProvider(
            Context context,
            UnfoldTransitionConfig config,
            LifecycleScreenStatusProvider screenStatusProvider,
            DeviceStateManager deviceStateManager,
            SensorManager sensorManager,
            @Main Executor executor,
            @Main Handler handler
    ) {
        return UnfoldTransitionFactory
                .createUnfoldTransitionProgressProvider(
                        context,
                        config,
                        screenStatusProvider,
                        deviceStateManager,
                        sensorManager,
                        handler,
                        executor
                );
    }

    /** */
    @Provides
    @SysUISingleton
    public UnfoldTransitionConfig provideUnfoldTransitionConfig(Context context) {
        return UnfoldTransitionFactory.createConfig(context);
    }

    /** */
    @Provides
    @SysUISingleton
    public Choreographer providesChoreographer() {
        return Choreographer.getInstance();
    }

    /** */
    @Provides
    @SysUISingleton
    public ModeSwitchesController providesModeSwitchesController(Context context) {
        return new ModeSwitchesController(context);
    }
}
