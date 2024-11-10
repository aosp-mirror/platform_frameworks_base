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

import android.os.vibrator.IVibrationSession;

/**
 * Callback for vibration session state.
 * {@hide}
 */
oneway interface IVibrationSessionCallback {

    /**
     * A method called by the service after a vibration session has successfully started. After this
     * is called the app has control over the vibrator through this given session.
     */
    void onStarted(in IVibrationSession session);

    /**
     * A method called by the service to indicate the session is ending and should no longer receive
     * vibration requests.
     */
    void onFinishing();

    /**
     * A method called by the service after the session has ended. This might be triggered by the
     * app or the service. The status code indicates the end reason.
     */
    void onFinished(int status);
}
