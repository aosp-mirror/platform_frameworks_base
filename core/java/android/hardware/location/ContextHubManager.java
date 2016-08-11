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
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
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
    private final IContextHubService mService;
    private Callback mCallback;
    private Handler mCallbackHandler;

    /**
     * @deprecated Use {@code mCallback} instead.
     */
    @Deprecated
    private ICallback mLocalCallback;

    /**
     * An interface to receive asynchronous communication from the context hub.
     */
    public abstract static class Callback {
        protected Callback() {}

        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
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
     * @deprecated Use {@link Callback} instead.
     * @hide
     */
    @Deprecated
    public interface ICallback {
        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        void onMessageReceipt(int hubHandle, int nanoAppHandle, ContextHubMessage message);
    }

    /**
     * Get a handle to all the context hubs in the system
     * @return array of context hub handles
     */
    public int[] getContextHubHandles() {
        try {
            return mService.getContextHubHandles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return mService.getContextHubInfo(hubHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return mService.loadNanoApp(hubHandle, app);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unload a specified nanoApp
     *
     * @param nanoAppHandle handle of the nanoApp to load
     *
     * @return int  0 on success, -1 otherwise
     */
    public int unloadNanoApp(int nanoAppHandle) {
        try {
            return mService.unloadNanoApp(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return mService.getNanoAppInstanceInfo(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return mService.findNanoAppOnHub(hubHandle, filter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        try {
            return mService.sendMessage(hubHandle, nanoAppHandle, message);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     */
    public int registerCallback(Callback callback) {
        return registerCallback(callback, null);
    }

    /**
     * @deprecated Use {@link #registerCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public int registerCallback(ICallback callback) {
        if (mLocalCallback != null) {
            Log.w(TAG, "Max number of local callbacks reached!");
            return -1;
        }
        mLocalCallback = callback;
        return 0;
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     * @param handler Handler object
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     */
    public int registerCallback(Callback callback, Handler handler) {
        synchronized(this) {
            if (mCallback != null) {
                Log.w(TAG, "Max number of callbacks reached!");
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
     * @see Callback
     *
     * @param callback method to deregister
     *
     * @return int 0 on success, -1 otherwise
     */
    public int unregisterCallback(Callback callback) {
      synchronized(this) {
          if (callback != mCallback) {
              Log.w(TAG, "Cannot recognize callback!");
              return -1;
          }

          mCallback = null;
          mCallbackHandler = null;
      }
      return 0;
    }

    /**
     * @deprecated Use {@link #unregisterCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public synchronized int unregisterCallback(ICallback callback) {
        if (callback != mLocalCallback) {
            Log.w(TAG, "Cannot recognize local callback!");
            return -1;
        }
        mLocalCallback = null;
        return 0;
    }

    private final IContextHubCallback.Stub mClientCallback = new IContextHubCallback.Stub() {
        @Override
        public void onMessageReceipt(final int hubId, final int nanoAppId,
                final ContextHubMessage message) {
            if (mCallback != null) {
                synchronized(this) {
                    final Callback callback = mCallback;
                    Handler handler = mCallbackHandler == null ?
                            new Handler(mMainLooper) : mCallbackHandler;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onMessageReceipt(hubId, nanoAppId, message);
                        }
                    });
                }
            } else if (mLocalCallback != null) {
                // we always ensure that mCallback takes precedence, because mLocalCallback is only
                // for internal compatibility
                synchronized (this) {
                    mLocalCallback.onMessageReceipt(hubId, nanoAppId, message);
                }
            } else {
                Log.d(TAG, "Context hub manager client callback is NULL");
            }
        }
    };

    /** @throws ServiceNotFoundException
     * @hide */
    public ContextHubManager(Context context, Looper mainLooper) throws ServiceNotFoundException {
        mMainLooper = mainLooper;
        mService = IContextHubService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(ContextHubService.CONTEXTHUB_SERVICE));

        try {
            mService.registerCallback(mClientCallback);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not register callback:" + e);
        }
    }
}
