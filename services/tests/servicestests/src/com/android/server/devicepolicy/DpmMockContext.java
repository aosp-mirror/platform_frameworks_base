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

import static org.mockito.Mockito.mock;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.test.mock.MockContext;
import android.util.ArrayMap;
import android.util.ExceptionUtils;

import androidx.annotation.NonNull;

import com.android.internal.util.FunctionalUtils;

import org.junit.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Context used throughout DPMS tests.
 */
public class DpmMockContext extends MockContext {
    /**
     * User-id of a non-system user we use throughout unit tests.
     */
    public static final int CALLER_USER_HANDLE = 20;

    /**
     * UID corresponding to {@link #CALLER_USER_HANDLE}.
     */
    public static final int CALLER_UID = UserHandle.getUid(CALLER_USER_HANDLE, 20123);

    /**
     * UID corresponding to {@link #CALLER_USER_HANDLE}.
     */
    public static final int CALLER_MANAGED_PROVISIONING_UID = UserHandle.getUid(CALLER_USER_HANDLE,
            20125);

    /**
     * UID used when a caller is on the system user.
     */
    public static final int CALLER_SYSTEM_USER_UID = 20321;

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

    public static final String ANOTHER_PACKAGE_NAME = "com.another.package.name";
    public static final int ANOTHER_UID = UserHandle.getUid(UserHandle.USER_SYSTEM, 18434);

    public static final String DELEGATE_PACKAGE_NAME = "com.delegate.package.name";
    public static final int DELEGATE_CERT_INSTALLER_UID = UserHandle.getUid(UserHandle.USER_SYSTEM,
            18437);

    private final MockSystemServices mMockSystemServices;

    public static class MockBinder {
        public int callingUid = CALLER_UID;
        public int callingPid = CALLER_PID;
        public final Map<Integer, List<String>> callingPermissions = new ArrayMap<>();

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

