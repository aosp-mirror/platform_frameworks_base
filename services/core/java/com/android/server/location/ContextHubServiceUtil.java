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

import android.Manifest;
import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HostEndPoint;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.util.Log;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * A class encapsulating helper functions used by the ContextHubService class
 */
/* package */ class ContextHubServiceUtil {
    private static final String TAG = "ContextHubServiceUtil";
    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission '"
            + HARDWARE_PERMISSION + "' not granted to access ContextHub Hardware";

    /**
     * Creates a ConcurrentHashMap of the Context Hub ID to the ContextHubInfo object given an
     * ArrayList of HIDL ContextHub objects.
     *
     * @param hubList the ContextHub ArrayList
     * @return the HashMap object
     */
    /* package */
    static HashMap<Integer, ContextHubInfo> createContextHubInfoMap(List<ContextHub> hubList) {
        HashMap<Integer, ContextHubInfo> contextHubIdToInfoMap = new HashMap<>();
        for (ContextHub contextHub : hubList) {
            contextHubIdToInfoMap.put(contextHub.hubId, new ContextHubInfo(contextHub));
        }

        return contextHubIdToInfoMap;
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
     * Creates a primitive integer array given a Collection<Integer>.
     * @param collection the collection to iterate
     * @return the primitive integer array
     */
    static int[] createPrimitiveIntArray(Collection<Integer> collection) {
        int[] primitiveArray = new int[collection.size()];

        int i = 0;
        for (int contextHubId : collection) {
            primitiveArray[i++] = contextHubId;
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

    /**
     * Creates a HIDL ContextHubMsg object to send to a nanoapp.
     *
     * @param hostEndPoint the ID of the client sending the message
     * @param message      the client-facing NanoAppMessage object describing the message
     * @return the HIDL ContextHubMsg object
     */
    /* package */
    static ContextHubMsg createHidlContextHubMessage(short hostEndPoint, NanoAppMessage message) {
        ContextHubMsg hidlMessage = new ContextHubMsg();

        hidlMessage.appName = message.getNanoAppId();
        hidlMessage.hostEndPoint = hostEndPoint;
        hidlMessage.msgType = message.getMessageType();
        copyToByteArrayList(message.getMessageBody(), hidlMessage.msg);

        return hidlMessage;
    }

    /**
     * Creates a client-facing NanoAppMessage object to send to a client.
     *
     * @param message the HIDL ContextHubMsg object from a nanoapp
     * @return the NanoAppMessage object
     */
    /* package */
    static NanoAppMessage createNanoAppMessage(ContextHubMsg message) {
        byte[] messageArray = createPrimitiveByteArray(message.msg);

        return NanoAppMessage.createMessageFromNanoApp(
                message.appName, message.msgType, messageArray,
                message.hostEndPoint == HostEndPoint.BROADCAST);
    }

    /**
     * Checks for location hardware permissions.
     *
     * @param context the context of the service
     */
    /* package */
    static void checkPermissions(Context context) {
        context.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }

    /**
     * Helper function to convert from the HAL Result enum error code to the
     * ContextHubTransaction.Result type.
     *
     * @param halResult the Result enum error code
     * @return the ContextHubTransaction.Result equivalent
     */
    @ContextHubTransaction.Result
    /* package */
    static int toTransactionResult(int halResult) {
        switch (halResult) {
            case Result.OK:
                return ContextHubTransaction.RESULT_SUCCESS;
            case Result.BAD_PARAMS:
                return ContextHubTransaction.RESULT_FAILED_BAD_PARAMS;
            case Result.NOT_INIT:
                return ContextHubTransaction.RESULT_FAILED_UNINITIALIZED;
            case Result.TRANSACTION_PENDING:
                return ContextHubTransaction.RESULT_FAILED_BUSY;
            case Result.TRANSACTION_FAILED:
            case Result.UNKNOWN_FAILURE:
            default: /* fall through */
                return ContextHubTransaction.RESULT_FAILED_UNKNOWN;
        }
    }
}
