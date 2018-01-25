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

import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;

/**
 * API to the broadcast radio service.
 *
 * {@hide}
 */
interface IRadioService {
    List<RadioManager.ModuleProperties> listModules();

    ITuner openTuner(int moduleId, in RadioManager.BandConfig bandConfig, boolean withAudio,
            in ITunerCallback callback);

    ICloseHandle addAnnouncementListener(in int[] enabledTypes,
            in IAnnouncementListener listener);
}
