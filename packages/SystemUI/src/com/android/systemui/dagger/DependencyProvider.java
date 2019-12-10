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

import android.app.INotificationManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.view.IWindowManager;
import android.view.LayoutInflater;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.dagger.qualifiers.BgHandler;
import com.android.systemui.dagger.qualifiers.BgLooper;
import com.android.systemui.dagger.qualifiers.MainHandler;
import com.android.systemui.dagger.qualifiers.MainLooper;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.PluginInitializerImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
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
 *
 * Only SystemUI owned classes and instances should go in here. Other, framework-owned classes
 * should go in {@link SystemServicesModule}.
 *
 * See SystemUI/docs/dagger.md
 */
@Module
public class DependencyProvider {

    @Singleton
    @Provides
    @Named(TIME_TICK_HANDLER_NAME)
    public Handler provideTimeTickHandler() {
        HandlerThread thread = new HandlerThread("TimeTick");
        thread.start();
        return new Handler(thread.getLooper());
    }

    @Singleton
    @Provides
    @BgLooper
    public Looper provideBgLooper() {
        HandlerThread thread = new HandlerThread("SysUiBg",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return thread.getLooper();
    }

    /** Main Looper */
    @Provides
    @MainLooper
    public Looper provideMainLooper() {
        return Looper.getMainLooper();
    }

    @Provides
    @BgHandler
    public Handler provideBgHandler(@BgLooper Looper bgLooper) {
        return new Handler(bgLooper);
    }

    @Provides
    @MainHandler
    public Handler provideMainHandler(@MainLooper Looper mainLooper) {
        return new Handler(mainLooper);
    }

    /** */
    @Provides
    public AmbientDisplayConfiguration provideAmbientDispalyConfiguration(Context context) {
        return new AmbientDisplayConfiguration(context);
    }

    /** */
    @Provides
    public Handler provideHandler() {
        return new Handler();
    }

    @Singleton
    @Provides
    public DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
    }

    @Singleton
    @Provides
    // Single instance of DisplayMetrics, gets updated by StatusBar, but can be used
    // anywhere it is needed.
    public DisplayMetrics provideDisplayMetrics() {
        return new DisplayMetrics();
    }

    /** */
    @Singleton
    @Provides
    public INotificationManager provideINotificationManager() {
        return INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    /** */
    @Singleton
    @Provides
    public IPackageManager provideIPackageManager() {
        return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    /** */
    @Singleton
    @Provides
    public LayoutInflater providerLayoutInflater(Context context) {
        return LayoutInflater.from(context);
    }

    @Singleton
    @Provides
    public LeakDetector provideLeakDetector() {
        return LeakDetector.create();

    }

    @Singleton
    @Provides
    public MetricsLogger provideMetricsLogger() {
        return new MetricsLogger();
    }

    @Singleton
    @Provides
    public NightDisplayListener provideNightDisplayListener(Context context,
            @BgHandler Handler bgHandler) {
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
            @MainHandler Handler mainHandler, CommandQueue commandQueue) {
        return new NavigationBarController(context, mainHandler, commandQueue);
    }

    @Singleton
    @Provides
    public ConfigurationController provideConfigurationController(Context context) {
        return new ConfigurationControllerImpl(context);
    }

    @Singleton
    @Provides
    public AutoHideController provideAutoHideController(Context context,
            @MainHandler Handler mainHandler,
            NotificationRemoteInputManager notificationRemoteInputManager,
            IWindowManager iWindowManager) {
        return new AutoHideController(context, mainHandler, notificationRemoteInputManager,
                iWindowManager);
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

    /** */
    @Provides
    public LockPatternUtils provideLockPatternUtils(Context context) {
        return new LockPatternUtils(context);
    }

    /** */
    @Provides
    public AlwaysOnDisplayPolicy provideAlwaysOnDisplayPolicy(Context context) {
        return new AlwaysOnDisplayPolicy(context);
    }

    /** */
    @Provides
    public ViewMediatorCallback providesViewMediatorCallback(KeyguardViewMediator viewMediator) {
        return viewMediator.getViewMediatorCallback();
    }
}
