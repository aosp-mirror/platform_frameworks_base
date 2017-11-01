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

package com.android.server.location;

import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppState;
import android.util.Log;

import java.util.List;
import java.util.ArrayList;

/**
 * A class encapsulating helper functions used by the ContextHubService class
 */
/* package */ class ContextHubServiceUtil {
    private static final String TAG = "ContextHubServiceUtil";

    /**
     * Creates a ContextHubInfo array from an ArrayList of HIDL ContextHub objects.
     *
     * @param hubList the ContextHub ArrayList
     * @return the ContextHubInfo array
     */
    /* package */
    static ContextHubInfo[] createContextHubInfoArray(List<ContextHub> hubList) {
        ContextHubInfo[] contextHubInfoList = new ContextHubInfo[hubList.size()];
        for (int i = 0; i < hubList.size(); i++) {
            contextHubInfoList[i] = new ContextHubInfo(hubList.get(i));
        }

        return contextHubInfoList;
    }

    /**
     * Copies a primitive byte array to a ArrayList<Byte>.
     *
     * @param inputArray  the primitive byte array
     * @param outputArray the ArrayList<Byte> array to append
     */
    /* package */
    static void copyToByteArrayList(byte[] inputArray, ArrayList<Byte> outputArray) {
        outputArray.clear();
        outputArray.ensureCapacity(inputArray.length);
        for (byte element : inputArray) {
            outputArray.add(element);
        }
    }

    /**
     * Creates a byte array given a ArrayList<Byte> and copies its contents.
     *
     * @param array the ArrayList<Byte> object
     * @return the byte array
     */
    /* package */
    static byte[] createPrimitiveByteArray(ArrayList<Byte> array) {
        byte[] primitiveArray = new byte[array.size()];
        for (int i = 0; i < array.size(); i++) {
            primitiveArray[i] = array.get(i);
        }

        return primitiveArray;
    }

    /**
     * Generates the Context Hub HAL's NanoAppBinary object from the client-facing
     * android.hardware.location.NanoAppBinary object.
     *
     * @param nanoAppBinary the client-facing NanoAppBinary object
     * @return the Context Hub HAL's NanoAppBinary object
     */
    /* package */
    static android.hardware.contexthub.V1_0.NanoAppBinary createHidlNanoAppBinary(
            NanoAppBinary nanoAppBinary) {
        android.hardware.contexthub.V1_0.NanoAppBinary hidlNanoAppBinary =
                new android.hardware.contexthub.V1_0.NanoAppBinary();

        hidlNanoAppBinary.appId = nanoAppBinary.getNanoAppId();
        hidlNanoAppBinary.appVersion = nanoAppBinary.getNanoAppVersion();
        hidlNanoAppBinary.flags = nanoAppBinary.getFlags();
        hidlNanoAppBinary.targetChreApiMajorVersion = nanoAppBinary.getTargetChreApiMajorVersion();
        hidlNanoAppBinary.targetChreApiMinorVersion = nanoAppBinary.getTargetChreApiMinorVersion();

        // Log exceptions while processing the binary, but continue to pass down the binary
        // since the error checking is deferred to the Context Hub.
        try {
            copyToByteArrayList(nanoAppBinary.getBinaryNoHeader(), hidlNanoAppBinary.customBinary);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, e.getMessage());
        } catch (NullPointerException e) {
            Log.w(TAG, "NanoApp binary was null");
        }

        return hidlNanoAppBinary;
    }

    /**
     * Generates a client-facing NanoAppState array from a HAL HubAppInfo array.
     *
     * @param nanoAppInfoList the array of HubAppInfo objects
     * @return the corresponding array of NanoAppState objects
     */
    /* package */
    static List<NanoAppState> createNanoAppStateList(
            List<HubAppInfo> nanoAppInfoList) {
        ArrayList<NanoAppState> nanoAppStateList = new ArrayList<>();
        for (HubAppInfo appInfo : nanoAppInfoList) {
            nanoAppStateList.add(
                    new NanoAppState(appInfo.appId, appInfo.version, appInfo.enabled));
        }

        return nanoAppStateList;
    }
}
