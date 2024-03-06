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
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.virtual.audio.VirtualAudioDevice;
import android.companion.virtual.audio.VirtualAudioDevice.AudioConfigurationChangeCallback;
import android.companion.virtual.camera.VirtualCamera;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.flags.Flags;
import android.companion.virtual.sensor.VirtualSensor;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.VirtualDisplayFlag;
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
import android.hardware.input.VirtualStylus;
import android.hardware.input.VirtualStylusConfig;
import android.hardware.input.VirtualTouchscreen;
import android.hardware.input.VirtualTouchscreenConfig;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * System level service for creation and management of virtual devices.
 *
 * <p>VirtualDeviceManager enables interactive sharing of capabilities between the host Android
 * device and a remote device.
 *
 * <p class="note">Not to be confused with the Android Studio's Virtual Device Manager, which allows
 * for device emulation.
 */
@SystemService(Context.VIRTUAL_DEVICE_SERVICE)
public final class VirtualDeviceManager {

    private static final String TAG = "VirtualDeviceManager";

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

    /**
     * Persistent device identifier corresponding to the default device.
     *
     * @see Context#DEVICE_ID_DEFAULT
     * @see VirtualDevice#getPersistentDeviceId()
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_PERSISTENT_DEVICE_ID_API)
    public static final String PERSISTENT_DEVICE_ID_DEFAULT =
            "default:" + Context.DEVICE_ID_DEFAULT;

    private final IVirtualDeviceManager mService;
    private final Context mContext;

    @GuardedBy("mVirtualDeviceListeners")
    private final List<VirtualDeviceListenerDelegate> mVirtualDeviceListeners = new ArrayList<>();

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
        Objects.requireNonNull(params, "params must not be null");
        try {
            return new VirtualDevice(mService, mContext, associationId, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the details of all available virtual devices.
     *
     * <p>The returned objects are read-only representations that expose the properties of all
     * existing virtual devices.</p>
     *
     * <p>Note that if a virtual device is closed and becomes invalid, the returned objects will
     * not be updated and may contain stale values.</p>
     */
    // TODO(b/310912420): Add "Use a VirtualDeviceListener for real time updates of the
    // availability  of virtual devices." in the note paragraph above with a link annotation.
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
     * Returns the details of the virtual device with the given ID, if any.
     *
     * <p>The returned object is a read-only representation of the virtual device that expose its
     * properties.</p>
     *
     * <p>Note that if the virtual device is closed and becomes invalid, the returned object will
     * not be updated and may contain stale values. Use a {@link VirtualDeviceListener} for real
     * time updates of the availability of virtual devices.</p>
     *
     * @return the virtual device with the requested ID, or {@code null} if no such device exists or
     *   it has already been closed.
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    @Nullable
    public android.companion.virtual.VirtualDevice getVirtualDevice(int deviceId) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return null;
        }
        if (deviceId == Context.DEVICE_ID_INVALID || deviceId == Context.DEVICE_ID_DEFAULT) {
            return null;  // Don't even bother making a Binder call.
        }
        try {
            return mService.getVirtualDevice(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a virtual device listener to receive notifications when virtual devices are created
     * or closed.
     *
     * @param executor The executor where the listener is executed on.
     * @param listener The listener to add.
     * @see #unregisterVirtualDeviceListener
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public void registerVirtualDeviceListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull VirtualDeviceListener listener) {
        if (mService == null) {
            Log.w(TAG, "Failed to register listener; no virtual device manager service.");
            return;
        }
        final VirtualDeviceListenerDelegate delegate =
                new VirtualDeviceListenerDelegate(Objects.requireNonNull(executor),
                        Objects.requireNonNull(listener));
        synchronized (mVirtualDeviceListeners) {
            try {
                mService.registerVirtualDeviceListener(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mVirtualDeviceListeners.add(delegate);
        }
    }

    /**
     * Unregisters a virtual device listener previously registered with
     * {@link #registerVirtualDeviceListener}.
     *
     * @param listener The listener to unregister.
     * @see #registerVirtualDeviceListener
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public void unregisterVirtualDeviceListener(@NonNull VirtualDeviceListener listener) {
        if (mService == null) {
            Log.w(TAG, "Failed to unregister listener; no virtual device manager service.");
            return;
        }
        Objects.requireNonNull(listener);
        synchronized (mVirtualDeviceListeners) {
            final Iterator<VirtualDeviceListenerDelegate> it = mVirtualDeviceListeners.iterator();
            while (it.hasNext()) {
                final VirtualDeviceListenerDelegate delegate = it.next();
                if (delegate.mListener == listener) {
                    try {
                        mService.unregisterVirtualDeviceListener(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    it.remove();
                }
            }
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
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public @VirtualDeviceParams.DevicePolicy int getDevicePolicy(
            int deviceId, @VirtualDeviceParams.PolicyType int policyType) {
        if (deviceId == Context.DEVICE_ID_DEFAULT) {
            // Avoid unnecessary binder call, for default device, policy will be always default.
            return VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
        }
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
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public int getDeviceIdForDisplayId(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == Display.INVALID_DISPLAY) {
            // Avoid unnecessary binder call for default / invalid display id.
            return Context.DEVICE_ID_DEFAULT;
        }
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return Context.DEVICE_ID_DEFAULT;
        }
        try {
            return mService.getDeviceIdForDisplayId(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the display name for a given persistent device ID.
     *
     * <p>This will work even if currently there is no valid virtual device with the given
     * persistent ID, as long as such a device has been created or can be created.</p>
     *
     * @return the display name associated with the given persistent device ID, or {@code null} if
     *     the persistent ID is invalid or does not correspond to a virtual device.
     *
     * @hide
     */
    // TODO(b/315481938): Link @see VirtualDevice#getPersistentDeviceId()
    @FlaggedApi(Flags.FLAG_PERSISTENT_DEVICE_ID_API)
    @SystemApi
    @Nullable
    public CharSequence getDisplayNameForPersistentDeviceId(@NonNull String persistentDeviceId) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return null;
        }
        try {
            return mService.getDisplayNameForPersistentDeviceId(
                    Objects.requireNonNull(persistentDeviceId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all current persistent device IDs, including the ones for which no virtual device
     * exists, as long as one may have existed or can be created.
     *
     * @hide
     */
    // TODO(b/315481938): Link @see VirtualDevice#getPersistentDeviceId()
    @FlaggedApi(Flags.FLAG_PERSISTENT_DEVICE_ID_API)
    @SystemApi
    @NonNull
    public Set<String> getAllPersistentDeviceIds() {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve persistent ids; no virtual device manager service.");
            return Collections.emptySet();
        }
        try {
            return new ArraySet<>(mService.getAllPersistentDeviceIds());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the passed {@code deviceId} is a valid virtual device ID or not.
     * {@link Context#DEVICE_ID_DEFAULT} is not valid as it is the ID of the default
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
     *   {@link AudioManager#generateAudioSessionId}) if virtual device has
     *   {@link VirtualDeviceParams#POLICY_TYPE_AUDIO} set to
     *   {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM} and Virtual Audio Device
     *   is configured in context-aware mode. Otherwise
     *   {@link AudioManager#AUDIO_SESSION_ID_GENERATE} constant is returned.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
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
     *   {@link AudioManager#generateAudioSessionId}) if virtual device has
     *   {@link VirtualDeviceParams#POLICY_TYPE_AUDIO} set to
     *   {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM} and Virtual Audio Device
     *   is configured in context-aware mode. Otherwise
     *   {@link AudioManager#AUDIO_SESSION_ID_GENERATE} constant is returned.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
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
     * @see AudioManager#playSoundEffect(int)
     *
     * @param deviceId - id of the virtual audio device
     * @param effectType the type of sound effect
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public void playSoundEffect(int deviceId, @AudioManager.SystemSoundEffect int effectType) {
        if (mService == null) {
            Log.w(TAG, "Failed to dispatch sound effect; no virtual device manager service.");
            return;
        }
        try {
            mService.playSoundEffect(deviceId, effectType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given display is an auto-mirror display owned by a virtual device.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_INTERACTIVE_SCREEN_MIRROR)
    @TestApi
    public boolean isVirtualDeviceOwnedMirrorDisplay(int displayId) {
        if (mService == null) {
            Log.w(TAG, "Failed to retrieve virtual devices; no virtual device manager service.");
            return false;
        }
        try {
            return mService.isVirtualDeviceOwnedMirrorDisplay(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A representation of a virtual device.
     *
     * <p>A virtual device can have its own virtual displays, audio input/output, sensors, etc.
     * The creator of a virtual device can take the output from the virtual display and stream it
     * over to another device, and inject input and sensor events that are received from the remote
     * device.
     *
     * <p>This object is only used by the virtual device creator and allows them to manage the
     * device's behavior, peripherals, and the user interaction with that device.
     *
     * <p class="note">Not to be confused with {@link android.companion.virtual.VirtualDevice},
     * which is a read-only representation exposing the properties of an existing virtual device.
     *
     * @hide
     */
    @SystemApi
    public static class VirtualDevice implements AutoCloseable {

        private final VirtualDeviceInternal mVirtualDeviceInternal;

        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        private VirtualDevice(
                IVirtualDeviceManager service,
                Context context,
                int associationId,
                VirtualDeviceParams params) throws RemoteException {
            mVirtualDeviceInternal =
                    new VirtualDeviceInternal(service, context, associationId, params);
        }

        /** @hide */
        public VirtualDevice(IVirtualDeviceManager service, Context context,
                IVirtualDevice virtualDevice) {
            mVirtualDeviceInternal = new VirtualDeviceInternal(service, context, virtualDevice);
        }

        /**
         * Returns the unique ID of this virtual device.
         */
        public int getDeviceId() {
            return mVirtualDeviceInternal.getDeviceId();
        }

        /**
         * Returns the persistent ID of this virtual device.
         */
        @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
        public @Nullable String getPersistentDeviceId() {
            return mVirtualDeviceInternal.getPersistentDeviceId();
        }

        /**
         * Returns a new context bound to this device.
         *
         * <p>This is a convenience method equivalent to calling
         * {@link Context#createDeviceContext(int)} with the id of this device.
         */
        public @NonNull Context createContext() {
            return mVirtualDeviceInternal.createContext();
        }

        /**
         * Returns this device's sensors.
         *
         * @see VirtualDeviceParams.Builder#addVirtualSensorConfig
         *
         * @return A list of all sensors for this device, or an empty list if no sensors exist.
         */
        @NonNull
        public List<VirtualSensor> getVirtualSensorList() {
            return mVirtualDeviceInternal.getVirtualSensorList();
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
            Objects.requireNonNull(pendingIntent, "pendingIntent must not be null");
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            mVirtualDeviceInternal.launchPendingIntent(
                    displayId, pendingIntent, executor, listener);
        }

        /**
         * Creates a virtual display for this virtual device. All displays created on the same
         * device belongs to the same display group.
         *
         * @param width The width of the virtual display in pixels, must be greater than 0.
         * @param height The height of the virtual display in pixels, must be greater than 0.
         * @param densityDpi The density of the virtual display in dpi, must be greater than 0.
         * @param surface The surface to which the content of the virtual display should
         *   be rendered, or null if there is none initially. The surface can also be set later
         *   using {@link VirtualDisplay#setSurface(Surface)}.
         * @param flags A combination of virtual display flags accepted by
         *   {@link DisplayManager#createVirtualDisplay}. In addition, the following flags are
         *   automatically set for all virtual devices:
         *   {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PUBLIC} and
         *   {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY}.
         * @param executor The executor on which {@code callback} will be invoked. This is ignored
         *   if {@code callback} is {@code null}. If {@code callback} is specified, this executor
         *   must not be null.
         * @param callback Callback to call when the state of the {@link VirtualDisplay} changes
         * @return The newly created virtual display, or {@code null} if the application could
         *   not create the virtual display.
         *
         * @see DisplayManager#createVirtualDisplay
         *
         * @deprecated use {@link #createVirtualDisplay(VirtualDisplayConfig, Executor,
         * VirtualDisplay.Callback)}
         */
        @Deprecated
        @Nullable
        public VirtualDisplay createVirtualDisplay(
                @IntRange(from = 1) int width,
                @IntRange(from = 1) int height,
                @IntRange(from = 1) int densityDpi,
                @Nullable Surface surface,
                @VirtualDisplayFlag int flags,
                @Nullable @CallbackExecutor Executor executor,
                @Nullable VirtualDisplay.Callback callback) {
            // Currently this just uses the device ID, which means all of the virtual displays
            // created using the same virtual device will have the same name if they use this
            // deprecated API. The name should only be used for informational purposes, and not for
            // identifying the display in code.
            String virtualDisplayName =  "VirtualDevice_" + getDeviceId();
            VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                    virtualDisplayName, width, height, densityDpi)
                    .setFlags(flags);
            if (surface != null) {
                builder.setSurface(surface);
            }
            return mVirtualDeviceInternal.createVirtualDisplay(builder.build(), executor, callback);
        }

        /**
         * Creates a virtual display for this virtual device. All displays created on the same
         * device belongs to the same display group.
         *
         * @param config The configuration of the display.
         * @param executor The executor on which {@code callback} will be invoked. This is ignored
         *   if {@code callback} is {@code null}. If {@code callback} is specified, this executor
         *   must not be null.
         * @param callback Callback to call when the state of the {@link VirtualDisplay} changes
         * @return The newly created virtual display, or {@code null} if the application could
         *   not create the virtual display.
         *
         * @see DisplayManager#createVirtualDisplay
         */
        @Nullable
        public VirtualDisplay createVirtualDisplay(
                @NonNull VirtualDisplayConfig config,
                @Nullable @CallbackExecutor Executor executor,
                @Nullable VirtualDisplay.Callback callback) {
            Objects.requireNonNull(config, "config must not be null");
            return mVirtualDeviceInternal.createVirtualDisplay(config, executor, callback);
        }

        /**
         * Closes the virtual device, stopping and tearing down any virtual displays, associated
         * virtual audio device, and event injection that's currently in progress.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void close() {
            mVirtualDeviceInternal.close();
        }

        /**
         * Specifies a policy for this virtual device.
         *
         * <p>Policies define the system behavior that may be specific for this virtual device. The
         * given policy must be able to be changed dynamically during the lifetime of the device.
         *
         * @param policyType the type of policy, i.e. which behavior to specify a policy for.
         * @param devicePolicy the value of the policy, i.e. how to interpret the device behavior.
         *
         * @see VirtualDeviceParams#POLICY_TYPE_RECENTS
         * @see VirtualDeviceParams#POLICY_TYPE_ACTIVITY
         */
        @FlaggedApi(Flags.FLAG_DYNAMIC_POLICY)
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void setDevicePolicy(@VirtualDeviceParams.DynamicPolicyType int policyType,
                @VirtualDeviceParams.DevicePolicy int devicePolicy) {
            mVirtualDeviceInternal.setDevicePolicy(policyType, devicePolicy);
        }

        /**
         * Specifies a component name to be exempt from the current activity launch policy.
         *
         * <p>If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} allows activity
         * launches by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_DEFAULT}),
         * then the specified component will be blocked from launching.
         * If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} blocks activity launches
         * by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM}), then the
         * specified component will be allowed to launch.</p>
         *
         * <p>Note that changing the activity launch policy will clear current set of exempt
         * components.</p>
         *
         * @see #removeActivityPolicyExemption
         * @see #setDevicePolicy
         */
        @FlaggedApi(Flags.FLAG_DYNAMIC_POLICY)
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void addActivityPolicyExemption(@NonNull ComponentName componentName) {
            mVirtualDeviceInternal.addActivityPolicyExemption(
                    Objects.requireNonNull(componentName));
        }

        /**
         * Makes the specified component name to adhere to the default activity launch policy.
         *
         * <p>If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} allows activity
         * launches by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_DEFAULT}),
         * then the specified component will be allowed to launch.
         * If the current {@link VirtualDeviceParams#POLICY_TYPE_ACTIVITY} blocks activity launches
         * by default, (i.e. it is {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM}), then the
         * specified component will be blocked from launching.</p>
         *
         * <p>Note that changing the activity launch policy will clear current set of exempt
         * components.</p>
         *
         * @see #addActivityPolicyExemption
         * @see #setDevicePolicy
         */
        @FlaggedApi(Flags.FLAG_DYNAMIC_POLICY)
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void removeActivityPolicyExemption(@NonNull ComponentName componentName) {
            mVirtualDeviceInternal.removeActivityPolicyExemption(
                    Objects.requireNonNull(componentName));
        }

        /**
         * Creates a virtual dpad.
         *
         * @param config the configurations of the virtual dpad.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualDpad createVirtualDpad(@NonNull VirtualDpadConfig config) {
            Objects.requireNonNull(config, "config must not be null");
            return mVirtualDeviceInternal.createVirtualDpad(config);
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param config the configurations of the virtual keyboard.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualKeyboard createVirtualKeyboard(@NonNull VirtualKeyboardConfig config) {
            Objects.requireNonNull(config, "config must not be null");
            return mVirtualDeviceInternal.createVirtualKeyboard(config);
        }

        /**
         * Creates a virtual keyboard.
         *
         * @param display the display that the events inputted through this device should target.
         * @param inputDeviceName the name of this keyboard device.
         * @param vendorId the PCI vendor id.
         * @param productId the product id, as defined by the vendor.
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
            return mVirtualDeviceInternal.createVirtualKeyboard(keyboardConfig);
        }

        /**
         * Creates a virtual mouse.
         *
         * @param config the configurations of the virtual mouse.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualMouse createVirtualMouse(@NonNull VirtualMouseConfig config) {
            Objects.requireNonNull(config, "config must not be null");
            return mVirtualDeviceInternal.createVirtualMouse(config);
        }

        /**
         * Creates a virtual mouse.
         *
         * @param display the display that the events inputted through this device should target.
         * @param inputDeviceName the name of this mouse.
         * @param vendorId the PCI vendor id.
         * @param productId the product id, as defined by the vendor.
         * @see #createVirtualMouse(VirtualMouseConfig config)
         * @deprecated Use {@link #createVirtualMouse(VirtualMouseConfig config)} instead
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
            return mVirtualDeviceInternal.createVirtualMouse(mouseConfig);
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
            Objects.requireNonNull(config, "config must not be null");
            return mVirtualDeviceInternal.createVirtualTouchscreen(config);
        }

        /**
         * Creates a virtual touchscreen.
         *
         * @param display the display that the events inputted through this device should target.
         * @param inputDeviceName the name of this touchscreen device.
         * @param vendorId the PCI vendor id.
         * @param productId the product id, as defined by the vendor.
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
            return mVirtualDeviceInternal.createVirtualTouchscreen(touchscreenConfig);
        }

        /**
         * Creates a virtual touchpad in navigation mode.
         *
         * <p>A touchpad in navigation mode means that its events are interpreted as navigation
         * events (up, down, etc) instead of using them to update a cursor's absolute position. If
         * the events are not consumed they are converted to DPAD events and delivered to the target
         * again.
         *
         * @param config the configurations of the virtual navigation touchpad.
         * @see android.view.InputDevice#SOURCE_TOUCH_NAVIGATION
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualNavigationTouchpad createVirtualNavigationTouchpad(
                @NonNull VirtualNavigationTouchpadConfig config) {
            return mVirtualDeviceInternal.createVirtualNavigationTouchpad(config);
        }

        /**
         * Creates a virtual stylus.
         *
         * @param config the touchscreen configurations for the virtual stylus.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        @FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
        public VirtualStylus createVirtualStylus(
                @NonNull VirtualStylusConfig config) {
            return mVirtualDeviceInternal.createVirtualStylus(config);
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
         *   the callback. If <code>null</code>, the {@link Executor} associated with the main
         *   {@link Looper} will be used.
         * @param callback Interface to be notified when playback or recording configuration of
         *   applications running on virtual display is changed.
         * @return A {@link VirtualAudioDevice} instance.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        public VirtualAudioDevice createVirtualAudioDevice(
                @NonNull VirtualDisplay display,
                @Nullable Executor executor,
                @Nullable AudioConfigurationChangeCallback callback) {
            Objects.requireNonNull(display, "display must not be null");
            return mVirtualDeviceInternal.createVirtualAudioDevice(display, executor, callback);
        }

        /**
         * Creates a new virtual camera with the given {@link VirtualCameraConfig}. A virtual device
         * can create a virtual camera only if it has
         * {@link VirtualDeviceParams#DEVICE_POLICY_CUSTOM} as its
         * {@link VirtualDeviceParams#POLICY_TYPE_CAMERA}.
         *
         * @param config camera configuration.
         * @return newly created camera.
         * @throws UnsupportedOperationException if virtual camera isn't supported on this device.
         * @see VirtualDeviceParams#POLICY_TYPE_CAMERA
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @NonNull
        @FlaggedApi(Flags.FLAG_VIRTUAL_CAMERA)
        public VirtualCamera createVirtualCamera(@NonNull VirtualCameraConfig config) {
            if (!Flags.virtualCamera()) {
                throw new UnsupportedOperationException(
                        "Flag is not enabled: %s".formatted(Flags.FLAG_VIRTUAL_CAMERA));
            }
            return mVirtualDeviceInternal.createVirtualCamera(Objects.requireNonNull(config));
        }

        /**
         * Sets the visibility of the pointer icon for this VirtualDevice's associated displays.
         *
         * @param showPointerIcon True if the pointer should be shown; false otherwise. The default
         *   visibility is true.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void setShowPointerIcon(boolean showPointerIcon) {
            mVirtualDeviceInternal.setShowPointerIcon(showPointerIcon);
        }

        /**
         * Specifies the IME behavior on the given display. By default, all displays created by
         * virtual devices have {@link WindowManager#DISPLAY_IME_POLICY_LOCAL}.
         *
         * @param displayId the ID of the display to change the IME policy for. It must be owned by
         *                  this virtual device.
         * @param policy the IME policy to use on that display
         * @throws SecurityException if the display is not owned by this device or is not
         *                           {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED trusted}
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        @FlaggedApi(Flags.FLAG_VDM_CUSTOM_IME)
        public void setDisplayImePolicy(int displayId, @WindowManager.DisplayImePolicy int policy) {
            if (Flags.vdmCustomIme()) {
                mVirtualDeviceInternal.setDisplayImePolicy(displayId, policy);
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
            mVirtualDeviceInternal.addActivityListener(executor, listener);
        }

        /**
         * Removes an activity listener previously added with {@link #addActivityListener}.
         *
         * @param listener The listener to remove.
         * @see #addActivityListener(Executor, ActivityListener)
         */
        public void removeActivityListener(@NonNull ActivityListener listener) {
            mVirtualDeviceInternal.removeActivityListener(listener);
        }

        /**
         * Adds a sound effect listener.
         *
         * @param executor The executor where the listener is executed on.
         * @param soundEffectListener The listener to add.
         * @see #removeSoundEffectListener(SoundEffectListener)
         */
        public void addSoundEffectListener(@CallbackExecutor @NonNull Executor executor,
                @NonNull SoundEffectListener soundEffectListener) {
            mVirtualDeviceInternal.addSoundEffectListener(executor, soundEffectListener);
        }

        /**
         * Removes a sound effect listener previously added with {@link #addSoundEffectListener}.
         *
         * @param soundEffectListener The listener to remove.
         * @see #addSoundEffectListener(Executor, SoundEffectListener)
         */
        public void removeSoundEffectListener(@NonNull SoundEffectListener soundEffectListener) {
            mVirtualDeviceInternal.removeSoundEffectListener(soundEffectListener);
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
            mVirtualDeviceInternal.registerIntentInterceptor(
                    interceptorFilter, executor, interceptorCallback);
        }

        /**
         * Unregisters the intent interceptor previously registered with
         * {@link #registerIntentInterceptor}.
         */
        @RequiresPermission(android.Manifest.permission.CREATE_VIRTUAL_DEVICE)
        public void unregisterIntentInterceptor(
                    @NonNull IntentInterceptorCallback interceptorCallback) {
            mVirtualDeviceInternal.unregisterIntentInterceptor(interceptorCallback);
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
         * @deprecated Use {@link #onTopActivityChanged(int, ComponentName, int)} instead
         */
        void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity);

        /**
         * Called when the top activity is changed.
         *
         * <p>Note: When there are no activities running on the virtual display, the
         * {@link #onDisplayEmpty(int)} will be called. If the value topActivity is cached, it
         * should be cleared when {@link #onDisplayEmpty(int)} is called.
         *
         * @param displayId The display ID on which the activity change happened.
         * @param topActivity The component name of the top activity.
         * @param userId The user ID associated with the top activity.
         */
        default void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity,
                @UserIdInt int userId) {}

        /**
         * Called when the display becomes empty (e.g. if the user hits back on the last
         * activity of the root task).
         *
         * @param displayId The display ID that became empty.
         */
        void onDisplayEmpty(int displayId);
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
     * Listener for system sound effect playback on virtual device.
     *
     * @hide
     */
    @SystemApi
    public interface SoundEffectListener {

        /**
         * Called when there's a system sound effect to be played on virtual device.
         *
         * @param effectType - system sound effect type
         * @see android.media.AudioManager.SystemSoundEffect
         */
        void onPlaySoundEffect(@AudioManager.SystemSoundEffect int effectType);
    }

    /**
     * Listener for changes in the available virtual devices.
     *
     * @see #registerVirtualDeviceListener
     */
    @FlaggedApi(Flags.FLAG_VDM_PUBLIC_APIS)
    public interface VirtualDeviceListener {
        /**
         * Called whenever a new virtual device has been added to the system.
         * Use {@link VirtualDeviceManager#getVirtualDevice(int)} to get more information about
         * the device.
         *
         * @param deviceId The id of the virtual device that was added.
         */
        default void onVirtualDeviceCreated(int deviceId) {}

        /**
         * Called whenever a virtual device has been removed from the system.
         *
         * @param deviceId The id of the virtual device that was removed.
         */
        default void onVirtualDeviceClosed(int deviceId) {}
    }

    /**
     * A wrapper for {@link VirtualDeviceListener} that executes callbacks on the given executor.
     */
    private static class VirtualDeviceListenerDelegate extends IVirtualDeviceListener.Stub {
        private final VirtualDeviceListener mListener;
        private final Executor mExecutor;

        private VirtualDeviceListenerDelegate(Executor executor, VirtualDeviceListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onVirtualDeviceCreated(int deviceId) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mListener.onVirtualDeviceCreated(deviceId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onVirtualDeviceClosed(int deviceId) {
            final long token = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mListener.onVirtualDeviceClosed(deviceId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
