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

package com.android.server.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubManager;
import android.hardware.location.ContextHubMessage;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubCallback;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppInstanceInfo;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

/**
 * @hide
 */
public class ContextHubService extends IContextHubService.Stub {
    private static final String TAG = "ContextHubService";
    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission '"
        + HARDWARE_PERMISSION + "' not granted to access ContextHub Hardware";

    public static final int ANY_HUB             = -1;
    public static final int MSG_LOAD_NANO_APP   = 3;
    public static final int MSG_UNLOAD_NANO_APP = 4;

    private static final String PRE_LOADED_GENERIC_UNKNOWN = "Preloaded app, unknown";
    private static final String PRE_LOADED_APP_NAME = PRE_LOADED_GENERIC_UNKNOWN;
    private static final String PRE_LOADED_APP_PUBLISHER = PRE_LOADED_GENERIC_UNKNOWN;
    private static final int PRE_LOADED_APP_MEM_REQ = 0;

    private static final int MSG_HEADER_SIZE = 4;
    private static final int HEADER_FIELD_MSG_TYPE = 0;
    private static final int HEADER_FIELD_MSG_VERSION = 1;
    private static final int HEADER_FIELD_HUB_HANDLE = 2;
    private static final int HEADER_FIELD_APP_INSTANCE = 3;

    private static final int HEADER_FIELD_LOAD_APP_ID_LO = MSG_HEADER_SIZE;
    private static final int HEADER_FIELD_LOAD_APP_ID_HI = MSG_HEADER_SIZE + 1;
    private static final int MSG_LOAD_APP_HEADER_SIZE = MSG_HEADER_SIZE + 2;

    private static final int OS_APP_INSTANCE = -1;

    private final Context mContext;
    private final ConcurrentHashMap<Integer, NanoAppInstanceInfo> mNanoAppHash =
            new ConcurrentHashMap<>();
    private final ContextHubInfo[] mContextHubInfo;
    private final RemoteCallbackList<IContextHubCallback> mCallbacksList =
            new RemoteCallbackList<>();

    private native int nativeSendMessage(int[] header, byte[] data);
    private native ContextHubInfo[] nativeInitialize();

    public ContextHubService(Context context) {
        mContext = context;
        mContextHubInfo = nativeInitialize();

        for (int i = 0; i < mContextHubInfo.length; i++) {
            Log.d(TAG, "ContextHub[" + i + "] id: " + mContextHubInfo[i].getId()
                  + ", name:  " + mContextHubInfo[i].getName());
        }
    }

    @Override
    public int registerCallback(IContextHubCallback callback) throws RemoteException {
        checkPermissions();
        mCallbacksList.register(callback);
        Log.d(TAG, "Added callback, total callbacks " +
              mCallbacksList.getRegisteredCallbackCount());
        return 0;
    }

    @Override
    public int[] getContextHubHandles() throws RemoteException {
        checkPermissions();
        int[] returnArray = new int[mContextHubInfo.length];

        Log.d(TAG, "System supports " + returnArray.length + " hubs");
        for (int i = 0; i < returnArray.length; ++i) {
            returnArray[i] = i;
            Log.d(TAG, String.format("Hub %s is mapped to %d",
                                     mContextHubInfo[i].getName(), returnArray[i]));
        }

        return returnArray;
    }

    @Override
    public ContextHubInfo getContextHubInfo(int contextHubHandle) throws RemoteException {
        checkPermissions();
        if (!(contextHubHandle >= 0 && contextHubHandle < mContextHubInfo.length)) {
            Log.e(TAG, "Invalid context hub handle " + contextHubHandle);
            return null; // null means fail
        }

        return mContextHubInfo[contextHubHandle];
    }

    @Override
    public int loadNanoApp(int contextHubHandle, NanoApp app) throws RemoteException {
        checkPermissions();

        if (!(contextHubHandle >= 0 && contextHubHandle < mContextHubInfo.length)) {
            Log.e(TAG, "Invalid contextHubhandle " + contextHubHandle);
            return -1;
        }
        if (app == null) {
            Log.e(TAG, "Invalid null app");
            return -1;
        }

        int[] msgHeader = new int[MSG_LOAD_APP_HEADER_SIZE];
        msgHeader[HEADER_FIELD_HUB_HANDLE] = contextHubHandle;
        msgHeader[HEADER_FIELD_APP_INSTANCE] = OS_APP_INSTANCE;
        msgHeader[HEADER_FIELD_MSG_VERSION] = 0;
        msgHeader[HEADER_FIELD_MSG_TYPE] = MSG_LOAD_NANO_APP;

        long appId = app.getAppId();

        msgHeader[HEADER_FIELD_LOAD_APP_ID_LO] = (int)(appId & 0xFFFFFFFF);
        msgHeader[HEADER_FIELD_LOAD_APP_ID_HI] = (int)((appId >> 32) & 0xFFFFFFFF);

        int errVal = nativeSendMessage(msgHeader, app.getAppBinary());
        if (errVal != 0) {
            Log.e(TAG, "Send Message returns error" + contextHubHandle);
            return -1;
        }

        // Do not add an entry to mNanoAppInstance Hash yet. The HAL may reject the app
        return 0;
    }

    @Override
    public int unloadNanoApp(int nanoAppInstanceHandle) throws RemoteException {
        checkPermissions();
        NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstanceHandle);
        if (info == null) {
            Log.e(TAG, "Cannot find app with handle " + nanoAppInstanceHandle);
            return -1; //means failed
        }

