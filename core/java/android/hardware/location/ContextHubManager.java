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

import android.annotation.SystemApi;
import android.hardware.location.NanoAppInstanceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.Manifest;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A class that exposes the Context hubs on a device to
 * applicaions.
 *
 * Please not that this class is not expected to be used by
 * unbundled applications. Also, calling applications are
 * expected to have LOCTION_HARDWARE premissions to use this
 * class.
 *
 * @hide
 */
@SystemApi
public final class ContextHubManager {

    private static final String TAG = "ContextHubManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission '"
            + HARDWARE_PERMISSION + "' not granted to access ContextHub Hardware";

    private Context mContext;
    private IContextHubService mContextHubService;
    private boolean mContextHubConnected;

    /**
     * A special context hub identifer meaning any possible hub on
     * the system.
     */
    public static final int ANY_HUB       = -1;
    /**
     * A constant denoting a message to load a a Nano App
     */
    public static final int MSG_LOAD_NANO_APP   = 1;
    /**
     * A constant denoting a message to unload a a Nano App
     */
    public static final int MSG_UNLOAD_NANO_APP = 2;
    /**
     * A constant denoting a message to send a message
     */
    public static final int MSG_DATA_SEND       = 3;


    /**
     * Get a handle to all the context hubs in the system
     * @return array of context hub handles
     */
    public int[] getContexthubHandles() {
        int[] retVal = null;
        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.getContextHubHandles();
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch context hub handles :" + e.toString());
            }
        }
        return retVal;
    }

    /**
     * Get more information about a specific hub.
     *
     * @param contexthubHandle Handle of context hub
     *
     * @return ContextHubInfo  returned information about the hub
     *
     * @see ContextHubInfo
     */
    public ContextHubInfo getContexthubInfo(int contexthubHandle) {
        ContextHubInfo retVal = null;
        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.getContextHubInfo(contexthubHandle);
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch context hub info :" + e.toString());
            }
        }

        return(retVal);
    }

    /**
     * Load a nanoapp on a specified context hub
     *
     * @param hubHandle handle of context hub to load the app on.
     * @param app the nanoApp to load on the hub
     *
     * @return int nanoAppInstance of the loaded nanoApp on success,
     *         -1 otherwise
     *
     * @see NanoApp
     */
    public int loadNanoApp(int hubHandle, NanoApp app) {
        int retVal = -1;

        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.loadNanoApp(hubHandle, app);
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch load nanoApp :" + e.toString());
            }
        }

        return retVal;
    }

    /**
     * Unload a specified nanoApp
     *
     * @param nanoAppInstanceHandle handle of the nanoApp to load
     *
     * @return int  0 on success, -1 otherewise
     */
    public int unloadNanoApp(int nanoAppInstanceHandle) {
        int retVal = -1;

        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.unloadNanoApp(nanoAppInstanceHandle);
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch unload nanoApp :" + e.toString());
            }
        }

        return retVal;
    }

    /**
     * get information about the nano app instance
     *
     * @param nanoAppInstanceHandle handle of the nanoAppInstance
     *
     * @return NanoAppInstanceInfo Inforamtion about the nano app
     *         instance.
     *
     * @see NanoAppInstanceInfo
     */
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppInstanceHandle) {
        NanoAppInstanceInfo retVal = null;

        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.getNanoAppInstanceInfo(nanoAppInstanceHandle);
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch nanoApp info :" + e.toString());
            }
        }

        return retVal;
    }

    /**
     * Find a specified nano app on the system
     *
     * @param hubHandle handle of hub to search for nano app
     * @param filter filter specifying the search criteria for app
     *
     * @see NanoAppFilter
     *
     * @return Integer[] Array of handles to any found nano apps
     */
    public Integer[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) {
        int[] temp;
        Integer[] retVal = null;

        if(mContextHubConnected) {
            try {
                temp = mContextHubService.findNanoAppOnHub(hubHandle, filter);
                retVal = new Integer[temp.length];
                for (int i = 0; i < temp.length; i++) {
                    retVal[i] = temp[i];
                }
            }catch (RemoteException e) {
                Log.e (TAG, "Could not query nanoApp instance :" + e.toString());
            }
        }

        return retVal;
    }

    /**
     * Send a message to a spcific nano app instance on a context
     * hub
     *
     *
     * @param hubHandle handle of the hub to send the message to
     * @param nanoAppHandle  handle of the nano app to send to
     * @param msg Message to be sent
     *
     * @see ContextHubMessage
     *
     * @return int 0 on success, -1 otherwise
     */
    public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage msg) {
        int retVal = -1;

        if(mContextHubConnected) {
            try {
                retVal = mContextHubService.sendMessage(hubHandle, nanoAppHandle, msg);
            }catch (RemoteException e) {
                Log.e (TAG, "Could not fetch send message :" + e.toString());
            }
        }

        return retVal;
    }

    private void checkPermissions() {
        mContext.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }

    private IContextHubCallback.Stub mClientCallback = new IContextHubCallback.Stub() {
        @Override
        public void onMessageReceipt(int hubId, int nanoAppId, ContextHubMessage msg) throws RemoteException {

        }
    };

    private ContextHubManager(Context context) {
        checkPermissions();
        mContext = context;
        mContextHubConnected = false;
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mContextHubService = IContextHubService.Stub.asInterface(service);
            mContextHubConnected = true;

            // Register our Callback
            try {
                mContextHubService.registerCallBack(mClientCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register callback with context hub service :" + e.toString());
            }
            Log.d(TAG, "contexthub manager connected to " + name.toString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mContextHubService = null;
            mContextHubConnected = false;
            Log.d(TAG, "contexthub manager disconnected from " + name.toString());
        }
    };

}
