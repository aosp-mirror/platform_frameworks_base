/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.devicepolicy;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.LockPatternUtils;

import android.app.IActivityManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager.WakeLock;
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.mock.MockContext;
import android.view.IWindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Context used throughout DPMS tests.
 */
public class DpmMockContext extends MockContext {
    /**
     * User-id of a non-system user we use throughout unit tests.
     */
    public static final int CALLER_USER_HANDLE = 20;

    /**
     * UID of the caller.
     */
    public static final int CALLER_UID = UserHandle.PER_USER_RANGE * CALLER_USER_HANDLE + 123;

    /**
     * PID of the caller.
     */
    public static final int CALLER_PID = 22222;

    /**
     * UID of the system server.
     */
    public static final int SYSTEM_UID = android.os.Process.SYSTEM_UID;

    /**
     * PID of the system server.
     */
    public static final int SYSTEM_PID = 11111;

    public static class MockBinder {
        public int callingUid = CALLER_UID;
        public int callingPid = CALLER_PID;

        public long clearCallingIdentity() {
            final long token = (((long) callingUid) << 32) | (callingPid);
            callingUid = SYSTEM_UID;
            callingPid = SYSTEM_PID;
            return token;
        }

        public void restoreCallingIdentity(long token) {
            callingUid = (int) (token >> 32);
            callingPid = (int) token;
        }

        public int getCallingUid() {
            return callingUid;
        }

        public int getCallingPid() {
            return callingPid;
        }

        public UserHandle getCallingUserHandle() {
            return new UserHandle(UserHandle.getUserId(getCallingUid()));
        }

        public boolean isCallerUidMyUid() {
            return callingUid == SYSTEM_UID;
        }
    }

    public static class EnvironmentForMock {
        public File getUserSystemDirectory(int userId) {
            return null;
        }
    }

    public static class PowerManagerForMock {
        public WakeLock newWakeLock(int levelAndFlags, String tag) {
            return null;
        }

        public void goToSleep(long time, int reason, int flags) {
        }
    }

    public static class SystemPropertiesForMock {
        public boolean getBoolean(String key, boolean def) {
            return false;
        }

        public long getLong(String key, long def) {
            return 0;
        }

        public String get(String key, String def) {
            return null;
        }

        public String get(String key) {
            return null;
        }

        public void set(String key, String value) {
        }
    }

    public final Context realTestContext;

    /**
     * Use this instance to verify unimplemented methods such as {@link #sendBroadcast}.
     * (Spying on {@code this} instance will confuse mockito somehow and I got weired "wrong number
     * of arguments" exceptions.)
     */
    public final Context spiedContext;

    public final MockBinder binder;
    public final EnvironmentForMock environment;
    public final SystemPropertiesForMock systemProperties;
    public final UserManager userManager;
    public final PowerManagerForMock powerManager;
    public final PowerManagerInternal powerManagerInternal;
    public final NotificationManager notificationManager;
    public final IWindowManager iwindowManager;
    public final IActivityManager iactivityManager;
    public final IPackageManager ipackageManager;
    public final LockPatternUtils lockPatternUtils;

    /** Note this is a partial mock, not a real mock. */
    public final PackageManager packageManager;

    public final List<String> callerPermissions = new ArrayList<>();

    public DpmMockContext(Context context) {
        realTestContext = context;
        binder = new MockBinder();
        environment = mock(EnvironmentForMock.class);
        systemProperties= mock(SystemPropertiesForMock.class);
        userManager = mock(UserManager.class);
        powerManager = mock(PowerManagerForMock.class);
        powerManagerInternal = mock(PowerManagerInternal.class);
        notificationManager = mock(NotificationManager.class);
        iwindowManager = mock(IWindowManager.class);
        iactivityManager = mock(IActivityManager.class);
        ipackageManager = mock(IPackageManager.class);
        lockPatternUtils = mock(LockPatternUtils.class);

        // Package manager is huge, so we use a partial mock instead.
        packageManager = spy(context.getPackageManager());

        spiedContext = mock(Context.class);
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case Context.USER_SERVICE:
                return userManager;
            case Context.POWER_SERVICE:
                return powerManager;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public PackageManager getPackageManager() {
        return packageManager;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (binder.getCallingUid() == SYSTEM_UID) {
            return; // Assume system has all permissions.
        }
        if (!callerPermissions.contains(permission)) {
            throw new SecurityException("Caller doesn't have " + permission + " : " + message);
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        spiedContext.sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        spiedContext.sendBroadcast(intent, receiverPermission);
    }

    @Override
    public void sendBroadcastMultiplePermissions(Intent intent, String[] receiverPermissions) {
        spiedContext.sendBroadcastMultiplePermissions(intent, receiverPermissions);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, Bundle options) {
        spiedContext.sendBroadcast(intent, receiverPermission, options);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission, int appOp) {
        spiedContext.sendBroadcast(intent, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission) {
        spiedContext.sendOrderedBroadcast(intent, receiverPermission);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcast(intent, receiverPermission, resultReceiver, scheduler,
                initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, Bundle options,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcast(intent, receiverPermission, options, resultReceiver,
                scheduler,
                initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcast(Intent intent, String receiverPermission, int appOp,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcast(intent, receiverPermission, appOp, resultReceiver,
                scheduler,
                initialCode, initialData, initialExtras);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {
        spiedContext.sendBroadcastAsUser(intent, user);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission) {
        spiedContext.sendBroadcastAsUser(intent, user, receiverPermission);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, String receiverPermission,
            int appOp) {
        spiedContext.sendBroadcastAsUser(intent, user, receiverPermission, appOp);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
            int initialCode, String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcastAsUser(intent, user, receiverPermission, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp,
                resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, options,
                resultReceiver, scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        spiedContext.sendStickyBroadcast(intent);
    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        spiedContext.sendStickyOrderedBroadcast(intent, resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        spiedContext.removeStickyBroadcast(intent);
    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        spiedContext.sendStickyBroadcastAsUser(intent, user);
    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
            String initialData, Bundle initialExtras) {
        spiedContext.sendStickyOrderedBroadcastAsUser(intent, user, resultReceiver, scheduler, initialCode,
                initialData, initialExtras);
    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {
        spiedContext.removeStickyBroadcastAsUser(intent, user);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        return spiedContext.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return spiedContext.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        return spiedContext.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        spiedContext.unregisterReceiver(receiver);
    }
}
