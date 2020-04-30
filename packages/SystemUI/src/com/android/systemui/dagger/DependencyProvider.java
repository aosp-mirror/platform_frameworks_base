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
import android.content.SharedPreferences;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.WindowManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Prefs;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.PluginInitializerImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.DevicePolicyManagerWrapper;
import com.android.systemui.statusbar.CommandQueue;
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

    @Singleton
    @Provides
    public DataSaverController provideDataSaverController(NetworkController networkController) {
        return networkController.getDataSaverController();
    }

    @Singleton
    @Provides
    public DisplayMetrics provideDisplayMetrics(Context context, WindowManager windowManager) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        context.getDisplay().getMetrics(displayMetrics);
        return displayMetrics;
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
            @Background Handler bgHandler) {
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
            @Main Handler mainHandler, CommandQueue commandQueue) {
        return new NavigationBarController(context, mainHandler, commandQueue);
    }

    @Singleton
    @Provides
    public ConfigurationController provideConfigurationController(Context context) {
        return new ConfigurationControllerImpl(context);
    }

    /** */
    @Singleton
    @Provides
    public AutoHideController provideAutoHideController(Context context,
            @Main Handler mainHandler, IWindowManager iWindowManager) {
        return new AutoHideController(context, mainHandler, iWindowManager);
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
    @Singleton
    @Provides
    public Choreographer providesChoreographer() {
        return Choreographer.getInstance();
    }

    /** Provides an instance of {@link com.android.internal.logging.UiEventLogger} */
    @Singleton
    @Provides
    static UiEventLogger provideUiEventLogger() {
        return new UiEventLoggerImpl();
    }
}
