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

import java.util.Set;

/** Stub implementation for when the verification agent is unavailable */
public class DomainVerificationProxyUnavailable implements DomainVerificationProxy {

    @Override
    public void sendBroadcastForPackages(@NonNull Set<String> packageNames) {
    }

    @Override
    public boolean runMessage(int messageCode, Object object) {
        return false;
    }

    @Override
    public boolean isCallerVerifier(int callingUid) {
        return false;
    }

    @Nullable
    @Override
    public ComponentName getComponentName() {
        return null;
    }
}
