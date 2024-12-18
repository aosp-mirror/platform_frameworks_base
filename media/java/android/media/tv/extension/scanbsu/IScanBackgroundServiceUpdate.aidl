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

package android.media.tv.extension.scanbsu;

import android.media.tv.extension.scanbsu.IScanBackgroundServiceUpdateListener;

/**
 * @hide
 */
interface IScanBackgroundServiceUpdate {
    // Set the listener for background service update
    // receives notifications for svl/tsl/nwl update during background service update.
    void addBackgroundServiceUpdateListener(String clientToken,
        in IScanBackgroundServiceUpdateListener listener);
    // Remove the listener for background service update to stop receiving notifications
    // for svl/tsl/nwl update during background service update.
    void removeBackgroundServiceUpdateListener(in IScanBackgroundServiceUpdateListener listener);
}
