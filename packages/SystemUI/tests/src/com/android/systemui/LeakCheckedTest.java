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

package com.android.systemui;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for tests to check if receivers are left registered, services bound, or other
 * listeners listening.
 */
public class LeakCheckedTest extends SysuiTestCase {
    private static final String TAG = "LeakCheckedTest";

    private final Map<String, Tracker> mTrackers = new HashMap<>();
    private final Map<Class, Object> mLeakCheckers = new ArrayMap<>();
    private TrackingContext mTrackedContext;

    @Rule
    public TestWatcher successWatcher = new TestWatcher() {
        @Override
        protected void succeeded(Description description) {
            verify();
        }
    };

    @Before
    public void setup() {
        mTrackedContext = new TrackingContext(mContext);
        addSupportedLeakCheckers();
    }

    public <T> T getLeakChecker(Class<T> cls) {
        T obj = (T) mLeakCheckers.get(cls);
        if (obj == null) {
            Assert.fail(cls.getName() + " is not supported by LeakCheckedTest yet");
        }
        return obj;
    }

    public Context getTrackedContext() {
        return mTrackedContext;
    }

    private Tracker getTracker(String tag) {
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

    public static class Tracker {
        private Map<Object, LeakInfo> mObjects = new ArrayMap<>();

        LeakInfo getLeakInfo(Object object) {
            LeakInfo leakInfo = mObjects.get(object);
            if (leakInfo == null) {
                leakInfo = new LeakInfo();
                mObjects.put(object, leakInfo);
            }
            return leakInfo;
        }

        private void verify() {
            mObjects.values().forEach(LeakInfo::verify);
        }
    }

    public static class LeakInfo {
        private List<Throwable> mThrowables = new ArrayList<>();

        private LeakInfo() {
        }

        private void addAllocation(Throwable t) {
            // TODO: Drop off the first element in the stack trace here to have a cleaner stack.
            mThrowables.add(t);
        }

        private void clearAllocations() {
            mThrowables.clear();
        }

        public void verify() {
            if (mThrowables.size() == 0) return;
            Log.e(TAG, "Listener or binding not properly released");
            for (Throwable t : mThrowables) {
                Log.e(TAG, "Allocation found", t);
            }
            StringWriter writer = new StringWriter();
            mThrowables.get(0).printStackTrace(new PrintWriter(writer));
            Assert.fail("Listener or binding not properly released\n"
                    + writer.toString());
        }
    }

    private void addSupportedLeakCheckers() {
        addListening("bluetooth", BluetoothController.class);
        addListening("location", LocationController.class);
        addListening("rotation", RotationLockController.class);
        addListening("zen", ZenModeController.class);
        addListening("cast", CastController.class);
        addListening("hotspot", HotspotController.class);
        addListening("flashlight", FlashlightController.class);
        addListening("user", UserInfoController.class);
        addListening("keyguard", KeyguardMonitor.class);
        addListening("battery", BatteryController.class);
        addListening("security", SecurityController.class);
        addListening("profile", ManagedProfileController.class);
        addListening("alarm", NextAlarmController.class);
        NetworkController network = addListening("network", NetworkController.class);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                getTracker("emergency").getLeakInfo(invocation.getArguments()[0])
                        .addAllocation(new Throwable());
                return null;
            }
        }).when(network).addEmergencyListener(any());
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                getTracker("emergency").getLeakInfo(invocation.getArguments()[0]).clearAllocations();
                return null;
            }
        }).when(network).removeEmergencyListener(any());
        DataSaverController datasaver = addListening("datasaver", DataSaverController.class);
        when(network.getDataSaverController()).thenReturn(datasaver);
    }

    private <T extends CallbackController> T addListening(final String tag, Class<T> cls) {
        T mock = mock(cls);
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

    class TrackingContext extends ContextWrapper {
        public TrackingContext(Context base) {
            super(base);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            getTracker("receiver").getLeakInfo(receiver).addAllocation(new Throwable());
            return super.registerReceiver(receiver, filter);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler) {
            getTracker("receiver").getLeakInfo(receiver).addAllocation(new Throwable());
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler);
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            getTracker("receiver").getLeakInfo(receiver).addAllocation(new Throwable());
            return super.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                    scheduler);
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            getTracker("receiver").getLeakInfo(receiver).clearAllocations();
            super.unregisterReceiver(receiver);
        }

        @Override
        public boolean bindService(Intent service, ServiceConnection conn, int flags) {
            getTracker("service").getLeakInfo(conn).addAllocation(new Throwable());
            return super.bindService(service, conn, flags);
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                Handler handler, UserHandle user) {
            getTracker("service").getLeakInfo(conn).addAllocation(new Throwable());
            return super.bindServiceAsUser(service, conn, flags, handler, user);
        }

        @Override
        public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
                UserHandle user) {
            getTracker("service").getLeakInfo(conn).addAllocation(new Throwable());
            return super.bindServiceAsUser(service, conn, flags, user);
        }

        @Override
        public void unbindService(ServiceConnection conn) {
            getTracker("service").getLeakInfo(conn).clearAllocations();
            super.unbindService(conn);
        }

        @Override
        public void registerComponentCallbacks(ComponentCallbacks callback) {
            getTracker("component").getLeakInfo(callback).addAllocation(new Throwable());
            super.registerComponentCallbacks(callback);
        }

        @Override
        public void unregisterComponentCallbacks(ComponentCallbacks callback) {
            getTracker("component").getLeakInfo(callback).clearAllocations();
            super.unregisterComponentCallbacks(callback);
        }
    }
}
