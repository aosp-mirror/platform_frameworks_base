/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.devicepolicy.flags;

import static com.android.server.devicepolicy.flags.Flags.devicePolicySizeTrackingEnabled;
import static com.android.server.devicepolicy.flags.Flags.policyEngineMigrationV2Enabled;

import android.os.Binder;

public final class FlagUtils {
    private FlagUtils(){}

    public static boolean isPolicyEngineMigrationV2Enabled() {
        return Binder.withCleanCallingIdentity(() -> {
            return policyEngineMigrationV2Enabled();
        });
    }

    public static boolean isDevicePolicySizeTrackingEnabled() {
        return Binder.withCleanCallingIdentity(() -> {
            return devicePolicySizeTrackingEnabled();
        });
    }
}
