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
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.INotificationManager;
import android.app.IUriGrantsManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.StatsManager;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.ambientcontext.AmbientContextManager;
import android.app.job.JobScheduler;
import android.app.role.RoleManager;
import android.app.smartspace.SmartspaceManager;
import android.app.trust.TrustManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.om.OverlayManager;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.camera2.CameraManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.MediaRouter2Manager;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaSessionManager;
import android.nearby.NearbyManager;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.IDeviceIdleController;
import android.os.PowerExemptionManager;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemUpdateManager;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.storage.StorageManager;
import android.permission.PermissionManager;
import android.safetycenter.SafetyCenterManager;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.vr.IVrManager;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.satellite.SatelliteManager;
import android.view.Choreographer;
import android.view.CrossWindowBlurListeners;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.inputmethod.InputMethodManager;
import android.view.textclassifier.TextClassificationManager;

import androidx.asynclayoutinflater.view.AsyncLayoutInflater;
import androidx.core.app.NotificationManagerCompat;

import com.android.internal.app.IBatteryStats;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.Prefs;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.TestHarness;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.user.utils.UserScopedService;
import com.android.systemui.user.utils.UserScopedServiceImpl;

import dagger.Module;
import dagger.Provides;

import java.util.Optional;

import javax.inject.Singleton;

/**
 * Provides Non-SystemUI, Framework-Owned instances to the dependency graph.
 */
