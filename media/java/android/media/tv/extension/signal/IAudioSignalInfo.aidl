/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.signal;

import android.media.tv.extension.signal.IAudioSignalInfoListener;
import android.os.Bundle;

/**
 * @hide
 */
interface IAudioSignalInfo {
    // Get audio signal information.
    Bundle getAudioSignalInfo(String sessionToken);
    // Notify TIS whether user selects audio track via mts button on the remote control.
    void notifyMtsSelectTrackFlag(boolean mtsFlag);
    // Get the audio track id selected via mts.
    String getMtsSelectedTrackId();
    // Register a listener to receive the updated audio signal information.
    void addAudioSignalInfoListener(String clientToken, in IAudioSignalInfoListener listener);
    // Remove a listener for audio signal information update notifications.
    void removeAudioSignalInfoListener(in IAudioSignalInfoListener listener);
}
