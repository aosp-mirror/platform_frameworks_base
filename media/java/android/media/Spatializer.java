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
import android.util.Log;

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

    private static final String TAG = "Spatializer";

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
     * A false value can originate for instance from the user electing to
     * disable the feature.<br>
     * Note that this state reflects a platform-wide state of the "desire" to use spatialization,
     * but availability of the audio processing is still dictated by the compatibility between
     * the effect and the hardware configuration, as indicated by {@link #isAvailable()}.
     * @return {@code true} if spatialization is enabled
     * @see #isAvailable()
     */
    public boolean isEnabled() {
        try {
            return mAm.getService().isSpatializerEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying isSpatializerEnabled, returning false", e);
            return false;
        }
    }

    /**
     * Returns whether spatialization is available.
     * Reasons for spatialization being unavailable include situations where audio output is
     * incompatible with sound spatialization, such as playback on a monophonic speaker.<br>
     * Note that spatialization can be available, but disabled by the user, in which case this
     * method would still return {@code true}, whereas {@link #isEnabled()}
     * would return {@code false}.
     * @return {@code true} if the spatializer effect is available and capable
     *         of processing the audio for the current configuration of the device,
     *         {@code false} otherwise.
     * @see #isEnabled()
     */
    public boolean isAvailable()  {
        try {
            return mAm.getService().isSpatializerAvailable();
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying isSpatializerAvailable, returning false", e);
            return false;
        }
    }

    /** @hide */
    @IntDef(flag = false, value = {
            SPATIALIZER_IMMERSIVE_LEVEL_OTHER,
            SPATIALIZER_IMMERSIVE_LEVEL_NONE,
            SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImmersiveAudioLevel {};

    /**
     * Constant indicating the {@code Spatializer} on this device supports a spatialization
     * mode that differs from the ones available at this SDK level.
     * @see #getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_OTHER = -1;

    /**
     * Constant indicating there are no spatialization capabilities supported on this device.
     * @see #getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_NONE = 0;

    /**
     * Constant indicating the {@code Spatializer} on this device supports multichannel
     * spatialization.
     * @see #getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL = 1;

    /**
     * Return the level of support for the spatialization feature on this device.
     * This level of support is independent of whether the {@code Spatializer} is currently
     * enabled or available and will not change over time.
     * @return the level of spatialization support
     * @see #isEnabled()
     * @see #isAvailable()
     */
    public @ImmersiveAudioLevel int getImmersiveAudioLevel() {
        int level = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        try {
            level = mAm.getService().getSpatializerImmersiveAudioLevel();
        } catch (Exception e) { /* using NONE */ }
        return level;
    }

    /**
     * @hide
     * Enables / disables the spatializer effect.
     * Changing the enabled state will trigger the public
     * {@link OnSpatializerStateChangedListener#onSpatializerEnabledChanged(Spatializer, boolean)}
     * registered listeners.
     * @param enabled {@code true} for enabling the effect
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setEnabled(boolean enabled) {
        try {
            mAm.getService().setSpatializerEnabled(enabled);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setSpatializerEnabled", e);
        }
    }

    /**
     * An interface to be notified of changes to the state of the spatializer.
     */
    public interface OnSpatializerStateChangedListener {
        /**
         * Called when the enabled state of the spatializer effect changes
         * @param spat the {@code Spatializer} instance whose state changed
         * @param enabled {@code true} if the spatializer effect is enabled on the device,
         *                            {@code false} otherwise
         * @see #isEnabled()
         */
        void onSpatializerEnabledChanged(@NonNull Spatializer spat, boolean enabled);

        /**
         * Called when the availability of the spatializer effect changes
         * @param spat the {@code Spatializer} instance whose state changed
         * @param available {@code true} if the spatializer effect is available and capable
         *                  of processing the audio for the current configuration of the device,
         *                  {@code false} otherwise.
         * @see #isAvailable()
         */
        void onSpatializerAvailableChanged(@NonNull Spatializer spat, boolean available);
    }

    /**
     * Returns whether audio of the given {@link AudioFormat}, played with the given
     * {@link AudioAttributes} can be spatialized.
     * Note that the result reflects the capabilities of the device and may change when
     * audio accessories are connected/disconnected (e.g. wired headphones plugged in or not).
     * The result is independent from whether spatialization processing is enabled or not.
     * @param attributes the {@code AudioAttributes} of the content as used for playback
     * @param format the {@code AudioFormat} of the content as used for playback
     * @return {@code true} if the device is capable of spatializing the combination of audio format
     *     and attributes, {@code false} otherwise.
     */
    public boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        try {
            return mAm.getService().canBeSpatialized(
                    Objects.requireNonNull(attributes), Objects.requireNonNull(format));
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying canBeSpatialized for attr:" + attributes
                    + " format:" + format + " returning false", e);
            return false;
        }
    }

    /**
     * Adds a listener to be notified of changes to the enabled state of the
     * {@code Spatializer}.
     * @param executor the {@code Executor} handling the callback
     * @param listener the listener to receive enabled state updates
     * @see #isEnabled()
     */
    public void addOnSpatializerStateChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSpatializerStateChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        synchronized (mStateListenerLock) {
            if (hasSpatializerStateListener(listener)) {
                throw new IllegalArgumentException(
                        "Called addOnSpatializerStateChangedListener() "
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
     * @param listener the listener to receive enabled state updates
     * @see #isEnabled()
     */
    public void removeOnSpatializerStateChangedListener(
            @NonNull OnSpatializerStateChangedListener listener) {
        Objects.requireNonNull(listener);
        synchronized (mStateListenerLock) {
            if (!removeStateListener(listener)) {
                throw new IllegalArgumentException(
                        "Called removeOnSpatializerStateChangedListener() "
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
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        try {
            return mAm.getService().getSpatializerCompatibleAudioDevices();
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying getSpatializerCompatibleAudioDevices(), "
                    + " returning empty list", e);
            return new ArrayList<AudioDeviceAttributes>(0);
        }
    }

    /**
     * @hide
     * Adds a playback device to the list of devices compatible with the playback of multichannel
     * audio through spatialization.
     * @see #getCompatibleAudioDevices()
     * @param ada the audio device compatible with spatialization
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        try {
            mAm.getService().addSpatializerCompatibleAudioDevice(Objects.requireNonNull(ada));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling addSpatializerCompatibleAudioDevice(), ", e);
        }
    }

    /**
     * @hide
     * Remove a playback device from the list of devices compatible with the playback of
     * multichannel audio through spatialization.
     * @see #getCompatibleAudioDevices()
     * @param ada the audio device incompatible with spatialization
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        try {
            mAm.getService().removeSpatializerCompatibleAudioDevice(Objects.requireNonNull(ada));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling removeSpatializerCompatibleAudioDevice(), ", e);
        }
    }

    private final class SpatializerInfoDispatcherStub extends ISpatializerCallback.Stub {
        @Override
        public void dispatchSpatializerEnabledChanged(boolean enabled) {
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
                            info.mListener.onSpatializerEnabledChanged(Spatializer.this, enabled));
                }
            }
        }

        @Override
        public void dispatchSpatializerAvailableChanged(boolean available) {
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
                            info.mListener.onSpatializerAvailableChanged(
                                    Spatializer.this, available));
                }
            }
        }
    }

    private static class StateListenerInfo {
        final @NonNull OnSpatializerStateChangedListener mListener;
        final @NonNull Executor mExecutor;

        StateListenerInfo(@NonNull OnSpatializerStateChangedListener listener,
                @NonNull Executor exe) {
            mListener = listener;
            mExecutor = exe;
        }
    }

    @GuardedBy("mStateListenerLock")
    private boolean hasSpatializerStateListener(OnSpatializerStateChangedListener listener) {
        return getStateListenerInfo(listener) != null;
    }

    @GuardedBy("mStateListenerLock")
    private @Nullable StateListenerInfo getStateListenerInfo(
            OnSpatializerStateChangedListener listener) {
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
    private boolean removeStateListener(OnSpatializerStateChangedListener listener) {
        final StateListenerInfo infoToRemove = getStateListenerInfo(listener);
        if (infoToRemove != null) {
            mStateListeners.remove(infoToRemove);
            return true;
        }
        return false;
    }
}
