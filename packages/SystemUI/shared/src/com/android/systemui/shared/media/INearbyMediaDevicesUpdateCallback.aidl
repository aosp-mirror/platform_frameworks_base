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

package com.android.systemui.shared.media;

/**
 * A callback used to notify implementors of changes in the status of nearby devices that are able
 * to play media.
 *
 * External clients may allow registration of these callbacks and external clients will be
 * responsible for notifying the callbacks appropriately. System UI is only a mediator between the
 * external client and these callbacks.
 */
interface INearbyMediaDevicesUpdateCallback {
    /** Unknown distance range. */
    const int RANGE_UNKNOWN = 0;
    /** Distance is very far away from the peer device. */
    const int RANGE_FAR = 1;
    /** Distance is relatively long from the peer device, typically a few meters. */
    const int RANGE_LONG = 2;
    /** Distance is close to the peer device, typically with one or two meter. */
    const int RANGE_CLOSE = 3;
    /** Distance is very close to the peer device, typically within one meter or less. */
    const int RANGE_WITHIN_REACH = 4;

    /** Invoked by external clients when media device changes are detected. */
    oneway void nearbyDeviceUpdate(in String routeId, in int rangeZone) = 1;
}
