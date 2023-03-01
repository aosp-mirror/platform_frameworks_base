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
import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_PLATFORM_INFO;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.companion.transport.Transport.Listener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressLint("LongLogTag")
public class CompanionTransportManager {
    private static final String TAG = "CDM_CompanionTransportManager";
    private static final boolean DEBUG = false;

    private static final int SECURE_CHANNEL_AVAILABLE_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    private static final int NON_ANDROID = -1;

    private boolean mSecureTransportEnabled = true;

    private static boolean isRequest(int message) {
        return (message & 0xFF000000) == 0x63000000;
    }

    private static boolean isResponse(int message) {
        return (message & 0xFF000000) == 0x33000000;
    }

    private final Context mContext;

    @GuardedBy("mTransports")
    private final SparseArray<Transport> mTransports = new SparseArray<>();

    @NonNull
    private final Map<Integer, Listener> mListeners = new HashMap<>();

    private Transport mTempTransport;

    public CompanionTransportManager(Context context) {
        mContext = context;
    }

    /**
     * Add a message listener when a message is received for the message type
     */
    @GuardedBy("mTransports")
    public void addListener(int message, @NonNull Listener listener) {
        mListeners.put(message, listener);
        for (int i = 0; i < mTransports.size(); i++) {
            mTransports.valueAt(i).addListener(message, listener);
        }
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

            initializeTransport(associationId, fd);
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

    @GuardedBy("mTransports")
    private void initializeTransport(int associationId, ParcelFileDescriptor fd) {
        if (!isSecureTransportEnabled()) {
            Transport transport = new RawTransport(associationId, fd, mContext);
            for (Map.Entry<Integer, Listener> entry : mListeners.entrySet()) {
                transport.addListener(entry.getKey(), entry.getValue());
            }
            transport.start();
            mTransports.put(associationId, transport);
            Slog.i(TAG, "RawTransport is created");
            return;
        }

        // Exchange platform info to decide which transport should be created
        mTempTransport = new RawTransport(associationId, fd, mContext);
        for (Map.Entry<Integer, Listener> entry : mListeners.entrySet()) {
            mTempTransport.addListener(entry.getKey(), entry.getValue());
        }
        mTempTransport.addListener(MESSAGE_REQUEST_PLATFORM_INFO, this::onPlatformInfoReceived);
        mTempTransport.start();

        int sdk = Build.VERSION.SDK_INT;
        String release = Build.VERSION.RELEASE;
        // data format: | SDK_INT (int) | release length (int) | release |
        final ByteBuffer data = ByteBuffer.allocate(4 + 4 + release.getBytes().length)
                .putInt(sdk)
                .putInt(release.getBytes().length)
                .put(release.getBytes());

        // TODO: it should check if preSharedKey is given
        mTempTransport.requestForResponse(MESSAGE_REQUEST_PLATFORM_INFO, data.array());
    }

    /**
     * Depending on the remote platform info to decide which transport should be created
     */
    @GuardedBy("mTransports")
    private void onPlatformInfoReceived(byte[] data) {
        // TODO: it should check if preSharedKey is given

        ByteBuffer buffer = ByteBuffer.wrap(data);
        int remoteSdk = buffer.getInt();
        byte[] remoteRelease = new byte[buffer.getInt()];
        buffer.get(remoteRelease);

        Slog.i(TAG, "Remote device SDK: " + remoteSdk + ", release:" + new String(remoteRelease));

        Transport transport = mTempTransport;
        mTempTransport = null;

        int sdk = Build.VERSION.SDK_INT;
        String release = Build.VERSION.RELEASE;
        if (remoteSdk == NON_ANDROID) {
            // TODO: pass in a real preSharedKey
            transport = new SecureTransport(transport.getAssociationId(), transport.getFd(),
                    mContext, null, null);
        } else if (sdk < SECURE_CHANNEL_AVAILABLE_SDK
                || remoteSdk < SECURE_CHANNEL_AVAILABLE_SDK) {
            // TODO: depending on the release version, either
            //       1) using a RawTransport for old T versions
            //       2) or an Ukey2 handshaked transport for UKey2 backported T versions
        } else {
            Slog.i(TAG, "Creating a secure channel");
            transport = new SecureTransport(transport.getAssociationId(), transport.getFd(),
                    mContext);
            for (Map.Entry<Integer, Listener> entry : mListeners.entrySet()) {
                transport.addListener(entry.getKey(), entry.getValue());
            }
            transport.start();
        }
        mTransports.put(transport.getAssociationId(), transport);
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

    private boolean isSecureTransportEnabled() {
        boolean enabled = !Build.IS_DEBUGGABLE || mSecureTransportEnabled;

        return enabled;
    }
}
