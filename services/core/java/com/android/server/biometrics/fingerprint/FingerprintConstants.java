/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.biometrics.fingerprint;

import android.hardware.fingerprint.FingerprintManager;

import com.android.internal.logging.nano.MetricsProto;
import com.android.server.biometrics.Constants;

public class FingerprintConstants implements Constants {

    @Override
    public String logTag() {
        return FingerprintService.TAG;
    }

    @Override
    public String tagHalDied() {
        return "fingerprintd_died";
    }

    @Override
    public String tagAuthToken() {
        return "fingerprint_token";
    }

    @Override
    public String tagAuthStartError() {
        return "fingerprintd_auth_start_error";
    }

    @Override
    public String tagEnrollStartError() {
        return "fingerprintd_enroll_start_error";
    }

    @Override
    public String tagEnumerateStartError() {
        return "fingerprintd_enum_start_error";
    }

    @Override
    public String tagRemoveStartError() {
        return "fingerprintd_remove_start_error";
    }

    @Override
    public int actionBiometricAuth() {
        return MetricsProto.MetricsEvent.ACTION_FINGERPRINT_AUTH;
    }

    @Override
    public int actionBiometricEnroll() {
        return MetricsProto.MetricsEvent.ACTION_FINGERPRINT_ENROLL;
    }

    @Override
    public int acquireVendorCode() {
        return FingerprintManager.FINGERPRINT_ACQUIRED_VENDOR;
    }
}
