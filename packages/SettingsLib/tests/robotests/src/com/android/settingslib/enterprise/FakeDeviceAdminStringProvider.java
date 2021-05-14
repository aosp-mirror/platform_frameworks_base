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

package com.android.settingslib.enterprise;

import android.annotation.Nullable;

class FakeDeviceAdminStringProvider implements DeviceAdminStringProvider {

    static final String DEFAULT_DISABLED_BY_POLICY_TITLE = "default_disabled_by_policy_title";
    static final String DISALLOW_ADJUST_VOLUME_TITLE = "disallow_adjust_volume_title";
    static final String DISALLOW_OUTGOING_CALLS_TITLE = "disallow_outgoing_calls_title";
    static final String DISALLOW_SMS_TITLE = "disallow_sms_title";
    static final String DISABLE_CAMERA_TITLE = "disable_camera_title";
    static final String DISABLE_SCREEN_CAPTURE_TITLE = "disable_screen_capture_title";
    static final String SUSPENDED_PACKAGES_TITLE = "suspended_packages_title";
    static final String DEFAULT_DISABLED_BY_POLICY_CONTENT = "default_disabled_by_policy_content";
    static final String DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE =
            "default_disabled_by_policy_title_financed_device";

    private final String mUrl;

    FakeDeviceAdminStringProvider(@Nullable String url) {
        mUrl = url;
    }

    @Override
    public String getDefaultDisabledByPolicyTitle() {
        return DEFAULT_DISABLED_BY_POLICY_TITLE;
    }

    @Override
    public String getDisallowAdjustVolumeTitle() {
        return DISALLOW_ADJUST_VOLUME_TITLE;
    }

    @Override
    public String getDisallowOutgoingCallsTitle() {
        return DISALLOW_OUTGOING_CALLS_TITLE;
    }

    @Override
    public String getDisallowSmsTitle() {
        return DISALLOW_SMS_TITLE;
    }

    @Override
    public String getDisableCameraTitle() {
        return DISABLE_CAMERA_TITLE;
    }

    @Override
    public String getDisableScreenCaptureTitle() {
        return DISABLE_SCREEN_CAPTURE_TITLE;
    }

    @Override
    public String getSuspendPackagesTitle() {
        return SUSPENDED_PACKAGES_TITLE;
    }

    @Override
    public String getDefaultDisabledByPolicyContent() {
        return DEFAULT_DISABLED_BY_POLICY_CONTENT;
    }

    @Override
    public String getLearnMoreHelpPageUrl() {
        return mUrl;
    }

    @Override
    public String getDisabledByPolicyTitleForFinancedDevice() {
        return DEFAULT_DISABLED_BY_POLICY_TITLE_FINANCED_DEVICE;
    }
}
