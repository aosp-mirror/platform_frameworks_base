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

package android.media;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.SafeCloseable;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Spatializer provides access to querying capabilities and behavior of sound spatialization
 * on the device.
 * Sound spatialization simulates sounds originating around the listener as if they were coming
 * from virtual speakers placed around the listener.<br>
 * Support for spatialization is optional, use {@link AudioManager#getSpatializer()} to obtain an
 * instance of this class if the feature is supported.
 *
 */
public class Spatializer {

    private final @NonNull AudioManager mAm;

    private final Object mStateListenerLock = new Object();

    /**
     * List of listeners for state listener and their associated Executor.
     * List is lazy-initialized on first registration
     */
    @GuardedBy("mStateListenerLock")
    private @Nullable ArrayList<StateListenerInfo> mStateListeners;

    @GuardedBy("mStateListenerLock")
    private SpatializerInfoDispatcherStub mInfoDispatcherStub;

    /**
     * @hide
     * Constructor with AudioManager acting as proxy to AudioService
     * @param am a non-null AudioManager
     */
    protected Spatializer(@NonNull AudioManager am) {
        mAm = Objects.requireNonNull(am);
    }

    /**
     * Returns whether spatialization is enabled or not.
     * A false value can originate from a number of sources, examples are the user electing to
     * disable the feature, or the use of an audio device that is not compatible with multichannel
     * audio spatialization (for instance playing audio over a monophonic speaker).
     * @return {@code true} if spatialization is enabled
     */
    public boolean isEnabled() {
        return mAm.isSpatializerEnabled();
    }

    /** @hide */
    @IntDef(flag = false, value = {
            SPATIALIZER_IMMERSIVE_LEVEL_NONE,
            SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImmersiveAudioLevel {};

    /**
     * @hide
     * Constant indicating there are no spatialization capabilities supported on this device.
     * @see AudioManager#getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_NONE = 0;

    /**
     * @hide
     * Constant indicating the {@link Spatializer} on this device supports multichannel
     * spatialization.
     * @see AudioManager#getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL = 1;


    /**
     * @hide
     * @param enabled
     * @param device
     */
    //TODO make as API if needed for UX, remove otherwise
    //@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    //@RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setEnabledForDevice(boolean enabled,
            @NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(device);
        mAm.setSpatializerEnabledForDevice(enabled, device);
    }

    /**
     * @hide
     * Enables / disables the spatializer effect
     * @param enabled {@code true} for enabling the effect
     */
    //TODO make as API if needed for UX, remove otherwise
    //@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void setEnabled(boolean enabled) {
        mAm.setSpatializerFeatureEnabled(enabled);
    }

    /**
     * An interface to be notified of changes to the state of the spatializer.
     */
    public interface OnSpatializerEnabledChangedListener {
        /**
         * Called when the enabled state of the Spatializer changes
         * @param enabled {@code true} if the Spatializer effect is enabled on the device,
         *                            {@code false} otherwise
         */
        void onSpatializerEnabledChanged(boolean enabled);
    }

    /**
     * Returns whether audio of the given {@link AudioFormat}, played with the given
     * {@link AudioAttributes} can be spatialized.
     * Note that the result reflects the capabilities of the device and may change when
     * audio accessories are connected/disconnected (e.g. wired headphones plugged in or not).
     * The result is independent from whether spatialization processing is enabled or not.
     * @param attributes the {@code AudioAttributes} of the content as used for playback
     * @param format the {@code AudioFormat} of the content as used for playback
     * @return true if the device is capable of spatializing the combination of audio format and
     *     attributes.
     */
    public boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        return mAm.canBeSpatialized(
                Objects.requireNonNull(attributes), Objects.requireNonNull(format));
    }

    /**
     * Adds a listener to be notified of changes to the enabled state of the
     * {@code Spatializer}.
     * @see #isEnabled()
     * @param executor the {@code Executor} handling the callback
     * @param listener the listener to receive enabled state updates
     */
    public void addOnSpatializerEnabledChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSpatializerEnabledChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        synchronized (mStateListenerLock) {
            if (hasSpatializerStateListener(listener)) {
                throw new IllegalArgumentException(
                        "Called addOnSpatializerEnabledChangedListener() "
                        + "on a previously registered listener");
            }
            // lazy initialization of the list of strategy-preferred device listener
            if (mStateListeners == null) {
                mStateListeners = new ArrayList<>();
            }
            mStateListeners.add(new StateListenerInfo(listener, executor));
            if (mStateListeners.size() == 1) {
                // register binder for callbacks
                if (mInfoDispatcherStub == null) {
                    mInfoDispatcherStub =
                            new SpatializerInfoDispatcherStub();
                }
                try {
                    mAm.getService().registerSpatializerCallback(
                            mInfoDispatcherStub);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Removes a previously added listener for changes to the enabled state of the
     * {@code Spatializer}.
     * @see #isEnabled()
     * @param listener the listener to receive enabled state updates
     */
    public void removeOnSpatializerEnabledChangedListener(
            @NonNull OnSpatializerEnabledChangedListener listener) {
        Objects.requireNonNull(listener);
        synchronized (mStateListenerLock) {
            if (!removeStateListener(listener)) {
                throw new IllegalArgumentException(
                        "Called removeOnSpatializerEnabledChangedListener() "
                        + "on an unregistered listener");
            }
            if (mStateListeners.size() == 0) {
                // unregister binder for callbacks
                try {
                    mAm.getService().unregisterSpatializerCallback(mInfoDispatcherStub);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } finally {
                    mInfoDispatcherStub = null;
                    mStateListeners = null;
                }
            }
        }
    }

    /**
     * @hide
     * Returns the list of playback devices that are compatible with the playback of multichannel
     * audio through virtualization
     * @return a list of devices. An empty list indicates virtualization is not supported.
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        return mAm.getSpatializerCompatibleAudioDevices();
    }

    /**
     * @hide
     * Adds a playback device to the list of devices compatible with the playback of multichannel
     * audio through spatialization.
     * @see #getCompatibleAudioDevices()
     * @param ada the audio device compatible with spatialization
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mAm.addSpatializerCompatibleAudioDevice(Objects.requireNonNull(ada));
    }

    /**
     * @hide
     * Remove a playback device from the list of devices compatible with the playback of
     * multichannel audio through spatialization.
     * @see #getCompatibleAudioDevices()
     * @param ada the audio device incompatible with spatialization
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mAm.removeSpatializerCompatibleAudioDevice(Objects.requireNonNull(ada));
    }

    private final class SpatializerInfoDispatcherStub
            extends ISpatializerCallback.Stub {
        @Override
        public void dispatchSpatializerStateChanged(boolean enabled) {
            // make a shallow copy of listeners so callback is not executed under lock
            final ArrayList<StateListenerInfo> stateListeners;
            synchronized (mStateListenerLock) {
                if (mStateListeners == null || mStateListeners.size() == 0) {
                    return;
                }
                stateListeners = (ArrayList<StateListenerInfo>) mStateListeners.clone();
            }
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                for (StateListenerInfo info : stateListeners) {
                    info.mExecutor.execute(() ->
                            info.mListener.onSpatializerEnabledChanged(enabled));
                }
            }
        }
    }

    private static class StateListenerInfo {
        final @NonNull OnSpatializerEnabledChangedListener mListener;
        final @NonNull Executor mExecutor;

        StateListenerInfo(@NonNull OnSpatializerEnabledChangedListener listener,
                @NonNull Executor exe) {
            mListener = listener;
            mExecutor = exe;
        }
    }

    @GuardedBy("mStateListenerLock")
    private boolean hasSpatializerStateListener(OnSpatializerEnabledChangedListener listener) {
        return getStateListenerInfo(listener) != null;
    }

    @GuardedBy("mStateListenerLock")
    private @Nullable StateListenerInfo getStateListenerInfo(
            OnSpatializerEnabledChangedListener listener) {
        if (mStateListeners == null) {
            return null;
        }
        for (StateListenerInfo info : mStateListeners) {
            if (info.mListener == listener) {
                return info;
            }
        }
        return null;
    }

    @GuardedBy("mStateListenerLock")
    /**
     * @return true if the listener was removed from the list
     */
    private boolean removeStateListener(OnSpatializerEnabledChangedListener listener) {
        final StateListenerInfo infoToRemove = getStateListenerInfo(listener);
        if (infoToRemove != null) {
            mStateListeners.remove(infoToRemove);
            return true;
        }
        return false;
    }
}
