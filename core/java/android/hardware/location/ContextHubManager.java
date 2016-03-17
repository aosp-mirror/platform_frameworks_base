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
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * A class that exposes the Context hubs on a device to applications.
 *
 * Please note that this class is not expected to be used by unbundled applications. Also, calling
 * applications are expected to have LOCATION_HARDWARE permissions to use this class.
 *
 * @hide
 */
@SystemApi
public final class ContextHubManager {

    private static final String TAG = "ContextHubManager";

    private final Looper mMainLooper;
    private IContextHubService mContextHubService;
    private ContextHubCallback mCallback;
    private Handler mCallbackHandler;

    /**
     * A special context hub identifier meaning any possible hub on the system.
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
     * An interface to receive asynchronous communication from the context hub.
     */
    public abstract static class ContextHubCallback {
        protected ContextHubCallback() {}

        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for the app that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        public abstract void onMessageReceipt(
                int hubHandle,
                int nanoAppHandle,
                ContextHubMessage message);
    }

    /**
     * Get a handle to all the context hubs in the system
     * @return array of context hub handles
     */
    public int[] getContextHubHandles() {
        int[] retVal = null;
        try {
            retVal = getBinder().getContextHubHandles();
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch context hub handles : " + e);
        }
        return retVal;
    }

    /**
     * Get more information about a specific hub.
     *
     * @param hubHandle Handle (system-wide unique identifier) of a context hub.
     * @return ContextHubInfo Information about the requested context hub.
     *
     * @see ContextHubInfo
     */
    public ContextHubInfo getContextHubInfo(int hubHandle) {
        ContextHubInfo retVal = null;
        try {
            retVal = getBinder().getContextHubInfo(hubHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch context hub info :" + e);
        }

        return retVal;
    }

    /**
     * Load a nano app on a specified context hub.
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
        if (app == null) {
            return retVal;
        }

        try {
            retVal = getBinder().loadNanoApp(hubHandle, app);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch load nanoApp :" + e);
        }

        return retVal;
    }

    /**
     * Unload a specified nanoApp
     *
     * @param nanoAppHandle handle of the nanoApp to load
     *
     * @return int  0 on success, -1 otherwise
     */
    public int unloadNanoApp(int nanoAppHandle) {
        int retVal = -1;

        try {
            retVal = getBinder().unloadNanoApp(nanoAppHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch unload nanoApp :" + e);
        }

        return retVal;
    }

    /**
     * get information about the nano app instance
     *
     * @param nanoAppHandle handle of the nanoAppInstance
     * @return NanoAppInstanceInfo Information about the nano app instance.
     *
     * @see NanoAppInstanceInfo
     */
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) {
        NanoAppInstanceInfo retVal = null;

        try {
            retVal = getBinder().getNanoAppInstanceInfo(nanoAppHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch nanoApp info :" + e);
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
     * @return int[] Array of handles to any found nano apps
     */
    public int[] findNanoAppOnHub(int hubHandle, NanoAppFilter filter) {
        int[] retVal = null;
        try {
            retVal = getBinder().findNanoAppOnHub(hubHandle, filter);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not query nanoApp instance :" + e);
        }
        return retVal;
    }

    /**
     * Send a message to a specific nano app instance on a context hub.
     *
     * @param hubHandle handle of the hub to send the message to
     * @param nanoAppHandle  handle of the nano app to send to
     * @param message Message to be sent
     *
     * @see ContextHubMessage
     *
     * @return int 0 on success, -1 otherwise
     */
    public int sendMessage(int hubHandle, int nanoAppHandle, ContextHubMessage message) {
        int retVal = -1;

        try {
            retVal = getBinder().sendMessage(hubHandle, nanoAppHandle, message);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not fetch send message :" + e.toString());
        }

        return retVal;
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     *
     * @see ContextHubCallback
     *
     * @return int 0 on success, -1 otherwise
     */
    public int registerContextHubCallback(ContextHubCallback callback) {
        return registerContextHubCallback(callback, null);
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     * @param handler Handler object
     *
     * @see ContextHubCallback
     *
     * @return int 0 on success, -1 otherwise
     */
    public int registerContextHubCallback(ContextHubCallback callback, Handler handler) {
        synchronized(this) {
            if (mCallback != null) {
                Log.e(TAG, "Max number of callbacks reached!");
                return -1;
            }
            mCallback = callback;
            mCallbackHandler = handler;
        }
        return 0;
    }

    /**
     * Unregister a callback for receive messages from the context hub.
     *
     * @see ContextHubCallback
     *
     * @param callback method to deregister
     *
     * @return int 0 on success, -1 otherwise
     */
    public int unregisterContextHubCallback(ContextHubCallback callback) {
      synchronized(this) {
          if (callback != mCallback) {
              Log.e(TAG, "Cannot recognize callback!");
              return -1;
          }

          mCallback = null;
          mCallbackHandler = null;
      }
      return 0;
    }

    private IContextHubCallback.Stub mClientCallback = new IContextHubCallback.Stub() {
        @Override
        public void onMessageReceipt(final int hubId, final int nanoAppId,
                final ContextHubMessage message) {
            if (mCallback != null) {
                synchronized(this) {
                    final ContextHubCallback callback = mCallback;
                    Handler handler = mCallbackHandler == null ?
                            new Handler(mMainLooper) : mCallbackHandler;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onMessageReceipt(hubId, nanoAppId, message);
                        }
                    });
                }
            } else {
                Log.d(TAG, "Context hub manager client callback is NULL");
            }
        }
    };

    /** @hide */
    public ContextHubManager(Context context, Looper mainLooper) {
        mMainLooper = mainLooper;

        IBinder b = ServiceManager.getService(ContextHubService.CONTEXTHUB_SERVICE);
        if (b != null) {
            mContextHubService = IContextHubService.Stub.asInterface(b);

            try {
                getBinder().registerCallback(mClientCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register callback:" + e);
            }

        } else {
            Log.d(TAG, "failed to getService");
        }
    }

    private IContextHubService getBinder() throws RemoteException {
        if (mContextHubService == null) {
            throw new RemoteException("Service not connected.");
        }
        return mContextHubService;
    }
}
