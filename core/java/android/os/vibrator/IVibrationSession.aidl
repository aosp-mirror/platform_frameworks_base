/**
 * Copyright (c) 2024, The Android Open Source Project
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
package android.os.vibrator;

import android.os.CombinedVibration;

/**
 * The communication channel by which an app control the system vibrators.
 *
 * In order to synchronize the places where vibrations might be controlled we provide this interface
 * so the vibrator subsystem has a chance to:
 *
 * 1) Decide whether the current session should have the vibrator control.
 * 2) Stop any on-going session for a new session/vibration, based on current system policy.
 * {@hide}
 */
interface IVibrationSession {
    const int STATUS_UNKNOWN = 0;
    const int STATUS_SUCCESS = 1;
    const int STATUS_IGNORED = 2;
    const int STATUS_UNSUPPORTED = 3;
    const int STATUS_CANCELED = 4;
    const int STATUS_UNKNOWN_ERROR = 5;

    /**
     * A method called to start a vibration within this session. This will fail if the session
     * is finishing or was canceled.
     */
    void vibrate(in CombinedVibration vibration, String reason);

    /**
     * A method called by the app to stop this session gracefully. The vibrator will complete any
     * ongoing vibration before the session is ended.
     */
    void finishSession();

    /**
     * A method called by the app to stop this session immediatelly by interrupting any ongoing
     * vibration.
     */
    void cancelSession();
}
