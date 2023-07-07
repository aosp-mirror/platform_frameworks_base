/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.radio;

import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;

/** {@hide} */
oneway interface ITunerCallback {
    void onError(int status);

    /**
     * Callback called when tuning operations, such as tune, step, seek, failed.
     *
     * @param result Tuning result of {@link RadioTuner#TunerResultType} type.
     * @param selector Program selector used for the tuning operation.
     */
    void onTuneFailed(int result, in ProgramSelector selector);
    void onConfigurationChanged(in RadioManager.BandConfig config);
    void onCurrentProgramInfoChanged(in RadioManager.ProgramInfo info);
    void onTrafficAnnouncement(boolean active);
    void onEmergencyAnnouncement(boolean active);
    void onAntennaState(boolean connected);
    void onBackgroundScanAvailabilityChange(boolean isAvailable);
    void onBackgroundScanComplete();
    void onProgramListChanged();
    void onProgramListUpdated(in ProgramList.Chunk chunk);

    /**
     * Callback for passing updates to config flags from {@link IRadioService} to
     * {@link RadioTuner}.
     *
     * @param flag Config flag (defined in {@link RadioManager.ConfigFlag}) updated
     * @param value Updated value for the config flag
     */
    void onConfigFlagUpdated(int flag, boolean value);

    /**
     * Callback for passing updates to vendor-specific parameter values from
     * {@link IRadioService} to {@link RadioTuner}.
     *
     * @param parameters Vendor-specific key-value pairs
     */
    void onParametersUpdated(in Map<String, String> parameters);
}
