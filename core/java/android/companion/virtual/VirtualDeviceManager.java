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

package android.companion.virtual;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.companion.AssociationInfo;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualTouchscreen;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * System level service for managing virtual devices.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "VirtualDeviceManager";

    /** @hide */
    @IntDef(prefix = "DISPLAY_FLAG_",
            flag = true,
            value = {DISPLAY_FLAG_TRUSTED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface DisplayFlags {}

    /**
     * Indicates that the display is trusted to show system decorations and receive inputs without
     * users' touch.
     *
     * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED
     * @hide  // TODO(b/194949534): Unhide this API
     */
    public static final int DISPLAY_FLAG_TRUSTED = 1;

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;

    private final IVirtualDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public VirtualDeviceManager(
            @Nullable IVirtualDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Creates a virtual device.
     *
     * @param associationId The association ID as returned by {@link AssociationInfo#getId()} from
     *   Companion Device Manager. Virtual devices must have a corresponding association with CDM in
     *   order to be created.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @Nullable
    public VirtualDevice createVirtualDevice(int associationId, VirtualDeviceParams params) {
        // TODO(b/194949534): Unhide this API
        try {
            IVirtualDevice virtualDevice = mService.createVirtualDevice(
                    new Binder(), mContext.getPackageName(), associationId, params);
            return new VirtualDevice(mContext, virtualDevice);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A virtual device has its own virtual display, audio output, microphone, and camera etc. The
     * creator of a virtual device can take the output from the virtual display and stream it over
     * to another device, and inject input events that are received from the remote device.
     *
     * TODO(b/204081582): Consider using a builder pattern for the input APIs.
     */
    public static class VirtualDevice implements AutoCloseable {

        private final Context mContext;
        private final IVirtualDevice mVirtualDevice;

        private VirtualDevice(Context context, IVirtualDevice virtualDevice) {
            mContext = context.getApplicationContext();
            mVirtualDevice = virtualDevice;
        }

        /**
         * Creates a virtual display for this virtual device. All displays created on the same
         * device belongs to the same display group.
         *
         * @param width The width of the virtual display in pixels, must be greater than 0.
         * @param height The height of the virtual display in pixels, must be greater than 0.
         * @param densityDpi The density of the virtual display in dpi, must be greater than 0.
         * @param surface The surface to which the content of the virtual display should
         * be rendered, or null if there is none initially. The surface can also be set later using
         * {@link VirtualDisplay#setSurface(Surface)}.
         * @param flags Either 0, or {@link #DISPLAY_FLAG_TRUSTED}.
         * @param callback Callback to call when the state of the {@link VirtualDisplay} changes
         * @param handler The handler on which the listener should be invoked, or null
         * if the listener should be invoked on the calling thread's looper.
         * @return The newly created virtual display, or {@code null} if the application could
         * not create the virtual display.
         *
         * @see DisplayManager#createVirtualDisplay
         * @hide
         */
        // TODO(b/194949534): Unhide this API
        // Suppress "ExecutorRegistration" because DisplayManager.createVirtualDisplay takes a
        // handler
        @SuppressLint("ExecutorRegistration")
        @Nullable
        public VirtualDisplay createVirtualDisplay(
                int width,
                int height,
                int densityDpi,
                @Nullable Surface surface,
                @DisplayFlags int flags,
                @Nullable Handler handler,
                @Nullable VirtualDisplay.Callback callback) {
            // TODO(b/205343547): Handle display groups properly instead of creating a new display
            //  group for every new virtual display created using this API.
            // belongs to the same display group.
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
            // DisplayManager will call into VirtualDeviceManagerInternal to register the
            // created displays.
            return displayManager.createVirtualDisplay(
                    mVirtualDevice,
                    new VirtualDisplayConfig.Builder(
                            getVirtualDisplayName(), width, height, densityDpi)
                            .setSurface(surface)
                            .setFlags(getVirtualDisplayFlags(flags))
                            .build(),
                    callback,
                    handler);
        }

        /**
         * Closes the virtual device, stopping and tearing down any virtual displays,
         * audio policies, and event injection that's currently in progress.
         */
        public void close() {
            try {
                mVirtualDevice.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualKeyboard createVirtualKeyboard(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualKeyboard:" + inputDeviceName);
                mVirtualDevice.createVirtualKeyboard(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token);
                return new VirtualKeyboard(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual mouse.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualMouse createVirtualMouse(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualMouse:" + inputDeviceName);
                mVirtualDevice.createVirtualMouse(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token);
                return new VirtualMouse(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual touchscreen.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the vendor id
         * @param productId the product id
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualTouchscreen createVirtualTouchscreen(
                @NonNull VirtualDisplay display,
                @NonNull String inputDeviceName,
                int vendorId,
                int productId) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualTouchscreen:" + inputDeviceName);
                final Point size = new Point();
                display.getDisplay().getSize(size);
                mVirtualDevice.createVirtualTouchscreen(display.getDisplay().getDisplayId(),
                        inputDeviceName, vendorId, productId, token, size);
                return new VirtualTouchscreen(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns the display flags that should be added to a particular virtual display.
         * Additional device-level flags from {@link
         * com.android.server.companion.virtual.VirtualDeviceImpl#getBaseVirtualDisplayFlags()} will
         * be added by DisplayManagerService.
         */
        private int getVirtualDisplayFlags(@DisplayFlags int flags) {
            int virtualDisplayFlags = DEFAULT_VIRTUAL_DISPLAY_FLAGS;
            if ((flags & DISPLAY_FLAG_TRUSTED) != 0) {
                virtualDisplayFlags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;
            }
            return virtualDisplayFlags;
        }

        private String getVirtualDisplayName() {
            try {
                // Currently this just use the association ID, which means all of the virtual
                // displays created using the same virtual device will have the same name. The name
                // should only be used for informational purposes, and not for identifying the
                // display in code.
                return "VirtualDevice_" + mVirtualDevice.getAssociationId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
