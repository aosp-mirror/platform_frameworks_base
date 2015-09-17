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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.PowerManager.WakeLock;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
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

        final private File mLegacyFile;
        final private File mDeviceOwnerFile;
        final private File mProfileOwnerBase;

        public OwnersTestable(Context context, File dataDir) {
            super(context);
            mLegacyFile = new File(dataDir, LEGACY_FILE);
            mDeviceOwnerFile = new File(dataDir, DEVICE_OWNER_FILE);
            mProfileOwnerBase = new File(dataDir, PROFILE_OWNER_FILE_BASE);
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

    public final File dataDir;
    public final File systemUserDataDir;
    public final File secondUserDataDir;

    public DevicePolicyManagerServiceTestable(DpmMockContext context, File dataDir) {
        super(context);
        this.dataDir = dataDir;

        systemUserDataDir = new File(dataDir, "user0");
        DpmTestUtils.clearDir(dataDir);

        secondUserDataDir = new File(dataDir, "user" + DpmMockContext.CALLER_USER_HANDLE);
        DpmTestUtils.clearDir(secondUserDataDir);

        when(getContext().environment.getUserSystemDirectory(
                eq(DpmMockContext.CALLER_USER_HANDLE))).thenReturn(secondUserDataDir);
    }

    @Override
    DpmMockContext getContext() {
        return (DpmMockContext) super.getContext();
    }

    @Override
    protected Owners newOwners() {
        return new OwnersTestable(getContext(), dataDir);
    }

    @Override
    protected UserManager getUserManager() {
        return getContext().userManager;
    }

    @Override
    protected PackageManager getPackageManager() {
        return getContext().packageManager;
    }

    @Override
    protected PowerManagerInternal getPowerManagerInternal() {
        return getContext().powerManagerInternal;
    }

    @Override
    protected NotificationManager getNotificationManager() {
        return getContext().notificationManager;
    }

    @Override
    protected IWindowManager newIWindowManager() {
        return getContext().iwindowManager;
    }

    @Override
    protected IActivityManager getIActivityManager() {
        return getContext().iactivityManager;
    }

    @Override
    protected LockPatternUtils newLockPatternUtils(Context context) {
        return getContext().lockPatternUtils;
    }

    @Override
    protected Looper getMyLooper() {
        return Looper.getMainLooper();
    }

    @Override
    String getDevicePolicyFilePathForSystemUser() {
        return systemUserDataDir.getAbsolutePath();
    }

    @Override
    long binderClearCallingIdentity() {
        return getContext().binder.clearCallingIdentity();
    }

    @Override
    void binderRestoreCallingIdentity(long token) {
        getContext().binder.restoreCallingIdentity(token);
    }

    @Override
    int binderGetCallingUid() {
        return getContext().binder.getCallingUid();
    }

    @Override
    int binderGetCallingPid() {
        return getContext().binder.getCallingPid();
    }

    @Override
    UserHandle binderGetCallingUserHandle() {
        return getContext().binder.getCallingUserHandle();
    }

    @Override
    boolean binderIsCallingUidMyUid() {
        return getContext().binder.isCallerUidMyUid();
    }

    @Override
    File environmentGetUserSystemDirectory(int userId) {
        return getContext().environment.getUserSystemDirectory(userId);
    }

    @Override
    WakeLock powerManagerNewWakeLock(int levelAndFlags, String tag) {
        return getContext().powerManager.newWakeLock(levelAndFlags, tag);
    }

    @Override
    void powerManagerGoToSleep(long time, int reason, int flags) {
        getContext().powerManager.goToSleep(time, reason, flags);
    }
}
