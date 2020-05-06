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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.app.IBatteryStats;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.PackageManagerWrapper;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Provides Non-SystemUI, Framework-Owned instances to the dependency graph.
 */
@Module
public class SystemServicesModule {
    @Provides
    @Singleton
    static AccessibilityManager provideAccessibilityManager(Context context) {
        return context.getSystemService(AccessibilityManager.class);
    }

    @Provides
    @Singleton
    static ActivityManager provideActivityManager(Context context) {
        return context.getSystemService(ActivityManager.class);
    }

    @Singleton
    @Provides
    static AlarmManager provideAlarmManager(Context context) {
        return context.getSystemService(AlarmManager.class);
    }

    @Provides
    @Singleton
    static AudioManager provideAudioManager(Context context) {
        return context.getSystemService(AudioManager.class);
    }

    @Provides
    @Singleton
    static ConnectivityManager provideConnectivityManagager(Context context) {
        return context.getSystemService(ConnectivityManager.class);
    }

    @Provides
    @Singleton
    static ContentResolver provideContentResolver(Context context) {
        return context.getContentResolver();
    }

    @Provides
    @Singleton
    static DevicePolicyManager provideDevicePolicyManager(Context context) {
        return context.getSystemService(DevicePolicyManager.class);
    }

    @Provides
    @DisplayId
    static int provideDisplayId(Context context) {
        return context.getDisplayId();
    }

    @Provides
    @Singleton
    static DisplayManager provideDisplayManager(Context context) {
        return context.getSystemService(DisplayManager.class);
    }

    @Singleton
    @Provides
    static IActivityManager provideIActivityManager() {
        return ActivityManager.getService();
    }

    @Singleton
    @Provides
    static IActivityTaskManager provideIActivityTaskManager() {
        return ActivityTaskManager.getService();
    }

    @Provides
    @Singleton
    static IBatteryStats provideIBatteryStats() {
        return IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
    }

    @Provides
    @Singleton
    static IDreamManager provideIDreamManager() {
        return IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
    }

    @Provides
    @Singleton
    static IPackageManager provideIPackageManager() {
        return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    @Singleton
    @Provides
    static IStatusBarService provideIStatusBarService() {
        return IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    @Provides
    @Nullable
    static IWallpaperManager provideIWallPaperManager() {
        return IWallpaperManager.Stub.asInterface(
                ServiceManager.getService(Context.WALLPAPER_SERVICE));
    }

    @Singleton
    @Provides
    static IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
    }

    @Singleton
    @Provides
    static KeyguardManager provideKeyguardManager(Context context) {
        return context.getSystemService(KeyguardManager.class);
    }

    @Singleton
    @Provides
    static LatencyTracker provideLatencyTracker(Context context) {
        return LatencyTracker.getInstance(context);
    }

    @Singleton
    @Provides
    static LauncherApps provideLauncherApps(Context context) {
        return context.getSystemService(LauncherApps.class);
    }

    @SuppressLint("MissingPermission")
    @Singleton
    @Provides
    @Nullable
    static LocalBluetoothManager provideLocalBluetoothController(Context context,
            @Background Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler, UserHandle.ALL);
    }

    @Provides
    @Singleton
    static NetworkScoreManager provideNetworkScoreManager(Context context) {
        return context.getSystemService(NetworkScoreManager.class);
    }

    @Singleton
    @Provides
    static NotificationManager provideNotificationManager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    @Singleton
    @Provides
    static PackageManager providePackageManager(Context context) {
        return context.getPackageManager();
    }

    @Singleton
    @Provides
    static PackageManagerWrapper providePackageManagerWrapper() {
        return PackageManagerWrapper.getInstance();
    }

    /** */
    @Singleton
    @Provides
    static PowerManager providePowerManager(Context context) {
        return context.getSystemService(PowerManager.class);
    }

    @Provides
    @Main
    static Resources provideResources(Context context) {
        return context.getResources();
    }

    @Singleton
    @Provides
    static SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
    }

    @Singleton
    @Provides
    static ShortcutManager provideShortcutManager(Context context) {
        return context.getSystemService(ShortcutManager.class);
    }

    @Provides
    @Singleton
    @Nullable
    static TelecomManager provideTelecomManager(Context context) {
        return context.getSystemService(TelecomManager.class);
    }

    @Provides
    @Singleton
    static TelephonyManager provideTelephonyManager(Context context) {
        return context.getSystemService(TelephonyManager.class);
    }

    @Provides
    @Singleton
    static TrustManager provideTrustManager(Context context) {
        return context.getSystemService(TrustManager.class);
    }

    @Provides
    @Singleton
    @Nullable
    static Vibrator provideVibrator(Context context) {
        return context.getSystemService(Vibrator.class);
    }

    @Provides
    @Singleton
    static UserManager provideUserManager(Context context) {
        return context.getSystemService(UserManager.class);
    }

    @Provides
    static WallpaperManager provideWallpaperManager(Context context) {
        return (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
    }

    @Provides
    @Singleton
    static WifiManager provideWifiManager(Context context) {
        return context.getSystemService(WifiManager.class);
    }

    @Singleton
    @Provides
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

}
