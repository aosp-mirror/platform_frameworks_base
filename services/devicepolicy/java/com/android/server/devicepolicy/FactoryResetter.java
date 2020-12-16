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

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RecoverySystem;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.Objects;

/**
 * Entry point for "factory reset" requests.
 */
public final class FactoryResetter {

    private static final String TAG = FactoryResetter.class.getSimpleName();

    private final Context mContext;
    private final @Nullable String mReason;
    private final boolean mShutdown;
    private final boolean mForce;
    private final boolean mWipeEuicc;
    private final boolean mWipeAdoptableStorage;
    private final boolean mWipeFactoryResetProtection;


    /**
     * Factory reset the device according to the builder's arguments.
     */
    public void factoryReset() throws IOException {
        Log.i(TAG, String.format("factoryReset(): reason=%s, shutdown=%b, force=%b, wipeEuicc=%b"
                + ", wipeAdoptableStorage=%b, wipeFRP=%b", mReason, mShutdown, mForce, mWipeEuicc,
                mWipeAdoptableStorage, mWipeFactoryResetProtection));

        Preconditions.checkCallAuthorization(mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MASTER_CLEAR) == PackageManager.PERMISSION_GRANTED);

        UserManager um = mContext.getSystemService(UserManager.class);
        if (!mForce && um.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)) {
            throw new SecurityException("Factory reset is not allowed for this user.");
        }

        if (mWipeFactoryResetProtection) {
            PersistentDataBlockManager manager = mContext
                    .getSystemService(PersistentDataBlockManager.class);
            if (manager != null) {
                Log.w(TAG, "Wiping factory reset protection");
                manager.wipe();
            } else {
                Log.w(TAG, "No need to wipe factory reset protection");
            }
        }

        if (mWipeAdoptableStorage) {
            Log.w(TAG, "Wiping adoptable storage");
            StorageManager sm = mContext.getSystemService(StorageManager.class);
            sm.wipeAdoptableDisks();
        }

        RecoverySystem.rebootWipeUserData(mContext, mShutdown, mReason, mForce, mWipeEuicc);
    }

    private FactoryResetter(Builder builder) {
        mContext = builder.mContext;
        mShutdown = builder.mShutdown;
        mReason = builder.mReason;
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
