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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide
 * AudioDeviceVolumeManager provides access to audio device volume control.
 */
public class AudioDeviceVolumeManager {

    // define when using Log.*
    //private static final String TAG = "AudioDeviceVolumeManager";
    private static IAudioService sService;

    private final String mPackageName;

    public AudioDeviceVolumeManager(Context context) {
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
    }

    static class ListenerInfo {
        final @NonNull OnAudioDeviceVolumeChangedListener mListener;
        final @NonNull Executor mExecutor;
        final @NonNull AudioDeviceAttributes mDevice;

        ListenerInfo(@NonNull OnAudioDeviceVolumeChangedListener listener, @NonNull Executor exe,
                @NonNull AudioDeviceAttributes device) {
            mListener = listener;
            mExecutor = exe;
            mDevice = device;
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

    final class DeviceVolumeDispatcherStub extends IAudioDeviceVolumeDispatcher.Stub {
        /**
         * Register / unregister the stub
         * @param register true for registering, false for unregistering
         * @param device device for which volume is monitored
         */
        public void register(boolean register, @NonNull AudioDeviceAttributes device,
                @NonNull List<VolumeInfo> volumes) {
            try {
                getService().registerDeviceVolumeDispatcherForAbsoluteVolume(register,
                        this, mPackageName,
                        Objects.requireNonNull(device), Objects.requireNonNull(volumes));
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
                if (listenerInfo.mDevice.equals(device)) {
                    listenerInfo.mExecutor.execute(
                            () -> listenerInfo.mListener.onAudioDeviceVolumeChanged(device, vol));
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
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        final ArrayList<VolumeInfo> volumes = new ArrayList<>(1);
        volumes.add(volume);
        setDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(volumes);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(vclistener);

        // TODO verify not already registered
        //final ListenerInfo listenerInfo = new ListenerInfo(vclistener, executor, device);
        synchronized (mDeviceVolumeListenerLock) {
            if (mDeviceVolumeListeners == null) {
                mDeviceVolumeListeners = new ArrayList<>();
            }
            if (mDeviceVolumeListeners.size() == 0) {
                if (mDeviceVolumeDispatcherStub == null) {
                    mDeviceVolumeDispatcherStub = new DeviceVolumeDispatcherStub();
                }
            }
            mDeviceVolumeDispatcherStub.register(true, device, volumes);
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
