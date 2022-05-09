/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.backup.IBackupManager;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.CrossProfileApps;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.Uri;
import android.net.VpnManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.IPermissionManager;
import android.provider.Settings;
import android.security.KeyChain;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.IWindowManager;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System services mocks and some other data that are shared by all contexts during the test.
 */
public class MockSystemServices {
    public final File systemUserDataDir;
    public final EnvironmentForMock environment;
    public final SystemPropertiesForMock systemProperties;
    public final Executor executor;
    public final UserManager userManager;
    public final UserManagerInternal userManagerInternal;
    public final UsageStatsManagerInternal usageStatsManagerInternal;
    public final NetworkPolicyManagerInternal networkPolicyManagerInternal;
    public final PackageManagerInternal packageManagerInternal;
    public final UserManagerForMock userManagerForMock;
    public final PowerManagerForMock powerManager;
    public final PowerManagerInternal powerManagerInternal;
    public final RecoverySystemForMock recoverySystem;
    public final NotificationManager notificationManager;
    public final IIpConnectivityMetrics iipConnectivityMetrics;
    public final IWindowManager iwindowManager;
    public final IActivityManager iactivityManager;
    public final IActivityTaskManager iactivityTaskManager;
    public ActivityManagerInternal activityManagerInternal;
    public ActivityTaskManagerInternal activityTaskManagerInternal;
    public final IPackageManager ipackageManager;
    public final IPermissionManager ipermissionManager;
    public final IBackupManager ibackupManager;
    public final IAudioService iaudioService;
    public final LockPatternUtils lockPatternUtils;
    public final LockSettingsInternal lockSettingsInternal;
    public final StorageManagerForMock storageManager;
    public final WifiManager wifiManager;
    public final SettingsForMock settings;
    public final MockContentResolver contentResolver;
    public final TelephonyManager telephonyManager;
    public final ConnectivityManager connectivityManager;
    public final AccountManager accountManager;
    public final AlarmManager alarmManager;
    public final KeyChain.KeyChainConnection keyChainConnection;
    public final CrossProfileApps crossProfileApps;
    public final PersistentDataBlockManagerInternal persistentDataBlockManagerInternal;
    public final AppOpsManager appOpsManager;
    public final UsbManager usbManager;
    public final VpnManager vpnManager;
    public final DevicePolicyManager devicePolicyManager;
    public final LocationManager locationManager;
    public final RoleManager roleManager;
    /** Note this is a partial mock, not a real mock. */
    public final PackageManager packageManager;
    public final BuildMock buildMock = new BuildMock();
    public final File dataDir;
    public final PolicyPathProvider pathProvider;

