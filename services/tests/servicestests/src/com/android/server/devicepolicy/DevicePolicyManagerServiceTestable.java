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

import android.app.IActivityManager;
import android.app.NotificationManager;
import android.app.backup.IBackupManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.media.IAudioService;
import android.net.Uri;
import android.os.Looper;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.IWindowManager;

import com.android.internal.widget.LockPatternUtils;

import java.io.File;
import java.util.Map;

/**
 * Overrides {@link #DevicePolicyManagerService} for dependency injection.
 */
public class DevicePolicyManagerServiceTestable extends DevicePolicyManagerService {
    /**
     * Overrides {@link #Owners} for dependency injection.
     */
    public static class OwnersTestable extends Owners {
        public static final String LEGACY_FILE = "legacy.xml";
        public static final String DEVICE_OWNER_FILE = "device_owner2.xml";
        public static final String PROFILE_OWNER_FILE_BASE = "profile_owner.xml";

        private final File mLegacyFile;
        private final File mDeviceOwnerFile;
        private final File mProfileOwnerBase;

        public OwnersTestable(DpmMockContext context) {
            super(context.userManager, context.userManagerInternal, context.packageManagerInternal);
            mLegacyFile = new File(context.dataDir, LEGACY_FILE);
            mDeviceOwnerFile = new File(context.dataDir, DEVICE_OWNER_FILE);
            mProfileOwnerBase = new File(context.dataDir, PROFILE_OWNER_FILE_BASE);
        }

        @Override
        File getLegacyConfigFileWithTestOverride() {
            return mLegacyFile;
        }

        @Override
        File getDeviceOwnerFileWithTestOverride() {
            return mDeviceOwnerFile;
        }

        @Override
        File getProfileOwnerFileWithTestOverride(int userId) {
            return new File(mDeviceOwnerFile.getAbsoluteFile() + "-" + userId);
        }
    }

    public final DpmMockContext context;
    private final MockInjector mMockInjector;

    public DevicePolicyManagerServiceTestable(DpmMockContext context, File dataDir) {
        this(new MockInjector(context, dataDir));
    }

    private DevicePolicyManagerServiceTestable(MockInjector injector) {
        super(injector);
        mMockInjector = injector;
        this.context = injector.context;
    }


    public void notifyChangeToContentObserver(Uri uri, int userHandle) {
        ContentObserver co = mMockInjector.mContentObservers
                .get(new Pair<Uri, Integer>(uri, userHandle));
        if (co != null) {
            co.onChange(false, uri, userHandle); // notify synchronously
        }

        // Notify USER_ALL observer too.
        co = mMockInjector.mContentObservers
                .get(new Pair<Uri, Integer>(uri, UserHandle.USER_ALL));
        if (co != null) {
            co.onChange(false, uri, userHandle); // notify synchronously
        }
    }


    private static class MockInjector extends Injector {

        public final DpmMockContext context;

        public final File dataDir;

        // Key is a pair of uri and userId
        private final Map<Pair<Uri, Integer>, ContentObserver> mContentObservers = new ArrayMap<>();

        private MockInjector(DpmMockContext context, File dataDir) {
            super(context);
            this.context = context;
            this.dataDir = dataDir;
        }

        @Override
        Owners newOwners() {
            return new OwnersTestable(context);
        }

        @Override
        UserManager getUserManager() {
            return context.userManager;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return context.userManagerInternal;
        }

        @Override
        PackageManagerInternal getPackageManagerInternal() {
            return context.packageManagerInternal;
        }

        @Override
        PowerManagerInternal getPowerManagerInternal() {
            return context.powerManagerInternal;
        }

        @Override
        NotificationManager getNotificationManager() {
            return context.notificationManager;
        }

        @Override
        IWindowManager getIWindowManager() {
            return context.iwindowManager;
        }

        @Override
        IActivityManager getIActivityManager() {
            return context.iactivityManager;
        }

        @Override
        IPackageManager getIPackageManager() {
            return context.ipackageManager;
        }

        @Override
        IBackupManager getIBackupManager() {
            return context.ibackupManager;
        }

