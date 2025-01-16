/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context;
import android.hardware.contexthub.HubEndpointInfo;
import android.hardware.contexthub.HubServiceInfo;
import android.hardware.contexthub.IContextHubEndpointDiscoveryCallback;
import android.hardware.location.HubInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

class HubInfoRegistry implements ContextHubHalEndpointCallback.IEndpointLifecycleCallback {
    private static final String TAG = "HubInfoRegistry";

    /** The duration of wakelocks acquired during discovery callbacks */
    private static final long WAKELOCK_TIMEOUT_MILLIS = 5 * 1000;

    private final Object mLock = new Object();

    private final IContextHubWrapper mContextHubWrapper;

    @GuardedBy("mLock")
    private List<HubInfo> mHubsInfo;

    @GuardedBy("mLock")
    private final ArrayMap<HubEndpointInfo.HubEndpointIdentifier, HubEndpointInfo>
            mHubEndpointInfos = new ArrayMap<>();

    /**
     * A wrapper class that is used to store arguments to
     * ContextHubManager.registerEndpointCallback.
     */
    private static class DiscoveryCallback implements IBinder.DeathRecipient {
        private final HubInfoRegistry mHubInfoRegistry;
        private final IContextHubEndpointDiscoveryCallback mCallback;
        private final Optional<Long> mEndpointId;
        private final Optional<String> mServiceDescriptor;

        // True if the binder death recipient fired
        private final AtomicBoolean mBinderDied = new AtomicBoolean(false);

        DiscoveryCallback(
                HubInfoRegistry registry,
                IContextHubEndpointDiscoveryCallback callback,
                long endpointId)
                throws RemoteException {
            mHubInfoRegistry = registry;
            mCallback = callback;
            mEndpointId = Optional.of(endpointId);
            mServiceDescriptor = Optional.empty();
            attachDeathRecipient();
        }

        DiscoveryCallback(
                HubInfoRegistry registry,
                IContextHubEndpointDiscoveryCallback callback,
                String serviceDescriptor)
                throws RemoteException {
            mHubInfoRegistry = registry;
            mCallback = callback;
            mEndpointId = Optional.empty();
            mServiceDescriptor = Optional.of(serviceDescriptor);
            attachDeathRecipient();
        }

        public IContextHubEndpointDiscoveryCallback getCallback() {
            return mCallback;
        }

        /**
         * @param info The hub endpoint info to check
         * @return true if info matches
         */
        public boolean isMatch(HubEndpointInfo info) {
            if (mBinderDied.get()) {
                Log.w(TAG, "Callback died, isMatch returning false");
                return false;
            }
            if (mEndpointId.isPresent()) {
                return mEndpointId.get() == info.getIdentifier().getEndpoint();
            }
            if (mServiceDescriptor.isPresent()) {
                for (HubServiceInfo serviceInfo : info.getServiceInfoCollection()) {
                    if (mServiceDescriptor.get().equals(serviceInfo.getServiceDescriptor())) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder died for discovery callback");
            mBinderDied.set(true);
            mHubInfoRegistry.unregisterEndpointDiscoveryCallback(mCallback);
        }

        private void attachDeathRecipient() throws RemoteException {
            mCallback.asBinder().linkToDeath(this, 0 /* flags */);
        }
    }

    /* The list of discovery callbacks registered with the service */
    @GuardedBy("mCallbackLock")
    private final List<DiscoveryCallback> mEndpointDiscoveryCallbacks = new ArrayList<>();

    private final Object mCallbackLock = new Object();

    /** Wakelock held while endpoint callbacks are being invoked */
    private final WakeLock mWakeLock;

    HubInfoRegistry(Context context, IContextHubWrapper contextHubWrapper)
            throws InstantiationException {
        mContextHubWrapper = contextHubWrapper;
        try {
            refreshCachedHubs();
            refreshCachedEndpoints();
        } catch (UnsupportedOperationException e) {
            String error = "Failed to update hub and endpoint cache";
            Log.e(TAG, error, e);
            throw new InstantiationException(error);
        }

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            String error = "PowerManager was null";
            Log.e(TAG, error);
            throw new InstantiationError(error);
        }
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setWorkSource(new WorkSource(Process.myUid(), context.getPackageName()));
        mWakeLock.setReferenceCounted(true);
    }

