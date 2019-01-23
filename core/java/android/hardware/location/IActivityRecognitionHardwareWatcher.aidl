/*
 * Copyright (C) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.hardware.location.IActivityRecognitionHardware;

/**
 * Activity Recognition Hardware watcher. This interface can be used to receive interfaces to
 * implementations of {@link IActivityRecognitionHardware}.
 *
 * @deprecated use {@link IActivityRecognitionHardwareClient} instead.

 * @hide
 */
interface IActivityRecognitionHardwareWatcher {
    /**
     * Hardware Activity-Recognition availability event.
     */
    void onInstanceChanged(in IActivityRecognitionHardware instance);
}