        // Call Native interface here
        int[] msgHeader = new int[MSG_HEADER_SIZE];
        msgHeader[HEADER_FIELD_HUB_HANDLE] = ANY_HUB;
        msgHeader[HEADER_FIELD_APP_INSTANCE] = nanoAppInstanceHandle;
        msgHeader[HEADER_FIELD_MSG_VERSION] = 0;
        msgHeader[HEADER_FIELD_MSG_TYPE] = MSG_UNLOAD_NANO_APP;

        byte msg[] = new byte[0];

        if (nativeSendMessage(msgHeader, msg) != 0) {
            Log.e(TAG, "native send message fails");
            return -1;
        }

        // Do not add an entry to mNanoAppInstance Hash yet. The HAL may reject the app
        return 0;
    }

    @Override
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppInstanceHandle)
                                                      throws RemoteException {
        checkPermissions();
        // This assumes that all the nanoAppInfo is current. This is reasonable
        // for the use cases for tightly controlled nanoApps.
        if (mNanoAppHash.containsKey(nanoAppInstanceHandle)) {
            return mNanoAppHash.get(nanoAppInstanceHandle);
        } else {
            Log.e(TAG, "Could not find nanoApp with handle " + nanoAppInstanceHandle);
            return null;
        }
    }

    @Override
    public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) throws RemoteException {
        checkPermissions();
        ArrayList<Integer> foundInstances = new ArrayList<Integer>();

        for (Integer nanoAppInstance: mNanoAppHash.keySet()) {
            NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstance);

            if (filter.testMatch(info)) {
                foundInstances.add(nanoAppInstance);
            }
        }

        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }

        Log.w(TAG, "Found " + retArray.length + " apps on hub handle " + hubHandle);
        return retArray;
    }

    @Override
    public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage msg)
                           throws RemoteException {
        checkPermissions();

        if (msg == null || msg.getData() == null) {
            Log.w(TAG, "null ptr");
            return -1;
        }

        int[] msgHeader = new int[MSG_HEADER_SIZE];
        msgHeader[HEADER_FIELD_HUB_HANDLE] = hubHandle;
        msgHeader[HEADER_FIELD_APP_INSTANCE] = nanoAppHandle;
        msgHeader[HEADER_FIELD_MSG_VERSION] = msg.getVersion();
        msgHeader[HEADER_FIELD_MSG_TYPE] = msg.getMsgType();

        return nativeSendMessage(msgHeader, msg.getData());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("Dumping ContextHub Service");

        pw.println("");
        // dump ContextHubInfo
        pw.println("=================== CONTEXT HUBS ====================");
        for (int i = 0; i < mContextHubInfo.length; i++) {
            pw.println("Handle " + i + " : " + mContextHubInfo[i].toString());
        }
        pw.println("");
        pw.println("=================== NANOAPPS ====================");
        // Dump nanoAppHash
        for (Integer nanoAppInstance: mNanoAppHash.keySet()) {
            pw.println(nanoAppInstance + " : " + mNanoAppHash.get(nanoAppInstance).toString());
        }

        // dump eventLog
    }

    private void checkPermissions() {
        mContext.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }

    private int onMessageReceipt(int[] header, byte[] data) {
        if (header == null || data == null || header.length < MSG_HEADER_SIZE) {
            return  -1;
        }

        int callbacksCount = mCallbacksList.beginBroadcast();
        int msgType = header[HEADER_FIELD_MSG_TYPE];
        int msgVersion = header[HEADER_FIELD_MSG_VERSION];
        int hubHandle = header[HEADER_FIELD_HUB_HANDLE];
        int appInstance = header[HEADER_FIELD_APP_INSTANCE];

        Log.d(TAG, "Sending message " + msgType + " version " + msgVersion + " from hubHandle " +
              hubHandle + ", appInstance " + appInstance + ", callBackCount " + callbacksCount);

        if (callbacksCount < 1) {
            Log.v(TAG, "No message callbacks registered.");
            return 0;
        }

        ContextHubMessage msg = new ContextHubMessage(msgType, msgVersion, data);
        for (int i = 0; i < callbacksCount; ++i) {
            IContextHubCallback callback = mCallbacksList.getBroadcastItem(i);
            try {
                callback.onMessageReceipt(hubHandle, appInstance, msg);
            } catch (RemoteException e) {
                Log.i(TAG, "Exception (" + e + ") calling remote callback (" + callback + ").");
                continue;
            }
        }
        mCallbacksList.finishBroadcast();
        return 0;
    }

    private int addAppInstance(int hubHandle, int appInstanceHandle, long appId, int appVersion) {
        // App Id encodes vendor & version
        NanoAppInstanceInfo appInfo = new NanoAppInstanceInfo();

        appInfo.setAppId(appId);
        appInfo.setAppVersion(appVersion);
        appInfo.setName(PRE_LOADED_APP_NAME);
        appInfo.setContexthubId(hubHandle);
        appInfo.setHandle(appInstanceHandle);
        appInfo.setPublisher(PRE_LOADED_APP_PUBLISHER);
        appInfo.setNeededExecMemBytes(PRE_LOADED_APP_MEM_REQ);
        appInfo.setNeededReadMemBytes(PRE_LOADED_APP_MEM_REQ);
        appInfo.setNeededWriteMemBytes(PRE_LOADED_APP_MEM_REQ);

        String action;
        if (mNanoAppHash.containsKey(appInstanceHandle)) {
            action = "Updated";
        } else {
            action = "Added";
        }

        mNanoAppHash.put(appInstanceHandle, appInfo);
        Log.d(TAG, action + " app instance " + appInstanceHandle + " with id "
              + appId + " version " + appVersion);

        return 0;
    }

    private int deleteAppInstance(int appInstanceHandle) {
        if (mNanoAppHash.remove(appInstanceHandle) == null) {
            return -1;
        }

        return 0;
    }
}