        @Override
        IAudioService getIAudioService() {
            return context.iaudioService;
        }

        @Override
        Looper getMyLooper() {
            return Looper.getMainLooper();
        }

        @Override
        LockPatternUtils newLockPatternUtils() {
            return context.lockPatternUtils;
        }

        @Override
        boolean storageManagerIsFileBasedEncryptionEnabled() {
            return context.storageManager.isFileBasedEncryptionEnabled();
        }

        @Override
        boolean storageManagerIsNonDefaultBlockEncrypted() {
            return context.storageManager.isNonDefaultBlockEncrypted();
        }

        @Override
        boolean storageManagerIsEncrypted() {
            return context.storageManager.isEncrypted();
        }

        @Override
        boolean storageManagerIsEncryptable() {
            return context.storageManager.isEncryptable();
        }

        @Override
        String getDevicePolicyFilePathForSystemUser() {
            return context.systemUserDataDir.getAbsolutePath() + "/";
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
            return context.environment.getUserSystemDirectory(userId);
        }

        @Override
        void powerManagerGoToSleep(long time, int reason, int flags) {
            context.powerManager.goToSleep(time, reason, flags);
        }

        @Override
        void powerManagerReboot(String reason) {
            context.powerManager.reboot(reason);
        }

        @Override
        boolean systemPropertiesGetBoolean(String key, boolean def) {
            return context.systemProperties.getBoolean(key, def);
        }

        @Override
        long systemPropertiesGetLong(String key, long def) {
            return context.systemProperties.getLong(key, def);
        }

        @Override
        String systemPropertiesGet(String key, String def) {
            return context.systemProperties.get(key, def);
        }

        @Override
        String systemPropertiesGet(String key) {
            return context.systemProperties.get(key);
        }

        @Override
        void systemPropertiesSet(String key, String value) {
            context.systemProperties.set(key, value);
        }

        @Override
        boolean userManagerIsSplitSystemUser() {
            return context.userManagerForMock.isSplitSystemUser();
        }

        @Override
        void registerContentObserver(Uri uri, boolean notifyForDescendents,
                ContentObserver observer, int userHandle) {
            mContentObservers.put(new Pair<Uri, Integer>(uri, userHandle), observer);
        }

        @Override
        int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return context.settings.settingsSecureGetIntForUser(name, def, userHandle);
        }

        @Override
        void settingsSecurePutIntForUser(String name, int value, int userHandle) {
            context.settings.settingsSecurePutIntForUser(name, value, userHandle);
        }

        @Override
        void settingsSecurePutStringForUser(String name, String value, int userHandle) {
            context.settings.settingsSecurePutStringForUser(name, value, userHandle);
        }

        @Override
        void settingsGlobalPutStringForUser(String name, String value, int userHandle) {
            context.settings.settingsGlobalPutStringForUser(name, value, userHandle);
        }

        @Override
        void settingsSecurePutInt(String name, int value) {
            context.settings.settingsSecurePutInt(name, value);
        }

        @Override
        void settingsGlobalPutInt(String name, int value) {
            context.settings.settingsGlobalPutInt(name, value);
        }

        @Override
        void settingsSecurePutString(String name, String value) {
            context.settings.settingsSecurePutString(name, value);
        }

        @Override
        void settingsGlobalPutString(String name, String value) {
            context.settings.settingsGlobalPutString(name, value);
        }

        @Override
        int settingsGlobalGetInt(String name, int def) {
            return context.settings.settingsGlobalGetInt(name, def);
        }

        @Override
        void securityLogSetLoggingEnabledProperty(boolean enabled) {
            context.settings.securityLogSetLoggingEnabledProperty(enabled);
        }

        @Override
        boolean securityLogGetLoggingEnabledProperty() {
            return context.settings.securityLogGetLoggingEnabledProperty();
        }

        @Override
        boolean securityLogIsLoggingEnabled() {
            return context.settings.securityLogIsLoggingEnabled();
        }

        @Override
        TelephonyManager getTelephonyManager() {
            return context.telephonyManager;
        }
    }
}
