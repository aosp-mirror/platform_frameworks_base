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

package com.android.server.pm.intent.verify.legacy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.server.DeviceIdleInternal;
import com.android.server.pm.PackageSetting;
import com.android.server.utils.WatchedSparseIntArray;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;

public class IntentVerifierProxy {

    private final Context mContext;
    private final PackageManagerServiceConnection mConnection;

    private final ArrayList<Integer> mCurrentIntentFilterVerifications = new ArrayList<>();

    @Nullable
    private ComponentName mIntentFilterVerifierComponent;

    public IntentVerifierProxy(Context context, PackageManagerServiceConnection connection) {
        mConnection = connection;
        mContext = context;
    }

    private String getDefaultScheme() {
        return IntentFilter.SCHEME_HTTPS;
    }

    public void setComponent(@Nullable ComponentName componentName) {
        this.mIntentFilterVerifierComponent = componentName;
    }

    @Nullable
    public ComponentName getComponent() {
        return mIntentFilterVerifierComponent;
    }

    public void startVerifications(int userId, SparseArray<IntentFilterVerificationState> states) {
        if (mIntentFilterVerifierComponent == null) {
            return;
        }

        // Launch verifications requests
        int count = mCurrentIntentFilterVerifications.size();
        for (int n = 0; n < count; n++) {
            int verificationId = mCurrentIntentFilterVerifications.get(n);
            final IntentFilterVerificationState ivs = states.get(verificationId);

            String packageName = ivs.getPackageName();

            ArrayList<ParsedIntentInfo> filters = ivs.getFilters();
            final int filterCount = filters.size();
            ArraySet<String> domainsSet = new ArraySet<>();
            for (int m = 0; m < filterCount; m++) {
                ParsedIntentInfo filter = filters.get(m);
                domainsSet.addAll(filter.getHostsList());
            }
            mConnection.writeSettings(packageName, domainsSet);
            sendVerificationRequest(verificationId, ivs);
        }
        mCurrentIntentFilterVerifications.clear();
    }

    private void sendVerificationRequest(int verificationId, IntentFilterVerificationState ivs) {
        Intent verificationIntent = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);
        verificationIntent.putExtra(
                PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID,
                verificationId);
        verificationIntent.putExtra(
                PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME,
                getDefaultScheme());
        verificationIntent.putExtra(
                PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS,
                ivs.getHostsString());
        verificationIntent.putExtra(
                PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME,
                ivs.getPackageName());
        verificationIntent.setComponent(mIntentFilterVerifierComponent);
        verificationIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        final long allowListTimeout = mConnection.getVerificationTimeout();
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppWhitelistDuration(allowListTimeout);

        mConnection.getDeviceIdleInternal().addPowerSaveTempWhitelistApp(Process.myUid(),
                mIntentFilterVerifierComponent.getPackageName(), allowListTimeout,
                UserHandle.USER_SYSTEM, true, "intent filter verifier");

        mContext.sendBroadcastAsUser(verificationIntent, UserHandle.SYSTEM,
                null, options.toBundle());
        mConnection.debugLog("Sending IntentFilter verification broadcast");
    }

    public boolean addOneIntentFilterVerification(int verifierUid, int userId, int verificationId,
            ParsedIntentInfo filter, String packageName,
            SparseArray<IntentFilterVerificationState> states) {
        if (!IntentVerifyUtils.hasValidDomains(filter)) {
            return false;
        }
        IntentFilterVerificationState ivs = states.get(verificationId);
        if (ivs == null) {
            ivs = createDomainVerificationState(verifierUid, userId, verificationId,
                    packageName, states);
        }
        mConnection.debugLog("Adding verification filter for " + packageName + ": " + filter);
        ivs.addFilter(filter);
        return true;
    }

    private IntentFilterVerificationState createDomainVerificationState(int verifierUid,
            int userId, int verificationId, String packageName,
            SparseArray<IntentFilterVerificationState> states) {
        IntentFilterVerificationState
                ivs = new IntentFilterVerificationState(
                verifierUid, userId, packageName);
        ivs.setPendingState();
        mConnection.lock(() -> {
            states.append(verificationId, ivs);
            mCurrentIntentFilterVerifications.add(verificationId);
        });
        return ivs;
    }

    public interface PackageManagerServiceConnection {
        void lock(Runnable block);

        <T> T lockReturn(Supplier<T> block);

        void debugLog(String message);

        void verboseLog(String message);

        void warnLog(String message);

        void infoLog(String message);

        void writeSettings(String packageName, ArraySet<String> domainsSet);

        // Seems this is used when an IFVI object is mutated, and it's assumed that the same object
        // ends up written to disk.
        void scheduleWriteSettingsLocked();

        long getVerificationTimeout();

        void scheduleWritePackageRestrictionsLocked(@UserIdInt int userId);

        String getInstantAppPackageName(int callingUid);

        @Nullable
        PackageSetting getPackageSettingLPr(@NonNull String packageName);

        @NonNull
        Map<String, PackageSetting> getPackageSettingsLPr();

        boolean shouldFilterApplicationLocked(PackageSetting ps, int callingUid,
                @UserIdInt int userId);

        int getPackageUid(String packageName, int flags, @UserIdInt int userId);

        @NonNull
        WatchedSparseIntArray getNextAppLinkGeneration();

        /**
         * DeviceIdleInternal has a dependency on PackageManager, so it can't be passed in at
         * initialization. It has to be accessed at use time.
         */
        @NonNull
        DeviceIdleInternal getDeviceIdleInternal();
    }
}
