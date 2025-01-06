/*
 *
 * Copyright 2024, The Android Open Source Project
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

package android.os;

import android.hardware.power.SessionTag;
import android.hardware.power.SessionMode;

/** {@hide} */
parcelable SessionCreationConfig {
    /**
     * List of tids to be included in the hint session.
     */
    int[] tids;

    /**
     * The initial target work duration of this hint session in nanoseconds.
     */
    long targetWorkDurationNanos;

    /**
     * List of the modes to be enabled upon session creation.
     */
    SessionMode[] modesToEnable;

    /**
     * List of layers to attach this session to.
     *
     * Note: DO NOT STORE THESE IN HintSessionManager, as
     * it will break the layer lifecycle.
     */
    IBinder[] layerTokens;
}
