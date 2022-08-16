/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media;

import android.media.NearbyDevice;

/**
 * A callback used to receive updates about the status of nearby devices that are able to play
 * media.
 *
 * External clients may allow registration of these callbacks and external clients will be
 * responsible for notifying the callbacks appropriately.
 *
 * @hide
 */
oneway interface INearbyMediaDevicesUpdateCallback {
    /**
     * Invoked by external clients when changes in nearby media device(s) are detected.
     *
     * When a callback is newly registered, it should be immediately notified of the current nearby
     * media devices. Afterwards, the list of devices passed to the callback should always contain
     * the full set of nearby media devices any time you get an update. If a device is no longer
     * valid (went offline, e.g.) then it should be omitted from the list in the next update.
     *
     * @param nearbyDevices the list of nearby devices that have changed status due to moving closer
     *                      or further away.
     */
    void onDevicesUpdated(in List<NearbyDevice> nearbyDevices);
}
