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

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;

import android.util.ArrayMap;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CallbackController;
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
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for tests to check if receivers are left registered, services bound, or other
 * listeners listening.
 */
public abstract class LeakCheckedTest extends SysuiTestCase {
    private static final String TAG = "LeakCheckedTest";

    private final Map<String, Tracker> mTrackers = new HashMap<>();
    private final Map<Class, Object> mLeakCheckers = new ArrayMap<>();

    public static final Class<?>[] ALL_SUPPORTED_CLASSES = new Class[] {
            BluetoothController.class,
            LocationController.class,
            RotationLockController.class,
            ZenModeController.class,
            CastController.class,
            HotspotController.class,
            FlashlightController.class,
            UserInfoController.class,
            KeyguardMonitor.class,
            BatteryController.class,
            SecurityController.class,
            ManagedProfileController.class,
            NextAlarmController.class,
            NetworkController.class,
            PluginManager.class,
            TunerService.class,
            StatusBarIconController.class,
    };

    @Rule
    public TestWatcher successWatcher = new TestWatcher() {
        @Override
        protected void succeeded(Description description) {
            verify();
        }
    };

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
                obj = new FakeCastController(this);
            } else if (cls == HotspotController.class) {
                obj = new FakeHotspotController(this);
            } else if (cls == FlashlightController.class) {
                obj = new FakeFlashlightController(this);
            } else if (cls == UserInfoController.class) {
                obj = new FakeUserInfoController(this);
            } else if (cls == KeyguardMonitor.class) {
                obj = new FakeKeyguardMonitor(this);
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
                obj = new FakePluginManager(mContext, this);
            } else if (cls == TunerService.class) {
                obj = new FakeTunerService(mContext, this);
            } else if (cls == StatusBarIconController.class) {
                obj = new FakeStatusBarIconController(this);
            } else {
                Assert.fail(cls.getName() + " is not supported by LeakCheckedTest yet");
            }
            mLeakCheckers.put(cls, obj);
        }
        return (T) obj;
    }

    @Override
    public Tracker getTracker(String tag) {
        Tracker t = mTrackers.get(tag);
        if (t == null) {
            t = new Tracker();
            mTrackers.put(tag, t);
        }
        return t;
    }

    public void verify() {
        mTrackers.values().forEach(Tracker::verify);
    }

    public void injectLeakCheckedDependencies(Class<?>... cls) {
        for (Class<?> c : cls) {
            injectLeakCheckedDependency(c);
        }
    }

    public <T> void injectLeakCheckedDependency(Class<T> c) {
        injectTestDependency(c, getLeakChecker(c));
    }

    public <T extends CallbackController> T addListening(T mock, Class<T> cls, String tag) {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                getTracker(tag).getLeakInfo(invocation.getArguments()[0])
                        .addAllocation(new Throwable());
                return null;
            }
        }).when(mock).addCallback(any());
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                getTracker(tag).getLeakInfo(invocation.getArguments()[0]).clearAllocations();
                return null;
            }
        }).when(mock).removeCallback(any());
        mLeakCheckers.put(cls, mock);
        return mock;
    }
}
