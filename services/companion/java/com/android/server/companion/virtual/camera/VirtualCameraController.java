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

import static com.android.server.companion.virtual.camera.VirtualCameraConversionUtil.getServiceCameraConfiguration;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Set;

/**
 * Manages the registration and removal of virtual camera from the server side.
 *
 * <p>This classes delegate calls to the virtual camera service, so it is dependent on the service
 * to be up and running.
 */
public final class VirtualCameraController implements IBinder.DeathRecipient {

    private static final String VIRTUAL_CAMERA_SERVICE_NAME = "virtual_camera";
    private static final String TAG = "VirtualCameraController";

    @Nullable private IVirtualCameraService mVirtualCameraService;

    @GuardedBy("mCameras")
    private final Set<VirtualCameraConfig> mCameras = new ArraySet<>();

    public VirtualCameraController() {
        connectVirtualCameraService();
    }

    @VisibleForTesting
    VirtualCameraController(IVirtualCameraService virtualCameraService) {
        mVirtualCameraService = virtualCameraService;
    }

    /**
     * Register a new virtual camera with the given config.
     *
     * @param cameraConfig The {@link VirtualCameraConfig} sent by the client.
     */
    public void registerCamera(@NonNull VirtualCameraConfig cameraConfig) {
        // Try to connect to service if not connected already.
        if (mVirtualCameraService == null) {
            connectVirtualCameraService();
        }
        // Throw exception if we are unable to connect to service.
        if (mVirtualCameraService == null) {
            throw new IllegalStateException("Virtual camera service is not connected.");
        }

        try {
            if (registerCameraWithService(cameraConfig)) {
                synchronized (mCameras) {
                    mCameras.add(cameraConfig);
                }
            } else {
                // TODO(b/310857519): Revisit this to find a better way of indicating failure.
                throw new RuntimeException("Failed to register virtual camera.");
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister the virtual camera with the given config.
     *
     * @param cameraConfig The {@link VirtualCameraConfig} sent by the client.
     */
    public void unregisterCamera(@NonNull VirtualCameraConfig cameraConfig) {
        try {
            if (mVirtualCameraService == null) {
                Slog.w(TAG, "Virtual camera service is not connected.");
            } else {
                mVirtualCameraService.unregisterCamera(cameraConfig.getCallback().asBinder());
            }
            synchronized (mCameras) {
                mCameras.remove(cameraConfig);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @Override
    public void binderDied() {
        Slog.d(TAG, "Virtual camera service died.");
        mVirtualCameraService = null;
        synchronized (mCameras) {
            mCameras.clear();
        }
    }

    /** Release resources associated with this controller. */
    public void close() {
        synchronized (mCameras) {
            if (mVirtualCameraService == null) {
                Slog.w(TAG, "Virtual camera service is not connected.");
            } else {
                for (VirtualCameraConfig config : mCameras) {
                    try {
                        mVirtualCameraService.unregisterCamera(config.getCallback().asBinder());
                    } catch (RemoteException e) {
                        Slog.w(TAG, "close(): Camera failed to be removed on camera "
                                + "service.", e);
                    }
                }
            }
            mCameras.clear();
        }
        mVirtualCameraService = null;
    }

    /** Dumps information about this {@link VirtualCameraController} for debugging purposes. */
    public void dump(PrintWriter fout, String indent) {
        fout.println(indent + "VirtualCameraController:");
        indent += indent;
        fout.printf("%sService:%s\n", indent, mVirtualCameraService);
        synchronized (mCameras) {
            fout.printf("%sRegistered cameras:%d%n\n", indent, mCameras.size());
            for (VirtualCameraConfig config : mCameras) {
                fout.printf("%s token: %s\n", indent, config);
            }
        }
    }

    private void connectVirtualCameraService() {
        final long callingId = Binder.clearCallingIdentity();
        try {
            IBinder virtualCameraBinder =
                    ServiceManager.waitForService(VIRTUAL_CAMERA_SERVICE_NAME);
            if (virtualCameraBinder == null) {
                Slog.e(TAG, "connectVirtualCameraService: Failed to connect to the virtual "
                        + "camera service");
                return;
            }
            virtualCameraBinder.linkToDeath(this, 0);
            mVirtualCameraService = IVirtualCameraService.Stub.asInterface(virtualCameraBinder);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private boolean registerCameraWithService(VirtualCameraConfig config) throws RemoteException {
        VirtualCameraConfiguration serviceConfiguration = getServiceCameraConfiguration(config);
        return mVirtualCameraService.registerCamera(config.getCallback().asBinder(),
                serviceConfiguration);
    }
}
