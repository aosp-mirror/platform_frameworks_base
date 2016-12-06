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

import com.android.internal.widget.LockPatternUtils;

import android.app.IActivityManager;
import android.app.NotificationManager;
import android.app.backup.IBackupManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.media.IAudioService;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager.WakeLock;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.view.IWindowManager;

import org.junit.Assert;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

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

        public void reboot(String reason) {
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

    public static class UserManagerForMock {
        public boolean isSplitSystemUser() {
            return false;
        }
    }

    public static class SettingsForMock {
        public int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return 0;
        }

        public void settingsSecurePutIntForUser(String name, int value, int userHandle) {
        }

        public void settingsSecurePutStringForUser(String name, String value, int userHandle) {
        }

        public void settingsGlobalPutStringForUser(String name, String value, int userHandle) {
        }

        public void settingsSecurePutInt(String name, int value) {
        }

        public void settingsGlobalPutInt(String name, int value) {
        }

        public void settingsSecurePutString(String name, String value) {
        }

        public void settingsGlobalPutString(String name, String value) {
        }

        public int settingsGlobalGetInt(String name, int value) {
            return 0;
        }

        public void securityLogSetLoggingEnabledProperty(boolean enabled) {
        }

        public boolean securityLogGetLoggingEnabledProperty() {
            return false;
        }

        public boolean securityLogIsLoggingEnabled() {
            return false;
        }
    }

    public static class StorageManagerForMock {
        public boolean isFileBasedEncryptionEnabled() {
            return false;
        }

        public boolean isNonDefaultBlockEncrypted() {
            return false;
        }

        public boolean isEncrypted() {
            return false;
        }

        public boolean isEncryptable() {
            return false;
        }
    }

    public final Context realTestContext;

    /**
     * Use this instance to verify unimplemented methods such as {@link #sendBroadcast}.
     * (Spying on {@code this} instance will confuse mockito somehow and I got weired "wrong number
     * of arguments" exceptions.)
     */
    public final Context spiedContext;

    public final File dataDir;
    public final File systemUserDataDir;

    public final MockBinder binder;
    public final EnvironmentForMock environment;
    public final SystemPropertiesForMock systemProperties;
    public final UserManager userManager;
    public final UserManagerInternal userManagerInternal;
    public final PackageManagerInternal packageManagerInternal;
    public final UserManagerForMock userManagerForMock;
    public final PowerManagerForMock powerManager;
    public final PowerManagerInternal powerManagerInternal;
    public final NotificationManager notificationManager;
    public final IWindowManager iwindowManager;
    public final IActivityManager iactivityManager;
    public final IPackageManager ipackageManager;
    public final IBackupManager ibackupManager;
    public final IAudioService iaudioService;
    public final LockPatternUtils lockPatternUtils;
    public final StorageManagerForMock storageManager;
    public final WifiManager wifiManager;
    public final SettingsForMock settings;
    public final MockContentResolver contentResolver;
    public final TelephonyManager telephonyManager;

    /** Note this is a partial mock, not a real mock. */
    public final PackageManager packageManager;

    public final List<String> callerPermissions = new ArrayList<>();

    private final ArrayList<UserInfo> mUserInfos = new ArrayList<>();

    public DpmMockContext(Context context, File dataDir) {
        realTestContext = context;

        this.dataDir = dataDir;
        DpmTestUtils.clearDir(dataDir);

        binder = new MockBinder();
        environment = mock(EnvironmentForMock.class);
        systemProperties= mock(SystemPropertiesForMock.class);
        userManager = mock(UserManager.class);
        userManagerInternal = mock(UserManagerInternal.class);
        userManagerForMock = mock(UserManagerForMock.class);
        packageManagerInternal = mock(PackageManagerInternal.class);
        powerManager = mock(PowerManagerForMock.class);
        powerManagerInternal = mock(PowerManagerInternal.class);
        notificationManager = mock(NotificationManager.class);
        iwindowManager = mock(IWindowManager.class);
        iactivityManager = mock(IActivityManager.class);
        ipackageManager = mock(IPackageManager.class);
        ibackupManager = mock(IBackupManager.class);
        iaudioService = mock(IAudioService.class);
        lockPatternUtils = mock(LockPatternUtils.class);
        storageManager = mock(StorageManagerForMock.class);
        wifiManager = mock(WifiManager.class);
        settings = mock(SettingsForMock.class);
        telephonyManager = mock(TelephonyManager.class);

        // Package manager is huge, so we use a partial mock instead.
        packageManager = spy(context.getPackageManager());

        spiedContext = mock(Context.class);

        contentResolver = new MockContentResolver();

        // Add the system user
        systemUserDataDir = addUser(UserHandle.USER_SYSTEM, UserInfo.FLAG_PRIMARY);

        // System user is always running.
        setUserRunning(UserHandle.USER_SYSTEM, true);
    }

    public File addUser(int userId, int flags) {

        // Set up (default) UserInfo for CALLER_USER_HANDLE.
        final UserInfo uh = new UserInfo(userId, "user" + userId, flags);
        when(userManager.getUserInfo(eq(userId))).thenReturn(uh);

        mUserInfos.add(uh);
        when(userManager.getUsers()).thenReturn(mUserInfos);
        when(userManager.getUsers(anyBoolean())).thenReturn(mUserInfos);
        when(userManager.isUserRunning(eq(new UserHandle(userId)))).thenReturn(true);
        when(userManager.getUserInfo(anyInt())).thenAnswer(
                new Answer<UserInfo>() {
                    @Override
                    public UserInfo answer(InvocationOnMock invocation) throws Throwable {
                        final int userId = (int) invocation.getArguments()[0];
                        for (UserInfo ui : mUserInfos) {
                            if (ui.id == userId) {
                                return ui;
                            }
                        }
                        return null;
                    }
                }
        );
        when(userManager.getProfiles(anyInt())).thenAnswer(
                new Answer<List<UserInfo>>() {
                    @Override
                    public List<UserInfo> answer(InvocationOnMock invocation) throws Throwable {
                        final int userId = (int) invocation.getArguments()[0];
                        return getProfiles(userId);
                    }
                }
        );
        when(userManager.getProfileIdsWithDisabled(anyInt())).thenAnswer(
                new Answer<int[]>() {
                    @Override
                    public int[] answer(InvocationOnMock invocation) throws Throwable {
                        final int userId = (int) invocation.getArguments()[0];
                        List<UserInfo> profiles = getProfiles(userId);
                        int[] results = new int[profiles.size()];
                        for (int i = 0; i < results.length; i++) {
                            results[i] = profiles.get(i).id;
                        }
                        return results;
                    }
                }
        );


        // Create a data directory.
        final File dir = new File(dataDir, "user" + userId);
        DpmTestUtils.clearDir(dir);

        when(environment.getUserSystemDirectory(eq(userId))).thenReturn(dir);
        return dir;
    }

    private List<UserInfo> getProfiles(int userId) {
        final ArrayList<UserInfo> ret = new ArrayList<UserInfo>();
        UserInfo parent = null;
        for (UserInfo ui : mUserInfos) {
            if (ui.id == userId) {
                parent = ui;
                break;
            }
        }
        if (parent == null) {
            return ret;
        }
        ret.add(parent);
        for (UserInfo ui : mUserInfos) {
            if (ui.id == userId) {
                continue;
            }
            if (ui.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && ui.profileGroupId == parent.profileGroupId) {
                ret.add(ui);
            }
        }
        return ret;
    }

    /**
     * Add multiple users at once.  They'll all have flag 0.
     */
    public void addUsers(int... userIds) {
        for (int userId : userIds) {
            addUser(userId, 0);
        }
    }

    public void setUserRunning(int userId, boolean isRunning) {
        when(userManager.isUserRunning(MockUtils.checkUserHandle(userId)))
                .thenReturn(isRunning);
    }

    @Override
    public Object getSystemService(String name) {
        switch (name) {
            case Context.USER_SERVICE:
                return userManager;
            case Context.POWER_SERVICE:
                return powerManager;
            case Context.WIFI_SERVICE:
                return wifiManager;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        return realTestContext.getSystemServiceName(serviceClass);
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
        if (binder.callingPid != SYSTEM_PID) {
            // Unless called as the system process, can only call if the target user is the
            // calling user.
            // (The actual check is more complex; we may need to change it later.)
            Assert.assertEquals(UserHandle.getUserId(binder.getCallingUid()), user.getIdentifier());
        }

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
        resultReceiver.onReceive(spiedContext, intent);
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

    @Override
    public ContentResolver getContentResolver() {
        return contentResolver;
    }
}
