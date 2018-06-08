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

import android.hardware.location.NanoAppMessage;

/**
 * An interface used by the Context Hub Service to invoke callbacks for lifecycle notifications of a
 * Context Hub and nanoapps, as well as for nanoapp messaging.
 *
 * @hide
 */
oneway interface IContextHubClientCallback {

    // Callback invoked when receiving a message from a nanoapp.
    void onMessageFromNanoApp(in NanoAppMessage message);

    // Callback invoked when the attached Context Hub has reset.
    void onHubReset();

    // Callback invoked when a nanoapp aborts at the attached Context Hub.
    void onNanoAppAborted(long nanoAppId, int abortCode);

    // Callback invoked when a nanoapp is loaded at the attached Context Hub.
    void onNanoAppLoaded(long nanoAppId);

    // Callback invoked when a nanoapp is unloaded from the attached Context Hub.
    void onNanoAppUnloaded(long nanoAppId);

    // Callback invoked when a nanoapp is enabled at the attached Context Hub.
    void onNanoAppEnabled(long nanoAppId);

    // Callback invoked when a nanoapp is disabled at the attached Context Hub.
    void onNanoAppDisabled(long nanoAppId);
}
