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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.media.CallbackUtil.ListenerInfo;
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

    private static final String TAG = "Spatializer";

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
     * disable the feature, or when the feature is not supported on the device (indicated
     * by {@link #getImmersiveAudioLevel()} returning {@link #SPATIALIZER_IMMERSIVE_LEVEL_NONE}).
     * <br>
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
     * would return {@code false}.<br>
     * Also when the feature is not supported on the device (indicated
     * by {@link #getImmersiveAudioLevel()} returning {@link #SPATIALIZER_IMMERSIVE_LEVEL_NONE}),
     * the return value will be false.
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

    /**
     * @hide
     * Returns whether spatialization is available for a given audio device
     * Reasons for spatialization being unavailable include situations where audio output is
     * incompatible with sound spatialization, such as the device being a monophonic speaker, or
     * the spatializer effect not supporting transaural processing when querying for speaker.
     * @param device the audio device for which spatializer availability is queried
     * @return {@code true} if the spatializer effect is available and capable
     *         of processing the audio over the given audio device,
     *         {@code false} otherwise.
     * @see #isEnabled()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public boolean isAvailableForDevice(@NonNull AudioDeviceAttributes device)  {
        Objects.requireNonNull(device);
        try {
            return mAm.getService().isSpatializerAvailableForDevice(device);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * @hide
     * Returns whether the given device has an associated headtracker
     * @param device the audio device to query
     * @return true if the device has a head tracker, false otherwise
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public boolean hasHeadTracker(@NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(device);
        try {
            return mAm.getService().hasHeadTracker(device);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * @hide
     * Enables or disables the head tracker of the given device
     * @param enabled true to enable, false to disable
     * @param device the device whose head tracker state is changed
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setHeadTrackerEnabled(boolean enabled, @NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(device);
        try {
            mAm.getService().setHeadTrackerEnabled(enabled, device);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether the head tracker of the device is enabled
     * @param device the device to query
     * @return true if the head tracker is enabled, false if disabled or if there isn't one
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public boolean isHeadTrackerEnabled(@NonNull AudioDeviceAttributes device) {
        Objects.requireNonNull(device);
        try {
            return mAm.getService().isHeadTrackerEnabled(device);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns whether a head tracker is currently available for the audio device used by the
     * spatializer effect.
     * @return true if a head tracker is available and the effect is enabled, false otherwise.
     * @see OnHeadTrackerAvailableListener
     * @see #addOnHeadTrackerAvailableListener(Executor, OnHeadTrackerAvailableListener)
     */
    public boolean isHeadTrackerAvailable() {
        try {
            return mAm.getService().isHeadTrackerAvailable();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Adds a listener to be notified of changes to the availability of a head tracker.
     * @param executor the {@code Executor} handling the callback
     * @param listener the listener to receive availability updates
     * @see #removeOnHeadTrackerAvailableListener(OnHeadTrackerAvailableListener)
     */
    public void addOnHeadTrackerAvailableListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnHeadTrackerAvailableListener listener) {
        mHeadTrackerListenerMgr.addListener(executor, listener,
                "addOnHeadTrackerAvailableListener",
                () -> new SpatializerHeadTrackerAvailableDispatcherStub());
    }

    /**
     * Removes a previously registered listener for the availability of a head tracker.
     * @param listener the listener previously registered with
     *      {@link #addOnHeadTrackerAvailableListener(Executor, OnHeadTrackerAvailableListener)}
     */
    public void removeOnHeadTrackerAvailableListener(
            @NonNull OnHeadTrackerAvailableListener listener) {
        mHeadTrackerListenerMgr.removeListener(listener, "removeOnHeadTrackerAvailableListener");
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
     * @hide
     * Constant indicating the {@code Spatializer} on this device supports the spatialization of
     * multichannel bed plus objects.
     * @see #getImmersiveAudioLevel()
     */
    public static final int SPATIALIZER_IMMERSIVE_LEVEL_MCHAN_BED_PLUS_OBJECTS = 2;

    /** @hide */
    @IntDef(flag = false, value = {
            HEAD_TRACKING_MODE_UNSUPPORTED,
            HEAD_TRACKING_MODE_DISABLED,
            HEAD_TRACKING_MODE_RELATIVE_WORLD,
            HEAD_TRACKING_MODE_RELATIVE_DEVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HeadTrackingMode {};

    /** @hide */
    @IntDef(flag = false, value = {
            HEAD_TRACKING_MODE_DISABLED,
            HEAD_TRACKING_MODE_RELATIVE_WORLD,
            HEAD_TRACKING_MODE_RELATIVE_DEVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HeadTrackingModeSet {};

    /** @hide */
    @IntDef(flag = false, value = {
            HEAD_TRACKING_MODE_RELATIVE_WORLD,
            HEAD_TRACKING_MODE_RELATIVE_DEVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HeadTrackingModeSupported {};

    /**
     * @hide
     * Constant indicating head tracking is not supported by this {@code Spatializer}
     * @see #getHeadTrackingMode()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public static final int HEAD_TRACKING_MODE_UNSUPPORTED = -2;

    /**
     * @hide
     * Constant indicating head tracking is disabled on this {@code Spatializer}
     * @see #getHeadTrackingMode()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public static final int HEAD_TRACKING_MODE_DISABLED = -1;

    /**
     * @hide
     * Constant indicating head tracking is in a mode whose behavior is unknown. This is not an
     * error state but represents a customized behavior not defined by this API.
     * @see #getHeadTrackingMode()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public static final int HEAD_TRACKING_MODE_OTHER = 0;

    /**
     * @hide
     * Constant indicating head tracking is tracking the user's position / orientation relative to
     * the world around them
     * @see #getHeadTrackingMode()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public static final int HEAD_TRACKING_MODE_RELATIVE_WORLD = 1;

    /**
     * @hide
     * Constant indicating head tracking is tracking the user's position / orientation relative to
     * the device
     * @see #getHeadTrackingMode()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public static final int HEAD_TRACKING_MODE_RELATIVE_DEVICE = 2;

    /**
     * @hide
     * Head tracking mode to string conversion
     * @param mode a valid head tracking mode
     * @return a string containing the matching constant name
     */
    public static final String headtrackingModeToString(int mode) {
        switch(mode) {
            case HEAD_TRACKING_MODE_UNSUPPORTED:
                return "HEAD_TRACKING_MODE_UNSUPPORTED";
            case HEAD_TRACKING_MODE_DISABLED:
                return "HEAD_TRACKING_MODE_DISABLED";
            case HEAD_TRACKING_MODE_OTHER:
                return "HEAD_TRACKING_MODE_OTHER";
            case HEAD_TRACKING_MODE_RELATIVE_WORLD:
                return "HEAD_TRACKING_MODE_RELATIVE_WORLD";
            case HEAD_TRACKING_MODE_RELATIVE_DEVICE:
                return "HEAD_TRACKING_MODE_RELATIVE_DEVICE";
            default:
                return "head tracking mode unknown " + mode;
        }
    }

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
     * An interface to be notified of changes to the state of the spatializer effect.
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
     * @hide
     * An interface to be notified of changes to the head tracking mode, used by the spatializer
     * effect.
     * Changes to the mode may come from explicitly setting a different mode
     * (see {@link #setDesiredHeadTrackingMode(int)}) or a change in system conditions (see
     * {@link #getHeadTrackingMode()}
     * @see #addOnHeadTrackingModeChangedListener(Executor, OnHeadTrackingModeChangedListener)
     * @see #removeOnHeadTrackingModeChangedListener(OnHeadTrackingModeChangedListener)
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public interface OnHeadTrackingModeChangedListener {
        /**
         * Called when the actual head tracking mode of the spatializer changed.
         * @param spatializer the {@code Spatializer} instance whose head tracking mode is changing
         * @param mode the new head tracking mode
         */
        void onHeadTrackingModeChanged(@NonNull Spatializer spatializer,
                @HeadTrackingMode int mode);

        /**
         * Called when the desired head tracking mode of the spatializer changed
         * @param spatializer the {@code Spatializer} instance whose head tracking mode was set
         * @param mode the newly set head tracking mode
         */
        void onDesiredHeadTrackingModeChanged(@NonNull Spatializer spatializer,
                @HeadTrackingModeSet int mode);
    }

    /**
     * Interface to be notified of changes to the availability of a head tracker on the audio
     * device to be used by the spatializer effect.
     */
    public interface OnHeadTrackerAvailableListener {
        /**
         * Called when the availability of the head tracker changed.
         * @param spatializer the {@code Spatializer} instance for which the head tracker
         *                    availability was updated
         * @param available true if the audio device that would output audio processed by
         *                  the {@code Spatializer} has a head tracker associated with it, false
         *                  otherwise.
         */
        void onHeadTrackerAvailableChanged(@NonNull Spatializer spatializer,
                boolean available);
    }

    /**
     * @hide
     * An interface to be notified of changes to the output stream used by the spatializer
     * effect.
     * @see #getOutput()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public interface OnSpatializerOutputChangedListener {
        /**
         * Called when the id of the output stream of the spatializer effect changed.
         * @param spatializer the {@code Spatializer} instance whose output is updated
         * @param output the id of the output stream, or 0 when there is no spatializer output
         */
        void onSpatializerOutputChanged(@NonNull Spatializer spatializer,
                @IntRange(from = 0) int output);
    }

    /**
     * @hide
     * An interface to be notified of updates to the head to soundstage pose, as represented by the
     * current head tracking mode.
     * @see #setOnHeadToSoundstagePoseUpdatedListener(Executor, OnHeadToSoundstagePoseUpdatedListener)
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public interface OnHeadToSoundstagePoseUpdatedListener {
        /**
         * Called when the head to soundstage transform is updated
         * @param spatializer the {@code Spatializer} instance affected by the pose update
         * @param pose the new pose data representing the transform between the frame
         *                 of reference for the current head tracking mode (see
         *                 {@link #getHeadTrackingMode()}) and the device being tracked (for
         *                 instance a pair of headphones with a head tracker).<br>
         *                 The head pose data is represented as an array of six float values, where
         *                 the first three values are the translation vector, and the next three
         *                 are the rotation vector.
         */
        void onHeadToSoundstagePoseUpdated(@NonNull Spatializer spatializer,
                @NonNull float[] pose);
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
        mStateListenerMgr.addListener(executor, listener, "addOnSpatializerStateChangedListener",
                () -> new SpatializerInfoDispatcherStub());
    }

    /**
     * Removes a previously added listener for changes to the enabled state of the
     * {@code Spatializer}.
     * @param listener the listener to receive enabled state updates
     * @see #isEnabled()
     */
    public void removeOnSpatializerStateChangedListener(
            @NonNull OnSpatializerStateChangedListener listener) {
        mStateListenerMgr.removeListener(listener, "removeOnSpatializerStateChangedListener");
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

    /**
     * manages the OnSpatializerStateChangedListener listeners and the
     * SpatializerInfoDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnSpatializerStateChangedListener>
            mStateListenerMgr = new CallbackUtil.LazyListenerManager();

    private final class SpatializerInfoDispatcherStub extends ISpatializerCallback.Stub
            implements CallbackUtil.DispatcherStub {
        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    mAm.getService().registerSpatializerCallback(this);
                } else {
                    mAm.getService().unregisterSpatializerCallback(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchSpatializerEnabledChanged(boolean enabled) {
            mStateListenerMgr.callListeners(
                    (listener) -> listener.onSpatializerEnabledChanged(
                            Spatializer.this, enabled));
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchSpatializerAvailableChanged(boolean available) {
            mStateListenerMgr.callListeners(
                    (listener) -> listener.onSpatializerAvailableChanged(
                            Spatializer.this, available));
        }
    }


    /**
     * @hide
     * Return the current head tracking mode as used by the system.
     * Note this may differ from the desired head tracking mode. Reasons for the two to differ
     * include: a head tracking device is not available for the current audio output device,
     * the transmission conditions between the tracker and device have deteriorated and tracking
     * has been disabled.
     * @see #getDesiredHeadTrackingMode()
     * @return the current head tracking mode
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public @HeadTrackingMode int getHeadTrackingMode() {
        try {
            return mAm.getService().getActualHeadTrackingMode();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getActualHeadTrackingMode", e);
            return HEAD_TRACKING_MODE_UNSUPPORTED;
        }

    }

    /**
     * @hide
     * Return the desired head tracking mode.
     * Note this may differ from the actual head tracking mode, reflected by
     * {@link #getHeadTrackingMode()}.
     * @return the desired head tring mode
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public @HeadTrackingMode int getDesiredHeadTrackingMode() {
        try {
            return mAm.getService().getDesiredHeadTrackingMode();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getDesiredHeadTrackingMode", e);
            return HEAD_TRACKING_MODE_UNSUPPORTED;
        }
    }

    /**
     * @hide
     * Returns the list of supported head tracking modes.
     * @return the list of modes that can be used in {@link #setDesiredHeadTrackingMode(int)} to
     *         enable head tracking. The list will be empty if {@link #getHeadTrackingMode()}
     *         is {@link #HEAD_TRACKING_MODE_UNSUPPORTED}. Values can be
     *         {@link #HEAD_TRACKING_MODE_OTHER},
     *         {@link #HEAD_TRACKING_MODE_RELATIVE_WORLD} or
     *         {@link #HEAD_TRACKING_MODE_RELATIVE_DEVICE}
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public @NonNull List<Integer> getSupportedHeadTrackingModes() {
        try {
            final int[] modes = mAm.getService().getSupportedHeadTrackingModes();
            final ArrayList<Integer> list = new ArrayList<>(0);
            for (int mode : modes) {
                list.add(mode);
            }
            return list;
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getSupportedHeadTrackModes", e);
            return new ArrayList(0);
        }
    }

    /**
     * @hide
     * Sets the desired head tracking mode.
     * Note a set desired mode may differ from the actual head tracking mode.
     * @see #getHeadTrackingMode()
     * @param mode the desired head tracking mode, one of the values returned by
     *             {@link #getSupportedHeadTrackModes()}, or {@link #HEAD_TRACKING_MODE_DISABLED} to
     *             disable head tracking.
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setDesiredHeadTrackingMode(@HeadTrackingModeSet int mode) {
        try {
            mAm.getService().setDesiredHeadTrackingMode(mode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDesiredHeadTrackingMode to " + mode, e);
        }
    }

    /**
     * @hide
     * Recenters the head tracking at the current position / orientation.
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void recenterHeadTracker() {
        try {
            mAm.getService().recenterHeadTracker();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling recenterHeadTracker", e);
        }
    }

    /**
     * @hide
     * Adds a listener to be notified of changes to the head tracking mode of the
     * {@code Spatializer}
     * @param executor the {@code Executor} handling the callbacks
     * @param listener the listener to register
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void addOnHeadTrackingModeChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnHeadTrackingModeChangedListener listener) {
        mHeadTrackingListenerMgr.addListener(executor, listener,
                "addOnHeadTrackingModeChangedListener",
                 () -> new SpatializerHeadTrackingDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener for changes to the head tracking mode of the
     * {@code Spatializer}.
     * @param listener the listener to unregister
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void removeOnHeadTrackingModeChangedListener(
            @NonNull OnHeadTrackingModeChangedListener listener) {
        mHeadTrackingListenerMgr.removeListener(listener,
                "removeOnHeadTrackingModeChangedListener");
    }

    /**
     * @hide
     * Set the listener to receive head to soundstage pose updates.
     * @param executor the {@code Executor} handling the callbacks
     * @param listener the listener to register
     * @see #clearOnHeadToSoundstagePoseUpdatedListener()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setOnHeadToSoundstagePoseUpdatedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnHeadToSoundstagePoseUpdatedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        synchronized (mPoseListenerLock) {
            if (mPoseListener != null) {
                throw new IllegalStateException("Trying to overwrite existing listener");
            }
            mPoseListener =
                    new ListenerInfo<OnHeadToSoundstagePoseUpdatedListener>(listener, executor);
            mPoseDispatcher = new SpatializerPoseDispatcherStub();
            try {
                mAm.getService().registerHeadToSoundstagePoseCallback(mPoseDispatcher);
            } catch (RemoteException e) {
                mPoseListener = null;
                mPoseDispatcher = null;
            }
        }
    }

    /**
     * @hide
     * Clears the listener for head to soundstage pose updates
     * @see #setOnHeadToSoundstagePoseUpdatedListener(Executor, OnHeadToSoundstagePoseUpdatedListener)
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void clearOnHeadToSoundstagePoseUpdatedListener() {
        synchronized (mPoseListenerLock) {
            if (mPoseDispatcher == null) {
                throw (new IllegalStateException("No listener to clear"));
            }
            try {
                mAm.getService().unregisterHeadToSoundstagePoseCallback(mPoseDispatcher);
            } catch (RemoteException e) { }
            mPoseListener = null;
            mPoseDispatcher = null;
        }
    }

    /**
     * @hide
     * Sets an additional transform over the soundstage.
     * The transform represents the pose of the soundstage, relative
     * to either the device (in {@link #HEAD_TRACKING_MODE_RELATIVE_DEVICE} mode), the world (in
     * {@link #HEAD_TRACKING_MODE_RELATIVE_WORLD}) or the listener’s head (in
     * {@link #HEAD_TRACKING_MODE_DISABLED} mode).
     * @param transform an array of 6 float values, the first 3 are the translation vector, the
     *                  other 3 are the rotation vector.
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setGlobalTransform(@NonNull float[] transform) {
        if (Objects.requireNonNull(transform).length != 6) {
            throw new IllegalArgumentException("transform array must be of size 6, was "
                    + transform.length);
        }
        try {
            mAm.getService().setSpatializerGlobalTransform(transform);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setGlobalTransform", e);
        }
    }

    /**
     * @hide
     * Sets a parameter on the platform spatializer effect implementation.
     * This is to be used for vendor-specific configurations of their effect, keys and values are
     * not reuseable across implementations.
     * @param key the parameter to change
     * @param value an array for the value of the parameter to change
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setEffectParameter(int key, @NonNull byte[] value) {
        Objects.requireNonNull(value);
        try {
            mAm.getService().setSpatializerParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setEffectParameter", e);
        }
    }

    /**
     * @hide
     * Retrieves a parameter value from the platform spatializer effect implementation.
     * This is to be used for vendor-specific configurations of their effect, keys and values are
     * not reuseable across implementations.
     * @param key the parameter for which the value is queried
     * @param value a non-empty array to contain the return value. The caller is responsible for
     *              passing an array of size matching the parameter.
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void getEffectParameter(int key, @NonNull byte[] value) {
        Objects.requireNonNull(value);
        try {
            mAm.getService().getSpatializerParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getEffectParameter", e);
        }
    }

    /**
     * @hide
     * Returns the id of the output stream used for the spatializer effect playback.
     * This getter or associated listener {@link OnSpatializerOutputChangedListener} can be used for
     * handling spatializer output-specific configurations (e.g. disabling speaker post-processing
     * to avoid double-processing of the spatialized path).
     * @return id of the output stream, or 0 if no spatializer playback is active
     * @see #setOnSpatializerOutputChangedListener(Executor, OnSpatializerOutputChangedListener)
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public @IntRange(from = 0) int getOutput() {
        try {
            return mAm.getService().getSpatializerOutput();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getSpatializerOutput", e);
            return 0;
        }
    }

    /**
     * @hide
     * Sets the listener to receive spatializer effect output updates
     * @param executor the {@code Executor} handling the callbacks
     * @param listener the listener to register
     * @see #clearOnSpatializerOutputChangedListener()
     * @see #getOutput()
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void setOnSpatializerOutputChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSpatializerOutputChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        synchronized (mOutputListenerLock) {
            if (mOutputListener != null) {
                throw new IllegalStateException("Trying to overwrite existing listener");
            }
            mOutputListener =
                    new ListenerInfo<OnSpatializerOutputChangedListener>(listener, executor);
            mOutputDispatcher = new SpatializerOutputDispatcherStub();
            try {
                mAm.getService().registerSpatializerOutputCallback(mOutputDispatcher);
                // immediately report the current output
                mOutputDispatcher.dispatchSpatializerOutputChanged(getOutput());
            } catch (RemoteException e) {
                mOutputListener = null;
                mOutputDispatcher = null;
            }
        }
    }

    /**
     * @hide
     * Clears the listener for spatializer effect output updates
     * @see #setOnSpatializerOutputChangedListener(Executor, OnSpatializerOutputChangedListener)
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS)
    public void clearOnSpatializerOutputChangedListener() {
        synchronized (mOutputListenerLock) {
            if (mOutputDispatcher == null) {
                throw (new IllegalStateException("No listener to clear"));
            }
            try {
                mAm.getService().unregisterSpatializerOutputCallback(mOutputDispatcher);
            } catch (RemoteException e) { }
            mOutputListener = null;
            mOutputDispatcher = null;
        }
    }

    //-----------------------------------------------------------------------------
    // head tracking callback management and stub

    /**
     * manages the OnHeadTrackingModeChangedListener listeners and the
     * SpatializerHeadTrackingDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnHeadTrackingModeChangedListener>
            mHeadTrackingListenerMgr = new CallbackUtil.LazyListenerManager();

    private final class SpatializerHeadTrackingDispatcherStub
            extends ISpatializerHeadTrackingModeCallback.Stub
            implements CallbackUtil.DispatcherStub {
        @Override
        public void register(boolean register) {
            try {
                if (register) {
                    mAm.getService().registerSpatializerHeadTrackingCallback(this);
                } else {
                    mAm.getService().unregisterSpatializerHeadTrackingCallback(this);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchSpatializerActualHeadTrackingModeChanged(int mode) {
            mHeadTrackingListenerMgr.callListeners(
                    (listener) -> listener.onHeadTrackingModeChanged(Spatializer.this, mode));
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchSpatializerDesiredHeadTrackingModeChanged(int mode) {
            mHeadTrackingListenerMgr.callListeners(
                    (listener) -> listener.onDesiredHeadTrackingModeChanged(
                            Spatializer.this, mode));
        }
    }

    //-----------------------------------------------------------------------------
    // head tracker availability callback management and stub
    /**
     * manages the OnHeadTrackerAvailableListener listeners and the
     * SpatializerHeadTrackerAvailableDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnHeadTrackerAvailableListener>
            mHeadTrackerListenerMgr = new CallbackUtil.LazyListenerManager();

    private final class SpatializerHeadTrackerAvailableDispatcherStub
            extends ISpatializerHeadTrackerAvailableCallback.Stub
            implements CallbackUtil.DispatcherStub {
        @Override
        public void register(boolean register) {
            try {
                mAm.getService().registerSpatializerHeadTrackerAvailableCallback(this, register);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        @SuppressLint("GuardedBy") // lock applied inside callListeners method
        public void dispatchSpatializerHeadTrackerAvailable(boolean available) {
            mHeadTrackerListenerMgr.callListeners(
                    (listener) -> listener.onHeadTrackerAvailableChanged(
                            Spatializer.this, available));
        }
    }

    //-----------------------------------------------------------------------------
    // head pose callback management and stub
    private final Object mPoseListenerLock = new Object();
    /**
     * Listener for head to soundstage updates
     */
    @GuardedBy("mPoseListenerLock")
    private @Nullable ListenerInfo<OnHeadToSoundstagePoseUpdatedListener> mPoseListener;
    @GuardedBy("mPoseListenerLock")
    private @Nullable SpatializerPoseDispatcherStub mPoseDispatcher;

    private final class SpatializerPoseDispatcherStub
            extends ISpatializerHeadToSoundStagePoseCallback.Stub {

        @Override
        public void dispatchPoseChanged(float[] pose) {
            // make a copy of ref to listener so callback is not executed under lock
            final ListenerInfo<OnHeadToSoundstagePoseUpdatedListener> listener;
            synchronized (mPoseListenerLock) {
                listener = mPoseListener;
            }
            if (listener == null) {
                return;
            }
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                listener.mExecutor.execute(() -> listener.mListener
                        .onHeadToSoundstagePoseUpdated(Spatializer.this, pose));
            }
        }
    }

    //-----------------------------------------------------------------------------
    // output callback management and stub
    private final Object mOutputListenerLock = new Object();
    /**
     * Listener for output updates
     */
    @GuardedBy("mOutputListenerLock")
    private @Nullable ListenerInfo<OnSpatializerOutputChangedListener> mOutputListener;
    @GuardedBy("mOutputListenerLock")
    private @Nullable SpatializerOutputDispatcherStub mOutputDispatcher;

    private final class SpatializerOutputDispatcherStub
            extends ISpatializerOutputCallback.Stub {

        @Override
        public void dispatchSpatializerOutputChanged(int output) {
            // make a copy of ref to listener so callback is not executed under lock
            final ListenerInfo<OnSpatializerOutputChangedListener> listener;
            synchronized (mOutputListenerLock) {
                listener = mOutputListener;
            }
            if (listener == null) {
                return;
            }
            try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
                listener.mExecutor.execute(() -> listener.mListener
                        .onSpatializerOutputChanged(Spatializer.this, output));
            }
        }
    }
}
