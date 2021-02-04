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

package com.android.server.pm.domain.verify.proxy;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.server.DeviceIdleInternal;
import com.android.server.pm.domain.verify.DomainVerificationMessageCodes;

import java.util.Set;

public interface DomainVerificationProxy {

    default void sendBroadcastForPackages(@NonNull Set<String> packageNames) {
    }

    /**
     * Runs a message on the caller's Handler as a result of {@link Connection#schedule(int,
     * Object)}. Abstracts the actual scheduling/running from the manager class. This is also
     * necessary so that different what codes can be used depending on the verifier proxy on device,
     * to allow backporting v1. The backport proxy may schedule more or less messages than the v2
     * proxy.
     *
     * @param messageCode One of the values in {@link DomainVerificationMessageCodes}.
     * @param object      Arbitrary object that was originally included.
     */
    default boolean runMessage(int messageCode, Object object) {
        return false;
    }

    default boolean isCallerVerifier(int callingUid) {
        return false;
    }

    interface Connection {

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
