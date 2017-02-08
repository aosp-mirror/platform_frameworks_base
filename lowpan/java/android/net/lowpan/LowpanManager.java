/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AndroidException;
import android.util.Log;
import java.util.HashMap;

/**
 * Manager object for looking up LoWPAN interfaces.
 *
 * @hide
 */
//@SystemApi
public class LowpanManager {
    private static final String TAG = LowpanManager.class.getSimpleName();

    //////////////////////////////////////////////////////////////////////////
    // Public Classes

    /** @hide */
    //@SystemApi
    public abstract static class Callback {
        public void onInterfaceAdded(LowpanInterface lowpan_interface) {}

        public void onInterfaceRemoved(LowpanInterface lowpan_interface) {}
    }

    //////////////////////////////////////////////////////////////////////////
    // Instance Variables

    private ILowpanManager mManager;
    private HashMap<Integer, ILowpanManagerListener> mListenerMap = new HashMap<>();

    //////////////////////////////////////////////////////////////////////////

    private static LowpanManager sSingletonInstance;

    //////////////////////////////////////////////////////////////////////////
    // Static Methods

    /** Returns a reference to the LowpanManager object, allocating it if necessary. */
    public static LowpanManager getManager() {
        return from(null);
    }

    public static LowpanManager from(Context context) {
        // TODO: Actually get this from the context!

        if (sSingletonInstance == null) {
            sSingletonInstance = new LowpanManager();
        }
        return sSingletonInstance;
    }

    //////////////////////////////////////////////////////////////////////////
    // Constructors

    /**
     * Private LowpanManager constructor. Since we are a singleton, we do not allow external
     * construction.
     */
    private LowpanManager() {}

    //////////////////////////////////////////////////////////////////////////
    // Private Methods

    /**
     * Returns a reference to the ILowpanManager interface, provided by the LoWPAN Manager Service.
     */
    @Nullable
    private ILowpanManager getILowpanManager() {
        ILowpanManager manager = mManager;
        if (manager == null) {
            IBinder serviceBinder =
                    new ServiceManager().getService(ILowpanManager.LOWPAN_SERVICE_NAME);
            mManager = manager = ILowpanManager.Stub.asInterface(serviceBinder);

            // Add any listeners
            synchronized (mListenerMap) {
                for (Integer hashObj : mListenerMap.keySet()) {
                    try {
                        manager.addListener(mListenerMap.get(hashObj));
                    } catch (RemoteException x) {
                        // Consider any failure here as implying the manager is defunct
                        mManager = manager = null;
                    }
                }
            }
        }
        return manager;
    }

    //////////////////////////////////////////////////////////////////////////
    // Public Methods

    /**
     * Returns a reference to the requested LowpanInterface object. If the given interface doesn't
     * exist, or it is not a LoWPAN interface, returns null.
     */
    @Nullable
    public LowpanInterface getInterface(@NonNull String name) {
        LowpanInterface ret = null;
        ILowpanManager manager = getILowpanManager();

        // Maximum number of tries is two. We should only try
        // more than once if our manager has died or there
        // was some sort of AIDL buffer full event.
        for (int i = 0; i < 2 && manager != null; i++) {
            try {
                ILowpanInterface iface = manager.getInterface(name);
                if (iface != null) {
                    ret = LowpanInterface.getInterfaceFromBinder(iface.asBinder());
                }
                break;
            } catch (RemoteException x) {
                // In all of the cases when we get this exception, we reconnect and try again
                mManager = null;
                manager = getILowpanManager();
            }
        }
        return ret;
    }

    /**
     * Returns a reference to the first registered LowpanInterface object. If there are no LoWPAN
     * interfaces registered, returns null.
     */
    @Nullable
    public LowpanInterface getInterface() {
        String[] ifaceList = getInterfaceList();
        if (ifaceList != null && ifaceList.length > 0) {
            return getInterface(ifaceList[0]);
        }
        return null;
    }

    /**
     * Returns a string array containing the names of LoWPAN interfaces. This list may contain fewer
     * interfaces if the calling process does not have permissions to see individual interfaces.
     */
    @NonNull
    public String[] getInterfaceList() {
        ILowpanManager manager = getILowpanManager();

        if (manager != null) {
            try {
                return manager.getInterfaceList();

            } catch (RemoteException x) {
                // In all of the cases when we get this exception, we reconnect and try again
                mManager = null;
                try {
                    manager = getILowpanManager();
                    if (manager != null) {
                        return manager.getInterfaceList();
                    }
                } catch (RemoteException ex) {
                    // Something weird is going on, so we log it
                    // and fall back thru to returning an empty array.
                    Log.e(TAG, ex.toString());
                    mManager = null;
                }
            }
        }

        // Return empty list if we have no service.
        return new String[0];
    }

    /**
     * Registers a callback object to receive notifications when LoWPAN interfaces are added or
     * removed.
     *
     * @hide
     */
    public void registerCallback(@NonNull Callback cb, @Nullable Handler handler)
            throws LowpanException {
        ILowpanManagerListener.Stub listenerBinder =
                new ILowpanManagerListener.Stub() {
                    public void onInterfaceAdded(ILowpanInterface lowpan_interface) {
                        Runnable runnable =
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onInterfaceAdded(
                                                LowpanInterface.getInterfaceFromBinder(
                                                        lowpan_interface.asBinder()));
                                    }
                                };

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }

                    public void onInterfaceRemoved(ILowpanInterface lowpan_interface) {
                        Runnable runnable =
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onInterfaceRemoved(
                                                LowpanInterface.getInterfaceFromBinder(
                                                        lowpan_interface.asBinder()));
                                    }
                                };

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }
                };
        ILowpanManager manager = getILowpanManager();
        if (manager != null) {
            try {
                manager.addListener(listenerBinder);
            } catch (DeadObjectException x) {
                mManager = null;
                // Tickle the ILowpanManager instance, which might
                // get us added back.
                getILowpanManager();
            } catch (Throwable x) {
                LowpanException.throwAsPublicException(x);
            }
        }
        synchronized (mListenerMap) {
            mListenerMap.put(Integer.valueOf(System.identityHashCode(cb)), listenerBinder);
        }
    }

    /** @hide */
    public void registerCallback(@NonNull Callback cb) throws LowpanException {
        registerCallback(cb, null);
    }

    /**
     * Unregisters a previously registered {@link LowpanManager.Callback} object.
     *
     * @hide
     */
    public void unregisterCallback(@NonNull Callback cb) throws AndroidException {
        Integer hashCode = Integer.valueOf(System.identityHashCode(cb));
        ILowpanManagerListener listenerBinder = mListenerMap.get(hashCode);
        if (listenerBinder != null) {
            synchronized (mListenerMap) {
                mListenerMap.remove(hashCode);
            }
            if (getILowpanManager() != null) {
                try {
                    mManager.removeListener(listenerBinder);
                } catch (DeadObjectException x) {
                    mManager = null;
                }
            }
        }
    }
}
