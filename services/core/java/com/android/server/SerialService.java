/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * See the License for the specific language governing permissions an
 * limitations under the License.
 */

package com.android.server;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.content.Context;
import android.hardware.ISerialManager;
import android.hardware.SerialManagerInternal;
import android.os.ParcelFileDescriptor;
import android.os.PermissionEnforcer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class SerialService extends ISerialManager.Stub {
    private final Context mContext;

    @GuardedBy("mSerialPorts")
    private final LinkedHashMap<String, Supplier<ParcelFileDescriptor>> mSerialPorts =
            new LinkedHashMap<>();

    private static final String PREFIX_VIRTUAL = "virtual:";

    public SerialService(Context context) {
        super(PermissionEnforcer.fromContext(context));
        mContext = context;

        synchronized (mSerialPorts) {
            final String[] serialPorts = getSerialPorts(context);
            for (String serialPort : serialPorts) {
                mSerialPorts.put(serialPort, () -> {
                    return native_open(serialPort);
                });
            }
        }
    }

    private static String[] getSerialPorts(Context context) {
        return context.getResources().getStringArray(
                com.android.internal.R.array.config_serialPorts);
    }

    public static class Lifecycle extends SystemService {
        private SerialService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new SerialService(getContext());
            publishBinderService(Context.SERIAL_SERVICE, mService);
            publishLocalService(SerialManagerInternal.class, mService.mInternal);
        }
    }

    @EnforcePermission(android.Manifest.permission.SERIAL_PORT)
    public String[] getSerialPorts() {
        super.getSerialPorts_enforcePermission();

        synchronized (mSerialPorts) {
            final ArrayList<String> ports = new ArrayList<>();
            for (String path : mSerialPorts.keySet()) {
                if (path.startsWith(PREFIX_VIRTUAL) || new File(path).exists()) {
                    ports.add(path);
                }
            }
            return ports.toArray(new String[ports.size()]);
        }
    }

    @EnforcePermission(android.Manifest.permission.SERIAL_PORT)
    public ParcelFileDescriptor openSerialPort(String path) {
        super.openSerialPort_enforcePermission();

        synchronized (mSerialPorts) {
            final Supplier<ParcelFileDescriptor> supplier = mSerialPorts.get(path);
            if (supplier != null) {
                return supplier.get();
            } else {
                throw new IllegalArgumentException("Invalid serial port " + path);
            }
        }
    }

    private final SerialManagerInternal mInternal = new SerialManagerInternal() {
        @Override
        public void addVirtualSerialPortForTest(@NonNull String name,
                @NonNull Supplier<ParcelFileDescriptor> supplier) {
            synchronized (mSerialPorts) {
                Preconditions.checkState(!mSerialPorts.containsKey(name),
                        "Port " + name + " already defined");
                Preconditions.checkArgument(name.startsWith(PREFIX_VIRTUAL),
                        "Port " + name + " must be under " + PREFIX_VIRTUAL);
                mSerialPorts.put(name, supplier);
            }
        }

        @Override
        public void removeVirtualSerialPortForTest(@NonNull String name) {
            synchronized (mSerialPorts) {
                Preconditions.checkState(mSerialPorts.containsKey(name),
                        "Port " + name + " not yet defined");
                Preconditions.checkArgument(name.startsWith(PREFIX_VIRTUAL),
                        "Port " + name + " must be under " + PREFIX_VIRTUAL);
                mSerialPorts.remove(name);
            }
        }
    };

    private native ParcelFileDescriptor native_open(String path);
}
