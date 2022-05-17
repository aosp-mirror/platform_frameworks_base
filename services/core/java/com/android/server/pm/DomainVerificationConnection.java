/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.pm.PackageManagerService.DOMAIN_VERIFICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.Message;
import android.os.UserHandle;

import com.android.server.DeviceIdleInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV2;

public final class DomainVerificationConnection implements DomainVerificationService.Connection,
        DomainVerificationProxyV1.Connection, DomainVerificationProxyV2.Connection {
    final PackageManagerService mPm;
    final UserManagerInternal mUmInternal;

    DomainVerificationConnection(PackageManagerService pm) {
        mPm = pm;
        mUmInternal = mPm.mInjector.getLocalService(UserManagerInternal.class);
    }

    @Override
    public void scheduleWriteSettings() {
        mPm.scheduleWriteSettings();
    }

    @Override
    public int getCallingUid() {
        return Binder.getCallingUid();
    }

    @UserIdInt
    @Override
    public int getCallingUserId() {
        return UserHandle.getCallingUserId();
    }

    @Override
    public void schedule(int code, @Nullable Object object) {
        Message message = mPm.mHandler.obtainMessage(DOMAIN_VERIFICATION);
        message.arg1 = code;
        message.obj = object;
        mPm.mHandler.sendMessage(message);
    }

    @Override
    public long getPowerSaveTempWhitelistAppDuration() {
        return VerificationUtils.getDefaultVerificationTimeout(mPm.mContext);
    }

    @Override
    public DeviceIdleInternal getDeviceIdleInternal() {
        return mPm.mInjector.getLocalService(DeviceIdleInternal.class);
    }

    @Override
    public boolean isCallerPackage(int callingUid, @NonNull String packageName) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        return callingUid == mPm.snapshotComputer().getPackageUid(packageName, 0, callingUserId);
    }

    @Nullable
    @Override
    public AndroidPackage getPackage(@NonNull String packageName) {
        return mPm.snapshotComputer().getPackage(packageName);
    }

    @Override
    public boolean filterAppAccess(String packageName, int callingUid, int userId) {
        return mPm.snapshotComputer().filterAppAccess(packageName, callingUid, userId);
    }

    @Override
    public int[] getAllUserIds() {
        return mUmInternal.getUserIds();
    }

    @Override
    public boolean doesUserExist(@UserIdInt int userId) {
        return mUmInternal.exists(userId);
    }

    @NonNull
    public Computer snapshot() {
        return mPm.snapshotComputer();
    }
}