        public void withCleanCallingIdentity(@NonNull FunctionalUtils.ThrowingRunnable action) {
            long callingIdentity = clearCallingIdentity();
            Throwable throwableToPropagate = null;
            try {
                action.runOrThrow();
            } catch (Throwable throwable) {
                throwableToPropagate = throwable;
            } finally {
                restoreCallingIdentity(callingIdentity);
                if (throwableToPropagate != null) {
                    throw ExceptionUtils.propagate(throwableToPropagate);
                }
            }
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

    private final Context realTestContext;

    /**
     * Use this instance to verify unimplemented methods such as {@link #sendBroadcast}.
     * (Spying on {@code this} instance will confuse mockito somehow and I got weired "wrong number
     * of arguments" exceptions.)
     */
    public final Context spiedContext;

    public final MockBinder binder;
    public final Resources resources;

    /** TODO: Migrate everything to use {@link #permissions} to avoid confusion. */
    @Deprecated
    public final List<String> callerPermissions = new ArrayList<>();

    /** Less confusing alias for {@link #callerPermissions}. */
    public final List<String> permissions = callerPermissions;

    public String packageName = null;

    public ApplicationInfo applicationInfo = null;

    public DpmMockContext(MockSystemServices mockSystemServices, Context context) {
        mMockSystemServices = mockSystemServices;
        realTestContext = context;

        binder = new MockBinder();
        resources = mock(Resources.class);
        spiedContext = mock(Context.class);
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    @Override
    public Resources.Theme getTheme() {
        return spiedContext.getTheme();
    }

    @Override
    public String getPackageName() {
        if (packageName != null) {
            return packageName;
        }
        return super.getPackageName();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        if (applicationInfo != null) {
            return applicationInfo;
        }
        return super.getApplicationInfo();
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case Context.ALARM_SERVICE:
                return mMockSystemServices.alarmManager;
            case Context.USER_SERVICE:
                return mMockSystemServices.userManager;
            case Context.POWER_SERVICE:
                return mMockSystemServices.powerManager;
            case Context.WIFI_SERVICE:
                return mMockSystemServices.wifiManager;
            case Context.ACCOUNT_SERVICE:
                return mMockSystemServices.accountManager;
            case Context.TELEPHONY_SERVICE:
                return mMockSystemServices.telephonyManager;
            case Context.APP_OPS_SERVICE:
                return mMockSystemServices.appOpsManager;
            case Context.CROSS_PROFILE_APPS_SERVICE:
                return mMockSystemServices.crossProfileApps;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        return realTestContext.getSystemServiceName(serviceClass);
    }

    @Override
    public PackageManager getPackageManager() {
        return mMockSystemServices.packageManager;
    }

    public UserManagerInternal getUserManagerInternal() {
        return mMockSystemServices.userManagerInternal;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (UserHandle.isSameApp(binder.getCallingUid(), SYSTEM_UID)) {
            return; // Assume system has all permissions.
        }
        List<String> permissions = binder.callingPermissions.get(binder.getCallingUid());
        if (permissions == null) {
            // TODO: delete the following line. to do this without breaking any tests, first it's
            //       necessary to remove all tests that set it directly.
            permissions = callerPermissions;
            //            throw new UnsupportedOperationException(
            //                    "Caller UID " + binder.getCallingUid() + " doesn't exist");
        }
        if (!permissions.contains(permission)) {
            throw new SecurityException("Caller doesn't have " + permission + " : " + message);
        }
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (UserHandle.isSameApp(binder.getCallingUid(), SYSTEM_UID)) {
            return PackageManager.PERMISSION_GRANTED; // Assume system has all permissions.
        }
        List<String> permissions = binder.callingPermissions.get(binder.getCallingUid());
        if (permissions == null) {
            permissions = callerPermissions;
        }
        if (permissions.contains(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        } else {
            return PackageManager.PERMISSION_DENIED;
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
    public void sendBroadcastAsUserMultiplePermissions(Intent intent, UserHandle user,
            String[] receiverPermissions) {
        spiedContext.sendBroadcastAsUserMultiplePermissions(intent, user, receiverPermissions);
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
        if (binder.callingPid != SYSTEM_PID) {
            // Unless called as the system process, can only call if the target user is the
            // calling user.
            // (The actual check is more complex; we may need to change it later.)
            Assert.assertEquals(UserHandle.getUserId(binder.getCallingUid()), user.getIdentifier());
        }

        spiedContext.sendBroadcastAsUser(intent, user);
    }

    @Override
    public void sendBroadcastAsUser(Intent intent,
            UserHandle user, @Nullable String receiverPermission, @Nullable Bundle options) {
        spiedContext.sendBroadcastAsUser(intent, user, receiverPermission, options);
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
        sendOrderedBroadcastAsUser(
                intent, user, receiverPermission, AppOpsManager.OP_NONE, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        sendOrderedBroadcastAsUser(
                intent, user, receiverPermission, appOp, null, resultReceiver,
                scheduler, initialCode, initialData, initialExtras);
    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
            String receiverPermission, int appOp, Bundle options, BroadcastReceiver resultReceiver,
            Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
        spiedContext.sendOrderedBroadcastAsUser(intent, user, receiverPermission, appOp, options,
                resultReceiver, scheduler, initialCode, initialData, initialExtras);
        resultReceiver.onReceive(spiedContext, intent);
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
        mMockSystemServices.registerReceiver(receiver, filter, null);
        return spiedContext.registerReceiver(receiver, filter);
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        mMockSystemServices.registerReceiver(receiver, filter, scheduler);
        return spiedContext.registerReceiver(receiver, filter, broadcastPermission, scheduler);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String broadcastPermission, Handler scheduler) {
        mMockSystemServices.registerReceiver(receiver, filter, scheduler);
        return spiedContext.registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        mMockSystemServices.unregisterReceiver(receiver);
        spiedContext.unregisterReceiver(receiver);
    }

    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException {
        return mMockSystemServices.createPackageContextAsUser(packageName, flags, user);
    }

    @Override
    public ContentResolver getContentResolver() {
        return mMockSystemServices.contentResolver;
    }

    @Override
    public int getUserId() {
        return UserHandle.getUserId(binder.getCallingUid());
    }

    @Override
    public int checkCallingPermission(String permission) {
        return spiedContext.checkCallingPermission(permission);
    }
}
