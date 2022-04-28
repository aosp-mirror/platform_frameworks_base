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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.StatusBarManager;

import java.util.List;
import java.util.function.Consumer;

/**
 * An interface that provides information about nearby devices that are able to play media.
 * <p>
 * External clients can implement this interface and pass it to the system via
 * {@link StatusBarManager#registerNearbyMediaDevicesProvider} to inform the system of nearby media
 * devices.
 * <p>
 * @hide
 */
@SystemApi
public interface NearbyMediaDevicesProvider {
    /**
     * Registers a callback that should be notified each time nearby media device(s) change.
     * <p>
     * When a callback is newly registered, it should be immediately notified of the current nearby
     * media devices. Afterwards, the list of devices passed to the callback should always contain
     * the full set of nearby media devices any time you get an update. If a device is no longer
     * valid (went offline, e.g.) then it should be omitted from the list in the next update.
     * <p>
     * @param callback the callback that will consume updates to the nearby media devices.
     */
    void registerNearbyDevicesCallback(@NonNull Consumer<List<NearbyDevice>> callback);

    /**
     * Unregisters a callback. @see #registerNearbyDevicesCallback.
     * <p>
     * @param callback the callback to unregister.
     */
    void unregisterNearbyDevicesCallback(@NonNull Consumer<List<NearbyDevice>> callback);
}
