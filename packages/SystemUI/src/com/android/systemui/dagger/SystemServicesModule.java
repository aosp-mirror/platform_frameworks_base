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
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IWallpaperManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.dagger.qualifiers.BgHandler;
import com.android.systemui.dagger.qualifiers.MainResources;
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

    @Singleton
    @Provides
    static AlarmManager provideAlarmManager(Context context) {
        return context.getSystemService(AlarmManager.class);
    }

    @Provides
    @Singleton
    static DevicePolicyManager provideDevicePolicyManager(Context context) {
        return context.getSystemService(DevicePolicyManager.class);
    }

    @Singleton
    @Provides
    static IActivityManager provideIActivityManager() {
        return ActivityManager.getService();
    }

    @Provides
    @Singleton
    static IDreamManager provideIDreamManager() {
        return IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
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

    @SuppressLint("MissingPermission")
    @Singleton
    @Provides
    @Nullable
    static LocalBluetoothManager provideLocalBluetoothController(Context context,
            @BgHandler Handler bgHandler) {
        return LocalBluetoothManager.create(context, bgHandler, UserHandle.ALL);
    }

    @Singleton
    @Provides
    static NotificationManager provideNotificationManager(Context context) {
        return context.getSystemService(NotificationManager.class);
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
    @MainResources
    static Resources provideResources(Context context) {
        return context.getResources();
    }

    @Singleton
    @Provides
    static SensorPrivacyManager provideSensorPrivacyManager(Context context) {
        return context.getSystemService(SensorPrivacyManager.class);
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

    @Singleton
    @Provides
    static WindowManager provideWindowManager(Context context) {
        return context.getSystemService(WindowManager.class);
    }

}
