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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import com.android.server.DeviceIdleInternal;
import com.android.server.pm.verify.domain.DomainVerificationCollector;
import com.android.server.pm.verify.domain.DomainVerificationDebug;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationMessageCodes;

import java.util.Objects;
import java.util.Set;

public interface DomainVerificationProxy {

    String TAG = "DomainVerificationProxy";

    boolean DEBUG_PROXIES = DomainVerificationDebug.DEBUG_PROXIES;

    static <ConnectionType extends DomainVerificationProxyV1.Connection
            & DomainVerificationProxyV2.Connection> DomainVerificationProxy makeProxy(
            @Nullable ComponentName componentV1, @Nullable ComponentName componentV2,
            @NonNull Context context, @NonNull DomainVerificationManagerInternal manager,
            @NonNull DomainVerificationCollector collector, @NonNull ConnectionType connection) {
        if (DEBUG_PROXIES) {
            Slog.d(TAG, "Intent filter verification agent: " + componentV1);
            Slog.d(TAG, "Domain verification agent: " + componentV2);
        }

        if (componentV2 != null && componentV1 != null
                && !Objects.equals(componentV2.getPackageName(), componentV1.getPackageName())) {
            // Only allow a legacy verifier if it's in the same package as the v2 verifier
            componentV1 = null;
        }

        DomainVerificationProxy proxyV1 = null;
        DomainVerificationProxy proxyV2 = null;

        if (componentV1 != null) {
            proxyV1 = new DomainVerificationProxyV1(context, manager, collector, connection,
                    componentV1);
        }

        if (componentV2 != null) {
            proxyV2 = new DomainVerificationProxyV2(context, connection, componentV2);
        }

        if (proxyV1 != null && proxyV2 != null) {
            return new DomainVerificationProxyCombined(proxyV1, proxyV2);
        }

        if (proxyV1 != null) {
            return proxyV1;
        }

        if (proxyV2 != null) {
            return proxyV2;
        }

        return new DomainVerificationProxyUnavailable();
    }

    void sendBroadcastForPackages(@NonNull Set<String> packageNames);

    /**
     * Runs a message on the caller's Handler as a result of {@link BaseConnection#schedule(int,
     * Object)}. Abstracts the actual scheduling/running from the manager class. This is also
     * necessary so that different what codes can be used depending on the verifier proxy on device,
     * to allow backporting v1. The backport proxy may schedule more or less messages than the v2
     * proxy.
     *
     * @param messageCode One of the values in {@link DomainVerificationMessageCodes}.
     * @param object      Arbitrary object that was originally included.
     */
    boolean runMessage(int messageCode, Object object);

    boolean isCallerVerifier(int callingUid);

    @Nullable
    ComponentName getComponentName();

    interface BaseConnection {

        /**
         * Schedule something to be run later. The implementation is left up to the caller.
         *
         * @param code   One of the values in {@link DomainVerificationMessageCodes}.
         * @param object Arbitrary object to include with the message.
         */
        void schedule(int code, @Nullable Object object);

        long getPowerSaveTempWhitelistAppDuration();

        DeviceIdleInternal getDeviceIdleInternal();

        boolean isCallerPackage(int callingUid, @NonNull String packageName);
    }
}
