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

/**
 * A {@code String} provider for the action disabled by admin dialog.
 */
public interface DeviceAdminStringProvider {

    /**
     * Returns the default dialog title for the case when an action is disabled by policy on a
     * managed device.
     */
    String getDefaultDisabledByPolicyTitle();

    /**
     * Returns the dialog title for the case when volume adjusting is disabled.
     */
    String getDisallowAdjustVolumeTitle();

    /**
     * Returns the dialog title for the case when outgoing calls are disabled.
     */
    String getDisallowOutgoingCallsTitle();

    /**
     * Returns the dialog title for the case when sending SMS is disabled.
     */
    String getDisallowSmsTitle();

    /**
     * Returns the dialog title for the case when the camera is disabled.
     */
    String getDisableCameraTitle();

    /**
     * Returns the dialog title for the case when screen capturing is disabled.
     */
    String getDisableScreenCaptureTitle();

    /**
     * Returns the dialog title for the case when suspending apps is disabled.
     */
    String getSuspendPackagesTitle();

    /**
     * Returns the default dialog content for the case when an action is disabled by policy.
     */
    String getDefaultDisabledByPolicyContent();

    /**
     * Returns the URL for the page to be shown when the learn more button is chosen.
     */
    String getLearnMoreHelpPageUrl();

    /**
     * Returns the default dialog title for the case when an action is disabled by policy on
     * a financed device.
     */
    String getDisabledByPolicyTitleForFinancedDevice();
}
