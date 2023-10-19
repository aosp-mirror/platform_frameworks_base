/*
 * Copyright (C) 2023 The Android Open Source Project
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

 package android.flags;

import android.flags.IFeatureFlagsCallback;
import android.flags.SyncableFlag;

/**
 * Binder interface for communicating with {@link com.android.server.flags.FeatureFlagsService}.
 *
 * This interface is used by {@link android.flags.FeatureFlags} and developers should use that to
 * interface with the service. FeatureFlags is the "client" in this documentation.
 *
 * The methods allow client apps to communicate what flags they care about, and receive back
 * current values for those flags. For stable flags, this is the finalized value until the device
 * restarts. For {@link DynamicFlag}s, this is the last known value, though it may change in the
 * future. Clients can listen for changes to flag values so that it can react accordingly.
 * @hide
 */
interface IFeatureFlags {
    /**
     * Synchronize with the {@link com.android.server.flags.FeatureFlagsService} about flags of
     * interest.
     *
     * The client should pass in a list of flags that it is using as {@link SyncableFlag}s, which
     * includes what it thinks the default values of the flags are.
     *
     * The response will contain a list of matching SyncableFlags, whose values are set to what the
     * value of the flags actually are. The client should update its internal state flag data to
     * match.
     *
     * Generally speaking, if a flag that is passed in is new to the FeatureFlagsService, the
     * service will cache the passed-in value, and return it back out. If, however, a different
     * client has synced that flag with the service previously, FeatureFlagsService will return the
     * existing cached value, which may or may not be what the current client passed in. This allows
     * FeatureFlagsService to keep clients in agreement with one another.
     */
    List<SyncableFlag> syncFlags(in List<SyncableFlag> flagList);

    /**
     * Pass in an {@link IFeatureFlagsCallback} that will be called whenever a {@link DymamicFlag}
     * changes.
     */
    void registerCallback(IFeatureFlagsCallback callback);

    /**
     * Remove a {@link IFeatureFlagsCallback} that was previously registered with
     * {@link #registerCallback}.
     */
    void unregisterCallback(IFeatureFlagsCallback callback);

    /**
     * Query the {@link com.android.server.flags.FeatureFlagsService} for flags, but don't
     * cache them. See {@link #syncFlags}.
     *
     * You almost certainly don't want this method. This is intended for the Flag Flipper
     * application that needs to query the state of system but doesn't want to affect it by
     * doing so. All other clients should use {@link syncFlags}.
     */
    List<SyncableFlag> queryFlags(in List<SyncableFlag> flagList);

    /**
     * Change a flags value in the system.
     *
     * This is intended for use by the Flag Flipper application.
     */
    void overrideFlag(in SyncableFlag flag);

    /**
     * Restore a flag to its default value.
     *
     * This is intended for use by the Flag Flipper application.
     */
    void resetFlag(in SyncableFlag flag);
}