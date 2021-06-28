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
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.StatsManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.app.smartspace.SmartspaceManager;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.om.OverlayManager;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.MediaRouter2Manager;
import android.media.session.MediaSessionManager;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserManager;
import android.os.Vibrator;
import android.permission.PermissionManager;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.CrossWindowBlurListeners;
import android.view.IWindowManager;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.app.IBatteryStats;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.PackageManagerWrapper;

import java.util.Optional;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Provides Non-SystemUI, Framework-Owned instances to the dependency graph.
 */
@Module
public class FrameworkServicesModule {
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

    @Provides
    @Singleton
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
    static ColorDisplayManager provideColorDisplayManager(Context context) {
        return context.getSystemService(ColorDisplayManager.class);
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
    @Singleton
    static CrossWindowBlurListeners provideCrossWindowBlurListeners() {
        return CrossWindowBlurListeners.getInstance();
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

    @Provides
    @Singleton
    static DeviceStateManager provideDeviceStateManager(Context context) {
        return context.getSystemService(DeviceStateManager.class);
    }

    @Provides
    @Singleton
    static IActivityManager provideIActivityManager() {
        return ActivityManager.getService();
    }

    @Provides
    @Singleton
    static ActivityTaskManager provideActivityTaskManager() {
        return ActivityTaskManager.getInstance();
    }

    @Provides
    @Singleton
    static IActivityTaskManager provideIActivityTaskManager() {
        return ActivityTaskManager.getService();
    }

    @Provides
    @Singleton
    static IAudioService provideIAudioService() {
        return IAudioService.Stub.asInterface(ServiceManager.getService(Context.AUDIO_SERVICE));
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
    @Nullable
    static FaceManager provideFaceManager(Context context) {
        return context.getSystemService(FaceManager.class);

    }

    @Provides
    @Singleton
    @Nullable
    static FingerprintManager providesFingerprintManager(Context context) {
        return context.getSystemService(FingerprintManager.class);
    }

    @Provides
    @Singleton
    static InputMethodManager provideInputMethodManager(Context context) {
        return context.getSystemService(InputMethodManager.class);
    }

    @Provides
    @Singleton
    static IAppWidgetService provideIAppWidgetService() {
        return IAppWidgetService.Stub.asInterface(
                ServiceManager.getService(Context.APPWIDGET_SERVICE));
    }

    @Provides
    @Singleton
    static IPackageManager provideIPackageManager() {
        return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
    }

    @Provides
    @Singleton
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

    @Provides
    @Singleton
    static IWindowManager provideIWindowManager() {
        return WindowManagerGlobal.getWindowManagerService();
    }

    @Provides
    @Singleton
    static KeyguardManager provideKeyguardManager(Context context) {
        return context.getSystemService(KeyguardManager.class);
    }

    @Provides
    @Singleton
    static LatencyTracker provideLatencyTracker(Context context) {
        return LatencyTracker.getInstance(context);
    }

    @Provides
    @Singleton
    static LauncherApps provideLauncherApps(Context context) {
        return context.getSystemService(LauncherApps.class);
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
    @Singleton
    static NetworkScoreManager provideNetworkScoreManager(Context context) {
        return context.getSystemService(NetworkScoreManager.class);
    }

    @Provides
    @Singleton
    static NotificationManager provideNotificationManager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    @Provides
    @Singleton
    static PackageManager providePackageManager(Context context) {
        return context.getPackageManager();
    }

    @Provides
    @Singleton
    static PackageManagerWrapper providePackageManagerWrapper() {
        return PackageManagerWrapper.getInstance();
    }

    /** */
    @Provides
    @Singleton
    static PowerManager providePowerManager(Context context) {
        return context.getSystemService(PowerManager.class);
    }

    @Provides
    @Main
    static Resources provideResources(Context context) {
        return context.getResources();
    }

    @Provides
    @Singleton
    static RoleManager provideRoleManager(Context context) {
        return context.getSystemService(RoleManager.class);
    }

    @Provides
    @Singleton
    static SensorManager providesSensorManager(Context context) {
        return context.getSystemService(SensorManager.class);
    }

    @Provides
    @Singleton
    static SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
    }

    @Provides
    @Singleton
    static ShortcutManager provideShortcutManager(Context context) {
        return context.getSystemService(ShortcutManager.class);
    }

    @Provides
    @Singleton
    static StatsManager provideStatsManager(Context context) {
        return context.getSystemService(StatsManager.class);
    }

    @Provides
    @Singleton
    static SubscriptionManager provideSubcriptionManager(Context context) {
        return context.getSystemService(SubscriptionManager.class);
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
    static Optional<Vibrator> provideOptionalVibrator(Context context) {
        return Optional.ofNullable(context.getSystemService(Vibrator.class));
    }

    @Provides
    @Singleton
    static ViewConfiguration provideViewConfiguration(Context context) {
        return ViewConfiguration.get(context);
    }

    @Provides
    @Singleton
    static UserManager provideUserManager(Context context) {
        return context.getSystemService(UserManager.class);
    }

    @Provides
    static WallpaperManager provideWallpaperManager(Context context) {
        return context.getSystemService(WallpaperManager.class);
    }

    @Provides
    @Singleton
    @Nullable
    static WifiManager provideWifiManager(Context context) {
        return context.getSystemService(WifiManager.class);
    }

    @Provides
    @Singleton
    static OverlayManager provideOverlayManager(Context context) {
        return context.getSystemService(OverlayManager.class);
    }

    @Provides
    @Singleton
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

    @Provides
    @Singleton
    static PermissionManager providePermissionManager(Context context) {
        return context.getSystemService(PermissionManager.class);
    }

    @Provides
    @Singleton
    static SmartspaceManager provideSmartspaceManager(Context context) {
        return context.getSystemService(SmartspaceManager.class);
    }
}
