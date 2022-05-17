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

import android.Manifest;
import android.content.Context;
import android.hardware.contexthub.V1_0.AsyncEventType;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HostEndPoint;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.contexthub.V1_2.HubAppInfo;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppRpcService;
import android.hardware.location.NanoAppState;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * A class encapsulating helper functions used by the ContextHubService class
 */
/* package */ class ContextHubServiceUtil {
    private static final String TAG = "ContextHubServiceUtil";
    private static final String CONTEXT_HUB_PERMISSION = Manifest.permission.ACCESS_CONTEXT_HUB;

    /**
     * A host endpoint that is reserved to identify a broadcasted message.
     */
    private static final char HOST_ENDPOINT_BROADCAST = 0xFFFF;

    /**
     * Creates a ConcurrentHashMap of the Context Hub ID to the ContextHubInfo object given an
     * ArrayList of HIDL ContextHub objects.
     *
     * @param hubList the ContextHub ArrayList
     * @return the HashMap object
     */
    /* package */
    static HashMap<Integer, ContextHubInfo> createContextHubInfoMap(List<ContextHubInfo> hubList) {
        HashMap<Integer, ContextHubInfo> contextHubIdToInfoMap = new HashMap<>();
        for (ContextHubInfo contextHubInfo : hubList) {
            contextHubIdToInfoMap.put(contextHubInfo.getId(), contextHubInfo);
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
     * Generates the Context Hub HAL's HIDL NanoAppBinary object from the client-facing
     * android.hardware.location.NanoAppBinary object.
     *
     * @param nanoAppBinary the client-facing NanoAppBinary object
     * @return the Context Hub HAL's HIDL NanoAppBinary object
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
     * Generates the Context Hub HAL's AIDL NanoAppBinary object from the client-facing
     * android.hardware.location.NanoAppBinary object.
     *
     * @param nanoAppBinary the client-facing NanoAppBinary object
     * @return the Context Hub HAL's AIDL NanoAppBinary object
     */
    /* package */
    static android.hardware.contexthub.NanoappBinary createAidlNanoAppBinary(
            NanoAppBinary nanoAppBinary) {
        android.hardware.contexthub.NanoappBinary aidlNanoAppBinary =
                new android.hardware.contexthub.NanoappBinary();

        aidlNanoAppBinary.nanoappId = nanoAppBinary.getNanoAppId();
        aidlNanoAppBinary.nanoappVersion = nanoAppBinary.getNanoAppVersion();
        aidlNanoAppBinary.flags = nanoAppBinary.getFlags();
        aidlNanoAppBinary.targetChreApiMajorVersion = nanoAppBinary.getTargetChreApiMajorVersion();
        aidlNanoAppBinary.targetChreApiMinorVersion = nanoAppBinary.getTargetChreApiMinorVersion();
        // This explicit definition is required to avoid erroneous behavior at the binder.
        aidlNanoAppBinary.customBinary = new byte[0];

        // Log exceptions while processing the binary, but continue to pass down the binary
        // since the error checking is deferred to the Context Hub.
        try {
            aidlNanoAppBinary.customBinary = nanoAppBinary.getBinaryNoHeader();
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, e.getMessage());
        } catch (NullPointerException e) {
            Log.w(TAG, "NanoApp binary was null");
        }

        return aidlNanoAppBinary;
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
     * Generates a client-facing NanoAppState array from a AIDL NanoappInfo array.
     *
     * @param nanoAppInfoList the array of NanoappInfo objects
     * @return the corresponding array of NanoAppState objects
     */
    /* package */
    static List<NanoAppState> createNanoAppStateList(
            android.hardware.contexthub.NanoappInfo[] nanoAppInfoList) {
        ArrayList<NanoAppState> nanoAppStateList = new ArrayList<>();
        for (android.hardware.contexthub.NanoappInfo appInfo : nanoAppInfoList) {
            ArrayList<NanoAppRpcService> rpcServiceList = new ArrayList<>();
            for (android.hardware.contexthub.NanoappRpcService service : appInfo.rpcServices) {
                rpcServiceList.add(new NanoAppRpcService(service.id, service.version));
            }
            nanoAppStateList.add(
                    new NanoAppState(appInfo.nanoappId, appInfo.nanoappVersion,
                            appInfo.enabled, new ArrayList<>(Arrays.asList(appInfo.permissions)),
                            rpcServiceList));
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
     * Creates an AIDL ContextHubMessage object to send to a nanoapp.
     *
     * @param hostEndPoint the ID of the client sending the message
     * @param message      the client-facing NanoAppMessage object describing the message
     * @return the AIDL ContextHubMessage object
     */
    /* package */
    static android.hardware.contexthub.ContextHubMessage createAidlContextHubMessage(
            short hostEndPoint, NanoAppMessage message) {
        android.hardware.contexthub.ContextHubMessage aidlMessage =
                new android.hardware.contexthub.ContextHubMessage();

        aidlMessage.nanoappId = message.getNanoAppId();
        aidlMessage.hostEndPoint = (char) hostEndPoint;
        aidlMessage.messageType = message.getMessageType();
        aidlMessage.messageBody = message.getMessageBody();
        // This explicit definition is required to avoid erroneous behavior at the binder.
        aidlMessage.permissions = new String[0];

        return aidlMessage;
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
     * Creates a client-facing NanoAppMessage object to send to a client.
     *
     * @param message the AIDL ContextHubMessage object from a nanoapp
     * @return the NanoAppMessage object
     */
    /* package */
    static NanoAppMessage createNanoAppMessage(
            android.hardware.contexthub.ContextHubMessage message) {
        return NanoAppMessage.createMessageFromNanoApp(
                message.nanoappId, message.messageType, message.messageBody,
                message.hostEndPoint == HOST_ENDPOINT_BROADCAST);
    }

    /**
     * Checks for ACCESS_CONTEXT_HUB permissions.
     *
     * @param context the context of the service
     */
    /* package */
    static void checkPermissions(Context context) {
        context.enforceCallingOrSelfPermission(CONTEXT_HUB_PERMISSION,
                "ACCESS_CONTEXT_HUB permission required to use Context Hub");
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

    /**
     * Converts a HIDL AsyncEventType to the corresponding ContextHubService.CONTEXT_HUB_EVENT_*.
     *
     * @param hidlEventType The AsyncEventType value.
     * @return The converted event type.
     */
    /* package */
    static int toContextHubEvent(int hidlEventType) {
        switch (hidlEventType) {
            case AsyncEventType.RESTARTED:
                return ContextHubService.CONTEXT_HUB_EVENT_RESTARTED;
            default:
                Log.e(TAG, "toContextHubEvent: Unknown event type: " + hidlEventType);
                return ContextHubService.CONTEXT_HUB_EVENT_UNKNOWN;
        }
    }

    /**
     * Converts an AIDL AsyncEventType to the corresponding ContextHubService.CONTEXT_HUB_EVENT_*.
     *
     * @param aidlEventType The AsyncEventType value.
     * @return The converted event type.
     */
    /* package */
    static int toContextHubEventFromAidl(int aidlEventType) {
        switch (aidlEventType) {
            case android.hardware.contexthub.AsyncEventType.RESTARTED:
                return ContextHubService.CONTEXT_HUB_EVENT_RESTARTED;
            default:
                Log.e(TAG, "toContextHubEventFromAidl: Unknown event type: " + aidlEventType);
                return ContextHubService.CONTEXT_HUB_EVENT_UNKNOWN;
        }
    }
}
