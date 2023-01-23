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

import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.virtual.audio.VirtualAudioDevice;
import android.companion.virtual.audio.VirtualAudioDevice.AudioConfigurationChangeCallback;
import android.companion.virtual.camera.VirtualCameraDevice;
import android.companion.virtual.camera.VirtualCameraInput;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.VirtualDisplayFlag;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualDpad;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouse;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualNavigationTouchpad;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualTouchscreen;
import android.hardware.input.VirtualTouchscreenConfig;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Surface;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * System level service for managing virtual devices.
 */
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final String TAG = "VirtualDeviceManager";

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;

    /**
     * The default device ID, which is the ID of the primary (non-virtual) device.
     */
    public static final int DEVICE_ID_DEFAULT = 0;

    /**
     * Invalid device ID.
     */
    public static final int DEVICE_ID_INVALID = -1;

    /**
     * Broadcast Action: A Virtual Device was removed.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_VIRTUAL_DEVICE_REMOVED =
            "android.companion.virtual.action.VIRTUAL_DEVICE_REMOVED";

    /**
     * Int intent extra to be used with {@link #ACTION_VIRTUAL_DEVICE_REMOVED}.
     * Contains the identifier of the virtual device, which was removed.
     *
     * @hide
     */
    public static final String EXTRA_VIRTUAL_DEVICE_ID =
            "android.companion.virtual.extra.VIRTUAL_DEVICE_ID";

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
     *
     * @hide
     */
    @SystemApi
    public static final int LAUNCH_SUCCESS = 0;

    /**
     * Status for {@link VirtualDevice#launchPendingIntent}, indicating that the launch failed
     * because the pending intent was canceled.
     *
     * @hide
     */
    @SystemApi
    public static final int LAUNCH_FAILURE_PENDING_INTENT_CANCELED = 1;

    /**
     * Status for {@link VirtualDevice#launchPendingIntent}, indicating that the launch failed
     * because no activity starts were detected as a result of calling the pending intent.
     *
     * @hide
     */
    @SystemApi
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
     *
     * @hide
     */
    @SystemApi
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
     * Returns the details of all available virtual devices.
     */
    @NonNull
    public List<android.companion.virtual.VirtualDevice> getVirtualDevices() {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return new ArrayList<>();
        }
        try {
            return mService.getVirtualDevices();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the device policy for the given virtual device and policy type.
     *
     * <p>In case the virtual device identifier is not valid, or there's no explicitly specified
     * policy for that device and policy type, then
     * {@link VirtualDeviceParams#DEVICE_POLICY_DEFAULT} is returned.
     *
     * @hide
     */
    public @VirtualDeviceParams.DevicePolicy int getDevicePolicy(
            int deviceId, @VirtualDeviceParams.PolicyType int policyType) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve device policy; no virtual device manager service.");
            return VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
        }
        try {
            return mService.getDevicePolicy(deviceId, policyType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the ID of the device which owns the display with the given ID.
     *
     * @hide
     */
    public int getDeviceIdForDisplayId(int displayId) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return DEVICE_ID_DEFAULT;
        }
        try {
            return mService.getDeviceIdForDisplayId(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the passed {@code deviceId} is a valid virtual device ID or not.
     * {@link VirtualDeviceManager#DEVICE_ID_DEFAULT} is not valid as it is the ID of the default
     * device which is not a virtual device. {@code deviceId} must correspond to a virtual device
     * created by {@link VirtualDeviceManager#createVirtualDevice(int, VirtualDeviceParams)}.
     *
     * @hide
     */
    public boolean isValidVirtualDeviceId(int deviceId) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return false;
        }
        try {
            return mService.isValidVirtualDeviceId(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns device-specific audio session id for audio playback.
     *
     * @param deviceId - id of the virtual audio device
     * @return Device specific session id to be used for audio playback (see
     *     {@link android.media.AudioManager.generateAudioSessionId}) if virtual device has
     *     {@link VirtualDeviceParams.POLICY_TYPE_AUDIO} set to
     *     {@link VirtualDeviceParams.DEVICE_POLICY_CUSTOM} and Virtual Audio Device
     *     is configured in context-aware mode.
     *     Otherwise {@link AUDIO_SESSION_ID_GENERATE} constant is returned.
     * @hide
     */
    public int getAudioPlaybackSessionId(int deviceId) {
        if (mService == null) {
            return AUDIO_SESSION_ID_GENERATE;
        }
        try {
            return mService.getAudioPlaybackSessionId(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns device-specific audio session id for audio recording.
     *
     * @param deviceId - id of the virtual audio device
     * @return Device specific session id to be used for audio recording (see
     *     {@link android.media.AudioManager.generateAudioSessionId}) if virtual device has
     *     {@link VirtualDeviceParams.POLICY_TYPE_AUDIO} set to
     *     {@link VirtualDeviceParams.DEVICE_POLICY_CUSTOM} and Virtual Audio Device
     *     is configured in context-aware mode.
     *     Otherwise {@link AUDIO_SESSION_ID_GENERATE} constant is returned.
     * @hide
     */
    public int getAudioRecordingSessionId(int deviceId) {
        if (mService == null) {
            return AUDIO_SESSION_ID_GENERATE;
        }
        try {
            return mService.getAudioRecordingSessionId(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests sound effect to be played on virtual device.
     *
     * @see android.media.AudioManager#playSoundEffect(int)
     *
     * @param deviceId - id of the virtual audio device
     * @param effectType the type of sound effect
     * @hide
     */
    public void playSoundEffect(int deviceId, @AudioManager.SystemSoundEffect int effectType) {
        //TODO - handle requests to play sound effects by custom callbacks or SoundPool asociated
        // with device session id.
        // For now, this is intentionally left empty and effectively disables sound effects for
        // virtual devices with custom device audio policy.
    }

    /**
     * A virtual device has its own virtual display, audio output, microphone, and camera etc. The
     * creator of a virtual device can take the output from the virtual display and stream it over
     * to another device, and inject input events that are received from the remote device.
     *
     * TODO(b/204081582): Consider using a builder pattern for the input APIs.
     *
     * @hide
     */
    @SystemApi
    public static class VirtualDevice implements AutoCloseable {

        private final Context mContext;
        private final IVirtualDeviceManager mService;
        private final IVirtualDevice mVirtualDevice;
        private final ArrayMap<ActivityListener, ActivityListenerDelegate> mActivityListeners =
                new ArrayMap<>();
        private final ArrayMap<IntentInterceptorCallback,
                     VirtualIntentInterceptorDelegate> mIntentInterceptorListeners =
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
        private VirtualCameraDevice mVirtualCameraDevice;
        @NonNull
        private final List<VirtualSensor> mVirtualSensors = new ArrayList<>();
        @Nullable
        private VirtualAudioDevice mVirtualAudioDevice;

        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
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
            final List<VirtualSensorConfig> virtualSensorConfigs = params.getVirtualSensorConfigs();
            for (int i = 0; i < virtualSensorConfigs.size(); ++i) {
                mVirtualSensors.add(createVirtualSensor(virtualSensorConfigs.get(i)));
            }
        }

        /**
         * Returns the unique ID of this virtual device.
         */
        public int getDeviceId() {
            try {
                return mVirtualDevice.getDeviceId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return A new Context bound to this device. This is a convenience method equivalent to
         * calling {@link Context#createDeviceContext(int)} with the device id of this device.
         */
        public @NonNull Context createContext() {
            try {
                return mContext.createDeviceContext(mVirtualDevice.getDeviceId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Returns this device's sensor with the given type and name, if any.
         *
         * @see VirtualDeviceParams.Builder#addVirtualSensorConfig
         *
         * @param type The type of the sensor.
         * @param name The name of the sensor.
         * @return The matching sensor if found, {@code null} otherwise.
         */
        @Nullable
        public VirtualSensor getVirtualSensor(int type, @NonNull String name) {
            return mVirtualSensors.stream()
                    .filter(sensor -> sensor.getType() == type && sensor.getName().equals(name))
                    .findAny()
                    .orElse(null);
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
            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(
                    getVirtualDisplayName(), width, height, densityDpi)
                    .setSurface(surface)
                    .setFlags(getVirtualDisplayFlags(flags))
                    .build();
            return createVirtualDisplayInternal(config, executor, callback);
        }

        /**
         * Creates a virtual display for this virtual device. All displays created on the same
         * device belongs to the same display group.
         *
         * @param width The width of the virtual display in pixels, must be greater than 0.
         * @param height The height of the virtual display in pixels, must be greater than 0.
         * @param densityDpi The density of the virtual display in dpi, must be greater than 0.
         * @param displayCategories The categories of the virtual display, indicating the type of
         * activities allowed to run on the display. Activities can declare their type using
         * {@link android.content.pm.ActivityInfo#requiredDisplayCategory}.
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
                @NonNull List<String> displayCategories,
                @Nullable Surface surface,
                @VirtualDisplayFlag int flags,
                @Nullable @CallbackExecutor Executor executor,
                @Nullable VirtualDisplay.Callback callback) {
            VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(
                    getVirtualDisplayName(), width, height, densityDpi)
                    .setDisplayCategories(displayCategories)
                    .setSurface(surface)
                    .setFlags(getVirtualDisplayFlags(flags))
                    .build();
            return createVirtualDisplayInternal(config, executor, callback);
        }

        /**
         * @hide
         */
        @Nullable
        private VirtualDisplay createVirtualDisplayInternal(
                @NonNull VirtualDisplayConfig config,
                @Nullable @CallbackExecutor Executor executor,
                @Nullable VirtualDisplay.Callback callback) {
            // TODO(b/205343547): Handle display groups properly instead of creating a new display
            //  group for every new virtual display created using this API.
            // belongs to the same display group.
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
                // This also takes care of unregistering all virtual sensors.
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
         * Creates a virtual dpad.
         *
         * @param config the configurations of the virtual Dpad.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualDpad createVirtualDpad(@NonNull VirtualDpadConfig config) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualDpad:" + config.getInputDeviceName());
                mVirtualDevice.createVirtualDpad(config, token);
                return new VirtualDpad(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param config the configurations of the virtual keyboard.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualKeyboard createVirtualKeyboard(@NonNull VirtualKeyboardConfig config) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualKeyboard:" + config.getInputDeviceName());
                mVirtualDevice.createVirtualKeyboard(config, token);
                return new VirtualKeyboard(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param display         the display that the events inputted through this device should
         *                        target
         * @param inputDeviceName the name to call this input device
         * @param vendorId        the PCI vendor id
         * @param productId       the product id, as defined by the vendor
         * @see #createVirtualKeyboard(VirtualKeyboardConfig config)
         * @deprecated Use {@link #createVirtualKeyboard(VirtualKeyboardConfig config)} instead
         */
        @Deprecated
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualKeyboard createVirtualKeyboard(@NonNull VirtualDisplay display,
                @NonNull String inputDeviceName, int vendorId, int productId) {
            VirtualKeyboardConfig keyboardConfig =
                    new VirtualKeyboardConfig.Builder()
                            .setVendorId(vendorId)
                            .setProductId(productId)
                            .setInputDeviceName(inputDeviceName)
                            .setAssociatedDisplayId(display.getDisplay().getDisplayId())
                            .build();
            return createVirtualKeyboard(keyboardConfig);
        }

        /**
         * Creates a virtual mouse.
         *
         * @param config the configurations of the virtual mouse.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualMouse createVirtualMouse(@NonNull VirtualMouseConfig config) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualMouse:" + config.getInputDeviceName());
                mVirtualDevice.createVirtualMouse(config, token);
                return new VirtualMouse(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual mouse.
         *
         * @param display         the display that the events inputted through this device should
         *                        target
         * @param inputDeviceName the name to call this input device
         * @param vendorId        the PCI vendor id
         * @param productId       the product id, as defined by the vendor
         * @see #createVirtualMouse(VirtualMouseConfig config)
         * @deprecated Use {@link #createVirtualMouse(VirtualMouseConfig config)} instead
         * *
         */
        @Deprecated
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualMouse createVirtualMouse(@NonNull VirtualDisplay display,
                @NonNull String inputDeviceName, int vendorId, int productId) {
            VirtualMouseConfig mouseConfig =
                    new VirtualMouseConfig.Builder()
                            .setVendorId(vendorId)
                            .setProductId(productId)
                            .setInputDeviceName(inputDeviceName)
                            .setAssociatedDisplayId(display.getDisplay().getDisplayId())
                            .build();
            return createVirtualMouse(mouseConfig);
        }

        /**
         * Creates a virtual touchscreen.
         *
         * @param config the configurations of the virtual touchscreen.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualTouchscreen createVirtualTouchscreen(
                @NonNull VirtualTouchscreenConfig config) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualTouchscreen:" + config.getInputDeviceName());
                mVirtualDevice.createVirtualTouchscreen(config, token);
                return new VirtualTouchscreen(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual touchpad in navigation mode.
         *
         * A touchpad in navigation mode means that its events are interpreted as navigation events
         * (up, down, etc) instead of using them to update a cursor's absolute position. If the
         * events are not consumed they are converted to DPAD events.
         *
         * @param config the configurations of the virtual navigation touchpad.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualNavigationTouchpad createVirtualNavigationTouchpad(
                 @NonNull VirtualNavigationTouchpadConfig config) {
            try {
                final IBinder token = new Binder(
                        "android.hardware.input.VirtualNavigationTouchpad:"
                            + config.getInputDeviceName());
                mVirtualDevice.createVirtualNavigationTouchpad(config, token);
                return new VirtualNavigationTouchpad(mVirtualDevice, token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual touchscreen.
         *
         * @param display         the display that the events inputted through this device should
         *                        target
         * @param inputDeviceName the name to call this input device
         * @param vendorId        the PCI vendor id
         * @param productId       the product id, as defined by the vendor
         * @see #createVirtualTouchscreen(VirtualTouchscreenConfig config)
         * @deprecated Use {@link #createVirtualTouchscreen(VirtualTouchscreenConfig config)}
         * instead
         */
        @Deprecated
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualTouchscreen createVirtualTouchscreen(@NonNull VirtualDisplay display,
                @NonNull String inputDeviceName, int vendorId, int productId) {
            final Point size = new Point();
            display.getDisplay().getSize(size);
            VirtualTouchscreenConfig touchscreenConfig =
                    new VirtualTouchscreenConfig.Builder(size.x, size.y)
                            .setVendorId(vendorId)
                            .setProductId(productId)
                            .setInputDeviceName(inputDeviceName)
                            .setAssociatedDisplayId(display.getDisplay().getDisplayId())
                            .build();
            return createVirtualTouchscreen(touchscreenConfig);
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
                mVirtualAudioDevice = new VirtualAudioDevice(mContext, mVirtualDevice, display,
                        executor, callback, () -> mVirtualAudioDevice = null);
            }
            return mVirtualAudioDevice;
        }

        /**
         * Creates a new virtual camera. If a virtual camera was already created, it will be closed.
         *
         * @param cameraName name of the virtual camera.
         * @param characteristics camera characteristics.
         * @param virtualCameraInput callback that provides input to camera.
         * @param executor Executor on which camera input will be sent into system. Don't
         *         use the Main Thread for this executor.
         * @return newly created camera;
         *
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualCameraDevice createVirtualCameraDevice(
                @NonNull String cameraName,
                @NonNull CameraCharacteristics characteristics,
                @NonNull VirtualCameraInput virtualCameraInput,
                @NonNull Executor executor) {
            if (mVirtualCameraDevice != null) {
                mVirtualCameraDevice.close();
            }
            int deviceId = getDeviceId();
            mVirtualCameraDevice = new VirtualCameraDevice(
                    deviceId, cameraName, characteristics, virtualCameraInput, executor);
            return mVirtualCameraDevice;
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
                // Currently this just use the device ID, which means all of the virtual displays
                // created using the same virtual device will have the same name. The name should
                // only be used for informational purposes, and not for identifying the display in
                // code.
                return "VirtualDevice_" + mVirtualDevice.getDeviceId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a virtual sensor, capable of injecting sensor events into the system. Only for
         * internal use, since device sensors must remain valid for the entire lifetime of the
         * device.
         *
         * @param config The configuration of the sensor.
         * @hide
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualSensor createVirtualSensor(@NonNull VirtualSensorConfig config) {
            Objects.requireNonNull(config);
            try {
                final IBinder token = new Binder(
                        "android.hardware.sensor.VirtualSensor:" + config.getName());
                mVirtualDevice.createVirtualSensor(token, config);
                return new VirtualSensor(config.getType(), config.getName(), mVirtualDevice, token);
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

        /**
         * Registers an intent interceptor that will intercept an intent attempting to launch
         * when matching the provided IntentFilter and calls the callback with the intercepted
         * intent.
         *
         * @param interceptorFilter The filter to match intents intended for interception.
         * @param executor The executor where the interceptor is executed on.
         * @param interceptorCallback The callback called when an intent matching interceptorFilter
         * is intercepted.
         * @see #unregisterIntentInterceptor(IntentInterceptorCallback)
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void registerIntentInterceptor(
                @NonNull IntentFilter interceptorFilter,
                @CallbackExecutor @NonNull Executor executor,
                @NonNull IntentInterceptorCallback interceptorCallback) {
            Objects.requireNonNull(executor);
            Objects.requireNonNull(interceptorFilter);
            Objects.requireNonNull(interceptorCallback);
            final VirtualIntentInterceptorDelegate delegate =
                    new VirtualIntentInterceptorDelegate(executor, interceptorCallback);
            try {
                mVirtualDevice.registerIntentInterceptor(delegate, interceptorFilter);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mIntentInterceptorListeners.put(interceptorCallback, delegate);
        }

        /**
         * Unregisters the intent interceptorCallback previously registered with
         * {@link #registerIntentInterceptor}.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void unregisterIntentInterceptor(
                    @NonNull IntentInterceptorCallback interceptorCallback) {
            Objects.requireNonNull(interceptorCallback);
            final VirtualIntentInterceptorDelegate delegate =
                    mIntentInterceptorListeners.get(interceptorCallback);
            try {
                mVirtualDevice.unregisterIntentInterceptor(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mIntentInterceptorListeners.remove(interceptorCallback);
        }
    }

    /**
     * Listener for activity changes in this virtual device.
     *
     * @hide
     */
    @SystemApi
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

    /**
     * Interceptor interface to be called when an intent matches the IntentFilter passed into {@link
     * VirtualDevice#registerIntentInterceptor}. When the interceptor is called after matching the
     * IntentFilter, the intended activity launch will be aborted and alternatively replaced by
     * the interceptor's receiver.
     *
     * @hide
     */
    @SystemApi
    public interface IntentInterceptorCallback {

        /**
         * Called when an intent that matches the IntentFilter registered in {@link
         * VirtualDevice#registerIntentInterceptor} is intercepted for the virtual device to
         * handle.
         *
         * @param intent The intent that has been intercepted by the interceptor.
         */
        void onIntentIntercepted(@NonNull Intent intent);
    }

    /**
     * A wrapper for {@link IntentInterceptorCallback} that executes callbacks on the
     * the given executor.
     */
    private static class VirtualIntentInterceptorDelegate
            extends IVirtualDeviceIntentInterceptor.Stub {
        @NonNull private final IntentInterceptorCallback mIntentInterceptorCallback;
        @NonNull private final Executor mExecutor;

        private VirtualIntentInterceptorDelegate(Executor executor,
                IntentInterceptorCallback interceptorCallback) {
            mExecutor = executor;
            mIntentInterceptorCallback = interceptorCallback;
        }

        @Override
        public void onIntentIntercepted(Intent intent) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mIntentInterceptorCallback.onIntentIntercepted(intent));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
