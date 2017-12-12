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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Manager object for looking up LoWPAN interfaces.
 *
 * @hide
 */
// @SystemApi
public class LowpanManager {
    private static final String TAG = LowpanManager.class.getSimpleName();

    /** @hide */
    // @SystemApi
    public abstract static class Callback {
        public void onInterfaceAdded(LowpanInterface lowpanInterface) {}

        public void onInterfaceRemoved(LowpanInterface lowpanInterface) {}
    }

    private final Map<Integer, ILowpanManagerListener> mListenerMap = new HashMap<>();
    private final Map<String, LowpanInterface> mInterfaceCache = new HashMap<>();

    /* This is a WeakHashMap because we don't want to hold onto
     * a strong reference to ILowpanInterface, so that it can be
     * garbage collected if it isn't being used anymore. Since
     * the value class holds onto this specific ILowpanInterface,
     * we also need to have a weak reference to the value.
     * This design pattern allows us to skip removal of items
     * from this Map without leaking memory.
     */
    private final Map<ILowpanInterface, WeakReference<LowpanInterface>> mBinderCache =
            new WeakHashMap<>();

    private final ILowpanManager mService;
    private final Context mContext;
    private final Looper mLooper;

    // Static Methods

    public static LowpanManager from(Context context) {
        return (LowpanManager) context.getSystemService(Context.LOWPAN_SERVICE);
    }

    /** @hide */
    public static LowpanManager getManager() {
        IBinder binder = ServiceManager.getService(Context.LOWPAN_SERVICE);

        if (binder != null) {
            ILowpanManager service = ILowpanManager.Stub.asInterface(binder);
            return new LowpanManager(service);
        }

        return null;
    }

    // Constructors

    LowpanManager(ILowpanManager service) {
        mService = service;
        mContext = null;
        mLooper = null;
    }

    /**
     * Create a new LowpanManager instance. Applications will almost always want to use {@link
     * android.content.Context#getSystemService Context.getSystemService()} to retrieve the standard
     * {@link android.content.Context#LOWPAN_SERVICE Context.LOWPAN_SERVICE}.
     *
     * @param context the application context
     * @param service the Binder interface
     * @param looper the default Looper to run callbacks on
     * @hide - hide this because it takes in a parameter of type ILowpanManager, which is a system
     *     private class.
     */
    public LowpanManager(Context context, ILowpanManager service, Looper looper) {
        mContext = context;
        mService = service;
        mLooper = looper;
    }

    /** @hide */
    @Nullable
    public LowpanInterface getInterface(@NonNull ILowpanInterface ifaceService) {
        LowpanInterface iface = null;

        try {
            synchronized (mBinderCache) {
                if (mBinderCache.containsKey(ifaceService)) {
                    iface = mBinderCache.get(ifaceService).get();
                }

                if (iface == null) {
                    String ifaceName = ifaceService.getName();

                    iface = new LowpanInterface(mContext, ifaceService, mLooper);

                    synchronized (mInterfaceCache) {
                        mInterfaceCache.put(iface.getName(), iface);
                    }

                    mBinderCache.put(ifaceService, new WeakReference(iface));

                    /* Make sure we remove the object from the
                     * interface cache if the associated service
                     * dies.
                     */
                    ifaceService
                            .asBinder()
                            .linkToDeath(
                                    new IBinder.DeathRecipient() {
                                        @Override
                                        public void binderDied() {
                                            synchronized (mInterfaceCache) {
                                                LowpanInterface iface =
                                                        mInterfaceCache.get(ifaceName);

                                                if ((iface != null)
                                                        && (iface.getService() == ifaceService)) {
                                                    mInterfaceCache.remove(ifaceName);
                                                }
                                            }
                                        }
                                    },
                                    0);
                }
            }
        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }

        return iface;
    }

    /**
     * Returns a reference to the requested LowpanInterface object. If the given interface doesn't
     * exist, or it is not a LoWPAN interface, returns null.
     */
    @Nullable
    public LowpanInterface getInterface(@NonNull String name) {
        LowpanInterface iface = null;

        try {
            /* This synchronized block covers both branches of the enclosed
             * if() statement in order to avoid a race condition. Two threads
             * calling getInterface() with the same name would race to create
             * the associated LowpanInterface object, creating two of them.
             * Having the whole block be synchronized avoids that race.
             */
            synchronized (mInterfaceCache) {
                if (mInterfaceCache.containsKey(name)) {
                    iface = mInterfaceCache.get(name);

                } else {
                    ILowpanInterface ifaceService = mService.getInterface(name);

                    if (ifaceService != null) {
                        iface = getInterface(ifaceService);
                    }
                }
            }
        } catch (RemoteException x) {
            throw x.rethrowFromSystemServer();
        }

        return iface;
    }

    /**
     * Returns a reference to the first registered LowpanInterface object. If there are no LoWPAN
     * interfaces registered, returns null.
     */
    @Nullable
    public LowpanInterface getInterface() {
        String[] ifaceList = getInterfaceList();
        if (ifaceList.length > 0) {
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
        try {
            return mService.getInterfaceList();
        } catch (RemoteException x) {
            throw x.rethrowFromSystemServer();
        }
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
                    private Handler mHandler;

                    {
                        if (handler != null) {
                            mHandler = handler;
                        } else if (mLooper != null) {
                            mHandler = new Handler(mLooper);
                        } else {
                            mHandler = new Handler();
                        }
                    }

                    @Override
                    public void onInterfaceAdded(ILowpanInterface ifaceService) {
                        Runnable runnable =
                                () -> {
                                    LowpanInterface iface = getInterface(ifaceService);

                                    if (iface != null) {
                                        cb.onInterfaceAdded(iface);
                                    }
                                };

                        mHandler.post(runnable);
                    }

                    @Override
                    public void onInterfaceRemoved(ILowpanInterface ifaceService) {
                        Runnable runnable =
                                () -> {
                                    LowpanInterface iface = getInterface(ifaceService);

                                    if (iface != null) {
                                        cb.onInterfaceRemoved(iface);
                                    }
                                };

                        mHandler.post(runnable);
                    }
                };
        try {
            mService.addListener(listenerBinder);
        } catch (RemoteException x) {
            throw x.rethrowFromSystemServer();
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
    public void unregisterCallback(@NonNull Callback cb) {
        Integer hashCode = Integer.valueOf(System.identityHashCode(cb));
        ILowpanManagerListener listenerBinder = null;

        synchronized (mListenerMap) {
            listenerBinder = mListenerMap.get(hashCode);
            mListenerMap.remove(hashCode);
        }

        if (listenerBinder != null) {
            try {
                mService.removeListener(listenerBinder);
            } catch (RemoteException x) {
                throw x.rethrowFromSystemServer();
            }
        } else {
            throw new RuntimeException("Attempt to unregister an unknown callback");
        }
    }
}
