/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.FactoryResetter.setFactoryResetting;

import android.annotation.Nullable;
import android.app.admin.DevicePolicySafetyChecker;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RecoverySystem;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.persistentdata.PersistentDataBlockManager;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;

import java.io.IOException;
import java.util.Objects;

/**
 * Entry point for "factory reset" requests.
 *
 * @deprecated TODO(b/225012970): should be moved to {@code com.android.server.FactoryResetter}
 */
@Deprecated
public final class FactoryResetter {

    private static final String TAG = FactoryResetter.class.getSimpleName();

    private final Context mContext;
    private final @Nullable DevicePolicySafetyChecker mSafetyChecker;
    private final @Nullable String mReason;
    private final boolean mShutdown;
    private final boolean mForce;
    private final boolean mWipeEuicc;
    private final boolean mWipeAdoptableStorage;
    private final boolean mWipeFactoryResetProtection;

    /**
     * Factory reset the device according to the builder's arguments.
     *
     * @return {@code true} if device was factory reset, or {@code false} if it was delayed by the
     * {@link DevicePolicySafetyChecker}.
     */
    public boolean factoryReset() throws IOException {
        Preconditions.checkCallAuthorization(mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MASTER_CLEAR) == PackageManager.PERMISSION_GRANTED);

        setFactoryResetting(mContext);

        if (mSafetyChecker == null) {
            factoryResetInternalUnchecked();
            return true;
        }

        IResultReceiver receiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                Slogf.i(TAG, "Factory reset confirmed by %s, proceeding", mSafetyChecker);
                try {
                    factoryResetInternalUnchecked();
                } catch (IOException e) {
                    // Shouldn't happen
                    Slogf.wtf(TAG, e, "IOException calling underlying systems");
                }
            }
        };
        Slogf.i(TAG, "Delaying factory reset until %s confirms", mSafetyChecker);
        mSafetyChecker.onFactoryReset(receiver);
        return false;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("FactoryResetter[");
        if (mReason == null) {
            builder.append("no_reason");
        } else {
            builder.append("reason='").append(mReason).append("'");
        }
        if (mSafetyChecker != null) {
            builder.append(",hasSafetyChecker");
        }
        if (mShutdown) {
            builder.append(",shutdown");
        }
        if (mForce) {
            builder.append(",force");
        }
        if (mWipeEuicc) {
            builder.append(",wipeEuicc");
        }
        if (mWipeAdoptableStorage) {
            builder.append(",wipeAdoptableStorage");
        }
        if (mWipeFactoryResetProtection) {
            builder.append(",ipeFactoryResetProtection");
        }
        return builder.append(']').toString();
    }

    private void factoryResetInternalUnchecked() throws IOException {
        Slogf.i(TAG, "factoryReset(): reason=%s, shutdown=%b, force=%b, wipeEuicc=%b, "
                + "wipeAdoptableStorage=%b, wipeFRP=%b", mReason, mShutdown, mForce, mWipeEuicc,
                mWipeAdoptableStorage, mWipeFactoryResetProtection);

        UserManager um = mContext.getSystemService(UserManager.class);
        if (!mForce && um.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)) {
            throw new SecurityException("Factory reset is not allowed for this user.");
        }

        if (mWipeFactoryResetProtection) {
            PersistentDataBlockManager manager = mContext
                    .getSystemService(PersistentDataBlockManager.class);
            if (manager != null) {
                Slogf.w(TAG, "Wiping factory reset protection");
                manager.wipe();
            } else {
                Slogf.w(TAG, "No need to wipe factory reset protection");
            }
        }

        if (mWipeAdoptableStorage) {
            Slogf.w(TAG, "Wiping adoptable storage");
            StorageManager sm = mContext.getSystemService(StorageManager.class);
            sm.wipeAdoptableDisks();
        }

        RecoverySystem.rebootWipeUserData(mContext, mShutdown, mReason, mForce, mWipeEuicc);
    }

    private FactoryResetter(Builder builder) {
        mContext = builder.mContext;
        mSafetyChecker = builder.mSafetyChecker;
        mReason = builder.mReason;
        mShutdown = builder.mShutdown;
        mForce = builder.mForce;
        mWipeEuicc = builder.mWipeEuicc;
        mWipeAdoptableStorage = builder.mWipeAdoptableStorage;
        mWipeFactoryResetProtection = builder.mWipeFactoryResetProtection;
    }

    /**
     * Creates a new builder.
     */
    public static Builder newBuilder(Context context) {
        return new Builder(context);
    }

    /**
     * Builder for {@link FactoryResetter} instances.
     */
    public static final class Builder {

        private final Context mContext;
        private @Nullable DevicePolicySafetyChecker mSafetyChecker;
        private @Nullable String mReason;
        private boolean mShutdown;
        private boolean mForce;
        private boolean mWipeEuicc;
        private boolean mWipeAdoptableStorage;
        private boolean mWipeFactoryResetProtection;

        private Builder(Context context) {
            mContext = Objects.requireNonNull(context);
        }

        /**
         * Sets a {@link DevicePolicySafetyChecker} object that will be used to delay the
         * factory reset when it's not safe to do so.
         */
        public Builder setSafetyChecker(@Nullable DevicePolicySafetyChecker safetyChecker) {
            mSafetyChecker = safetyChecker;
            return this;
        }

        /**
         * Sets the (non-null) reason for the factory reset that is visible in the logs
         */
        public Builder setReason(String reason) {
            mReason = Objects.requireNonNull(reason);
            return this;
        }

        /**
         * Sets whether the device will be powered down after the wipe completes, rather than being
         * rebooted back to the regular system.
         */
        public Builder setShutdown(boolean value) {
            mShutdown = value;
            return this;
        }

        /**
         * Sets whether the {@link UserManager.DISALLOW_FACTORY_RESET} user restriction should be
         * ignored.
         */
        public Builder setForce(boolean value) {
            mForce = value;
            return this;
        }

        /**
         * Sets whether to wipe the {@code euicc} data.
         */
        public Builder setWipeEuicc(boolean value) {
            mWipeEuicc = value;
            return this;
        }

        /**
         * Sets whether to wipe the adoptable external storage (if any).
         */
        public Builder setWipeAdoptableStorage(boolean value) {
            mWipeAdoptableStorage = value;
            return this;
        }

        /**
         * Sets whether to reset the factory reset protection.
         */
        public Builder setWipeFactoryResetProtection(boolean value) {
            mWipeFactoryResetProtection = value;
            return this;
        }

        /**
         * Builds the {@link FactoryResetter} instance.
         */
        public FactoryResetter build() {
            return new FactoryResetter(this);
        }
    }
}
