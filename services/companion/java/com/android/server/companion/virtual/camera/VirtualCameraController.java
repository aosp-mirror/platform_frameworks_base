/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.virtual.camera;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.camera.IVirtualCamera;
import android.companion.virtual.camera.VirtualCameraHalConfig;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the registration and removal of virtual camera from the server side.
 *
 * <p>This classes delegate calls to the virtual camera service, so it is dependent on the service
 * to be up and running
 */
public class VirtualCameraController implements IBinder.DeathRecipient, ServiceConnection {

    private static class VirtualCameraInfo {

        private final IVirtualCamera mVirtualCamera;
        private boolean mIsRegistered;

        VirtualCameraInfo(IVirtualCamera virtualCamera) {
            mVirtualCamera = virtualCamera;
        }
    }

    private static final String TAG = "VirtualCameraController";

    private static final String VIRTUAL_CAMERA_SERVICE_PACKAGE = "com.android.virtualcamera";
    private static final String VIRTUAL_CAMERA_SERVICE_CLASS = ".VirtualCameraService";
    private final Context mContext;

    @Nullable private IVirtualCameraService mVirtualCameraService = null;

    @GuardedBy("mCameras")
    private final Map<IVirtualCamera, VirtualCameraInfo> mCameras = new HashMap<>(1);

    public VirtualCameraController(Context context) {
        mContext = context;
        connectVirtualCameraService();
    }

    private void connectVirtualCameraService() {
        final long callingId = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent();
            intent.setPackage(VIRTUAL_CAMERA_SERVICE_PACKAGE);
            intent.setComponent(
                    ComponentName.createRelative(
                            VIRTUAL_CAMERA_SERVICE_PACKAGE, VIRTUAL_CAMERA_SERVICE_CLASS));
            mContext.startServiceAsUser(intent, UserHandle.SYSTEM);
            if (!mContext.bindServiceAsUser(
                    intent,
                    this,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                    UserHandle.SYSTEM)) {
                mContext.unbindService(this);
                Log.w(
                        TAG,
                        "connectVirtualCameraService: Failed to connect to the virtual camera "
                                + "service");
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void forwardPendingRegistrations() {
        IVirtualCameraService cameraService = mVirtualCameraService;
        if (cameraService == null) {
            return;
        }
        synchronized (mCameras) {
            for (VirtualCameraInfo cameraInfo : mCameras.values()) {
                if (cameraInfo.mIsRegistered) {
                    continue;
                }
                try {
                    cameraService.registerCamera(cameraInfo.mVirtualCamera);
                    cameraInfo.mIsRegistered = true;
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Remove the virtual camera with the provided name
     *
     * @param camera The name of the camera to remove
     */
    public void unregisterCamera(@NonNull IVirtualCamera camera) {
        IVirtualCameraService virtualCameraService = mVirtualCameraService;
        if (virtualCameraService != null) {
            try {
                virtualCameraService.unregisterCamera(camera);
                synchronized (mCameras) {
                    VirtualCameraInfo cameraInfo = mCameras.remove(camera);
                    cameraInfo.mIsRegistered = false;
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Register a new virtual camera with the provided characteristics.
     *
     * @param camera The {@link IVirtualCamera} producing the image to communicate with the client.
     * @throws IllegalArgumentException if the characteristics could not be parsed.
     */
    public void registerCamera(@NonNull IVirtualCamera camera) {
        IVirtualCameraService service = mVirtualCameraService;
        VirtualCameraInfo virtualCameraInfo = new VirtualCameraInfo(camera);
        synchronized (mCameras) {
            mCameras.put(camera, virtualCameraInfo);
        }
        if (service != null) {
            try {
                if (service.registerCamera(camera)) {
                    virtualCameraInfo.mIsRegistered = true;
                    return;
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        // Service was not available or registration failed, save the registration for later
        connectVirtualCameraService();
    }

    @Override
    public void binderDied() {
        Log.d(TAG, "binderDied");
        mVirtualCameraService = null;
    }

    @Override
    public void onBindingDied(ComponentName name) {
        mVirtualCameraService = null;
        Log.d(TAG, "onBindingDied() called with: name = [" + name + "]");
    }

    @Override
    public void onNullBinding(ComponentName name) {
        mVirtualCameraService = null;
        Log.d(TAG, "onNullBinding() called with: name = [" + name + "]");
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.d(TAG, "onServiceConnected: " + name.toString());
        mVirtualCameraService = IVirtualCameraService.Stub.asInterface(service);
        try {
            service.linkToDeath(this, 0);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        forwardPendingRegistrations();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.d(TAG, "onServiceDisconnected() called with: name = [" + name + "]");
        mVirtualCameraService = null;
    }

    /** Release resources associated with this controller. */
    public void close() {
        if (mVirtualCameraService == null) {
            return;
        }
        synchronized (mCameras) {
            mCameras.forEach(
                    (name, cameraInfo) -> {
                        try {
                            mVirtualCameraService.unregisterCamera(name);
                        } catch (RemoteException e) {
                            Log.w(
                                    TAG,
                                    "close(): Camera failed to be removed on camera service.",
                                    e);
                        }
                    });
        }
        mContext.unbindService(this);
    }

    /** Dumps information about this {@link VirtualCameraController} for debugging purposes. */
    public void dump(PrintWriter fout, String indent) {
        fout.println(indent + "VirtualCameraController:");
        indent += indent;
        fout.printf("%sService:%s\n", indent, mVirtualCameraService);
        synchronized (mCameras) {
            fout.printf("%sRegistered cameras:%d%n\n", indent, mCameras.size());
            for (VirtualCameraInfo info : mCameras.values()) {
                VirtualCameraHalConfig config = null;
                try {
                    config = info.mVirtualCamera.getHalConfig();
                } catch (RemoteException ex) {
                    Log.w(TAG, ex);
                }
                fout.printf(
                        "%s- %s isRegistered: %s, token: %s\n",
                        indent,
                        config == null ? "" : config.displayName,
                        info.mIsRegistered,
                        info.mVirtualCamera);
            }
        }
    }
}
