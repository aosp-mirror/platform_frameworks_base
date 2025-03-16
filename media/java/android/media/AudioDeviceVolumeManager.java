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

package android.media;

import static com.android.media.flags.Flags.FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide
 * AudioDeviceVolumeManager provides access to audio device volume control.
 */
@SystemApi
public class AudioDeviceVolumeManager {

    private static final String TAG = "AudioDeviceVolumeManager";

    /** @hide
     * Indicates no special treatment in the handling of the volume adjustment */
    public static final int ADJUST_MODE_NORMAL = 0;
    /** @hide
     * Indicates the start of a volume adjustment */
    public static final int ADJUST_MODE_START = 1;
    /** @hide
     * Indicates the end of a volume adjustment */
    public static final int ADJUST_MODE_END = 2;

    /** @hide */
    @IntDef(flag = false, prefix = "ADJUST_MODE", value = {
            ADJUST_MODE_NORMAL,
            ADJUST_MODE_START,
            ADJUST_MODE_END}
    )
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeAdjustmentMode {}

    private static IAudioService sService;

    private final @NonNull String mPackageName;

    /**
     * @hide
     * Constructor
     * @param context the Context for the device volume operations
     */
    public AudioDeviceVolumeManager(@NonNull Context context) {
        Objects.requireNonNull(context);
        mPackageName = context.getApplicationContext().getOpPackageName();
    }

    /**
     * @hide
     * Interface to receive volume changes on a device that behaves in absolute volume mode.
     * @see #setDeviceAbsoluteMultiVolumeBehavior(AudioDeviceAttributes, List, Executor,
     *         OnAudioDeviceVolumeChangeListener)
     * @see #setDeviceAbsoluteVolumeBehavior(AudioDeviceAttributes, VolumeInfo, Executor,
     *         OnAudioDeviceVolumeChangeListener)
     */
    public interface OnAudioDeviceVolumeChangedListener {
        /**
         * Called the device for the given audio device has changed.
         * @param device the audio device whose volume has changed
         * @param vol the new volume for the device
         */
        void onAudioDeviceVolumeChanged(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo vol);

        /**
         * Called when the volume for the given audio device has been adjusted.
         * @param device the audio device whose volume has been adjusted
         * @param vol the volume info for the device
         * @param direction the direction of the adjustment
         * @param mode the volume adjustment mode
         */
        void onAudioDeviceVolumeAdjusted(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo vol,
                @AudioManager.VolumeAdjustment int direction,
                @VolumeAdjustmentMode int mode);
    }

    /** @hide */
    static class ListenerInfo {
        final @NonNull OnAudioDeviceVolumeChangedListener mListener;
        final @NonNull Executor mExecutor;
        final @NonNull AudioDeviceAttributes mDevice;
        final @NonNull boolean mHandlesVolumeAdjustment;

        ListenerInfo(@NonNull OnAudioDeviceVolumeChangedListener listener, @NonNull Executor exe,
                @NonNull AudioDeviceAttributes device, boolean handlesVolumeAdjustment) {
            mListener = listener;
            mExecutor = exe;
            mDevice = device;
            mHandlesVolumeAdjustment = handlesVolumeAdjustment;
        }
    }

    private final Object mDeviceVolumeListenerLock = new Object();
    /**
     * List of listeners for volume changes, the associated device, and their associated Executor.
     * List is lazy-initialized on first registration
     */
    @GuardedBy("mDeviceVolumeListenerLock")
    private @Nullable ArrayList<ListenerInfo> mDeviceVolumeListeners;

    @GuardedBy("mDeviceVolumeListenerLock")
    private DeviceVolumeDispatcherStub mDeviceVolumeDispatcherStub;

