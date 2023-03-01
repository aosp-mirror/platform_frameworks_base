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

package com.android.server.companion.transport;

import static android.Manifest.permission.DELIVER_COMPANION_MESSAGES;

import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_PERMISSION_RESTORE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressLint("LongLogTag")
public class CompanionTransportManager {
    private static final String TAG = "CDM_CompanionTransportManager";
    private static final boolean DEBUG = false;

    private boolean mSecureTransportEnabled = true;

    private static boolean isRequest(int message) {
        return (message & 0xFF000000) == 0x63000000;
    }

    private static boolean isResponse(int message) {
        return (message & 0xFF000000) == 0x33000000;
    }

    public interface Listener {
        void onRequestPermissionRestore(byte[] data);
    }

    private final Context mContext;

    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();

    @Nullable
    private Listener mListener;

    public CompanionTransportManager(Context context) {
        mContext = context;
    }

    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * For the moment, we only offer transporting of system data to built-in
     * companion apps; future work will improve the security model to support
     * third-party companion apps.
     */
    private void enforceCallerCanTransportSystemData(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(DELIVER_COMPANION_MESSAGES, TAG);

        try {
            final ApplicationInfo info = mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0, userId);
            final int instrumentationUid = LocalServices.getService(ActivityManagerInternal.class)
                    .getInstrumentationSourceUid(Binder.getCallingUid());
            if (!Build.isDebuggable() && !info.isSystemApp()
                    && instrumentationUid == android.os.Process.INVALID_UID) {
                throw new SecurityException("Transporting of system data currently only available "
                        + "to built-in companion apps or tests");
            }
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public void attachSystemDataTransport(String packageName, int userId, int associationId,
            ParcelFileDescriptor fd) {
        enforceCallerCanTransportSystemData(packageName, userId);
        synchronized (mTransports) {
            if (mTransports.contains(associationId)) {
                detachSystemDataTransport(packageName, userId, associationId);
            }

            final Transport transport;
            if (isSecureTransportEnabled(associationId)) {
                transport = new SecureTransport(associationId, fd, mContext, mListener);
            } else {
                transport = new RawTransport(associationId, fd, mContext, mListener);
            }

            transport.start();
            mTransports.put(associationId, transport);
        }
    }

    public void detachSystemDataTransport(String packageName, int userId, int associationId) {
        enforceCallerCanTransportSystemData(packageName, userId);
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport != null) {
                mTransports.delete(associationId);
                transport.stop();
            }
        }
    }

    public Future<?> requestPermissionRestore(int associationId, byte[] data) {
        synchronized (mTransports) {
            final Transport transport = mTransports.get(associationId);
            if (transport == null) {
                return CompletableFuture.failedFuture(new IOException("Missing transport"));
            }

            return transport.requestForResponse(MESSAGE_REQUEST_PERMISSION_RESTORE, data);
        }
    }

    /**
     * @hide
     */
    public void enableSecureTransport(boolean enabled) {
        this.mSecureTransportEnabled = enabled;
    }

    private boolean isSecureTransportEnabled(int associationId) {
        boolean enabled = !Build.IS_DEBUGGABLE || mSecureTransportEnabled;

        // TODO: version comparison logic
        return enabled;
    }
}
