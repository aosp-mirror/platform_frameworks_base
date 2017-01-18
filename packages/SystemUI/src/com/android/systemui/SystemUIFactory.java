/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.content.ComponentName;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Dependency.DependencyProvider;
import com.android.systemui.R;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.phone.KeyguardBouncer;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.volume.VolumeDialogController;

/**
 * Class factory to provide customizable SystemUI components.
 */
public class SystemUIFactory {
    private static final String TAG = "SystemUIFactory";

    static SystemUIFactory mFactory;

    public static SystemUIFactory getInstance() {
        return mFactory;
    }

    public static void createFromConfig(Context context) {
        final String clsName = context.getString(R.string.config_systemUIFactoryComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No SystemUIFactory component configured");
        }

        try {
            Class<?> cls = null;
            cls = context.getClassLoader().loadClass(clsName);
            mFactory = (SystemUIFactory) cls.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "Error creating SystemUIFactory component: " + clsName, t);
            throw new RuntimeException(t);
        }
    }

    public SystemUIFactory() {}

    public StatusBarKeyguardViewManager createStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        return new StatusBarKeyguardViewManager(context, viewMediatorCallback, lockPatternUtils);
    }

    public KeyguardBouncer createKeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils,
            ViewGroup container, DismissCallbackRegistry dismissCallbackRegistry) {
        return new KeyguardBouncer(context, callback, lockPatternUtils, container,
                dismissCallbackRegistry);
    }

    public ScrimController createScrimController(LightBarController lightBarController,
            ScrimView scrimBehind, ScrimView scrimInFront, View headsUpScrim,
            LockscreenWallpaper lockscreenWallpaper) {
        return new ScrimController(lightBarController, scrimBehind, scrimInFront, headsUpScrim);
    }

    public VolumeDialogController createVolumeDialogController(Context context,
            ComponentName name) {
        return new VolumeDialogController(context, name);
    }

    public NotificationIconAreaController createNotificationIconAreaController(Context context,
            PhoneStatusBar phoneStatusBar) {
        return new NotificationIconAreaController(context, phoneStatusBar);
    }

    public QSTileHost createQSTileHost(Context context, PhoneStatusBar statusBar,
            StatusBarIconController iconController) {
        return new QSTileHost(context, statusBar, iconController);
    }

    public <T> T createInstance(Class<T> classType) {
        return null;
    }

    public void injectDependencies(ArrayMap<String, DependencyProvider> providers,
            Context context) { }
}
