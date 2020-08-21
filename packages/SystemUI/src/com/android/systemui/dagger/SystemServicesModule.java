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
import android.app.role.RoleManager;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.media.AudioManager;
import android.media.MediaRouter2Manager;
import android.media.session.MediaSessionManager;
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
import android.view.ViewConfiguration;
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

import dagger.Module;
import dagger.Provides;

/**
 * Provides Non-SystemUI, Framework-Owned instances to the dependency graph.
 */
@Module
public class SystemServicesModule {
    @Provides
    @SysUISingleton
    static AccessibilityManager provideAccessibilityManager(Context context) {
        return context.getSystemService(AccessibilityManager.class);
    }

    @Provides
    @SysUISingleton
    static ActivityManager provideActivityManager(Context context) {
        return context.getSystemService(ActivityManager.class);
    }

    @SysUISingleton
    @Provides
    static AlarmManager provideAlarmManager(Context context) {
        return context.getSystemService(AlarmManager.class);
    }

    @Provides
    @SysUISingleton
    static AudioManager provideAudioManager(Context context) {
        return context.getSystemService(AudioManager.class);
    }

    @Provides
    @SysUISingleton
    static ColorDisplayManager provideColorDisplayManager(Context context) {
        return context.getSystemService(ColorDisplayManager.class);
    }

    @Provides
    @SysUISingleton
    static ConnectivityManager provideConnectivityManagager(Context context) {
        return context.getSystemService(ConnectivityManager.class);
    }

    @Provides
    @SysUISingleton
    static ContentResolver provideContentResolver(Context context) {
        return context.getContentResolver();
    }

    @Provides
    @SysUISingleton
    static DevicePolicyManager provideDevicePolicyManager(Context context) {
        return context.getSystemService(DevicePolicyManager.class);
    }

    @Provides
    @DisplayId
    static int provideDisplayId(Context context) {
        return context.getDisplayId();
    }

    @Provides
    @SysUISingleton
    static DisplayManager provideDisplayManager(Context context) {
        return context.getSystemService(DisplayManager.class);
    }

    @SysUISingleton
    @Provides
    static IActivityManager provideIActivityManager() {
        return ActivityManager.getService();
    }

    @SysUISingleton
    @Provides
    static IActivityTaskManager provideIActivityTaskManager() {
        return ActivityTaskManager.getService();
    }

    @Provides
    @SysUISingleton
    static IBatteryStats provideIBatteryStats() {
        return IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
    }

    @Provides
    @SysUISingleton
    static IDreamManager provideIDreamManager() {
        return IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
    }

    @Provides
    @SysUISingleton
    @Nullable
    static FaceManager provideFaceManager(Context context) {
        return context.getSystemService(FaceManager.class);

    }

    @Provides
    @SysUISingleton
    static IPackageManager provideIPackageManager() {
        return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    @SysUISingleton
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

    @SysUISingleton
    @Provides
    static IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
    }

    @SysUISingleton
    @Provides
    static KeyguardManager provideKeyguardManager(Context context) {
        return context.getSystemService(KeyguardManager.class);
    }

    @SysUISingleton
    @Provides
    static LatencyTracker provideLatencyTracker(Context context) {
        return LatencyTracker.getInstance(context);
    }

    @SysUISingleton
    @Provides
    static LauncherApps provideLauncherApps(Context context) {
        return context.getSystemService(LauncherApps.class);
    }

    @SuppressLint("MissingPermission")
    @SysUISingleton
    @Provides
    @Nullable
    static LocalBluetoothManager provideLocalBluetoothController(Context context,
            @Background Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler, UserHandle.ALL);
    }

    @Provides
    static MediaRouter2Manager provideMediaRouter2Manager(Context context) {
        return MediaRouter2Manager.getInstance(context);
    }

    @Provides
    static MediaSessionManager provideMediaSessionManager(Context context) {
        return context.getSystemService(MediaSessionManager.class);
    }

    @Provides
    @SysUISingleton
    static NetworkScoreManager provideNetworkScoreManager(Context context) {
        return context.getSystemService(NetworkScoreManager.class);
    }

    @SysUISingleton
    @Provides
    static NotificationManager provideNotificationManager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    @SysUISingleton
    @Provides
    static PackageManager providePackageManager(Context context) {
        return context.getPackageManager();
    }

    @SysUISingleton
    @Provides
    static PackageManagerWrapper providePackageManagerWrapper() {
        return PackageManagerWrapper.getInstance();
    }

    /** */
    @SysUISingleton
    @Provides
    static PowerManager providePowerManager(Context context) {
        return context.getSystemService(PowerManager.class);
    }

    @Provides
    @Main
    static Resources provideResources(Context context) {
        return context.getResources();
    }

    @Provides
    @SysUISingleton
    static SensorManager providesSensorManager(Context context) {
        return context.getSystemService(SensorManager.class);
    }

    @SysUISingleton
    @Provides
    static SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
    }

    @SysUISingleton
    @Provides
    static ShortcutManager provideShortcutManager(Context context) {
        return context.getSystemService(ShortcutManager.class);
    }

    @Provides
    @SysUISingleton
    @Nullable
    static TelecomManager provideTelecomManager(Context context) {
        return context.getSystemService(TelecomManager.class);
    }

    @Provides
    @SysUISingleton
    static TelephonyManager provideTelephonyManager(Context context) {
        return context.getSystemService(TelephonyManager.class);
    }

    @Provides
    @SysUISingleton
    static TrustManager provideTrustManager(Context context) {
        return context.getSystemService(TrustManager.class);
    }

    @Provides
    @SysUISingleton
    @Nullable
    static Vibrator provideVibrator(Context context) {
        return context.getSystemService(Vibrator.class);
    }

    @Provides
    @SysUISingleton
    static ViewConfiguration provideViewConfiguration(Context context) {
        return ViewConfiguration.get(context);
    }

    @Provides
    @SysUISingleton
    static UserManager provideUserManager(Context context) {
        return context.getSystemService(UserManager.class);
    }

    @Provides
    static WallpaperManager provideWallpaperManager(Context context) {
        return (WallpaperManager) context.getSystemService(Context.WALLPAPER_SERVICE);
    }

    @Provides
    @SysUISingleton
    @Nullable
    static WifiManager provideWifiManager(Context context) {
        return context.getSystemService(WifiManager.class);
    }

    @SysUISingleton
    @Provides
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

    @Provides
    @SysUISingleton
    static RoleManager provideRoleManager(Context context) {
        return context.getSystemService(RoleManager.class);
    }
}
