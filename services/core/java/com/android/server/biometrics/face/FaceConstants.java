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

package com.android.server.biometrics.face;

import android.hardware.face.FaceManager;

import com.android.internal.logging.nano.MetricsProto;
import com.android.server.biometrics.Constants;

public class FaceConstants implements Constants {
    @Override
    public String logTag() {
        return FaceService.TAG;
    }

    @Override
    public String tagHalDied() {
        return "faced_died";
    }

    @Override
    public String tagAuthToken() {
        return "face_token";
    }

    @Override
    public String tagAuthStartError() {
        return "faced_auth_start_error";
    }

    @Override
    public String tagEnrollStartError() {
        return "faced_enroll_start_error";
    }

    @Override
    public String tagEnumerateStartError() {
        return "faced_enum_start_error";
    }

    @Override
    public String tagRemoveStartError() {
        return "faced_remove_start_error";
    }

    @Override
    public int actionBiometricAuth() {
        return MetricsProto.MetricsEvent.ACTION_FACE_AUTH;
    }

    @Override
    public int actionBiometricEnroll() {
        return MetricsProto.MetricsEvent.ACTION_FACE_ENROLL;
    }

    @Override
    public int acquireVendorCode() {
        return FaceManager.FACE_ACQUIRED_VENDOR;
    }
}
