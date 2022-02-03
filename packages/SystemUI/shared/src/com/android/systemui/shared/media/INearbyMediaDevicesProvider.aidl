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

import com.android.systemui.shared.media.INearbyMediaDevicesUpdateCallback;
import com.android.systemui.shared.media.NearbyDevice;

/**
 * An interface that provides information about nearby devices that are able to play media.
 *
 * External clients will implement this interface and System UI will invoke it if it's passed to
 * SystemUI via {@link INearbyMediaDevicesService.registerProvider}.
 */
interface INearbyMediaDevicesProvider {
  /**
   * Returns a list of nearby devices that are able to play media.
   */
  List<NearbyDevice> getCurrentNearbyDevices() = 1;

  /**
   * Registers a callback that will be notified each time the status of a nearby device changes.
   */
  oneway void registerNearbyDevicesCallback(in INearbyMediaDevicesUpdateCallback callback) = 2;

  /**
   * Unregisters a callback. See {@link registerNearbyDevicesCallback}.
   */
  oneway void unregisterNearbyDevicesCallback(in INearbyMediaDevicesUpdateCallback callback) = 3;
}
