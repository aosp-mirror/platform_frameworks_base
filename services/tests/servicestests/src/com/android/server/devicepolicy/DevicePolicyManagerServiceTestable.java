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
import android.content.Context;
import android.content.pm.IPackageManager;
import android.media.IAudioService;
import android.os.Looper;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.view.IWindowManager;

import java.io.File;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

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
            super(context);
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

    public DevicePolicyManagerServiceTestable(DpmMockContext context, File dataDir) {
        this(new MockInjector(context, dataDir));
    }

    private DevicePolicyManagerServiceTestable(MockInjector injector) {
        super(injector);
        this.context = injector.context;
    }

    private static class MockInjector extends Injector {

        public final DpmMockContext context;

        public final File dataDir;

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
    }
}
