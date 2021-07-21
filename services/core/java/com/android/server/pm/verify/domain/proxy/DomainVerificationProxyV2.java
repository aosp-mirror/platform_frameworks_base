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

package com.android.server.pm.verify.domain.proxy;

import static android.os.PowerWhitelistManager.REASON_DOMAIN_VERIFICATION_V2;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationRequest;
import android.os.Process;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.verify.domain.DomainVerificationDebug;
import com.android.server.pm.verify.domain.DomainVerificationMessageCodes;

import java.util.Set;

public class DomainVerificationProxyV2 implements DomainVerificationProxy {

    private static final String TAG = "DomainVerificationProxyV2";

    private static final boolean DEBUG_BROADCASTS = DomainVerificationDebug.DEBUG_BROADCASTS;

    @NonNull
    private final Context mContext;

    @NonNull
    private final Connection mConnection;

    @NonNull
    private final ComponentName mVerifierComponent;

    public DomainVerificationProxyV2(@NonNull Context context, @NonNull Connection connection,
            @NonNull ComponentName verifierComponent) {
        mContext = context;
        mConnection = connection;
        mVerifierComponent = verifierComponent;
    }

    @Override
    public void sendBroadcastForPackages(@NonNull Set<String> packageNames) {
        mConnection.schedule(com.android.server.pm.verify.domain.DomainVerificationMessageCodes.SEND_REQUEST, packageNames);
    }

    @Override
    public boolean runMessage(int messageCode, Object object) {
        switch (messageCode) {
            case DomainVerificationMessageCodes.SEND_REQUEST:
                @SuppressWarnings("unchecked") Set<String> packageNames = (Set<String>) object;
                DomainVerificationRequest request = new DomainVerificationRequest(packageNames);

                final long allowListTimeout = mConnection.getPowerSaveTempWhitelistAppDuration();
                final BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setTemporaryAppAllowlist(allowListTimeout,
                        TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                        REASON_DOMAIN_VERIFICATION_V2, "");

                mConnection.getDeviceIdleInternal().addPowerSaveTempWhitelistApp(Process.myUid(),
                        mVerifierComponent.getPackageName(), allowListTimeout,
                        UserHandle.USER_SYSTEM, true, REASON_DOMAIN_VERIFICATION_V2,
                        "domain verification agent");

                Intent intent = new Intent(Intent.ACTION_DOMAINS_NEED_VERIFICATION)
                        .setComponent(mVerifierComponent)
                        .putExtra(DomainVerificationManager.EXTRA_VERIFICATION_REQUEST, request)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

                if (DEBUG_BROADCASTS) {
                    Slog.d(TAG, "Requesting domain verification for " + packageNames);
                }

                mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM, null, options.toBundle());
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isCallerVerifier(int callingUid) {
        return mConnection.isCallerPackage(callingUid, mVerifierComponent.getPackageName());
    }

    @Nullable
    @Override
    public ComponentName getComponentName() {
        return mVerifierComponent;
    }

    public interface Connection extends BaseConnection {
    }
}
