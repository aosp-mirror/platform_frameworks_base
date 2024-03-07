/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware;

import android.hardware.ISensorPrivacyListener;

/** @hide */
interface ISensorPrivacyManager {
    // Since these transactions are also called from native code, these must be kept in sync with
    // the ones in
    //   frameworks/native/libs/sensorprivacy/aidl/android/hardware/ISensorPrivacyManager.aidl
    // =============== Beginning of transactions used on native side as well ======================
    boolean supportsSensorToggle(int toggleType, int sensor);

    void addSensorPrivacyListener(in ISensorPrivacyListener listener);

    void addToggleSensorPrivacyListener(in ISensorPrivacyListener listener);

    void removeSensorPrivacyListener(in ISensorPrivacyListener listener);

    void removeToggleSensorPrivacyListener(in ISensorPrivacyListener listener);

    boolean isSensorPrivacyEnabled();

    boolean isCombinedToggleSensorPrivacyEnabled(int sensor);

    boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor);

    void setSensorPrivacy(boolean enable);

    void setToggleSensorPrivacy(int userId, int source, int sensor, boolean enable);

    void setToggleSensorPrivacyForProfileGroup(int userId, int source, int sensor, boolean enable);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.OBSERVE_SENSOR_PRIVACY)")
    List<String> getCameraPrivacyAllowlist();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.OBSERVE_SENSOR_PRIVACY)")
    int getToggleSensorPrivacyState(int toggleType, int sensor);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_SENSOR_PRIVACY)")
    void setToggleSensorPrivacyState(int userId, int source, int sensor, int state);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_SENSOR_PRIVACY)")
    void setToggleSensorPrivacyStateForProfileGroup(int userId, int source, int sensor, int  state);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.OBSERVE_SENSOR_PRIVACY)")
    boolean isCameraPrivacyEnabled(String packageName);

    /** @hide */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_SENSOR_PRIVACY)")
    void setCameraPrivacyAllowlist(in List<String> allowlist);

    // =============== End of transactions used on native side as well ============================

    void suppressToggleSensorPrivacyReminders(int userId, int sensor, IBinder token,
            boolean suppress);

    boolean requiresAuthentication();

    void showSensorUseDialog(int sensor);
}
