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
import static com.android.systemui.Dependency.MAIN_HANDLER_NAME;
import static com.android.systemui.Dependency.TIME_TICK_HANDLER_NAME;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.plugins.PluginInitializerImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.util.leak.LeakDetector;

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
    public DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
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
    public MetricsLogger provideMetricsLogger() {
        return new MetricsLogger();
    }

    @Singleton
    @Provides
    public IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
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
    public SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
    }

    @Singleton
    @Provides
    public LeakDetector provideLeakDetector() {
        return LeakDetector.create();

    }

    @Singleton
    @Provides
    public NightDisplayListener provideNightDisplayListener(Context context,
            @Named(BG_HANDLER_NAME) Handler bgHandler) {
        return new NightDisplayListener(context, bgHandler);
    }

    @Singleton
    @Provides
    public PluginManager providePluginManager(Context context) {
        return new PluginManagerImpl(context, new PluginInitializerImpl());
    }

    @Singleton
    @Provides
    public NavigationBarController provideNavigationBarController(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        return new NavigationBarController(context, mainHandler);
    }

    @Singleton
    @Provides
    public ConfigurationController provideConfigurationController(Context context) {
        return new ConfigurationControllerImpl(context);
    }

    @Singleton
    @Provides
    public AutoHideController provideAutoHideController(Context context,
            @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
        return new AutoHideController(context, mainHandler);
    }

    @Singleton
    @Provides
    public ActivityManagerWrapper provideActivityManagerWrapper() {
        return ActivityManagerWrapper.getInstance();
    }

    @Singleton
    @Provides
    public DevicePolicyManagerWrapper provideDevicePolicyManagerWrapper() {
        return DevicePolicyManagerWrapper.getInstance();
    }

    @Singleton
    @Provides
    public PackageManagerWrapper providePackageManagerWrapper() {
        return PackageManagerWrapper.getInstance();
    }
}
