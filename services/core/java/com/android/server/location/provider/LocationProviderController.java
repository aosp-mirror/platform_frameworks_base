/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.provider;

import android.annotation.Nullable;
import android.location.provider.ProviderRequest;
import android.os.Bundle;

import com.android.server.location.provider.AbstractLocationProvider.Listener;
import com.android.server.location.provider.AbstractLocationProvider.State;

/**
 * Interface for controlling location providers.
 */
interface LocationProviderController {

    /**
     * Sets the listener and returns the state at the moment the listener was set. The listener can
     * expect to receive all state updates from after this point. May be invoked at any time.
     */
    State setListener(@Nullable Listener listener);

    /**
     * Returns true if in the started state.
     */
    boolean isStarted();

    /**
     * Starts the location provider. Must be invoked before any other method (except
     * {@link #setListener(Listener)}).
     */
    void start();

    /**
     * Stops the location provider. No other methods may be invoked after this method (except
     * {@link #setListener(Listener)}), until {@link #start()} is called again.
     */
    void stop();

    /**
     * Sets a new request and worksource for the provider.
     */
    void setRequest(ProviderRequest request);

    /**
     * Requests that any batched locations are flushed.
     */
    void flush(Runnable listener);

    /**
     * Sends an extra command to the provider for it to interpret as it likes.
     */
    void sendExtraCommand(int uid, int pid, String command, Bundle extras);
}
