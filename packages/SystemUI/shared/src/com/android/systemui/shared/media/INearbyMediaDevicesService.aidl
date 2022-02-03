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

import com.android.systemui.shared.media.INearbyMediaDevicesProvider;

/**
 * An interface that can be invoked to notify System UI of nearby media devices.
 *
 * External clients wanting to notify System UI about the status of nearby media devices should
 * implement {@link INearbyMediaDevicesProvider} and then register it with system UI using this
 * service.
 *
 * System UI will implement this interface and external clients will invoke it.
 */
interface INearbyMediaDevicesService {
  /** Registers a new provider. */
  oneway void registerProvider(INearbyMediaDevicesProvider provider) = 1;
}
