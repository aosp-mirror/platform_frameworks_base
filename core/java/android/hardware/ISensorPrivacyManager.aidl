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
    boolean supportsSensorToggle(int sensor);

    void addSensorPrivacyListener(in ISensorPrivacyListener listener);

    void addIndividualSensorPrivacyListener(int userId, int sensor,
            in ISensorPrivacyListener listener);

    void removeSensorPrivacyListener(in ISensorPrivacyListener listener);

    void removeIndividualSensorPrivacyListener(int sensor, in ISensorPrivacyListener listener);

    boolean isSensorPrivacyEnabled();

    boolean isIndividualSensorPrivacyEnabled(int userId, int sensor);

    void setSensorPrivacy(boolean enable);

    void setIndividualSensorPrivacy(int userId, int source, int sensor, boolean enable);

    void setIndividualSensorPrivacyForProfileGroup(int userId, int source, int sensor, boolean enable);
    // =============== End of transactions used on native side as well ============================

    void suppressIndividualSensorPrivacyReminders(int userId, int sensor, IBinder token,
            boolean suppress);

    void addUserGlobalIndividualSensorPrivacyListener(int sensor, in ISensorPrivacyListener listener);

    void removeUserGlobalIndividualSensorPrivacyListener(int sensor, in ISensorPrivacyListener listener);

    void showSensorUseDialog(int sensor);
}