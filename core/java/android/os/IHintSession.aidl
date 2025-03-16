/*
 *
 * Copyright 2021, The Android Open Source Project
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

import android.hardware.power.WorkDuration;

/** {@hide} */
oneway interface IHintSession {
    void updateTargetWorkDuration(long targetDurationNanos);
    void reportActualWorkDuration(in long[] actualDurationNanos, in long[] timeStampNanos);
    void close();
    void sendHint(int hint);
    void setMode(int mode, boolean enabled);
    void reportActualWorkDuration2(in WorkDuration[] workDurations);

    /**
     * Used by apps to associate a session to a given set of layers
     */
    oneway void associateToLayers(in IBinder[] layerTokens);
}
