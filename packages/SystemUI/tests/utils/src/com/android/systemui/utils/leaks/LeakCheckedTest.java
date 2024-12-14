/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.testing.LeakCheck;
import android.util.ArrayMap;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import org.junit.Assert;
import org.junit.Rule;

import java.util.Map;

/**
 * Base class for tests to check if receivers are left registered, services bound, or other
 * listeners listening.
 */
public abstract class LeakCheckedTest extends SysuiTestCase {
    private static final String TAG = "LeakCheckedTest";

    public static final Class<?>[] ALL_SUPPORTED_CLASSES = new Class[]{
            BluetoothController.class,
            LocationController.class,
            RotationLockController.class,
            ZenModeController.class,
            CastController.class,
            HotspotController.class,
            FlashlightController.class,
            UserInfoController.class,
            KeyguardStateController.class,
            BatteryController.class,
            SecurityController.class,
            ManagedProfileController.class,
            NextAlarmController.class,
            NetworkController.class,
            PluginManager.class,
            TunerService.class,
            StatusBarIconController.class,
            ConfigurationController.class,
    };

    @Rule
    public SysuiLeakCheck mLeakCheck = new SysuiLeakCheck();

    @Override
    public LeakCheck getLeakCheck() {
        return mLeakCheck;
    }

    public void injectLeakCheckedDependencies(Class<?>... cls) {
        for (Class<?> c : cls) {
            injectLeakCheckedDependency(c);
        }
    }

    public <T> void injectLeakCheckedDependency(Class<T> c) {
        mDependency.injectTestDependency(c, mLeakCheck.getLeakChecker(c));
    }

    public static class SysuiLeakCheck extends LeakCheck {

        private final Map<Class, Object> mLeakCheckers = new ArrayMap<>();

        public SysuiLeakCheck() {
            super();
        }

        public <T> T getLeakChecker(Class<T> cls) {
            Object obj = mLeakCheckers.get(cls);
            if (obj == null) {
                // Lazy create checkers so we only have the ones we need.
                if (cls == BluetoothController.class) {
                    obj = new FakeBluetoothController(this);
                } else if (cls == LocationController.class) {
                    obj = new FakeLocationController(this);
                } else if (cls == RotationLockController.class) {
                    obj = new FakeRotationLockController(this);
                } else if (cls == ZenModeController.class) {
                    obj = new FakeZenModeController(this);
                } else if (cls == CastController.class) {
                    obj = new LeakCheckerCastController(this);
                } else if (cls == HotspotController.class) {
                    obj = new FakeHotspotController(this);
                } else if (cls == FlashlightController.class) {
                    obj = new FakeFlashlightController(this);
                } else if (cls == UserInfoController.class) {
                    obj = new FakeUserInfoController(this);
                } else if (cls == KeyguardStateController.class) {
                    obj = new FakeKeyguardStateController(this);
                } else if (cls == BatteryController.class) {
                    obj = new FakeBatteryController(this);
                } else if (cls == SecurityController.class) {
                    obj = new FakeSecurityController(this);
                } else if (cls == ManagedProfileController.class) {
                    obj = new FakeManagedProfileController(this);
                } else if (cls == NextAlarmController.class) {
                    obj = new FakeNextAlarmController(this);
                } else if (cls == NetworkController.class) {
                    obj = new FakeNetworkController(this);
                } else if (cls == PluginManager.class) {
                    obj = new FakePluginManager(this);
                } else if (cls == TunerService.class) {
                    obj = new FakeTunerService(this);
                } else if (cls == StatusBarIconController.class) {
                    obj = new FakeStatusBarIconController(this);
                } else if (cls == ConfigurationController.class) {
                    obj = new FakeConfigurationController(this);
                } else {
                    Assert.fail(cls.getName() + " is not supported by LeakCheckedTest yet");
                }
                mLeakCheckers.put(cls, obj);
            }
            return (T) obj;
        }
    }
}
