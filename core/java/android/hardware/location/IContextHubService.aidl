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
import android.hardware.location.ContextHubMessage;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.IContextHubCallback;

/**
 * @hide
 */
interface IContextHubService {

    // register a callback to receive messages
    int registerCallback(in IContextHubCallback callback);

    // Gets a list of available context hub handles
    int[] getContextHubHandles();

    // Get the properties of a hub
    ContextHubInfo getContextHubInfo(int contextHubHandle);

    // Load a nanoapp on a specified context hub
    int loadNanoApp(int hubHandle, in NanoApp app);

    // Unload a nanoapp instance
    int unloadNanoApp(int nanoAppInstanceHandle);

    // get information about a nanoAppInstance
    NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppInstanceHandle);

    // find all nanoApp instances matching some filter
    int[] findNanoAppOnHub(int hubHandle, in NanoAppFilter filter);

    // send a message to a nanoApp
    int sendMessage(int hubHandle, int nanoAppHandle, in ContextHubMessage msg);
}
