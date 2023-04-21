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
 * limitations under the License
 */

package com.android.server.locksettings;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;

public class MockLockSettingsContext extends ContextWrapper {

    private final Resources mResources;
    private final ContentResolver mContentResolver;
    private final UserManager mUserManager;
    private final NotificationManager mNotificationManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final StorageManager mStorageManager;
    private final TrustManager mTrustManager;
    private final KeyguardManager mKeyguardManager;
    private final FingerprintManager mFingerprintManager;
    private final FaceManager mFaceManager;
    private final PackageManager mPackageManager;

    public MockLockSettingsContext(Context base, Resources resources,
            ContentResolver contentResolver, UserManager userManager,
            NotificationManager notificationManager, DevicePolicyManager devicePolicyManager,
            StorageManager storageManager, TrustManager trustManager,
            KeyguardManager keyguardManager, FingerprintManager fingerprintManager,
            FaceManager faceManager, PackageManager packageManager) {
        super(base);
        mResources = resources;
        mContentResolver = contentResolver;
        mUserManager = userManager;
        mNotificationManager = notificationManager;
        mDevicePolicyManager = devicePolicyManager;
        mStorageManager = storageManager;
        mTrustManager = trustManager;
        mKeyguardManager = keyguardManager;
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
        mPackageManager = packageManager;
    }

    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    @Override
    public Object getSystemService(String name) {
        if (USER_SERVICE.equals(name)) {
            return mUserManager;
        } else if (NOTIFICATION_SERVICE.equals(name)) {
            return mNotificationManager;
        } else if (DEVICE_POLICY_SERVICE.equals(name)) {
            return mDevicePolicyManager;
        } else if (STORAGE_SERVICE.equals(name)) {
            return mStorageManager;
        } else if (TRUST_SERVICE.equals(name)) {
            return mTrustManager;
        } else if (KEYGUARD_SERVICE.equals(name)) {
            return mKeyguardManager;
        } else if (FINGERPRINT_SERVICE.equals(name)) {
            return mFingerprintManager;
        } else if (FACE_SERVICE.equals(name)) {
            return mFaceManager;
        } else {
            throw new RuntimeException("System service not mocked: " + name);
        }
    }

    @Override
    public PackageManager getPackageManager() {
        return mPackageManager;
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        // Skip permission checks for unit tests.
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver,
            UserHandle user, IntentFilter filter, String broadcastPermission,
            Handler scheduler) {
        return null;
    }
}
