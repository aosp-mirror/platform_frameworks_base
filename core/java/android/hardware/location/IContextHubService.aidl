/*
 * Copyright (C) 2016 The Android Open Source Project
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

// Declare any non-default types here with import statements
import android.app.PendingIntent;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubMessage;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.IContextHubCallback;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.IContextHubTransactionCallback;

/**
 * @hide
 */
interface IContextHubService {

    // Registers a callback to receive messages
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int registerCallback(in IContextHubCallback callback);

    // Gets a list of available context hub handles
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int[] getContextHubHandles();

    // Gets the properties of a hub
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    ContextHubInfo getContextHubInfo(int contextHubHandle);

    // Loads a nanoapp at the specified hub (old API)
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int loadNanoApp(int contextHubHandle, in NanoApp nanoApp);

    // Unloads a nanoapp given its instance ID (old API)
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int unloadNanoApp(int nanoAppHandle);

    // Gets the NanoAppInstanceInfo of a nanoapp give its instance ID
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle);

    // Finds all nanoApp instances matching some filter
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int[] findNanoAppOnHub(int contextHubHandle, in NanoAppFilter filter);

    // Sends a message to a nanoApp
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    int sendMessage(int contextHubHandle, int nanoAppHandle, in ContextHubMessage msg);

    // Creates a client to send and receive messages
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    IContextHubClient createClient(
            int contextHubId, in IContextHubClientCallback client, in String attributionTag,
            in String packageName);

    // Creates a PendingIntent-based client to send and receive messages
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    IContextHubClient createPendingIntentClient(
            int contextHubId, in PendingIntent pendingIntent, long nanoAppId,
            in String attributionTag);

    // Returns a list of ContextHub objects of available hubs
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    List<ContextHubInfo> getContextHubs();

    // Loads a nanoapp at the specified hub (new API)
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    void loadNanoAppOnHub(
            int contextHubId, in IContextHubTransactionCallback transactionCallback,
            in NanoAppBinary nanoAppBinary);

    // Unloads a nanoapp on a specified context hub (new API)
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    void unloadNanoAppFromHub(
            int contextHubId, in IContextHubTransactionCallback transactionCallback,
            long nanoAppId);

    // Enables a nanoapp at the specified hub
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    void enableNanoApp(
            int contextHubId, in IContextHubTransactionCallback transactionCallback,
            long nanoAppId);

    // Disables a nanoapp at the specified hub
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    void disableNanoApp(
            int contextHubId, in IContextHubTransactionCallback transactionCallback,
            long nanoAppId);

    // Queries for a list of nanoapps
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    void queryNanoApps(int contextHubId, in IContextHubTransactionCallback transactionCallback);

    // Queries for a list of preloaded nanoapps
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    long[] getPreloadedNanoAppIds(in ContextHubInfo hubInfo);

    // Enables or disables test mode
    @EnforcePermission("ACCESS_CONTEXT_HUB")
    boolean setTestMode(in boolean enable);
}