    /** Retrieve the list of hubs available. */
    List<HubInfo> getHubs() {
        synchronized (mLock) {
            return mHubsInfo;
        }
    }

    private void refreshCachedHubs() {
        List<HubInfo> hubInfos;
        try {
            hubInfos = mContextHubWrapper.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Hub info", e);
            hubInfos = Collections.emptyList();
        }

        synchronized (mLock) {
            mHubsInfo = hubInfos;
        }
    }

    private void refreshCachedEndpoints() {
        List<HubEndpointInfo> endpointInfos;
        try {
            endpointInfos = mContextHubWrapper.getEndpoints();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Hub info", e);
            endpointInfos = Collections.emptyList();
        }

        synchronized (mLock) {
            mHubEndpointInfos.clear();
            for (HubEndpointInfo endpointInfo : endpointInfos) {
                mHubEndpointInfos.put(endpointInfo.getIdentifier(), endpointInfo);
            }
        }
    }

    public HubEndpointInfo getEndpointInfo(HubEndpointInfo.HubEndpointIdentifier id) {
        synchronized (mLock) {
            return mHubEndpointInfos.get(id);
        }
    }

    /** Invoked when HAL restarts */
    public void onHalRestart() {
        synchronized (mLock) {
            refreshCachedHubs();
            refreshCachedEndpoints();
        }
    }

    @Override
    public void onEndpointStarted(HubEndpointInfo[] endpointInfos) {
        synchronized (mLock) {
            for (HubEndpointInfo endpointInfo : endpointInfos) {
                mHubEndpointInfos.remove(endpointInfo.getIdentifier());
                mHubEndpointInfos.put(endpointInfo.getIdentifier(), endpointInfo);
            }
        }

        invokeForMatchingEndpoints(
                endpointInfos,
                (cb, infoList) -> {
                    try {
                        cb.onEndpointsStarted(infoList);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Exception while calling onEndpointsStarted", e);
                    }
                });
    }

    @Override
    public void onEndpointStopped(
            HubEndpointInfo.HubEndpointIdentifier[] endpointIds, byte reason) {
        ArrayList<HubEndpointInfo> removedInfoList = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo.HubEndpointIdentifier endpointId : endpointIds) {
                HubEndpointInfo info = mHubEndpointInfos.remove(endpointId);
                if (info != null) {
                    removedInfoList.add(info);
                }
            }
        }

