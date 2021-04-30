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

package com.android.server.pm.verify.domain.proxy;

import android.annotation.NonNull;
import android.content.ComponentName;

import java.util.Set;

class DomainVerificationProxyCombined implements DomainVerificationProxy {

    @NonNull
    private final DomainVerificationProxy mProxyV1;
    @NonNull
    private final DomainVerificationProxy mProxyV2;

    DomainVerificationProxyCombined(@NonNull DomainVerificationProxy proxyV1,
            @NonNull DomainVerificationProxy proxyV2) {
        mProxyV1 = proxyV1;
        mProxyV2 = proxyV2;
    }

    @Override
    public void sendBroadcastForPackages(@NonNull Set<String> packageNames) {
        mProxyV2.sendBroadcastForPackages(packageNames);
        mProxyV1.sendBroadcastForPackages(packageNames);
    }

    @Override
    public boolean runMessage(int messageCode, Object object) {
        // Both proxies must run, so cannot use a direct ||, which may skip the right hand side
        boolean resultV2 = mProxyV2.runMessage(messageCode, object);
        boolean resultV1 = mProxyV1.runMessage(messageCode, object);
        return resultV2 || resultV1;
    }

    @Override
    public boolean isCallerVerifier(int callingUid) {
        return mProxyV2.isCallerVerifier(callingUid) || mProxyV1.isCallerVerifier(callingUid);
    }

    @NonNull
    @Override
    public ComponentName getComponentName() {
        return mProxyV2.getComponentName();
    }
}
