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
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Message;
import android.os.UserHandle;

import com.android.internal.util.FunctionalUtils;
import com.android.server.DeviceIdleInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV2;

import java.util.function.Consumer;
import java.util.function.Function;

public final class DomainVerificationConnection implements DomainVerificationService.Connection,
        DomainVerificationProxyV1.Connection, DomainVerificationProxyV2.Connection {
    final PackageManagerService mPm;
    final PackageManagerInternal mPmInternal;
    final UserManagerInternal mUmInternal;
    final VerificationHelper mVerificationHelper;

    // TODO(b/198166813): remove PMS dependency
    DomainVerificationConnection(PackageManagerService pm) {
        mPm = pm;
        mPmInternal = mPm.mInjector.getLocalService(PackageManagerInternal.class);
        mUmInternal = mPm.mInjector.getLocalService(UserManagerInternal.class);
        mVerificationHelper = new VerificationHelper(mPm.mContext);
    }

    @Override
    public void scheduleWriteSettings() {
        synchronized (mPm.mLock) {
            mPm.scheduleWriteSettingsLocked();
        }
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
        return mVerificationHelper.getVerificationTimeout();
    }

    @Override
    public DeviceIdleInternal getDeviceIdleInternal() {
        return mPm.mInjector.getLocalService(DeviceIdleInternal.class);
    }

    @Override
    public boolean isCallerPackage(int callingUid, @NonNull String packageName) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        return callingUid == mPmInternal.getPackageUid(packageName, 0, callingUserId);
    }

    @Nullable
    @Override
    public AndroidPackage getPackage(@NonNull String packageName) {
        return mPmInternal.getPackage(packageName);
    }

    @Override
    public boolean filterAppAccess(String packageName, int callingUid, int userId) {
        return mPmInternal.filterAppAccess(packageName, callingUid, userId);
    }

    @Override
    public int[] getAllUserIds() {
        return mUmInternal.getUserIds();
    }

    @Override
    public boolean doesUserExist(@UserIdInt int userId) {
        return mUmInternal.exists(userId);
    }

    @Override
    public void withPackageSettingsSnapshot(
            @NonNull Consumer<Function<String, PackageSetting>> block) {
        mPmInternal.withPackageSettingsSnapshot(block);
    }

    @Override
    public <Output> Output withPackageSettingsSnapshotReturning(
            @NonNull FunctionalUtils.ThrowingFunction<Function<String, PackageSetting>, Output>
                    block) {
        return mPmInternal.withPackageSettingsSnapshotReturning(block);
    }

    @Override
    public <ExceptionType extends Exception> void withPackageSettingsSnapshotThrowing(
            @NonNull FunctionalUtils.ThrowingCheckedConsumer<Function<String, PackageSetting>,
                    ExceptionType> block) throws ExceptionType {
        mPmInternal.withPackageSettingsSnapshotThrowing(block);
    }

    @Override
    public <ExceptionOne extends Exception, ExceptionTwo extends Exception> void
            withPackageSettingsSnapshotThrowing2(
                    @NonNull FunctionalUtils.ThrowingChecked2Consumer<
                            Function<String, PackageSetting>, ExceptionOne, ExceptionTwo> block)
            throws ExceptionOne, ExceptionTwo {
        mPmInternal.withPackageSettingsSnapshotThrowing2(block);
    }

    @Override
    public <Output, ExceptionType extends Exception> Output
            withPackageSettingsSnapshotReturningThrowing(
            @NonNull FunctionalUtils.ThrowingCheckedFunction<
                    Function<String, PackageSetting>, Output, ExceptionType> block)
            throws ExceptionType {
        return mPmInternal.withPackageSettingsSnapshotReturningThrowing(block);
    }
}
