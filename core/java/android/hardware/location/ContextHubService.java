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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * @hide
 */
public class ContextHubService extends Service {

    private static final String TAG = "ContextHubService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static ContextHubService sSingletonInstance;
    private static final Object sSingletonInstanceLock = new Object();

    private HashMap<Integer, ContextHubInfo> mHubHash;
    private HashMap<Integer, NanoAppInstanceInfo> mNanoAppHash;
    private ContextHubInfo[] mContexthubInfo;


    private native int nativeSendMessage(int[] header, byte[] data);
    private native ContextHubInfo[] nativeInitialize();

    private int onMessageReceipt(int[] header, byte[] data) {
        return 0;
    }
    private void initialize() {
        mContexthubInfo = nativeInitialize();

        mHubHash = new HashMap<Integer, ContextHubInfo>();

        for (int i = 0; i < mContexthubInfo.length; i++) {
            mHubHash.put(i + 1, mContexthubInfo[i]); // Avoiding zero
        }
    }

    private ContextHubService(Context context) {
        initialize();
        Log.d(TAG, "Created from " + context.toString());
    }

    public static ContextHubService getInstance(Context context) {
        synchronized (sSingletonInstanceLock) {
            if (sSingletonInstance == null) {
                sSingletonInstance = new ContextHubService(context);
            }
            return sSingletonInstance;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final IContextHubService.Stub mBinder = new IContextHubService.Stub() {

        private  IContextHubCallback callback;

        @Override
        public int registerCallBack(IContextHubCallback callback) throws RemoteException{
            this.callback = callback;
            return 0;
        }

        @Override
        public int[] getContextHubHandles() throws RemoteException {
            int [] returnArray = new int[mHubHash.size()];
            int i = 0;
            for (int key : mHubHash.keySet()) {
                // Add any filtering here
                returnArray[i] = key;
                i++;
            }
            return returnArray;
        }

        @Override
        public ContextHubInfo getContextHubInfo(int contexthubHandle) throws RemoteException {
            return mHubHash.get(contexthubHandle);
        }

        @Override
        public int loadNanoApp(int hubHandle, NanoApp app) throws RemoteException {
            if (!mHubHash.containsKey(hubHandle)) {
                return -1;
            } else {
                // Call Native interface here
                int[] msgHeader = new int[8];
                msgHeader[0] = ContextHubManager.MSG_LOAD_NANO_APP;
                msgHeader[1] = app.getAppId();
                msgHeader[2] = app.getAppVersion();
                msgHeader[3] = 0; // LOADING_HINTS
                msgHeader[4] = hubHandle;

                int handle = nativeSendMessage(msgHeader, app.getAppBinary());

                // if successful, add an entry to mNanoAppHash

                if(handle > 0) {
                    return 0;
                } else {

                    return -1;
                }
            }
        }

        @Override
        public int unloadNanoApp(int nanoAppInstanceHandle) throws RemoteException {
            if(!mNanoAppHash.containsKey(nanoAppInstanceHandle)) {
                return -1;
            } else {
                NanoAppInstanceInfo info = mNanoAppHash.get(nanoAppInstanceHandle);
                // Call Native interface here
                int[] msgHeader = new int[8];
                msgHeader[0] = ContextHubManager.MSG_UNLOAD_NANO_APP;
                msgHeader[1] = info.getContexthubId();
                msgHeader[2] = info.getHandle();

                int result = nativeSendMessage(msgHeader, null);
                // if successful, remove the entry in mNanoAppHash
                if(result == 0) {
                    mNanoAppHash.remove(nanoAppInstanceHandle);
                }
                return(result);
            }
        }

        @Override
        public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppInstanceHandle) throws RemoteException {
            // This assumes that all the nanoAppInfo is current. This is reasonable
            // for the use cases for tightly controlled nanoApps.
            //
            if(!mNanoAppHash.containsKey(nanoAppInstanceHandle)) {
                return(mNanoAppHash.get(nanoAppInstanceHandle));
            } else {
                return null;
            }
        }

        @Override
        public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) throws RemoteException {
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
        public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage msg) throws RemoteException {
            int[] msgHeader = new int[8];
            msgHeader[0] = ContextHubManager.MSG_DATA_SEND;
            msgHeader[1] = hubHandle;
            msgHeader[2] = nanoAppHandle;
            msgHeader[3] = msg.getMsgType();
            msgHeader[4] = msg.getVersion();

            return (nativeSendMessage(msgHeader, msg.getData()));
        }
    };
}