    /** @hide */
    final class DeviceVolumeDispatcherStub extends IAudioDeviceVolumeDispatcher.Stub {
        /**
         * Register / unregister the stub
         * @param register true for registering, false for unregistering
         * @param device device for which volume is monitored
         */
        @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED })
        public void register(boolean register, @NonNull AudioDeviceAttributes device,
                @NonNull List<VolumeInfo> volumes, boolean handlesVolumeAdjustment,
                @AudioManager.AbsoluteDeviceVolumeBehavior int behavior) {
            try {
                getService().registerDeviceVolumeDispatcherForAbsoluteVolume(register,
                        this, mPackageName,
                        Objects.requireNonNull(device), Objects.requireNonNull(volumes),
                        handlesVolumeAdjustment, behavior);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchDeviceVolumeChanged(
                @NonNull AudioDeviceAttributes device, @NonNull VolumeInfo vol) {
            final ArrayList<ListenerInfo> volumeListeners;
            synchronized (mDeviceVolumeListenerLock) {
                volumeListeners = (ArrayList<ListenerInfo>) mDeviceVolumeListeners.clone();
            }
            for (ListenerInfo listenerInfo : volumeListeners) {
                if (listenerInfo.mDevice.equalTypeAddress(device)) {
                    listenerInfo.mExecutor.execute(
                            () -> listenerInfo.mListener.onAudioDeviceVolumeChanged(device, vol));
                }
            }
        }

        @Override
        public void dispatchDeviceVolumeAdjusted(
                @NonNull AudioDeviceAttributes device, @NonNull VolumeInfo vol, int direction,
                int mode) {
            final ArrayList<ListenerInfo> volumeListeners;
            synchronized (mDeviceVolumeListenerLock) {
                volumeListeners = (ArrayList<ListenerInfo>) mDeviceVolumeListeners.clone();
            }
            for (ListenerInfo listenerInfo : volumeListeners) {
                if (listenerInfo.mDevice.equalTypeAddress(device)) {
                    listenerInfo.mExecutor.execute(
                            () -> listenerInfo.mListener.onAudioDeviceVolumeAdjusted(device, vol,
                                    direction, mode));
                }
            }
        }
    }

    /**
     * @hide
     * Configures a device to use absolute volume model, and registers a listener for receiving
     * volume updates to apply on that device
     * @param device the audio device set to absolute volume mode
     * @param volume the type of volume this device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment) {
        final ArrayList<VolumeInfo> volumes = new ArrayList<>(1);
        volumes.add(volume);
        setDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     * @param handlesVolumeAdjustment whether the controller handles volume adjustments separately
     *  from volume changes. If true, adjustments from {@link AudioManager#adjustStreamVolume}
     *  will be sent via {@link OnAudioDeviceVolumeChangedListener#onAudioDeviceVolumeAdjusted}.
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment) {
        baseSetDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment, AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model, and registers a listener for receiving
     * volume updates to apply on that device.
     *
     * Should be used instead of {@link #setDeviceAbsoluteVolumeBehavior} when there is no reliable
     * way to set the device's volume to a percentage.
     *
     * @param device the audio device set to absolute volume mode
     * @param volume the type of volume this device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteVolumeAdjustOnlyBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment) {
        final ArrayList<VolumeInfo> volumes = new ArrayList<>(1);
        volumes.add(volume);
        setDeviceAbsoluteMultiVolumeAdjustOnlyBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device.
     *
     * Should be used instead of {@link #setDeviceAbsoluteMultiVolumeBehavior} when there is
     * no reliable way to set the device's volume to a percentage.
     *
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteMultiVolumeAdjustOnlyBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment) {
        baseSetDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment, AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);
    }

    /**
     * Base method for configuring a device to use absolute volume behavior, or one of its variants.
     * See {@link AudioManager#AbsoluteDeviceVolumeBehavior} for a list of allowed behaviors.
     *
     * @param behavior the variant of absolute device volume behavior to adopt
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    private void baseSetDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment,
            @AudioManager.AbsoluteDeviceVolumeBehavior int behavior) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(volumes);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(vclistener);

        final ListenerInfo listenerInfo = new ListenerInfo(
                vclistener, executor, device, handlesVolumeAdjustment);
        synchronized (mDeviceVolumeListenerLock) {
            if (mDeviceVolumeListeners == null) {
                mDeviceVolumeListeners = new ArrayList<>();
            }
            if (mDeviceVolumeListeners.size() == 0) {
                if (mDeviceVolumeDispatcherStub == null) {
                    mDeviceVolumeDispatcherStub = new DeviceVolumeDispatcherStub();
                }
            } else {
                mDeviceVolumeListeners.removeIf(info -> info.mDevice.equalTypeAddress(device));
            }
            mDeviceVolumeListeners.add(listenerInfo);
            mDeviceVolumeDispatcherStub.register(true, device, volumes, handlesVolumeAdjustment,
                    behavior);
        }
    }

    /**
     * Manages the OnDeviceVolumeBehaviorChangedListener listeners and
     * DeviceVolumeBehaviorDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnDeviceVolumeBehaviorChangedListener>
            mDeviceVolumeBehaviorChangedListenerMgr = new CallbackUtil.LazyListenerManager();

    /**
     * @hide
     * Interface definition of a callback to be invoked when the volume behavior of an audio device
     * is updated.
     */
    public interface OnDeviceVolumeBehaviorChangedListener {
        /**
         * Called on the listener to indicate that the volume behavior of a device has changed.
         * @param device the audio device whose volume behavior changed
         * @param volumeBehavior the new volume behavior of the audio device
         */
        void onDeviceVolumeBehaviorChanged(
                @NonNull AudioDeviceAttributes device,
                @AudioManager.DeviceVolumeBehavior int volumeBehavior);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to any device's volume behavior.
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.QUERY_AUDIO_STATE
    })
    public void addOnDeviceVolumeBehaviorChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnDeviceVolumeBehaviorChangedListener listener)
            throws SecurityException {
        mDeviceVolumeBehaviorChangedListenerMgr.addListener(executor, listener,
                "addOnDeviceVolumeBehaviorChangedListener",
                () -> new DeviceVolumeBehaviorDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener of changes to device volume behavior.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.QUERY_AUDIO_STATE
    })
    public void removeOnDeviceVolumeBehaviorChangedListener(
            @NonNull OnDeviceVolumeBehaviorChangedListener listener) {
        mDeviceVolumeBehaviorChangedListenerMgr.removeListener(listener,
                "removeOnDeviceVolumeBehaviorChangedListener");
    }

    /**
     * @hide
     * Sets the volume on the given audio device
     * @param vi the volume information, only stream-based volumes are supported
     * @param ada the device for which volume is to be modified
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public void setDeviceVolume(@NonNull VolumeInfo vi, @NonNull AudioDeviceAttributes ada) {
        try {
            getService().setDeviceVolume(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns the volume on the given audio device for the given volume information.
     * For instance if using a {@link VolumeInfo} configured for {@link AudioManager#STREAM_ALARM},
     * it will return the alarm volume. When no volume index has ever been set for the given
     * device, the default volume will be returned (the volume setting that would have been
     * applied if playback for that use case had started).
     * @param vi the volume information, only stream-based volumes are supported. Information
     *           other than the stream type is ignored.
     * @param ada the device for which volume is to be retrieved
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public @NonNull VolumeInfo getDeviceVolume(@NonNull VolumeInfo vi,
            @NonNull AudioDeviceAttributes ada) {
        try {
            return getService().getDeviceVolume(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return VolumeInfo.getDefaultVolumeInfo();
    }

    /**
     * @hide
     * Sets the input gain index for a particular AudioDeviceAttributes.
     * TODO(b/364923030): create InputVolumeInfo on top of VolumeInfo rather than using index to
     * handle volume information, to solve issues e.g. gain index ranges might be different for
     * different categories of devices.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setInputGainIndex(@NonNull AudioDeviceAttributes ada, int index) {
        try {
            getService().setInputGainIndex(ada, index);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the input gain index for a particular AudioDeviceAttributes.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getInputGainIndex(@NonNull AudioDeviceAttributes ada) {
        try {
            return getService().getInputGainIndex(ada);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the maximum input gain index for input device.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getMaxInputGainIndex() {
        try {
            return getService().getMaxInputGainIndex();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the minimum input gain index for input device.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getMinInputGainIndex() {
        try {
            return getService().getMinInputGainIndex();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Indicates if an input device does not support input gain control.
     *     <p>The following APIs have no effect when input gain is fixed:
     *     <ul>
     *       <li>{@link #setInputGainIndex(AudioDeviceAttributes, int)}
     *     </ul>
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isInputGainFixed(@NonNull AudioDeviceAttributes ada) {
        try {
            return getService().isInputGainFixed(ada);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return human-readable name for volume behavior
     * @param behavior one of the volume behaviors defined in AudioManager
     * @return a string for the given behavior
     */
    public static String volumeBehaviorName(@AudioManager.DeviceVolumeBehavior int behavior) {
        switch (behavior) {
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE:
                return "DEVICE_VOLUME_BEHAVIOR_VARIABLE";
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL:
                return "DEVICE_VOLUME_BEHAVIOR_FULL";
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED:
                return "DEVICE_VOLUME_BEHAVIOR_FIXED";
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE";
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE";
            case AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY";
            default:
                return "invalid volume behavior " + behavior;
        }
    }

    private final class DeviceVolumeBehaviorDispatcherStub
            extends IDeviceVolumeBehaviorDispatcher.Stub implements CallbackUtil.DispatcherStub {
        public void register(boolean register) {
            try {
                getService().registerDeviceVolumeBehaviorDispatcher(register, this);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchDeviceVolumeBehaviorChanged(@NonNull AudioDeviceAttributes device,
                @AudioManager.DeviceVolumeBehavior int volumeBehavior) {
            mDeviceVolumeBehaviorChangedListenerMgr.callListeners((listener) ->
                    listener.onDeviceVolumeBehaviorChanged(device, volumeBehavior));
        }
    }

    private static IAudioService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
}
