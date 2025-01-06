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

package android.media.tv.extension.tune;

import android.os.Bundle;

/**
* @hide
*/
interface IMuxTuneSession {
    // Start mux tune with tune params.
    void start(int broadcastType, int frequency, int brandwith, in Bundle muxTuneParams);
    // Stop mux tune.
    void stop();
    // Release muxtune resources.
    void release();
    // Get the session token created by TIS to identify different sessions.
    String getSessionToken();
}