        invokeForMatchingEndpoints(
                removedInfoList.toArray(new HubEndpointInfo[removedInfoList.size()]),
                (cb, infoList) -> {
                    try {
                        cb.onEndpointsStopped(
                                infoList, ContextHubServiceUtil.toAppHubEndpointReason(reason));
                    } catch (RemoteException e) {
                        Log.e(TAG, "Exception while calling onEndpointsStopped", e);
                    }
                });
    }

    /** Return a list of {@link HubEndpointInfo} that represents endpoints with the matching id. */
    public List<HubEndpointInfo> findEndpoints(long endpointIdQuery) {
        List<HubEndpointInfo> searchResult = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo.HubEndpointIdentifier endpointId : mHubEndpointInfos.keySet()) {
                if (endpointId.getEndpoint() == endpointIdQuery) {
                    searchResult.add(mHubEndpointInfos.get(endpointId));
                }
            }
        }
        return searchResult;
    }

    /**
     * Return a list of {@link HubEndpointInfo} that represents endpoints with the matching service.
     */
    public List<HubEndpointInfo> findEndpointsWithService(String serviceDescriptor) {
        List<HubEndpointInfo> searchResult = new ArrayList<>();
        synchronized (mLock) {
            for (HubEndpointInfo endpointInfo : mHubEndpointInfos.values()) {
                for (HubServiceInfo serviceInfo : endpointInfo.getServiceInfoCollection()) {
                    if (serviceDescriptor.equals(serviceInfo.getServiceDescriptor())) {
                        searchResult.add(endpointInfo);
                    }
                }
            }
        }
        return searchResult;
    }

    /* package */
    void registerEndpointDiscoveryCallback(
            long endpointId, IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            checkCallbackAlreadyRegistered(callback);
            try {
                mEndpointDiscoveryCallbacks.add(new DiscoveryCallback(this, callback, endpointId));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while adding discovery callback", e);
            }
        }
    }

    /* package */
    void registerEndpointDiscoveryCallback(
            String serviceDescriptor, IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            checkCallbackAlreadyRegistered(callback);
            try {
                mEndpointDiscoveryCallbacks.add(
                        new DiscoveryCallback(this, callback, serviceDescriptor));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while adding discovery callback", e);
            }
        }
    }

    /* package */
    void unregisterEndpointDiscoveryCallback(IContextHubEndpointDiscoveryCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");
        synchronized (mCallbackLock) {
            Iterator<DiscoveryCallback> iterator = mEndpointDiscoveryCallbacks.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getCallback().asBinder() == callback.asBinder()) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    /* package */
    void onDiscoveryCallbackFinished() {
        releaseWakeLock();
    }

    private void checkCallbackAlreadyRegistered(
            IContextHubEndpointDiscoveryCallback callback) {
        synchronized (mCallbackLock) {
            for (DiscoveryCallback discoveryCallback : mEndpointDiscoveryCallbacks) {
                if (discoveryCallback.mCallback.asBinder() == callback.asBinder()) {
                    throw new IllegalArgumentException("Callback is already registered");
                }
            }
        }
    }

    /**
     * Iterates through all registered discovery callbacks and invokes a given callback for those
     * that match the endpoints the callback is targeted for.
     *
     * @param endpointInfos The list of endpoint infos to check for a match.
     * @param consumer The callback to invoke, which consumes the callback object and the list of
     *     matched endpoint infos.
     */
    private void invokeForMatchingEndpoints(
            HubEndpointInfo[] endpointInfos,
            BiConsumer<IContextHubEndpointDiscoveryCallback, HubEndpointInfo[]> consumer) {
        synchronized (mCallbackLock) {
            Iterator<DiscoveryCallback> iterator = mEndpointDiscoveryCallbacks.iterator();
            while (iterator.hasNext()) {
                DiscoveryCallback discoveryCallback = iterator.next();
                ArrayList<HubEndpointInfo> infoList = new ArrayList<>();
                for (HubEndpointInfo endpointInfo : endpointInfos) {
                    if (discoveryCallback.isMatch(endpointInfo)) {
                        infoList.add(endpointInfo);
                    }
                }

                acquireWakeLock();
                consumer.accept(
                        discoveryCallback.getCallback(),
                        infoList.toArray(new HubEndpointInfo[infoList.size()]));
            }
        }
    }

    private void acquireWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
                });
    }

    private void releaseWakeLock() {
        Binder.withCleanCallingIdentity(
                () -> {
                    if (mWakeLock.isHeld()) {
                        try {
                            mWakeLock.release();
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Releasing the wakelock fails - ", e);
                        }
                    }
                });
    }

    void dump(IndentingPrintWriter ipw) {
        synchronized (mLock) {
            dumpLocked(ipw);
        }
    }

    @GuardedBy("mLock")
    private void dumpLocked(IndentingPrintWriter ipw) {
        ipw.println(TAG);

        ipw.increaseIndent();
        ipw.println("Hubs");
        for (HubInfo hubInfo : mHubsInfo) {
            ipw.println(hubInfo);
        }
        ipw.decreaseIndent();

        ipw.println();

        ipw.increaseIndent();
        ipw.println("Endpoints");
        for (HubEndpointInfo endpointInfo : mHubEndpointInfos.values()) {
            ipw.println(endpointInfo);
        }
        ipw.decreaseIndent();

        ipw.println();
    }
}
