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

import android.media.INearbyMediaDevicesUpdateCallback;
import android.media.NearbyDevice;

/**
 * A binder-compatible version of {@link android.media.NearbyMediaDevicesProvider}. See that class
 * for more information.
 *
 * @hide
 */
interface INearbyMediaDevicesProvider {
  /**
   * Registers a callback that should be notified each time nearby media device(s) change.
   */
  oneway void registerNearbyDevicesCallback(in INearbyMediaDevicesUpdateCallback callback) = 2;

  /**
   * Unregisters a callback. See {@link registerNearbyDevicesCallback}.
   */
  oneway void unregisterNearbyDevicesCallback(in INearbyMediaDevicesUpdateCallback callback) = 3;
}
