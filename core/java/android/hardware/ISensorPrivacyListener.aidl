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

/**
 * @hide
 */
oneway interface ISensorPrivacyListener {
    // Since these transactions are also called from native code, these must be kept in sync with
    // the ones in
    //   frameworks/native/libs/sensorprivacy/aidl/android/hardware/ISensorPrivacyListener.aidl
    // =============== Beginning of transactions used on native side as well ======================
    void onSensorPrivacyChanged(int toggleType, int sensor, boolean enabled);
    void onSensorPrivacyStateChanged(int toggleType, int sensor, int state);
    // =============== End of transactions used on native side as well ============================
}
