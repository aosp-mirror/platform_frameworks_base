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

import android.Manifest;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * @hide
 */
public class ContextHubService extends IContextHubService.Stub {

    private static final String TAG = "ContextHubService";
    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission '"
            + HARDWARE_PERMISSION + "' not granted to access ContextHub Hardware";

    public static final String CONTEXTHUB_SERVICE = "contexthub_service";

    private final Context mContext;

    private HashMap<Integer, NanoAppInstanceInfo> mNanoAppHash;
    private ContextHubInfo[] mContextHubInfo;
    private IContextHubCallback mCallback;

    public ContextHubService(Context context) {
        mContext = context;
        mContextHubInfo = nativeInitialize();

        for (int i = 0; i < mContextHubInfo.length; i++) {
            Log.v(TAG, "ContextHub[" + i + "] id: " + mContextHubInfo[i].getId()
                  + ", name:  " + mContextHubInfo[i].getName());
        }
    }

    private native int nativeSendMessage(int[] header, byte[] data);
    private native ContextHubInfo[] nativeInitialize();

    @Override
    public int registerCallback(IContextHubCallback callback) throws RemoteException{
        checkPermissions();
        mCallback = callback;
        return 0;
    }


    private int onMessageReceipt(int[] header, byte[] data) {
        if (mCallback != null) {
            // TODO : Defend against unexpected header sizes
            //        Add abstraction for magic numbers
            //        onMessageRecipt should pass the right arguments
            ContextHubMessage msg = new ContextHubMessage(header[0], header[1], data);

            try {
                mCallback.onMessageReceipt(0, 0, msg);
            } catch (Exception e) {
                Log.e(TAG, "Exception " + e + " when calling remote callback");
                return -1;
            }
        } else {
            Log.d(TAG, "Message Callback is NULL");
        }

        return 0;
    }

    @Override
    public int[] getContextHubHandles() throws RemoteException {
        checkPermissions();
        int [] returnArray = new int[mContextHubInfo.length];

        for (int i = 0; i < returnArray.length; ++i) {
            returnArray[i] = i + 1; //valid handles from 1...n
            Log.d(TAG, String.format("Hub %s is mapped to %d",
                                     mContextHubInfo[i].getName(), returnArray[i]));
        }

        return returnArray;
    }

    @Override
    public ContextHubInfo getContextHubInfo(int contextHubHandle) throws RemoteException {
        checkPermissions();
        contextHubHandle -= 1;
        if (!(contextHubHandle >= 0 && contextHubHandle < mContextHubInfo.length)) {
            return null; // null means fail
        }

        return mContextHubInfo[contextHubHandle];
    }

    @Override
    public int loadNanoApp(int contextHubHandle, NanoApp app) throws RemoteException {
        checkPermissions();
        contextHubHandle -= 1;

        if (!(contextHubHandle >= 0 && contextHubHandle < mContextHubInfo.length)) {
            return -1; // negative handle are invalid, means failed
        }

        // Call Native interface here
        int[] msgHeader = new int[8];
        msgHeader[0] = contextHubHandle;
        msgHeader[1] = app.getAppId();
        msgHeader[2] = app.getAppVersion();
        msgHeader[3] = ContextHubManager.MSG_LOAD_NANO_APP;
        msgHeader[4] = 0; // Loading hints

        return nativeSendMessage(msgHeader, app.getAppBinary());
    }

    @Override
    public int unloadNanoApp(int nanoAppInstanceHandle) throws RemoteException {
        checkPermissions();
        NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstanceHandle);
        if (info == null) {
            return -1; //means failed
        }

        // Call Native interface here
        int[] msgHeader = new int[8];
        msgHeader[0] = info.getContexthubId();
        msgHeader[1] = ContextHubManager.MSG_UNLOAD_NANO_APP;
        msgHeader[2] = info.getHandle();

        return nativeSendMessage(msgHeader, null);
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
            return null;
        }
    }

    @Override
    public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) throws RemoteException {
        checkPermissions();
        ArrayList<Integer> foundInstances = new ArrayList<Integer>();

        for(Integer nanoAppInstance : mNanoAppHash.keySet()) {
            NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstance);

            if(filter.testMatch(info)){
                foundInstances.add(nanoAppInstance);
            }
        }

        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }

        return retArray;
    }

    @Override
    public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage msg)
            throws RemoteException {
        checkPermissions();
        int[] msgHeader = new int[8];
        msgHeader[0] = ContextHubManager.MSG_DATA_SEND;
        msgHeader[1] = hubHandle;
        msgHeader[2] = nanoAppHandle;
        msgHeader[3] = msg.getMsgType();
        msgHeader[4] = msg.getVersion();

        return nativeSendMessage(msgHeader, msg.getData());
    }

    private void checkPermissions() {
        mContext.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }
}

