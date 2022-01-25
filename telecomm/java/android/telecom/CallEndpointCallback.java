/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telecom;

/**
 * Provides callbacks from telecom to the cross device call streaming app with lifecycle events
 * related to an {@link CallEndpointSession}.
 */
public interface CallEndpointCallback {
    /**
     * Invoked by telecom when a {@link CallEndpointSession} is started but the streaming app has
     * not activated the endpoint in a timely manner and the framework deems the activation request
     * to have timed out.
     */
    void onCallEndpointSessionActivationTimeout();

    /**
     * Invoked by telecom when {@link CallEndpointSession#setCallEndpointSessionDeactivated()}
     * called by a cross device call streaming app, or when the app uninstalled. When a tethered
     * {@link CallEndpoint} is deactivated, the call streaming app should clean up any
     * audio/network resources and stop relaying call controls from the endpoint.
     */
    void onCallEndpointSessionDeactivated();
}
