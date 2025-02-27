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

package android.app.wearable;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A connection to a remote wearable device.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_CONCURRENT_WEARABLE_CONNECTIONS)
public interface WearableConnection {

    /** Returns the connection to provide. */
    @NonNull
    ParcelFileDescriptor getConnection();

    /** Returns the metadata related to this connection. */
    @NonNull
    PersistableBundle getMetadata();

    /**
     * Callback method called when the connection is accepted by the WearableSensingService.
     *
     * <p>See {@link WearableSensingManager#provideConnection(ParcelFileDescriptor, Executor,
     * Consumer)} for details about the relationship between the connection provided via {@link
     * #getConnection()} and the connection accepted by the WearableSensingService.
     *
     * <p>There will be no new invocation of this callback method after the connection is removed.
     * Ongoing invocation will continue to run.
     */
    void onConnectionAccepted();

    /**
     * Callback method called when an error occurred during secure connection setup.
     *
     * <p>There will be no new invocation of this callback method after the connection is removed.
     * Ongoing invocation will continue to run.
     */
    void onError(@WearableSensingManager.StatusCode int errorCode);
}
