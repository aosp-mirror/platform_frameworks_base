/*
 * Copyright 2017 The Android Open Source Project
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
package android.hardware.location;

/**
 * A class for {@link android.hardware.location.ContextHubClient ContextHubClient} to
 * receive messages and life-cycle events from nanoapps in the Context Hub at which the client is
 * attached to.
 *
 * This callback is registered through the
 * {@link android.hardware.location.ContextHubManager#createClient() creation} of
 * {@link android.hardware.location.ContextHubClient ContextHubClient}. Callbacks are
 * invoked in the following ways:
 * 1) Messages from nanoapps delivered through onMessageFromNanoApp may either be broadcasted
 *    or targeted to a specific client.
 * 2) Nanoapp or Context Hub events (the remaining callbacks) are broadcasted to all clients, and
 *    the client can choose to ignore the event by filtering through the parameters.
 *
 * @hide
 */
public class ContextHubClientCallback {
    /**
     * Callback invoked when receiving a message from a nanoapp.
     *
     * The message contents of this callback may either be broadcasted or targeted to the
     * client receiving the invocation.
     *
     * @param message the message sent by the nanoapp
     */
    public void onMessageFromNanoApp(NanoAppMessage message) {}

    /**
     * Callback invoked when the attached Context Hub has reset.
     */
    public void onHubReset() {}

    /**
     * Callback invoked when a nanoapp aborts at the attached Context Hub.
     *
     * @param nanoAppId the ID of the nanoapp that had aborted
     * @param abortCode the reason for nanoapp's abort, specific to each nanoapp
     */
    public void onNanoAppAborted(long nanoAppId, int abortCode) {}

    /**
     * Callback invoked when a nanoapp is loaded at the attached Context Hub.
     *
     * @param nanoAppId the ID of the nanoapp that had been loaded
     */
    public void onNanoAppLoaded(long nanoAppId) {}

    /**
     * Callback invoked when a nanoapp is unloaded from the attached Context Hub.
     *
     * @param nanoAppId the ID of the nanoapp that had been unloaded
     */
    public void onNanoAppUnloaded(long nanoAppId) {}

    /**
     * Callback invoked when a nanoapp is enabled at the attached Context Hub.
     *
     * @param nanoAppId the ID of the nanoapp that had been enabled
     */
    public void onNanoAppEnabled(long nanoAppId) {}

    /**
     * Callback invoked when a nanoapp is disabled at the attached Context Hub.
     *
     * @param nanoAppId the ID of the nanoapp that had been disabled
     */
    public void onNanoAppDisabled(long nanoAppId) {}
}
