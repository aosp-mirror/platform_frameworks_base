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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.virtual.audio.VirtualAudioDevice;
import android.companion.virtual.audio.VirtualAudioDevice.AudioConfigurationChangeCallback;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.VirtualDisplayFlag;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualTouchscreen;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.view.Surface;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * System level service for managing virtual devices.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final boolean DEBUG = false;
    private static final String TAG = "VirtualDeviceManager";

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "LAUNCH_",
            value = {
                    LAUNCH_SUCCESS,
                    LAUNCH_FAILURE_PENDING_INTENT_CANCELED,
                    LAUNCH_FAILURE_NO_ACTIVITY})
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface PendingIntentLaunchStatus {}

    /**
     * Status for {@link VirtualDevice#launchPendingIntent}, indicating that the launch was
     * successful.
     */
    public static final int LAUNCH_SUCCESS = 0;

    /**
     * Status for {@link VirtualDevice#launchPendingIntent}, indicating that the launch failed
     * because the pending intent was canceled.
     */
    public static final int LAUNCH_FAILURE_PENDING_INTENT_CANCELED = 1;

    /**
     * Status for {@link VirtualDevice#launchPendingIntent}, indicating that the launch failed
     * because no activity starts were detected as a result of calling the pending intent.
     */
    public static final int LAUNCH_FAILURE_NO_ACTIVITY = 2;

    private final IVirtualDeviceManager mService;
    private final Context mContext;

    /** @hide */
    public VirtualDeviceManager(
            @Nullable IVirtualDeviceManager service, @NonNull Context context) {
        mService = service;
        mContext = context;
    }

    /**
     * Creates a virtual device where applications can launch and receive input events injected by
     * the creator.
     *
     * <p>The {@link android.Manifest.permission#CREATE_VIRTUAL_DEVICE} permission is required to
     * create virtual devices, which is only available to system apps holding specific roles.
     *
     * @param associationId The association ID as returned by {@link AssociationInfo#getId()} from
     *   Companion Device Manager. Virtual devices must have a corresponding association with CDM in
     *   order to be created.
     * @param params The parameters for creating virtual devices. See {@link VirtualDeviceParams}
     *   for the available options.
     * @return The created virtual device.
     */
    @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
    @NonNull
    public VirtualDevice createVirtualDevice(
            int associationId,
            @NonNull VirtualDeviceParams params) {
        try {
            return new VirtualDevice(mService, mContext, associationId, params);
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
        private final IVirtualDeviceManager mService;
        private final IVirtualDevice mVirtualDevice;
        private final ArrayMap<ActivityListener, ActivityListenerDelegate> mActivityListeners =
                new ArrayMap<>();
        private final IVirtualDeviceActivityListener mActivityListenerBinder =
                new IVirtualDeviceActivityListener.Stub() {

                    @Override
                    public void onTopActivityChanged(int displayId, ComponentName topActivity) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            for (int i = 0; i < mActivityListeners.size(); i++) {
                                mActivityListeners.valueAt(i)
                                        .onTopActivityChanged(displayId, topActivity);
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }

                    @Override
                    public void onDisplayEmpty(int displayId) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            for (int i = 0; i < mActivityListeners.size(); i++) {
                                mActivityListeners.valueAt(i).onDisplayEmpty(displayId);
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                };
        @Nullable
        private VirtualAudioDevice mVirtualAudioDevice;

        private VirtualDevice(
                IVirtualDeviceManager service,
                Context context,
                int associationId,
                VirtualDeviceParams params) throws RemoteException {
            mService = service;
            mContext = context.getApplicationContext();
            mVirtualDevice = service.createVirtualDevice(
                    new Binder(),
                    mContext.getPackageName(),
                    associationId,
                    params,
                    mActivityListenerBinder);
        }

        /**
         * Launches a given pending intent on the give display ID.
         *
         * @param displayId The display to launch the pending intent on. This display must be
         *   created from this virtual device.
         * @param pendingIntent The pending intent to be launched. If the intent is an activity
         *   intent, the activity will be started on the virtual display using
         *   {@link android.app.ActivityOptions#setLaunchDisplayId}. If the intent is a service or
         *   broadcast intent, an attempt will be made to catch activities started as a result of
         *   sending the pending intent and move them to the given display. When it completes,
         *   {@code listener} will be called with the status of whether the launch attempt is
         *   successful or not.
         * @param executor The executor to run {@code launchCallback} on.
         * @param listener Listener that is called when the pending intent launching is complete.
         *   The argument is {@link #LAUNCH_SUCCESS} if the launch successfully started an activity
         *   on the virtual display, or one of the {@code LAUNCH_FAILED} status explaining why it
         *   failed.
         */
        public void launchPendingIntent(
                int displayId,
                @NonNull PendingIntent pendingIntent,
                @NonNull Executor executor,
                @NonNull IntConsumer listener) {
            try {
                mVirtualDevice.launchPendingIntent(
                        displayId,
                        pendingIntent,
                        new ResultReceiver(new Handler(Looper.getMainLooper())) {
                            @Override
                            protected void onReceiveResult(int resultCode, Bundle resultData) {
                                super.onReceiveResult(resultCode, resultData);
                                executor.execute(() -> listener.accept(resultCode));
                            }
                        });
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
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
         * @param flags A combination of virtual display flags accepted by
         * {@link DisplayManager#createVirtualDisplay}. In addition, the following flags are
         * automatically set for all virtual devices:
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PUBLIC VIRTUAL_DISPLAY_FLAG_PUBLIC} and
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
         * VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY}.
         * @param executor The executor on which {@code callback} will be invoked. This is ignored
         * if {@code callback} is {@code null}. If {@code callback} is specified, this executor must
         * not be null.
         * @param callback Callback to call when the state of the {@link VirtualDisplay} changes
         * @return The newly created virtual display, or {@code null} if the application could
         * not create the virtual display.
         *
         * @see DisplayManager#createVirtualDisplay
         */
        @Nullable
        public VirtualDisplay createVirtualDisplay(
                @IntRange(from = 1) int width,
                @IntRange(from = 1) int height,
                @IntRange(from = 1) int densityDpi,
                @Nullable Surface surface,
                @VirtualDisplayFlag int flags,
                @Nullable @CallbackExecutor Executor executor,
                @Nullable VirtualDisplay.Callback callback) {
            // TODO(b/205343547): Handle display groups properly instead of creating a new display
            //  group for every new virtual display created using this API.
            // belongs to the same display group.
            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(
                    getVirtualDisplayName(), width, height, densityDpi)
                    .setSurface(surface)
                    .setFlags(getVirtualDisplayFlags(flags))
                    .build();
            IVirtualDisplayCallback callbackWrapper =
                    new DisplayManagerGlobal.VirtualDisplayCallback(callback, executor);
            final int displayId;
            try {
                displayId = mService.createVirtualDisplay(config, callbackWrapper, mVirtualDevice,
                        mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            DisplayManagerGlobal displayManager = DisplayManagerGlobal.getInstance();
            return displayManager.createVirtualDisplayWrapper(config, mContext, callbackWrapper,
                    displayId);
        }

        /**
         * Closes the virtual device, stopping and tearing down any virtual displays, associated
         * virtual audio device, and event injection that's currently in progress.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void close() {
            try {
                mVirtualDevice.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (mVirtualAudioDevice != null) {
                mVirtualAudioDevice.close();
                mVirtualAudioDevice = null;
            }
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param display the display that the events inputted through this device should target
         * @param inputDeviceName the name to call this input device
         * @param vendorId the PCI vendor id
         * @param productId the product id, as defined by the vendor
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
         * @param vendorId the PCI vendor id
         * @param productId the product id, as defined by the vendor
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
         * @param vendorId the PCI vendor id
         * @param productId the product id, as defined by the vendor
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
         * Creates a VirtualAudioDevice, capable of recording audio emanating from this device,
         * or injecting audio from another device.
         *
         * <p>Note: One {@link VirtualDevice} can only create one {@link VirtualAudioDevice}, so
         * calling this method multiple times will return the same instance. When
         * {@link VirtualDevice#close()} is called, the associated {@link VirtualAudioDevice} will
         * also be closed automatically.
         *
         * @param display The target virtual display to capture from and inject into.
         * @param executor The {@link Executor} object for the thread on which to execute
         *                the callback. If <code>null</code>, the {@link Executor} associated with
         *                the main {@link Looper} will be used.
         * @param callback Interface to be notified when playback or recording configuration of
         *                applications running on virtual display is changed.
         * @return A {@link VirtualAudioDevice} instance.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualAudioDevice createVirtualAudioDevice(
                @NonNull VirtualDisplay display,
                @Nullable Executor executor,
                @Nullable AudioConfigurationChangeCallback callback) {
            if (mVirtualAudioDevice == null) {
                mVirtualAudioDevice = new VirtualAudioDevice(
                        mContext, mVirtualDevice, display, executor, callback);
            }
            return mVirtualAudioDevice;
        }

        /**
         * Sets the visibility of the pointer icon for this VirtualDevice's associated displays.
         *
         * @param showPointerIcon True if the pointer should be shown; false otherwise. The default
         *                        visibility is true.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public void setShowPointerIcon(boolean showPointerIcon) {
            try {
                mVirtualDevice.setShowPointerIcon(showPointerIcon);
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
        private int getVirtualDisplayFlags(int flags) {
            return DEFAULT_VIRTUAL_DISPLAY_FLAGS | flags;
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

        /**
         * Adds an activity listener to listen for events such as top activity change or virtual
         * display task stack became empty.
         *
         * @param executor The executor where the listener is executed on.
         * @param listener The listener to add.
         * @see #removeActivityListener(ActivityListener)
         */
        public void addActivityListener(
                @CallbackExecutor @NonNull Executor executor, @NonNull ActivityListener listener) {
            mActivityListeners.put(listener, new ActivityListenerDelegate(listener, executor));
        }

        /**
         * Removes an activity listener previously added with
         * {@link #addActivityListener}.
         *
         * @param listener The listener to remove.
         * @see #addActivityListener(Executor, ActivityListener)
         */
        public void removeActivityListener(@NonNull ActivityListener listener) {
            mActivityListeners.remove(listener);
        }
    }

    /**
     * Listener for activity changes in this virtual device.
     */
    public interface ActivityListener {

        /**
         * Called when the top activity is changed.
         *
         * <p>Note: When there are no activities running on the virtual display, the
         * {@link #onDisplayEmpty(int)} will be called. If the value topActivity is cached, it
         * should be cleared when {@link #onDisplayEmpty(int)} is called.
         *
         * @param displayId The display ID on which the activity change happened.
         * @param topActivity The component name of the top activity.
         */
        void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity);

        /**
         * Called when the display becomes empty (e.g. if the user hits back on the last
         * activity of the root task).
         *
         * @param displayId The display ID that became empty.
         */
        void onDisplayEmpty(int displayId);
    }

    /**
     * A wrapper for {@link ActivityListener} that executes callbacks on the given executor.
     */
    private static class ActivityListenerDelegate {
        @NonNull private final ActivityListener mActivityListener;
        @NonNull private final Executor mExecutor;

        ActivityListenerDelegate(@NonNull ActivityListener listener, @NonNull Executor executor) {
            mActivityListener = listener;
            mExecutor = executor;
        }

        public void onTopActivityChanged(int displayId, ComponentName topActivity) {
            mExecutor.execute(() -> mActivityListener.onTopActivityChanged(displayId, topActivity));
        }

        public void onDisplayEmpty(int displayId) {
            mExecutor.execute(() -> mActivityListener.onDisplayEmpty(displayId));
        }
    }
}
