/*
 * Copyright 2023 The Android Open Source Project
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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;

import static com.android.server.companion.virtual.camera.VirtualCameraConversionUtil.getServiceCameraConfiguration;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.virtual.VirtualDeviceParams.DevicePolicy;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Manages the registration and removal of virtual camera from the server side.
 *
 * <p>This classes delegate calls to the virtual camera service, so it is dependent on the service
 * to be up and running.
 */
public final class VirtualCameraController implements IBinder.DeathRecipient {

    private static final String VIRTUAL_CAMERA_SERVICE_NAME = "virtual_camera";
    private static final String TAG = "VirtualCameraController";

    private final Object mServiceLock = new Object();

    @GuardedBy("mServiceLock")
    @Nullable private IVirtualCameraService mVirtualCameraService;
    @DevicePolicy
    private final int mCameraPolicy;

    @GuardedBy("mCameras")
    private final Map<IBinder, CameraDescriptor> mCameras = new ArrayMap<>();

    public VirtualCameraController(@DevicePolicy int cameraPolicy) {
        this(/* virtualCameraService= */ null, cameraPolicy);
    }

    @VisibleForTesting
    VirtualCameraController(IVirtualCameraService virtualCameraService,
            @DevicePolicy int cameraPolicy) {
        mVirtualCameraService = virtualCameraService;
        mCameraPolicy = cameraPolicy;
    }

    /**
     * Register a new virtual camera with the given config.
     *
     * @param cameraConfig The {@link VirtualCameraConfig} sent by the client.
     */
    public void registerCamera(@NonNull VirtualCameraConfig cameraConfig,
            AttributionSource attributionSource) {
        checkConfigByPolicy(cameraConfig);

        connectVirtualCameraServiceIfNeeded();

        try {
            if (registerCameraWithService(cameraConfig)) {
                CameraDescriptor cameraDescriptor =
                        new CameraDescriptor(cameraConfig);
                IBinder binder = cameraConfig.getCallback().asBinder();
                binder.linkToDeath(cameraDescriptor, 0 /* flags */);
                synchronized (mCameras) {
                    mCameras.put(binder, cameraDescriptor);
                }
            } else {
                // TODO(b/310857519): Revisit this to find a better way of indicating failure.
                throw new RuntimeException("Failed to register virtual camera.");
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        if (android.companion.virtualdevice.flags.Flags.metricsCollection()) {
            Counter.logIncrementWithUid(
                    "virtual_devices.value_virtual_camera_created_count",
                    attributionSource.getUid());
        }
    }

    /**
     * Unregister the virtual camera with the given config.
     *
     * @param cameraConfig The {@link VirtualCameraConfig} sent by the client.
     */
    public void unregisterCamera(@NonNull VirtualCameraConfig cameraConfig) {
        synchronized (mCameras) {
            IBinder binder = cameraConfig.getCallback().asBinder();
            if (!mCameras.containsKey(binder)) {
                Slog.w(TAG, "Virtual camera was not registered.");
            } else {
                connectVirtualCameraServiceIfNeeded();

                try {
                    synchronized (mServiceLock) {
                        mVirtualCameraService.unregisterCamera(binder);
                    }
                    mCameras.remove(binder);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }

    /** Return the id of the virtual camera with the given config. */
    public int getCameraId(@NonNull VirtualCameraConfig cameraConfig) {
        connectVirtualCameraServiceIfNeeded();

        try {
            synchronized (mServiceLock) {
                return mVirtualCameraService.getCameraId(cameraConfig.getCallback().asBinder());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void binderDied() {
        Slog.d(TAG, "Virtual camera service died.");
        synchronized (mServiceLock) {
            mVirtualCameraService = null;
        }
        synchronized (mCameras) {
            mCameras.clear();
        }
    }

    /** Release resources associated with this controller. */
    public void close() {
        synchronized (mCameras) {
            if (!mCameras.isEmpty()) {
                connectVirtualCameraServiceIfNeeded();

                synchronized (mServiceLock) {
                    for (IBinder binder : mCameras.keySet()) {
                        try {
                            mVirtualCameraService.unregisterCamera(binder);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "close(): Camera failed to be removed on camera "
                                    + "service.", e);
                        }
                    }
                }
                mCameras.clear();
            }
        }
        synchronized (mServiceLock) {
            mVirtualCameraService = null;
        }
    }

    /** Dumps information about this {@link VirtualCameraController} for debugging purposes. */
    public void dump(PrintWriter fout, String indent) {
        fout.println(indent + "VirtualCameraController:");
        indent += indent;
        synchronized (mCameras) {
            fout.printf("%sRegistered cameras:%d%n\n", indent, mCameras.size());
            for (CameraDescriptor descriptor : mCameras.values()) {
                fout.printf("%s token: %s\n", indent, descriptor.mConfig);
            }
        }
    }

    private void checkConfigByPolicy(VirtualCameraConfig config) {
        if (mCameraPolicy == DEVICE_POLICY_DEFAULT) {
            throw new IllegalArgumentException(
                    "Cannot create virtual camera with DEVICE_POLICY_DEFAULT for "
                            + "POLICY_TYPE_CAMERA");
        } else if (isLensFacingAlreadyPresent(config.getLensFacing())) {
            throw new IllegalArgumentException(
                    "Only a single virtual camera can be created with lens facing "
                            + config.getLensFacing());
        }
    }

    private boolean isLensFacingAlreadyPresent(int lensFacing) {
        synchronized (mCameras) {
            for (CameraDescriptor cameraDescriptor : mCameras.values()) {
                if (cameraDescriptor.mConfig.getLensFacing() == lensFacing) {
                    return true;
                }
            }
        }
        return false;
    }

    private void connectVirtualCameraServiceIfNeeded() {
        synchronized (mServiceLock) {
            // Try to connect to service if not connected already.
            if (mVirtualCameraService == null) {
                connectVirtualCameraService();
            }
            // Throw exception if we are unable to connect to service.
            if (mVirtualCameraService == null) {
                throw new IllegalStateException("Virtual camera service is not connected.");
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
        synchronized (mServiceLock) {
            return mVirtualCameraService.registerCamera(config.getCallback().asBinder(),
                    serviceConfiguration);
        }
    }

    private final class CameraDescriptor implements IBinder.DeathRecipient {

        private final VirtualCameraConfig mConfig;

        CameraDescriptor(VirtualCameraConfig config) {
            mConfig = config;
        }

        @Override
        public void binderDied() {
            Slog.d(TAG, "Virtual camera binder died");
            unregisterCamera(mConfig);
        }
    }
}
