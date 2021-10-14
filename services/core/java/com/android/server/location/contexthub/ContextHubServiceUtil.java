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

package com.android.server.location.contexthub;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HostEndPoint;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.contexthub.V1_2.HubAppInfo;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.Binder;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class encapsulating helper functions used by the ContextHubService class
 */
/* package */ class ContextHubServiceUtil {
    private static final String TAG = "ContextHubServiceUtil";
    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String CONTEXT_HUB_PERMISSION = Manifest.permission.ACCESS_CONTEXT_HUB;

    // A set of packages that have already been warned regarding the ACCESS_CONTEXT_HUB permission.
    private static final Set<String> PERMISSION_WARNED_PACKAGES = new HashSet<String>();

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
     *
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
                    new NanoAppState(appInfo.info_1_0.appId, appInfo.info_1_0.version,
                                     appInfo.info_1_0.enabled, appInfo.permissions));
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
        boolean hasLocationHardwarePermission = (context.checkCallingPermission(HARDWARE_PERMISSION)
                == PERMISSION_GRANTED);
        boolean hasAccessContextHubPermission = (context.checkCallingPermission(
                CONTEXT_HUB_PERMISSION) == PERMISSION_GRANTED);

        if (!hasLocationHardwarePermission && !hasAccessContextHubPermission) {
            throw new SecurityException(
                    "LOCATION_HARDWARE or ACCESS_CONTEXT_HUB permission required to use Context "
                            + "Hub");
        }

        if (!hasAccessContextHubPermission && !Build.IS_USER) {
            String pkgName = context.getPackageManager().getNameForUid(Binder.getCallingUid());
            if (!PERMISSION_WARNED_PACKAGES.contains(pkgName)) {
                Log.w(TAG, pkgName
                        + ": please use the ACCESS_CONTEXT_HUB permission rather than "
                        + "LOCATION_HARDWARE (will be removed for Context Hub APIs in T)");
                PERMISSION_WARNED_PACKAGES.add(pkgName);
            }
        }
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

    /**
     * Converts old list of HubAppInfo received from the HAL to V1.2 HubAppInfo objects.
     *
     * @param oldInfoList list of V1.0 HubAppInfo objects
     * @return list of V1.2 HubAppInfo objects
     */
    /* package */
    static ArrayList<HubAppInfo> toHubAppInfo_1_2(
            ArrayList<android.hardware.contexthub.V1_0.HubAppInfo> oldInfoList) {
        ArrayList newAppInfo = new ArrayList<HubAppInfo>();
        for (android.hardware.contexthub.V1_0.HubAppInfo oldInfo : oldInfoList) {
            HubAppInfo newInfo = new HubAppInfo();
            newInfo.info_1_0.appId = oldInfo.appId;
            newInfo.info_1_0.version = oldInfo.version;
            newInfo.info_1_0.memUsage = oldInfo.memUsage;
            newInfo.info_1_0.enabled = oldInfo.enabled;
            newInfo.permissions = new ArrayList<String>();
            newAppInfo.add(newInfo);
        }
        return newAppInfo;
    }
}