@SuppressLint("NonInjectedService")
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
    static Optional<SystemUpdateManager> provideSystemUpdateManager(Context context) {
        return Optional.ofNullable(context.getSystemService(SystemUpdateManager.class));
    }

    @Provides
    @Nullable
    @Singleton
    static AmbientContextManager provideAmbientContextManager(Context context) {
        return context.getSystemService(AmbientContextManager.class);
    }

    /** */
    @Provides
    public AmbientDisplayConfiguration provideAmbientDisplayConfiguration(Context context) {
        return new AmbientDisplayConfiguration(context);
    }

    @Provides
    @Singleton
    static AppOpsManager provideAppOpsManager(Context context) {
        return context.getSystemService(AppOpsManager.class);
    }

    @Provides
    @Singleton
    static AudioManager provideAudioManager(Context context) {
        return context.getSystemService(AudioManager.class);
    }

    @Provides
    @Singleton
    static CaptioningManager provideCaptioningManager(Context context) {
        return context.getSystemService(CaptioningManager.class);
    }

    /** */
    @Provides
    @Singleton
    public Choreographer providesChoreographer() {
        return Choreographer.getInstance();
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
    static UserScopedService<ColorDisplayManager> provideScopedColorDisplayManager(
            Context context) {
        return new UserScopedServiceImpl<>(context, ColorDisplayManager.class);
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
    static VirtualDeviceManager provideVirtualDeviceManager(Context context) {
        return context.getSystemService(VirtualDeviceManager.class);
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
                ServiceManager.getService(DreamService.DREAM_SERVICE));
    }

    @Provides
    @Singleton
    @Nullable
    static IVrManager provideIVrManager() {
        return IVrManager.Stub.asInterface(ServiceManager.getService(Context.VR_SERVICE));
    }

    @Provides
    @Singleton
    @Nullable
    static FaceManager provideFaceManager(Context context) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
            return context.getSystemService(FaceManager.class);
        }
        return null;
    }

    @Provides
    @Singleton
    @Nullable
    static FingerprintManager providesFingerprintManager(Context context) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return context.getSystemService(FingerprintManager.class);
        }
        return null;
    }

    /**
     * @return null if both faceManager and fingerprintManager are null.
     */
    @Provides
    @Singleton
    @Nullable
    static BiometricManager providesBiometricManager(Context context,
            @Nullable FaceManager faceManager, @Nullable FingerprintManager fingerprintManager) {
        return faceManager == null && fingerprintManager == null ? null :
                context.getSystemService(BiometricManager.class);
    }

    @Provides
    @Singleton
    static JobScheduler provideJobScheduler(Context context) {
        return context.getSystemService(JobScheduler.class);
    }

    @Provides
    @Singleton
    static InteractionJankMonitor provideInteractionJankMonitor() {
        InteractionJankMonitor jankMonitor = InteractionJankMonitor.getInstance();
        jankMonitor.configDebugOverlay(Color.YELLOW, 0.75);
        return jankMonitor;
    }

    @Provides
    @Singleton
    static InputManager provideInputManager(Context context) {
        return context.getSystemService(InputManager.class);
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

    /** */
    @Provides
    @Singleton
    public LayoutInflater providerLayoutInflater(Context context) {
        return LayoutInflater.from(context);
    }

    /** */
    @Provides
    @Singleton
    public AsyncLayoutInflater provideAsyncLayoutInflater(Context context) {
        return new AsyncLayoutInflater(context);
    }

    @Provides
    static MediaProjectionManager provideMediaProjectionManager(Context context) {
        return context.getSystemService(MediaProjectionManager.class);
    }

    @Provides
    @Singleton
    static IMediaProjectionManager provideIMediaProjectionManager() {
        return IMediaProjectionManager.Stub.asInterface(
                ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE));
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
    static NearbyManager provideNearbyManager(Context context) {
        return context.getSystemService(NearbyManager.class);
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
    static NotificationManagerCompat provideNotificationManagerCompat(Context context) {
        return NotificationManagerCompat.from(context);
    }

    /** */
    @Provides
    @Singleton
    public INotificationManager provideINotificationManager() {
        return INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    @Provides
    @Singleton
    static PackageManager providePackageManager(Context context) {
        return context.getPackageManager();
    }

    @Provides
    @Singleton
    static PackageInstaller providePackageInstaller(PackageManager packageManager) {
        return packageManager.getPackageInstaller();
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

    /** */
    @Provides
    @Singleton
    static PowerExemptionManager providePowerExemptionManager(Context context) {
        return context.getSystemService(PowerExemptionManager.class);
    }

    /** */
    @Provides
    @Main
    public SharedPreferences provideSharePreferences(Context context) {
        return Prefs.get(context);
    }

    /** */
    @Provides
    @Singleton
    static UiModeManager provideUiModeManager(Context context) {
        return context.getSystemService(UiModeManager.class);
    }

    @Provides
    @Main
    static Resources provideResources(Context context) {
        return context.getResources();
    }

    @Provides
    @Application
    static AssetManager provideAssetManager(@Application Context context) {
        return context.getAssets();
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
    static StorageManager provideStorageManager(Context context) {
        return context.getSystemService(StorageManager.class);
    }

    @Provides
    @Singleton
    static SubscriptionManager provideSubscriptionManager(Context context) {
        return context.getSystemService(SubscriptionManager.class).createForAllUserProfiles();
    }

    @Provides
    @Singleton
    @Nullable
    static TelecomManager provideTelecomManager(Context context) {
        return context.getSystemService(TelecomManager.class);
    }

    @Provides
    @Singleton
    static Optional<TelecomManager> provideOptionalTelecomManager(Context context) {
        return Optional.ofNullable(context.getSystemService(TelecomManager.class));
    }

    @Provides
    @Singleton
    static TelephonyManager provideTelephonyManager(Context context) {
        return context.getSystemService(TelephonyManager.class);
    }

    @Provides
    @Singleton
    @TestHarness
    static boolean provideIsTestHarness() {
        return ActivityManager.isRunningInUserTestHarness();
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
    @Singleton
    static UserScopedService<UserManager> provideScopedUserManager(@Application Context context) {
        return new UserScopedServiceImpl<>(context, UserManager.class);
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
    @Nullable
    static CarrierConfigManager provideCarrierConfigManager(Context context) {
        return context.getSystemService(CarrierConfigManager.class);
    }

    @Provides
    @Singleton
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

    @Provides
    @Singleton
    static PermissionManager providePermissionManager(Context context) {
        PermissionManager pm = context.getSystemService(PermissionManager.class);
        if (pm != null) {
            pm.initializeUsageHelper();
        }
        return pm;
    }

    @Provides
    @Singleton
    static ClipboardManager provideClipboardManager(Context context) {
        return context.getSystemService(ClipboardManager.class);
    }

    @Provides
    @Singleton
    @Nullable
    static SmartspaceManager provideSmartspaceManager(Context context) {
        return context.getSystemService(SmartspaceManager.class);
    }

    @Provides
    @Singleton
    static SafetyCenterManager provideSafetyCenterManager(Context context) {
        return context.getSystemService(SafetyCenterManager.class);
    }

    @Provides
    @Singleton
    static CameraManager provideCameraManager(Context context) {
        return context.getSystemService(CameraManager.class);
    }

    @Provides
    @Singleton
    static BluetoothManager provideBluetoothManager(Context context) {
        return context.getSystemService(BluetoothManager.class);
    }

    @Provides
    @Singleton
    @Nullable
    static BluetoothAdapter provideBluetoothAdapter(BluetoothManager bluetoothManager) {
        return bluetoothManager.getAdapter();
    }

    @Provides
    @Singleton
    static TextClassificationManager provideTextClassificationManager(Context context) {
        return context.getSystemService(TextClassificationManager.class);
    }

    @Provides
    @Singleton
    static StatusBarManager provideStatusBarManager(Context context) {
        return context.getSystemService(StatusBarManager.class);
    }

    @Provides
    @Singleton
    static IUriGrantsManager provideIUriGrantsManager() {
        return IUriGrantsManager.Stub.asInterface(
                ServiceManager.getService(Context.URI_GRANTS_SERVICE)
        );
    }

    @Provides
    @Singleton
    static Optional<SatelliteManager> provideSatelliteManager(Context context) {
        return Optional.ofNullable(context.getSystemService(SatelliteManager.class));
    }

    @Provides
    @Singleton
    static IDeviceIdleController provideDeviceIdleController() {
        return IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
    }
}