    public MockSystemServices(Context realContext, String name) {
        dataDir = new File(realContext.getCacheDir(), name);
        DpmTestUtils.clearDir(dataDir);

        environment = mock(EnvironmentForMock.class);
        systemProperties = mock(SystemPropertiesForMock.class);
        executor = mock(Executor.class);
        userManager = mock(UserManager.class);
        userManagerInternal = mock(UserManagerInternal.class);
        usageStatsManagerInternal = mock(UsageStatsManagerInternal.class);
        networkPolicyManagerInternal = mock(NetworkPolicyManagerInternal.class);

        userManagerForMock = mock(UserManagerForMock.class);
        packageManagerInternal = mock(PackageManagerInternal.class);
        powerManager = mock(PowerManagerForMock.class);
        powerManagerInternal = mock(PowerManagerInternal.class);
        recoverySystem = mock(RecoverySystemForMock.class);
        notificationManager = mock(NotificationManager.class);
        iipConnectivityMetrics = mock(IIpConnectivityMetrics.class);
        iwindowManager = mock(IWindowManager.class);
        iactivityManager = mock(IActivityManager.class);
        iactivityTaskManager = mock(IActivityTaskManager.class);
        activityManagerInternal = mock(ActivityManagerInternal.class);
        activityTaskManagerInternal = mock(ActivityTaskManagerInternal.class);
        ipackageManager = mock(IPackageManager.class);
        ipermissionManager = mock(IPermissionManager.class);
        ibackupManager = mock(IBackupManager.class);
        iaudioService = mock(IAudioService.class);
        lockPatternUtils = mock(LockPatternUtils.class);
        lockSettingsInternal = mock(LockSettingsInternal.class);
        storageManager = mock(StorageManagerForMock.class);
        wifiManager = mock(WifiManager.class);
        settings = mock(SettingsForMock.class);
        telephonyManager = mock(TelephonyManager.class);
        connectivityManager = mock(ConnectivityManager.class);
        accountManager = mock(AccountManager.class);
        alarmManager = mock(AlarmManager.class);
        keyChainConnection = mock(KeyChain.KeyChainConnection.class, RETURNS_DEEP_STUBS);
        crossProfileApps = mock(CrossProfileApps.class);
        persistentDataBlockManagerInternal = mock(PersistentDataBlockManagerInternal.class);
        appOpsManager = mock(AppOpsManager.class);
        usbManager = mock(UsbManager.class);
        vpnManager = mock(VpnManager.class);
        devicePolicyManager = mock(DevicePolicyManager.class);
        locationManager = mock(LocationManager.class);
        roleManager = realContext.getSystemService(RoleManager.class);

        // Package manager is huge, so we use a partial mock instead.
        packageManager = spy(realContext.getPackageManager());
        when(packageManagerInternal.getSystemUiServiceComponent()).thenReturn(
                new ComponentName("com.android.systemui", ".Service"));

        contentResolver = new MockContentResolver();
        contentResolver.addProvider("telephony", new MockContentProvider(realContext) {
            @Override
            public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
                return 0;
            }

            @Override
            public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                    String sortOrder) {
                return null;
            }

            @Override
            public int delete(Uri uri, String selection, String[] selectionArgs) {
                return 0;
            }
        });
        contentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());

        // Add the system user with a fake profile group already set up (this can happen in the real
        // world if a managed profile is added and then removed).
        systemUserDataDir = addUser(UserHandle.USER_SYSTEM, UserInfo.FLAG_PRIMARY,
                UserManager.USER_TYPE_FULL_SYSTEM, UserHandle.USER_SYSTEM);

        // System user is always running.
        setUserRunning(UserHandle.USER_SYSTEM, true);
        pathProvider = new PolicyPathProvider() {
            @Override
            public File getDataSystemDirectory() {
                return new File(systemUserDataDir.getAbsolutePath());
            }

            @Override
            public File getUserSystemDirectory(int userId) {
                return environment.getUserSystemDirectory(userId);
            }
        };
    }

    /** Optional mapping of other user contexts for {@link #createPackageContextAsUser} to return */
    private final Map<Pair<UserHandle, String>, Context> userPackageContexts = new ArrayMap<>();

    private final ArrayList<UserInfo> mUserInfos = new ArrayList<>();

    private final List<BroadcastReceiverRegistration> mBroadcastReceivers = new ArrayList<>();

    public void registerReceiver(
            BroadcastReceiver receiver, IntentFilter filter, Handler scheduler) {
        mBroadcastReceivers.add(new BroadcastReceiverRegistration(receiver, filter, scheduler));
    }

    public void unregisterReceiver(BroadcastReceiver receiver) {
        mBroadcastReceivers.removeIf(r -> r.receiver == receiver);
    }

    public File addUser(int userId, int flags, String type) {
        return addUser(userId, flags, type, UserInfo.NO_PROFILE_GROUP_ID);
    }

    public File addUser(int userId, int flags, String type, int profileGroupId) {
        // Set up (default) UserInfo for CALLER_USER_HANDLE.
        final UserInfo uh = new UserInfo(userId, "user" + userId, flags);

        uh.userType = type;
        uh.profileGroupId = profileGroupId;
        when(userManager.getUserInfo(eq(userId))).thenReturn(uh);
        // Ensure there are no duplicate UserInfo records.
        // TODO: fix tests so that this is not needed.
        for (int i = 0; i < mUserInfos.size(); i++) {
            if (mUserInfos.get(i).id == userId) {
                mUserInfos.remove(i);
                break;
            }
        }
        mUserInfos.add(uh);
        when(userManager.getUsers()).thenReturn(mUserInfos);
        when(userManager.getAliveUsers()).thenReturn(mUserInfos);
        when(userManager.isUserRunning(eq(new UserHandle(userId)))).thenReturn(true);
        when(userManager.getProfileParent(anyInt())).thenAnswer(
                invocation -> {
                    final int userId1 = (int) invocation.getArguments()[0];
                    final UserInfo ui = getUserInfo(userId1);
                    return ui == null ? null : getUserInfo(ui.profileGroupId);
                }
        );
        when(userManager.getProfileParent(any(UserHandle.class))).thenAnswer(
                invocation -> {
                    final UserHandle userHandle = (UserHandle) invocation.getArguments()[0];
                    final UserInfo ui = getUserInfo(userHandle.getIdentifier());
                    return ui == null ? UserHandle.USER_NULL : UserHandle.of(ui.profileGroupId);
                }
        );
        when(userManager.getProfiles(anyInt())).thenAnswer(
                invocation -> {
                    final int userId12 = (int) invocation.getArguments()[0];
                    return getProfiles(userId12);
                }
        );
        when(userManager.getProfileIdsWithDisabled(anyInt())).thenAnswer(
                invocation -> {
                    final int userId13 = (int) invocation.getArguments()[0];
                    List<UserInfo> profiles = getProfiles(userId13);
                    return profiles.stream()
                            .mapToInt(profile -> profile.id)
                            .toArray();
                }
        );
        when(userManagerInternal.getUserInfos()).thenReturn(
                mUserInfos.toArray(new UserInfo[mUserInfos.size()]));

        when(accountManager.getAccountsAsUser(anyInt())).thenReturn(new Account[0]);

        // Create a data directory.
        final File dir = new File(dataDir, "users/" + userId);
        DpmTestUtils.clearDir(dir);

        when(environment.getUserSystemDirectory(eq(userId))).thenReturn(dir);
        return dir;
    }

    public void removeUser(int userId) {
        for (int i = 0; i < mUserInfos.size(); i++) {
            if (mUserInfos.get(i).id == userId) {
                mUserInfos.remove(i);
                break;
            }
        }
        when(userManager.getUserInfo(eq(userId))).thenReturn(null);

        when(userManager.isUserRunning(eq(new UserHandle(userId)))).thenReturn(false);
    }

    private UserInfo getUserInfo(int userId) {
        for (final UserInfo ui : mUserInfos) {
            if (ui.id == userId) {
                return ui;
            }
        }
        return null;
    }

    private List<UserInfo> getProfiles(int userId) {
        final ArrayList<UserInfo> ret = new ArrayList<>();
        UserInfo parent = null;
        for (final UserInfo ui : mUserInfos) {
            if (ui.id == userId) {
                parent = ui;
                break;
            }
        }
        if (parent == null) {
            return ret;
        }
        for (final UserInfo ui : mUserInfos) {
            if (ui == parent
                    || ui.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
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
        for (final int userId : userIds) {
            addUser(userId, 0, "");
        }
    }

    public void setUserRunning(int userId, boolean isRunning) {
        when(userManager.isUserRunning(MockUtils.checkUserHandle(userId)))
                .thenReturn(isRunning);
    }

    public void injectBroadcast(Context context, final Intent intent, int userId) {
        //final int userId = UserHandle.getUserId(binder.getCallingUid());
        for (final BroadcastReceiverRegistration receiver : mBroadcastReceivers) {
            receiver.sendBroadcastIfApplicable(context, userId, intent);
        }
    }

    public void rethrowBackgroundBroadcastExceptions() throws Exception {
        for (final BroadcastReceiverRegistration receiver : mBroadcastReceivers) {
            final Exception e = receiver.backgroundException.getAndSet(null);
            if (e != null) {
                throw e;
            }
        }
    }

    public void addPackageContext(UserHandle user, Context context) {
        if (context.getPackageName() == null) {
            throw new NullPointerException("getPackageName() == null");
        }
        userPackageContexts.put(new Pair<>(user, context.getPackageName()), context);
    }

    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException {
        final Pair<UserHandle, String> key = new Pair<>(user, packageName);
        if (userPackageContexts.containsKey(key)) {
            return userPackageContexts.get(key);
        }
        throw new UnsupportedOperationException("No package " + packageName + " for user " + user);
    }


    public static class EnvironmentForMock {
        public File getUserSystemDirectory(int userId) {
            return null;
        }
    }

    public static class BuildMock {
        public boolean isDebuggable = true;
    }

    public static class PowerManagerForMock {
        public PowerManager.WakeLock newWakeLock(int levelAndFlags, String tag) {
            return null;
        }

        public void goToSleep(long time, int reason, int flags) {
        }

        public void reboot(String reason) {
        }
    }

    public static class RecoverySystemForMock {
        public boolean rebootWipeUserData(boolean shutdown, String reason, boolean force,
                boolean wipeEuicc, boolean wipeExtRequested, boolean wipeResetProtectionData)
                        throws IOException {
            return false;
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
        public boolean isHeadlessSystemUserMode() {
            return false;
        }
    }

    public static class SettingsForMock {
        public int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return 0;
        }

        public String settingsSecureGetStringForUser(String name, int userHandle) {
            return null;
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

        public void settingsSystemPutStringForUser(String name, String value, int callingUserId) {
        }

        public int settingsGlobalGetInt(String name, int value) {
            return 0;
        }

        public String settingsGlobalGetString(String name) {
            return "";
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
    }

    // We have to keep track of broadcast receivers registered for a given intent ourselves as the
    // DPM unit tests mock out the package manager and PackageManager.queryBroadcastReceivers() does
    // not work.
    private static class BroadcastReceiverRegistration {
        public final BroadcastReceiver receiver;
        public final IntentFilter filter;
        public final Handler scheduler;

        // Exceptions thrown in a background thread kill the whole test. Save them instead.
        public final AtomicReference<Exception> backgroundException = new AtomicReference<>();

        public BroadcastReceiverRegistration(BroadcastReceiver receiver, IntentFilter filter,
                Handler scheduler) {
            this.receiver = receiver;
            this.filter = filter;
            this.scheduler = scheduler;
        }

        public void sendBroadcastIfApplicable(Context context, int userId, Intent intent) {
            final BroadcastReceiver.PendingResult result = new BroadcastReceiver.PendingResult(
                    0 /* resultCode */, null /* resultData */, null /* resultExtras */,
                    0 /* type */, false /* ordered */, false /* sticky */, null /* token */, userId,
                    0 /* flags */);
            if (filter.match(null, intent, false, "DpmMockContext") > 0) {
                final Runnable send = () -> {
                    receiver.setPendingResult(result);
                    receiver.onReceive(context, intent);
                };
                if (scheduler != null) {
                    scheduler.post(() -> {
                        try {
                            send.run();
                        } catch (Exception e) {
                            backgroundException.compareAndSet(null, e);
                        }
                    });
                } else {
                    send.run();
                }
            }
        }
    }
}
