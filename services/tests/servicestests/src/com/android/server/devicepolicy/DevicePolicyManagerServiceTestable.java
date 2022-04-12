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

import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.DevicePolicyManagerLiteInternal;
import android.app.backup.IBackupManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.media.IAudioService;
import android.net.IIpConnectivityMetrics;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.IPermissionManager;
import android.security.KeyChain;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.IWindowManager;

import androidx.annotation.NonNull;

import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.server.LocalServices;
import com.android.server.PersistentDataBlockManagerInternal;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.UserManagerInternal;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Overrides {@link #DevicePolicyManagerService} for dependency injection.
 */
public class DevicePolicyManagerServiceTestable extends DevicePolicyManagerService {
    /**
     * Overrides {@link #Owners} for dependency injection.
     */
    public static class OwnersTestable extends Owners {

        public OwnersTestable(MockSystemServices services) {
            super(services.userManager, services.userManagerInternal,
                    services.packageManagerInternal, services.activityTaskManagerInternal,
                    services.activityManagerInternal, new MockInjector(services));
        }

        static class MockInjector extends Injector {
            private final MockSystemServices mServices;

            private MockInjector(MockSystemServices services) {
                mServices = services;
            }

            @Override
            File environmentGetDataSystemDirectory() {
                return mServices.dataDir;
            }

            @Override
            File environmentGetUserSystemDirectory(int userId) {
                return mServices.environment.getUserSystemDirectory(userId);
            }
        }
    }

    public final DpmMockContext context;
    protected final MockInjector mMockInjector;

    public DevicePolicyManagerServiceTestable(MockSystemServices services, DpmMockContext context) {
        this(new MockInjector(services, context));
    }

    private DevicePolicyManagerServiceTestable(MockInjector injector) {
        super(unregisterLocalServices(injector));
        mMockInjector = injector;
        this.context = injector.context;
    }

    /**
     * Unregisters local services to avoid IllegalStateException when DPMS ctor re-registers them.
     * This is made into a static method to circumvent the requirement to call super() first.
     * Returns its parameter as is.
     */
    private static MockInjector unregisterLocalServices(MockInjector injector) {
        LocalServices.removeServiceForTest(DevicePolicyManagerLiteInternal.class);
        LocalServices.removeServiceForTest(DevicePolicyManagerInternal.class);
        return injector;
    }

    public void notifyChangeToContentObserver(Uri uri, int userHandle) {
        ContentObserver co = mMockInjector.mContentObservers.get(new Pair<>(uri, userHandle));
        if (co != null) {
            co.onChange(false, uri, userHandle); // notify synchronously
        }

        // Notify USER_ALL observer too.
        co = mMockInjector.mContentObservers.get(new Pair<>(uri, UserHandle.USER_ALL));
        if (co != null) {
            co.onChange(false, uri, userHandle); // notify synchronously
        }
    }

    static class MockInjector extends Injector {

        public final DpmMockContext context;
        private final MockSystemServices services;

        // Key is a pair of uri and userId
        private final Map<Pair<Uri, Integer>, ContentObserver> mContentObservers = new ArrayMap<>();

        // Used as an override when set to nonzero.
        private long mCurrentTimeMillis = 0;

        private final Map<Long, Pair<String, Integer>> mEnabledChanges = new ArrayMap<>();

        public MockInjector(MockSystemServices services, DpmMockContext context) {
            super(context);
            this.services = services;
            this.context = context;
        }

        @Override
        Owners newOwners() {
            return new OwnersTestable(services);
        }

        @Override
        UserManager getUserManager() {
            return services.userManager;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return services.userManagerInternal;
        }

        @Override
        UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return services.usageStatsManagerInternal;
        }

        @Override
        NetworkPolicyManagerInternal getNetworkPolicyManagerInternal() {
            return services.networkPolicyManagerInternal;
        }

        @Override
        PackageManagerInternal getPackageManagerInternal() {
            return services.packageManagerInternal;
        }

        @Override
        PowerManagerInternal getPowerManagerInternal() {
            return services.powerManagerInternal;
        }

        @Override
        NotificationManager getNotificationManager() {
            return services.notificationManager;
        }

        @Override
        IIpConnectivityMetrics getIIpConnectivityMetrics() {
            return services.iipConnectivityMetrics;
        }

        @Override
        IWindowManager getIWindowManager() {
            return services.iwindowManager;
        }

        @Override
        IActivityManager getIActivityManager() {
            return services.iactivityManager;
        }

        @Override
        IActivityTaskManager getIActivityTaskManager() {
            return services.iactivityTaskManager;
        }

        @Override
        ActivityManagerInternal getActivityManagerInternal() {
            return services.activityManagerInternal;
        }

        @Override
        IPackageManager getIPackageManager() {
            return services.ipackageManager;
        }

        @Override
        IPermissionManager getIPermissionManager() {
            return services.ipermissionManager;
        }

        @Override
        IBackupManager getIBackupManager() {
            return services.ibackupManager;
        }

        @Override
        LockSettingsInternal getLockSettingsInternal() {
            return services.lockSettingsInternal;
        }

        @Override
        IAudioService getIAudioService() {
            return services.iaudioService;
        }

        @Override
        PersistentDataBlockManagerInternal getPersistentDataBlockManagerInternal() {
            return services.persistentDataBlockManagerInternal;
        }

        @Override
        Looper getMyLooper() {
            return Looper.getMainLooper();
        }

        @Override
        AlarmManager getAlarmManager() {return services.alarmManager;}

        @Override
        LockPatternUtils newLockPatternUtils() {
            return services.lockPatternUtils;
        }

        @Override
        UsbManager getUsbManager() {
            return services.usbManager;
        }

        @Override
        boolean storageManagerIsFileBasedEncryptionEnabled() {
            return services.storageManager.isFileBasedEncryptionEnabled();
        }

        @Override
        boolean storageManagerIsNonDefaultBlockEncrypted() {
            return services.storageManager.isNonDefaultBlockEncrypted();
        }

        @Override
        boolean storageManagerIsEncrypted() {
            return services.storageManager.isEncrypted();
        }

        @Override
        boolean storageManagerIsEncryptable() {
            return services.storageManager.isEncryptable();
        }

        @Override
        String getDevicePolicyFilePathForSystemUser() {
            return services.systemUserDataDir.getAbsolutePath() + "/";
        }

        @Override
        long binderClearCallingIdentity() {
            return context.binder.clearCallingIdentity();
        }

        @Override
        void binderRestoreCallingIdentity(long token) {
            context.binder.restoreCallingIdentity(token);
        }

        @Override
        void binderWithCleanCallingIdentity(@NonNull ThrowingRunnable action) {
            context.binder.withCleanCallingIdentity(action);
        }

        @Override
        int binderGetCallingUid() {
            return context.binder.getCallingUid();
        }

        @Override
        int binderGetCallingPid() {
            return context.binder.getCallingPid();
        }

        @Override
        UserHandle binderGetCallingUserHandle() {
            return context.binder.getCallingUserHandle();
        }

        @Override
        boolean binderIsCallingUidMyUid() {
            return context.binder.isCallerUidMyUid();
        }

        @Override
        File environmentGetUserSystemDirectory(int userId) {
            return services.environment.getUserSystemDirectory(userId);
        }

        @Override
        void powerManagerGoToSleep(long time, int reason, int flags) {
            services.powerManager.goToSleep(time, reason, flags);
        }

        @Override
        void powerManagerReboot(String reason) {
            services.powerManager.reboot(reason);
        }

        @Override
        boolean recoverySystemRebootWipeUserData(boolean shutdown, String reason, boolean force,
                boolean wipeEuicc, boolean wipeExtRequested, boolean wipeResetProtectionData)
                        throws IOException {
            return services.recoverySystem.rebootWipeUserData(shutdown, reason, force, wipeEuicc,
                    wipeExtRequested, wipeResetProtectionData);
        }

        @Override
        boolean systemPropertiesGetBoolean(String key, boolean def) {
            return services.systemProperties.getBoolean(key, def);
        }

        @Override
        long systemPropertiesGetLong(String key, long def) {
            return services.systemProperties.getLong(key, def);
        }

        @Override
        String systemPropertiesGet(String key, String def) {
            return services.systemProperties.get(key, def);
        }

        @Override
        String systemPropertiesGet(String key) {
            return services.systemProperties.get(key);
        }

        @Override
        void systemPropertiesSet(String key, String value) {
            services.systemProperties.set(key, value);
        }

        @Override
        boolean userManagerIsHeadlessSystemUserMode() {
            return services.userManagerForMock.isHeadlessSystemUserMode();
        }

        @Override
        PendingIntent pendingIntentGetActivityAsUser(Context context, int requestCode,
                Intent intent, int flags, Bundle options, UserHandle user) {
            return null;
        }

        @Override
        PendingIntent pendingIntentGetBroadcast(Context context, int requestCode,
                Intent intent, int flags) {
            return null;
        }

        @Override
        void registerContentObserver(Uri uri, boolean notifyForDescendents,
                ContentObserver observer, int userHandle) {
            mContentObservers.put(new Pair<Uri, Integer>(uri, userHandle), observer);
        }

        @Override
        int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return services.settings.settingsSecureGetIntForUser(name, def, userHandle);
        }

        @Override
        String settingsSecureGetStringForUser(String name, int userHandle) {
            return services.settings.settingsSecureGetStringForUser(name, userHandle);
        }

        @Override
        void settingsSecurePutIntForUser(String name, int value, int userHandle) {
            services.settings.settingsSecurePutIntForUser(name, value, userHandle);
        }

        @Override
        void settingsSecurePutStringForUser(String name, String value, int userHandle) {
            services.settings.settingsSecurePutStringForUser(name, value, userHandle);
        }

        @Override
        void settingsGlobalPutStringForUser(String name, String value, int userHandle) {
            services.settings.settingsGlobalPutStringForUser(name, value, userHandle);
        }

        @Override
        void settingsSecurePutInt(String name, int value) {
            services.settings.settingsSecurePutInt(name, value);
        }

        @Override
        void settingsGlobalPutInt(String name, int value) {
            services.settings.settingsGlobalPutInt(name, value);
        }

        @Override
        void settingsSecurePutString(String name, String value) {
            services.settings.settingsSecurePutString(name, value);
        }

        @Override
        void settingsGlobalPutString(String name, String value) {
            services.settings.settingsGlobalPutString(name, value);
        }

        @Override
        void settingsSystemPutStringForUser(String name, String value, int userId) {
            services.settings.settingsSystemPutStringForUser(name, value, userId);
        }

        @Override
        int settingsGlobalGetInt(String name, int def) {
            return services.settings.settingsGlobalGetInt(name, def);
        }

        @Override
        String settingsGlobalGetString(String name) {
            return services.settings.settingsGlobalGetString(name);
        }

        @Override
        void securityLogSetLoggingEnabledProperty(boolean enabled) {
            services.settings.securityLogSetLoggingEnabledProperty(enabled);
        }

        @Override
        boolean securityLogGetLoggingEnabledProperty() {
            return services.settings.securityLogGetLoggingEnabledProperty();
        }

        @Override
        boolean securityLogIsLoggingEnabled() {
            return services.settings.securityLogIsLoggingEnabled();
        }

        @Override
        TelephonyManager getTelephonyManager() {
            return services.telephonyManager;
        }

        @Override
        boolean isBuildDebuggable() {
            return services.buildMock.isDebuggable;
        }

        @Override
        KeyChain.KeyChainConnection keyChainBind() {
            return services.keyChainConnection;
        }

        @Override
        KeyChain.KeyChainConnection keyChainBindAsUser(UserHandle user) {
            return services.keyChainConnection;
        }

        @Override
        void postOnSystemServerInitThreadPool(Runnable runnable) {
            runnable.run();
        }

        @Override
        public TransferOwnershipMetadataManager newTransferOwnershipMetadataManager() {
            return new TransferOwnershipMetadataManager(
                    new TransferOwnershipMetadataManagerTest.MockInjector());
        }

        @Override
        public void runCryptoSelfTest() {}

        @Override
        public String[] getPersonalAppsForSuspension(int userId) {
            return new String[]{};
        }

        public void setSystemCurrentTimeMillis(long value) {
            mCurrentTimeMillis = value;
        }

        @Override
        public long systemCurrentTimeMillis() {
            return mCurrentTimeMillis != 0 ? mCurrentTimeMillis : System.currentTimeMillis();
        }

        public void setChangeEnabledForPackage(
                long changeId, boolean enabled, String packageName, int userId) {
            if (enabled) {
                mEnabledChanges.put(changeId, Pair.create(packageName, userId));
            } else {
                mEnabledChanges.remove(changeId);
            }
        }

        public void clearEnabledChanges() {
            mEnabledChanges.clear();
        }

        @Override
        public boolean isChangeEnabled(long changeId, String packageName, int userId) {
            Pair<String, Integer> packageAndUser = mEnabledChanges.get(changeId);
            if (packageAndUser == null) {
                return false;
            }

            if (!packageAndUser.first.equals(packageName)
                    || !packageAndUser.second.equals(userId)) {
                return false;
            }

            return true;
        }

        @Override
        public Context createContextAsUser(UserHandle user) {
            return context;
        }
    }
}
