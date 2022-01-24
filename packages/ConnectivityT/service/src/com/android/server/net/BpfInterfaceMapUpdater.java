/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.net;

import android.content.Context;
import android.net.INetd;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.Struct.U32;

/**
 * Monitor interface added (without removed) and right interface name and its index to bpf map.
 */
public class BpfInterfaceMapUpdater {
    private static final String TAG = BpfInterfaceMapUpdater.class.getSimpleName();
    // This is current path but may be changed soon.
    private static final String IFACE_INDEX_NAME_MAP_PATH =
            "/sys/fs/bpf/map_netd_iface_index_name_map";
    private final IBpfMap<U32, InterfaceMapValue> mBpfMap;
    private final INetd mNetd;
    private final Handler mHandler;
    private final Dependencies mDeps;

    public BpfInterfaceMapUpdater(Context ctx, Handler handler) {
        this(ctx, handler, new Dependencies());
    }

    @VisibleForTesting
    public BpfInterfaceMapUpdater(Context ctx, Handler handler, Dependencies deps) {
        mDeps = deps;
        mBpfMap = deps.getInterfaceMap();
        mNetd = deps.getINetd(ctx);
        mHandler = handler;
    }

    /**
     * Dependencies of BpfInerfaceMapUpdater, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Create BpfMap for updating interface and index mapping. */
        public IBpfMap<U32, InterfaceMapValue> getInterfaceMap() {
            try {
                return new BpfMap<>(IFACE_INDEX_NAME_MAP_PATH, BpfMap.BPF_F_RDWR,
                    U32.class, InterfaceMapValue.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create interface map: " + e);
                return null;
            }
        }

        /** Get InterfaceParams for giving interface name. */
        public InterfaceParams getInterfaceParams(String ifaceName) {
            return InterfaceParams.getByName(ifaceName);
        }

        /** Get INetd binder object. */
        public INetd getINetd(Context ctx) {
            return INetd.Stub.asInterface((IBinder) ctx.getSystemService(Context.NETD_SERVICE));
        }
    }

    /**
     * Start listening interface update event.
     * Query current interface names before listening.
     */
    public void start() {
        mHandler.post(() -> {
            if (mBpfMap == null) {
                Log.wtf(TAG, "Fail to start: Null bpf map");
                return;
            }

            try {
                // TODO: use a NetlinkMonitor and listen for RTM_NEWLINK messages instead.
                mNetd.registerUnsolicitedEventListener(new InterfaceChangeObserver());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Unable to register netd UnsolicitedEventListener, " + e);
            }

            final String[] ifaces;
            try {
                // TODO: use a netlink dump to get the current interface list.
                ifaces = mNetd.interfaceGetList();
            } catch (RemoteException | ServiceSpecificException e) {
                Log.wtf(TAG, "Unable to query interface names by netd, " + e);
                return;
            }

            for (String ifaceName : ifaces) {
                addInterface(ifaceName);
            }
        });
    }

    private void addInterface(String ifaceName) {
        final InterfaceParams iface = mDeps.getInterfaceParams(ifaceName);
        if (iface == null) {
            Log.e(TAG, "Unable to get InterfaceParams for " + ifaceName);
            return;
        }

        try {
            mBpfMap.updateEntry(new U32(iface.index), new InterfaceMapValue(ifaceName));
        } catch (ErrnoException e) {
            Log.e(TAG, "Unable to update entry for " + ifaceName + ", " + e);
        }
    }

    private class InterfaceChangeObserver extends BaseNetdUnsolicitedEventListener {
        @Override
        public void onInterfaceAdded(String ifName) {
            mHandler.post(() -> addInterface(ifName));
        }
    }
}
