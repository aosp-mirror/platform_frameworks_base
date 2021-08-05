/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceManager;
import android.content.Context;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;


/** @hide */
@SuppressLint("LongLogTag")
public class VirtualDeviceManagerService extends SystemService {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManagerService";
    private final VirtualDeviceManagerImpl mImpl;
    @GuardedBy("mVirtualDevices")
    private final ArrayList<VirtualDeviceImpl> mVirtualDevices = new ArrayList<>();

    public VirtualDeviceManagerService(Context context) {
        super(context);
        mImpl = new VirtualDeviceManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VIRTUAL_DEVICE_SERVICE, mImpl);
    }

    private class VirtualDeviceImpl extends IVirtualDevice.Stub {

        private VirtualDeviceImpl() {}

        @Override
        public void close() {
            synchronized (mVirtualDevices) {
                mVirtualDevices.remove(this);
            }
        }
    }

    class VirtualDeviceManagerImpl extends IVirtualDeviceManager.Stub {

        @Override
        public IVirtualDevice createVirtualDevice() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CREATE_VIRTUAL_DEVICE,
                    "createVirtualDevice");
            VirtualDeviceImpl virtualDevice = new VirtualDeviceImpl();
            synchronized (mVirtualDevices) {
                mVirtualDevices.add(virtualDevice);
            }
            return virtualDevice;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(LOG_TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void dump(@NonNull FileDescriptor fd,
                @NonNull PrintWriter fout,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), LOG_TAG, fout)) {
                return;
            }
            fout.println("Created virtual devices: ");
            synchronized (mVirtualDevices) {
                for (VirtualDeviceImpl virtualDevice : mVirtualDevices) {
                    fout.println(virtualDevice.toString());
                }
            }
        }
    }
}
