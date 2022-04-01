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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.INativeSpatializerCallback;
import android.media.ISpatializer;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerHeadTrackerAvailableCallback;
import android.media.ISpatializerHeadTrackingCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerOutputCallback;
import android.media.SpatializationLevel;
import android.media.SpatializationMode;
import android.media.Spatializer;
import android.media.SpatializerHeadTrackingMode;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A helper class to manage Spatializer related functionality
 */
public class SpatializerHelper {

    private static final String TAG = "AS.SpatializerHelper";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_MORE = false;

    private static void logd(String s) {
        if (DEBUG) {
            Log.i(TAG, s);
        }
    }

    private final @NonNull AudioSystemAdapter mASA;
    private final @NonNull AudioService mAudioService;
    private @Nullable SensorManager mSensorManager;

    //------------------------------------------------------------
    /** head tracker sensor name */
    // TODO: replace with generic head tracker sensor name.
    //       the current implementation refers to the "google" namespace but will be replaced
    //       by an android name at the next API level revision, it is not Google-specific.
    private static final String HEADTRACKER_SENSOR =
            "com.google.hardware.sensor.hid_dynamic.headtracker";

    private static final SparseIntArray SPAT_MODE_FOR_DEVICE_TYPE = new SparseIntArray(15) {
        {
            append(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_WIRED_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, SpatializationMode.SPATIALIZER_BINAURAL);
            // assumption for A2DP: mostly headsets
            append(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_DOCK, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_ACCESSORY, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_DEVICE, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_USB_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_LINE_ANALOG, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_LINE_DIGITAL, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_AUX_LINE, SpatializationMode.SPATIALIZER_TRANSAURAL);
            append(AudioDeviceInfo.TYPE_HEARING_AID, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_BLE_HEADSET, SpatializationMode.SPATIALIZER_BINAURAL);
            append(AudioDeviceInfo.TYPE_BLE_SPEAKER, SpatializationMode.SPATIALIZER_TRANSAURAL);
            // assumption that BLE broadcast would be mostly consumed on headsets
            append(AudioDeviceInfo.TYPE_BLE_BROADCAST, SpatializationMode.SPATIALIZER_BINAURAL);
        }
    };

    private static final int[] WIRELESS_TYPES = { AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
    };

    private static final int[] WIRELESS_SPEAKER_TYPES = {
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
    };

    // Spatializer state machine
    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_NOT_SUPPORTED = 1;
    private static final int STATE_DISABLED_UNAVAILABLE = 3;
    private static final int STATE_ENABLED_UNAVAILABLE = 4;
    private static final int STATE_ENABLED_AVAILABLE = 5;
    private static final int STATE_DISABLED_AVAILABLE = 6;
    private int mState = STATE_UNINITIALIZED;

    private boolean mFeatureEnabled = false;
    /** current level as reported by native Spatializer in callback */
    private int mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private boolean mTransauralSupported = false;
    private boolean mBinauralSupported = false;
    private int mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private int mDesiredHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
    private boolean mHeadTrackerAvailable = false;
    /**
     *  The desired head tracking mode when enabling head tracking, tracks mDesiredHeadTrackingMode,
     *  except when head tracking gets disabled through setting the desired mode to
     *  {@link Spatializer#HEAD_TRACKING_MODE_DISABLED}.
     */
    private int mDesiredHeadTrackingModeWhenEnabled = Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
    private int mSpatOutput = 0;
    private @Nullable ISpatializer mSpat;
    private @Nullable SpatializerCallback mSpatCallback;
    private @Nullable SpatializerHeadTrackingCallback mSpatHeadTrackingCallback;
    private @Nullable HelperDynamicSensorCallback mDynSensorCallback;
    private boolean mIsHeadTrackingSupported = false;

    // default attributes and format that determine basic availability of spatialization
    private static final AudioAttributes DEFAULT_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build();
    private static final AudioFormat DEFAULT_FORMAT = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
            .build();
    // device array to store the routing for the default attributes and format, size 1 because
    // media is never expected to be duplicated
    private static final AudioDeviceAttributes[] ROUTING_DEVICES = new AudioDeviceAttributes[1];

    //---------------------------------------------------------------
    // audio device compatibility / enabled
    /**
     * List of device types that can be used on this device with Spatial Audio.
     * It is initialized based on the transaural/binaural capabilities
     * of the effect.
     */
    private final ArrayList<Integer> mSACapableDeviceTypes = new ArrayList<>(0);

    /**
     * List of devices where Spatial Audio is possible. Each device can be enabled or disabled
     * (== user choice to use or not)
     */
    private final ArrayList<SADeviceState> mSADevices = new ArrayList<>(0);

    //------------------------------------------------------
    // initialization
    SpatializerHelper(@NonNull AudioService mother, @NonNull AudioSystemAdapter asa) {
        mAudioService = mother;
        mASA = asa;
    }

    synchronized void init(boolean effectExpected) {
        Log.i(TAG, "Initializing");
        if (!effectExpected) {
            Log.i(TAG, "Setting state to STATE_NOT_SUPPORTED due to effect not expected");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        if (mState != STATE_UNINITIALIZED) {
            throw new IllegalStateException(("init() called in state:" + mState));
        }
        // is there a spatializer?
        mSpatCallback = new SpatializerCallback();
        final ISpatializer spat = AudioSystem.getSpatializer(mSpatCallback);
        if (spat == null) {
            Log.i(TAG, "init(): No Spatializer found");
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        // capabilities of spatializer?
        try {
            byte[] levels = spat.getSupportedLevels();
            if (levels == null
                    || levels.length == 0
                    || (levels.length == 1
                    && levels[0] == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE)) {
                Log.e(TAG, "Spatializer is useless");
                mState = STATE_NOT_SUPPORTED;
                return;
            }
            for (byte level : levels) {
                logd("found support for level: " + level);
                if (level == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL) {
                    logd("Setting capable level to LEVEL_MULTICHANNEL");
                    mCapableSpatLevel = level;
                    break;
                }
            }
            byte[] spatModes = spat.getSupportedModes();
            for (byte mode : spatModes) {
                switch (mode) {
                    case SpatializationMode.SPATIALIZER_BINAURAL:
                        mBinauralSupported = true;
                        break;
                    case SpatializationMode.SPATIALIZER_TRANSAURAL:
                        mTransauralSupported = true;
                        break;
                    default:
                        Log.e(TAG, "Spatializer reports unknown supported mode:" + mode);
                        break;
                }
            }
            // if neither transaural nor binaural is supported, bail
            if (!mBinauralSupported && !mTransauralSupported) {
                mState = STATE_NOT_SUPPORTED;
                return;
            }

            // initialize list of compatible devices
            for (int i = 0; i < SPAT_MODE_FOR_DEVICE_TYPE.size(); i++) {
                int mode = SPAT_MODE_FOR_DEVICE_TYPE.valueAt(i);
                if ((mode == (int) SpatializationMode.SPATIALIZER_BINAURAL && mBinauralSupported)
                        || (mode == (int) SpatializationMode.SPATIALIZER_TRANSAURAL
                            && mTransauralSupported)) {
                    mSACapableDeviceTypes.add(SPAT_MODE_FOR_DEVICE_TYPE.keyAt(i));
                }
            }
            if (mTransauralSupported) {
                // TODO deal with persisted values
                mSADevices.add(
                        new SADeviceState(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null));
            }
            if (mBinauralSupported) {
                // TODO deal with persisted values
                mSADevices.add(
                        new SADeviceState(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, null));
            }
            // TODO read persisted states
        } catch (RemoteException e) {
            /* capable level remains at NONE*/
        } finally {
            if (spat != null) {
                try {
                    spat.release();
                } catch (RemoteException e) { /* capable level remains at NONE*/ }
            }
        }
        if (mCapableSpatLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            mState = STATE_NOT_SUPPORTED;
            return;
        }
        mState = STATE_DISABLED_UNAVAILABLE;
        mASA.getDevicesForAttributes(
                DEFAULT_ATTRIBUTES, false /* forVolume */).toArray(ROUTING_DEVICES);
        // note at this point mSpat is still not instantiated
    }

    /**
     * Like init() but resets the state and spatializer levels
     * @param featureEnabled
     */
    synchronized void reset(boolean featureEnabled) {
        Log.i(TAG, "Resetting");
        releaseSpat();
        mState = STATE_UNINITIALIZED;
        mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        init(true);
        setSpatializerEnabledInt(featureEnabled);
    }

    //------------------------------------------------------
    // routing monitoring
    synchronized void onRoutingUpdated() {
        if (!mFeatureEnabled) {
            return;
        }
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                return;
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                break;
        }
        mASA.getDevicesForAttributes(
                DEFAULT_ATTRIBUTES, false /* forVolume */).toArray(ROUTING_DEVICES);

        // is media routed to a new device?
        if (isWireless(ROUTING_DEVICES[0].getType())) {
            addWirelessDeviceIfNew(ROUTING_DEVICES[0]);
        }

        // find if media device enabled / available
        final Pair<Boolean, Boolean> enabledAvailable = evaluateState(ROUTING_DEVICES[0]);

        boolean able = false;
        if (enabledAvailable.second) {
            // available for Spatial audio, check w/ effect
            able = canBeSpatializedOnDevice(DEFAULT_ATTRIBUTES, DEFAULT_FORMAT, ROUTING_DEVICES);
            Log.i(TAG, "onRoutingUpdated: can spatialize media 5.1:" + able
                    + " on device:" + ROUTING_DEVICES[0]);
            setDispatchAvailableState(able);
        } else {
            Log.i(TAG, "onRoutingUpdated: device:" + ROUTING_DEVICES[0]
                    + " not available for Spatial Audio");
            setDispatchAvailableState(false);
        }

        if (able && enabledAvailable.first) {
            Log.i(TAG, "Enabling Spatial Audio since enabled for media device:"
                    + ROUTING_DEVICES[0]);
        } else {
            Log.i(TAG, "Disabling Spatial Audio since disabled for media device:"
                    + ROUTING_DEVICES[0]);
        }
        setDispatchFeatureEnabledState(able && enabledAvailable.first);

        if (mDesiredHeadTrackingMode != Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED
                && mDesiredHeadTrackingMode != Spatializer.HEAD_TRACKING_MODE_DISABLED) {
            postInitSensors();
        }
    }

    //------------------------------------------------------
    // spatializer callback from native
    private final class SpatializerCallback extends INativeSpatializerCallback.Stub {

        public void onLevelChanged(byte level) {
            logd("SpatializerCallback.onLevelChanged level:" + level);
            synchronized (SpatializerHelper.this) {
                mSpatLevel = spatializationLevelToSpatializerInt(level);
            }
            // TODO use reported spat level to change state

            // init sensors
            postInitSensors();
        }

        public void onOutputChanged(int output) {
            logd("SpatializerCallback.onOutputChanged output:" + output);
            int oldOutput;
            synchronized (SpatializerHelper.this) {
                oldOutput = mSpatOutput;
                mSpatOutput = output;
            }
            if (oldOutput != output) {
                dispatchOutputUpdate(output);
            }
        }
    };

    //------------------------------------------------------
    // spatializer head tracking callback from native
    private final class SpatializerHeadTrackingCallback
            extends ISpatializerHeadTrackingCallback.Stub {
        public void onHeadTrackingModeChanged(byte mode)  {
            logd("SpatializerHeadTrackingCallback.onHeadTrackingModeChanged mode:" + mode);
            int oldMode, newMode;
            synchronized (this) {
                oldMode = mActualHeadTrackingMode;
                mActualHeadTrackingMode = headTrackingModeTypeToSpatializerInt(mode);
                newMode = mActualHeadTrackingMode;
            }
            if (oldMode != newMode) {
                dispatchActualHeadTrackingMode(newMode);
            }
        }

        public void onHeadToSoundStagePoseUpdated(float[] headToStage)  {
            if (headToStage == null) {
                Log.e(TAG, "SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated"
                        + "null transform");
                return;
            }
            if (headToStage.length != 6) {
                Log.e(TAG, "SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated"
                        + " invalid transform length" + headToStage.length);
                return;
            }
            if (DEBUG_MORE) {
                // 6 values * (4 digits + 1 dot + 2 brackets) = 42 characters
                StringBuilder t = new StringBuilder(42);
                for (float val : headToStage) {
                    t.append("[").append(String.format(Locale.ENGLISH, "%.3f", val)).append("]");
                }
                logd("SpatializerHeadTrackingCallback.onHeadToStagePoseUpdated headToStage:" + t);
            }
            dispatchPoseUpdate(headToStage);
        }
    };

    //------------------------------------------------------
    // dynamic sensor callback
    private final class HelperDynamicSensorCallback extends SensorManager.DynamicSensorCallback {
        @Override
        public void onDynamicSensorConnected(Sensor sensor) {
            postInitSensors();
        }

        @Override
        public void onDynamicSensorDisconnected(Sensor sensor) {
            postInitSensors();
        }
    }

    //------------------------------------------------------
    // compatible devices
    /**
     * Return the list of compatible devices, which reflects the device compatible with the
     * spatializer effect, and those that have been explicitly enabled or disabled
     * @return the list of compatible audio devices
     */
    synchronized @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        // build unionOf(mCompatibleAudioDevices, mEnabledDevice) - mDisabledAudioDevices
        ArrayList<AudioDeviceAttributes> compatList = new ArrayList<>();
        for (SADeviceState dev : mSADevices) {
            if (dev.mEnabled) {
                compatList.add(new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                        dev.mDeviceType, dev.mDeviceAddress == null ? "" : dev.mDeviceAddress));
            }
        }
        return compatList;
    }

    synchronized void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        // TODO add log
        Log.i(TAG, "addCompatibleAudioDevice: dev=" + ada);
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);
        boolean updateRouting = false;
        boolean isInList = false;

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                isInList = true;
                // state change?
                updateRouting = !deviceState.mEnabled;
                deviceState.mEnabled = true;
                break;
            }
        }
        if (!isInList) {
            final SADeviceState dev = new SADeviceState(deviceType,
                    wireless ? ada.getAddress() : null);
            dev.mEnabled = true;
            mSADevices.add(dev);
            updateRouting = true;
        }
        //if (updateRouting) {
            onRoutingUpdated();
        //}
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        // TODO add log
        Log.i(TAG, "removeCompatibleAudioDevice: dev=" + ada);
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);
        boolean updateRouting = false;

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                // state change?
                updateRouting = deviceState.mEnabled;
                deviceState.mEnabled = false;
                break;
            }
        }
        //###if (updateRouting) {
            onRoutingUpdated();
        //###}
    }

    /**
     * Return if Spatial Audio is enabled and available for the given device
     * @param ada
     * @return a pair of boolean, 1/ enabled? 2/ available?
     */
    private synchronized Pair<Boolean, Boolean> evaluateState(AudioDeviceAttributes ada) {
        // if not a wireless device, this value will be overwritten to map the type
        // to TYPE_BUILTIN_SPEAKER or TYPE_WIRED_HEADPHONES
        int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);

        // if not a wireless device: find if media device is in the speaker, wired headphones
        if (!wireless) {
            // is the device type capable of doing SA?
            if (!mSACapableDeviceTypes.contains(deviceType)) {
                Log.i(TAG, "Device incompatible with Spatial Audio dev:" + ada);
                return new Pair<>(false, false);
            }
            // what spatialization mode to use for this device?
            final int spatMode = SPAT_MODE_FOR_DEVICE_TYPE.get(deviceType, Integer.MIN_VALUE);
            if (spatMode == Integer.MIN_VALUE) {
                // error case, device not found
                Log.e(TAG, "no spatialization mode found for device type:" + deviceType);
                return new Pair<>(false, false);
            }
            // map the spatialization mode to the SPEAKER or HEADPHONES device
            if (spatMode == SpatializationMode.SPATIALIZER_TRANSAURAL) {
                deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
            } else {
                deviceType = AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
            }
        } else { // wireless device
            if (isWirelessSpeaker(deviceType) && !mTransauralSupported) {
                Log.i(TAG, "Device incompatible with Spatial Audio (no transaural) dev:"
                        + ada);
                return new Pair<>(false, false);
            }
            if (!mBinauralSupported) {
                Log.i(TAG, "Device incompatible with Spatial Audio (no binaural) dev:"
                        + ada);
                return new Pair<>(false, false);
            }
        }

        boolean enabled = false;
        boolean available = false;
        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                available = true;
                enabled = deviceState.mEnabled;
                break;
            }
        }
        return new Pair<>(enabled, available);
    }

    private synchronized void addWirelessDeviceIfNew(@NonNull AudioDeviceAttributes ada) {
        boolean knownDevice = false;
        for (SADeviceState deviceState : mSADevices) {
            // wireless device so always check address
            if (ada.getType() == deviceState.mDeviceType
                    && ada.getAddress().equals(deviceState.mDeviceAddress)) {
                knownDevice = true;
                break;
            }
        }
        if (!knownDevice) {
            mSADevices.add(new SADeviceState(ada.getType(), ada.getAddress()));
            //### TODO persist list
        }
    }

    //------------------------------------------------------
    // states

    synchronized boolean isEnabled() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                return false;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            default:
                return true;
        }
    }

    synchronized boolean isAvailable() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
                return false;
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
            default:
                return true;
        }
    }

    synchronized boolean isAvailableForDevice(@NonNull AudioDeviceAttributes ada) {
        if (ada.getRole() != AudioDeviceAttributes.ROLE_OUTPUT) {
            return false;
        }

        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);
        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean canBeSpatializedOnDevice(@NonNull AudioAttributes attributes,
            @NonNull AudioFormat format, @NonNull AudioDeviceAttributes[] devices) {
        final byte modeForDevice = (byte) SPAT_MODE_FOR_DEVICE_TYPE.get(devices[0].getType(),
                /*default when type not found*/ SpatializationMode.SPATIALIZER_BINAURAL);
        if ((modeForDevice == SpatializationMode.SPATIALIZER_BINAURAL && mBinauralSupported)
                || (modeForDevice == SpatializationMode.SPATIALIZER_TRANSAURAL
                        && mTransauralSupported)) {
            return AudioSystem.canBeSpatialized(attributes, format, devices);
        }
        return false;
    }

    synchronized void setFeatureEnabled(boolean enabled) {
        if (mFeatureEnabled == enabled) {
            return;
        }
        mFeatureEnabled = enabled;
        if (mFeatureEnabled) {
            if (mState == STATE_NOT_SUPPORTED) {
                Log.e(TAG, "Can't enabled Spatial Audio, unsupported");
                return;
            }
            if (mState == STATE_UNINITIALIZED) {
                init(true);
            }
            setSpatializerEnabledInt(true);
            onRoutingUpdated();
        } else {
            setSpatializerEnabledInt(false);
        }
    }

    synchronized void setSpatializerEnabledInt(boolean enabled) {
        switch (mState) {
            case STATE_UNINITIALIZED:
                if (enabled) {
                    throw(new IllegalStateException("Can't enable when uninitialized"));
                }
                return;
            case STATE_NOT_SUPPORTED:
                if (enabled) {
                    Log.e(TAG, "Can't enable when unsupported");
                }
                return;
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
                if (enabled) {
                    createSpat();
                    break;
                } else {
                    // already in disabled state
                    return;
                }
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (!enabled) {
                    releaseSpat();
                    break;
                } else {
                    // already in enabled state
                    return;
                }
        }
        setDispatchFeatureEnabledState(enabled);
    }

    synchronized int getCapableImmersiveAudioLevel() {
        return mCapableSpatLevel;
    }

    final RemoteCallbackList<ISpatializerCallback> mStateCallbacks =
            new RemoteCallbackList<ISpatializerCallback>();

    synchronized void registerStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.register(callback);
    }

    synchronized void unregisterStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.unregister(callback);
    }

    /**
     * Update the feature state, no-op if no change
     * @param featureEnabled
     */
    private synchronized void setDispatchFeatureEnabledState(boolean featureEnabled) {
        if (featureEnabled) {
            switch (mState) {
                case STATE_DISABLED_UNAVAILABLE:
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                case STATE_DISABLED_AVAILABLE:
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                case STATE_ENABLED_AVAILABLE:
                case STATE_ENABLED_UNAVAILABLE:
                    // already enabled: no-op
                    return;
                default:
                    throw(new IllegalStateException("Invalid mState:" + mState
                            + " for enabled true"));
            }
        } else {
            switch (mState) {
                case STATE_ENABLED_UNAVAILABLE:
                    mState = STATE_DISABLED_UNAVAILABLE;
                    break;
                case STATE_ENABLED_AVAILABLE:
                    mState = STATE_DISABLED_AVAILABLE;
                    break;
                case STATE_DISABLED_AVAILABLE:
                case STATE_DISABLED_UNAVAILABLE:
                    // already disabled: no-op
                    return;
                default:
                    throw (new IllegalStateException("Invalid mState:" + mState
                            + " for enabled false"));
            }
        }
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerEnabledChanged(featureEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerEnabledChanged", e);
            }
        }
        mStateCallbacks.finishBroadcast();
    }

    private synchronized void setDispatchAvailableState(boolean available) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw(new IllegalStateException(
                        "Should not update available state in state:" + mState));
            case STATE_DISABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_DISABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    return;
                }
            case STATE_ENABLED_UNAVAILABLE:
                if (available) {
                    mState = STATE_ENABLED_AVAILABLE;
                    break;
                } else {
                    // already in unavailable state
                    return;
                }
            case STATE_DISABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    return;
                } else {
                    mState = STATE_DISABLED_UNAVAILABLE;
                    break;
                }
            case STATE_ENABLED_AVAILABLE:
                if (available) {
                    // already in available state
                    return;
                } else {
                    mState = STATE_ENABLED_UNAVAILABLE;
                    break;
                }
        }
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerAvailableChanged(available);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerEnabledChanged", e);
            }
        }
        mStateCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // native Spatializer management

    /**
     * precondition: mState == STATE_DISABLED_*
     */
    private void createSpat() {
        if (mSpat == null) {
            mSpatCallback = new SpatializerCallback();
            mSpatHeadTrackingCallback = new SpatializerHeadTrackingCallback();
            mSpat = AudioSystem.getSpatializer(mSpatCallback);
            try {
                mSpat.setLevel((byte)  Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL);
                mIsHeadTrackingSupported = mSpat.isHeadTrackingSupported();
                //TODO: register heatracking callback only when sensors are registered
                if (mIsHeadTrackingSupported) {
                    mSpat.registerHeadTrackingCallback(mSpatHeadTrackingCallback);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set spatializer level", e);
                mState = STATE_NOT_SUPPORTED;
                mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
            }
        }
    }

    /**
     * precondition: mState == STATE_ENABLED_*
     */
    private void releaseSpat() {
        if (mSpat != null) {
            mSpatCallback = null;
            try {
                if (mIsHeadTrackingSupported) {
                    mSpat.registerHeadTrackingCallback(null);
                }
                mHeadTrackerAvailable = false;
                mSpat.release();
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set release spatializer cleanly", e);
            }
            mIsHeadTrackingSupported = false;
            mSpat = null;
        }
    }

    //------------------------------------------------------
    // virtualization capabilities
    synchronized boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        logd("canBeSpatialized usage:" + attributes.getUsage()
                + " format:" + format.toLogFriendlyString());
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
                logd("canBeSpatialized false due to state:" + mState);
                return false;
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                break;
        }

        // filter on AudioAttributes usage
        switch (attributes.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_GAME:
                break;
            default:
                logd("canBeSpatialized false due to usage:" + attributes.getUsage());
                return false;
        }
        AudioDeviceAttributes[] devices = new AudioDeviceAttributes[1];
        // going through adapter to take advantage of routing cache
        mASA.getDevicesForAttributes(
                attributes, false /* forVolume */).toArray(devices);
        final boolean able = canBeSpatializedOnDevice(attributes, format, devices);
        logd("canBeSpatialized returning " + able);
        return able;
    }

    //------------------------------------------------------
    // head tracking
    final RemoteCallbackList<ISpatializerHeadTrackingModeCallback> mHeadTrackingModeCallbacks =
            new RemoteCallbackList<ISpatializerHeadTrackingModeCallback>();

    synchronized void registerHeadTrackingModeCallback(
            @NonNull ISpatializerHeadTrackingModeCallback callback) {
        mHeadTrackingModeCallbacks.register(callback);
    }

    synchronized void unregisterHeadTrackingModeCallback(
            @NonNull ISpatializerHeadTrackingModeCallback callback) {
        mHeadTrackingModeCallbacks.unregister(callback);
    }

    final RemoteCallbackList<ISpatializerHeadTrackerAvailableCallback> mHeadTrackerCallbacks =
            new RemoteCallbackList<>();

    synchronized void registerHeadTrackerAvailableCallback(
            @NonNull ISpatializerHeadTrackerAvailableCallback cb, boolean register) {
        if (register) {
            mHeadTrackerCallbacks.register(cb);
        } else {
            mHeadTrackerCallbacks.unregister(cb);
        }
    }

    synchronized int[] getSupportedHeadTrackingModes() {
        switch (mState) {
            case STATE_UNINITIALIZED:
                return new int[0];
            case STATE_NOT_SUPPORTED:
                // return an empty list when Spatializer functionality is not supported
                // because the list of head tracking modes you can set is actually empty
                // as defined in {@link Spatializer#getSupportedHeadTrackingModes()}
                return new int[0];
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    return new int[0];
                }
                break;
        }
        // mSpat != null
        try {
            final byte[] values = mSpat.getSupportedHeadTrackingModes();
            ArrayList<Integer> list = new ArrayList<>(0);
            for (byte value : values) {
                switch (value) {
                    case SpatializerHeadTrackingMode.OTHER:
                    case SpatializerHeadTrackingMode.DISABLED:
                        // not expected here, skip
                        break;
                    case SpatializerHeadTrackingMode.RELATIVE_WORLD:
                    case SpatializerHeadTrackingMode.RELATIVE_SCREEN:
                        list.add(headTrackingModeTypeToSpatializerInt(value));
                        break;
                    default:
                        Log.e(TAG, "Unexpected head tracking mode:" + value,
                                new IllegalArgumentException("invalid mode"));
                        break;
                }
            }
            int[] modes = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                modes[i] = list.get(i);
            }
            return modes;
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getSupportedHeadTrackingModes", e);
            return new int[] { Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED };
        }
    }

    synchronized int getActualHeadTrackingMode() {
        switch (mState) {
            case STATE_UNINITIALIZED:
                return Spatializer.HEAD_TRACKING_MODE_DISABLED;
            case STATE_NOT_SUPPORTED:
                return Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    return Spatializer.HEAD_TRACKING_MODE_DISABLED;
                }
                break;
        }
        // mSpat != null
        try {
            return headTrackingModeTypeToSpatializerInt(mSpat.getActualHeadTrackingMode());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getActualHeadTrackingMode", e);
            return Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        }
    }

    synchronized int getDesiredHeadTrackingMode() {
        return mDesiredHeadTrackingMode;
    }

    synchronized void setGlobalTransform(@NonNull float[] transform) {
        if (transform.length != 6) {
            throw new IllegalArgumentException("invalid array size" + transform.length);
        }
        if (!checkSpatForHeadTracking("setGlobalTransform")) {
            return;
        }
        try {
            mSpat.setGlobalTransform(transform);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setGlobalTransform", e);
        }
    }

    synchronized void recenterHeadTracker() {
        if (!checkSpatForHeadTracking("recenterHeadTracker")) {
            return;
        }
        try {
            mSpat.recenterHeadTracker();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling recenterHeadTracker", e);
        }
    }

    synchronized void setDesiredHeadTrackingMode(@Spatializer.HeadTrackingModeSet int mode) {
        if (!checkSpatForHeadTracking("setDesiredHeadTrackingMode")) {
            return;
        }
        if (mode != Spatializer.HEAD_TRACKING_MODE_DISABLED) {
            mDesiredHeadTrackingModeWhenEnabled = mode;
        }
        try {
            if (mDesiredHeadTrackingMode != mode) {
                mDesiredHeadTrackingMode = mode;
                dispatchDesiredHeadTrackingMode(mode);
            }
            Log.i(TAG, "setDesiredHeadTrackingMode("
                    + Spatializer.headtrackingModeToString(mode) + ")");
            mSpat.setDesiredHeadTrackingMode(spatializerIntToHeadTrackingModeType(mode));
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDesiredHeadTrackingMode", e);
        }
    }

    synchronized void setHeadTrackerEnabled(boolean enabled, @NonNull AudioDeviceAttributes ada) {
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                if (!deviceState.mHasHeadTracker) {
                    Log.e(TAG, "Called setHeadTrackerEnabled enabled:" + enabled
                            + " device:" + ada + " on a device without headtracker");
                    return;
                }
                Log.i(TAG, "setHeadTrackerEnabled enabled:" + enabled + " device:" + ada);
                deviceState.mHeadTrackerEnabled = enabled;
                break;
            }
        }
        // check current routing to see if it affects the headtracking mode
        if (ROUTING_DEVICES[0].getType() == deviceType
                && ROUTING_DEVICES[0].getAddress().equals(ada.getAddress())) {
            setDesiredHeadTrackingMode(enabled ? mDesiredHeadTrackingModeWhenEnabled
                    : Spatializer.HEAD_TRACKING_MODE_DISABLED);
        }
    }

    synchronized boolean hasHeadTracker(@NonNull AudioDeviceAttributes ada) {
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                return deviceState.mHasHeadTracker;
            }
        }
        return false;
    }

    /**
     * Configures device in list as having a head tracker
     * @param ada
     * @return true if the head tracker is enabled, false otherwise or if device not found
     */
    synchronized boolean setHasHeadTracker(@NonNull AudioDeviceAttributes ada) {
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                deviceState.mHasHeadTracker = true;
                return deviceState.mHeadTrackerEnabled;
            }
        }
        Log.e(TAG, "setHasHeadTracker: device not found for:" + ada);
        return false;
    }

    synchronized boolean isHeadTrackerEnabled(@NonNull AudioDeviceAttributes ada) {
        final int deviceType = ada.getType();
        final boolean wireless = isWireless(deviceType);

        for (SADeviceState deviceState : mSADevices) {
            if (deviceType == deviceState.mDeviceType
                    && (wireless && ada.getAddress().equals(deviceState.mDeviceAddress))
                    || !wireless) {
                if (!deviceState.mHasHeadTracker) {
                    return false;
                }
                return deviceState.mHeadTrackerEnabled;
            }
        }
        return false;
    }

    synchronized boolean isHeadTrackerAvailable() {
        return mHeadTrackerAvailable;
    }

    private boolean checkSpatForHeadTracking(String funcName) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                return false;
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer when calling " + funcName));
                }
                break;
        }
        return mIsHeadTrackingSupported;
    }

    private void dispatchActualHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerActualHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerActualHeadTrackingModeChanged("
                        + newMode + ")", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
    }

    private void dispatchDesiredHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerDesiredHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerDesiredHeadTrackingModeChanged("
                        + newMode + ")", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
    }

    private void dispatchHeadTrackerAvailable(boolean available) {
        final int nbCallbacks = mHeadTrackerCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackerCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerHeadTrackerAvailable(available);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerHeadTrackerAvailable("
                        + available + ")", e);
            }
        }
        mHeadTrackerCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // head pose
    final RemoteCallbackList<ISpatializerHeadToSoundStagePoseCallback> mHeadPoseCallbacks =
            new RemoteCallbackList<ISpatializerHeadToSoundStagePoseCallback>();

    synchronized void registerHeadToSoundstagePoseCallback(
            @NonNull ISpatializerHeadToSoundStagePoseCallback callback) {
        mHeadPoseCallbacks.register(callback);
    }

    synchronized void unregisterHeadToSoundstagePoseCallback(
            @NonNull ISpatializerHeadToSoundStagePoseCallback callback) {
        mHeadPoseCallbacks.unregister(callback);
    }

    private void dispatchPoseUpdate(float[] pose) {
        final int nbCallbacks = mHeadPoseCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadPoseCallbacks.getBroadcastItem(i)
                        .dispatchPoseChanged(pose);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchPoseChanged", e);
            }
        }
        mHeadPoseCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // vendor parameters
    synchronized void setEffectParameter(int key, @NonNull byte[] value) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't set parameter key:" + key + " without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer for setParameter for key:" + key));
                }
                break;
        }
        // mSpat != null
        try {
            mSpat.setParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setParameter for key:" + key, e);
        }
    }

    synchronized void getEffectParameter(int key, @NonNull byte[] value) {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't get parameter key:" + key + " without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer for getParameter for key:" + key));
                }
                break;
        }
        // mSpat != null
        try {
            mSpat.getParameter(key, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getParameter for key:" + key, e);
        }
    }

    //------------------------------------------------------
    // output

    /** @see Spatializer#getOutput */
    synchronized int getOutput() {
        switch (mState) {
            case STATE_UNINITIALIZED:
            case STATE_NOT_SUPPORTED:
                throw (new IllegalStateException(
                        "Can't get output without a spatializer"));
            case STATE_ENABLED_UNAVAILABLE:
            case STATE_DISABLED_UNAVAILABLE:
            case STATE_DISABLED_AVAILABLE:
            case STATE_ENABLED_AVAILABLE:
                if (mSpat == null) {
                    throw (new IllegalStateException(
                            "null Spatializer for getOutput"));
                }
                break;
        }
        // mSpat != null
        try {
            return mSpat.getOutput();
        } catch (RemoteException e) {
            Log.e(TAG, "Error in getOutput", e);
            return 0;
        }
    }

    final RemoteCallbackList<ISpatializerOutputCallback> mOutputCallbacks =
            new RemoteCallbackList<ISpatializerOutputCallback>();

    synchronized void registerSpatializerOutputCallback(
            @NonNull ISpatializerOutputCallback callback) {
        mOutputCallbacks.register(callback);
    }

    synchronized void unregisterSpatializerOutputCallback(
            @NonNull ISpatializerOutputCallback callback) {
        mOutputCallbacks.unregister(callback);
    }

    private void dispatchOutputUpdate(int output) {
        final int nbCallbacks = mOutputCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mOutputCallbacks.getBroadcastItem(i).dispatchSpatializerOutputChanged(output);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchOutputUpdate", e);
            }
        }
        mOutputCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // sensors
    private void postInitSensors() {
        mAudioService.postInitSpatializerHeadTrackingSensors();
    }

    synchronized void onInitSensors() {
        final boolean init = mFeatureEnabled && (mSpatLevel != SpatializationLevel.NONE);
        final String action = init ? "initializing" : "releasing";
        if (mSpat == null) {
            Log.e(TAG, "not " + action + " sensors, null spatializer");
            return;
        }
        if (!mIsHeadTrackingSupported) {
            Log.e(TAG, "not " + action + " sensors, spatializer doesn't support headtracking");
            return;
        }
        int headHandle = -1;
        int screenHandle = -1;
        if (init) {
            if (mSensorManager == null) {
                try {
                    mSensorManager = (SensorManager)
                            mAudioService.mContext.getSystemService(Context.SENSOR_SERVICE);
                    mDynSensorCallback = new HelperDynamicSensorCallback();
                    mSensorManager.registerDynamicSensorCallback(mDynSensorCallback);
                } catch (Exception e) {
                    Log.e(TAG, "Error with SensorManager, can't initialize sensors", e);
                    mSensorManager = null;
                    mDynSensorCallback = null;
                    return;
                }
            }
            // initialize sensor handles
            // TODO check risk of race condition for updating the association of a head tracker
            //  and an audio device:
            //     does this happen before routing is updated?
            //     avoid by supporting adding device here AND in onRoutingUpdated()
            headHandle = getHeadSensorHandleUpdateTracker();
            Log.i(TAG, "head tracker sensor handle initialized to " + headHandle);
            screenHandle = getScreenSensorHandle();
            Log.i(TAG, "found screen sensor handle initialized to " + screenHandle);
        } else {
            if (mSensorManager != null && mDynSensorCallback != null) {
                mSensorManager.unregisterDynamicSensorCallback(mDynSensorCallback);
                mSensorManager = null;
                mDynSensorCallback = null;
            }
            // -1 is disable value for both screen and head tracker handles
        }
        try {
            Log.i(TAG, "setScreenSensor:" + screenHandle);
            mSpat.setScreenSensor(screenHandle);
        } catch (Exception e) {
            Log.e(TAG, "Error calling setScreenSensor:" + screenHandle, e);
        }
        try {
            Log.i(TAG, "setHeadSensor:" + headHandle);
            mSpat.setHeadSensor(headHandle);
            if (mHeadTrackerAvailable != (headHandle != -1)) {
                mHeadTrackerAvailable = (headHandle != -1);
                dispatchHeadTrackerAvailable(mHeadTrackerAvailable);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling setHeadSensor:" + headHandle, e);
        }
        setDesiredHeadTrackingMode(mDesiredHeadTrackingMode);
    }

    //------------------------------------------------------
    // SDK <-> AIDL converters
    private static int headTrackingModeTypeToSpatializerInt(byte mode) {
        switch (mode) {
            case SpatializerHeadTrackingMode.OTHER:
                return Spatializer.HEAD_TRACKING_MODE_OTHER;
            case SpatializerHeadTrackingMode.DISABLED:
                return Spatializer.HEAD_TRACKING_MODE_DISABLED;
            case SpatializerHeadTrackingMode.RELATIVE_WORLD:
                return Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
            case SpatializerHeadTrackingMode.RELATIVE_SCREEN:
                return Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE;
            default:
                throw(new IllegalArgumentException("Unexpected head tracking mode:" + mode));
        }
    }

    private static byte spatializerIntToHeadTrackingModeType(int sdkMode) {
        switch (sdkMode) {
            case Spatializer.HEAD_TRACKING_MODE_OTHER:
                return SpatializerHeadTrackingMode.OTHER;
            case Spatializer.HEAD_TRACKING_MODE_DISABLED:
                return SpatializerHeadTrackingMode.DISABLED;
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD:
                return SpatializerHeadTrackingMode.RELATIVE_WORLD;
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE:
                return SpatializerHeadTrackingMode.RELATIVE_SCREEN;
            default:
                throw(new IllegalArgumentException("Unexpected head tracking mode:" + sdkMode));
        }
    }

    private static int spatializationLevelToSpatializerInt(byte level) {
        switch (level) {
            case SpatializationLevel.NONE:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
            case SpatializationLevel.SPATIALIZER_MULTICHANNEL:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL;
            case SpatializationLevel.SPATIALIZER_MCHAN_BED_PLUS_OBJECTS:
                return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MCHAN_BED_PLUS_OBJECTS;
            default:
                throw(new IllegalArgumentException("Unexpected spatializer level:" + level));
        }
    }

    void dump(PrintWriter pw) {
        pw.println("SpatializerHelper:");
        pw.println("\tmState:" + mState);
        pw.println("\tmSpatLevel:" + mSpatLevel);
        pw.println("\tmCapableSpatLevel:" + mCapableSpatLevel);
        pw.println("\tmActualHeadTrackingMode:"
                + Spatializer.headtrackingModeToString(mActualHeadTrackingMode));
        pw.println("\tmDesiredHeadTrackingMode:"
                + Spatializer.headtrackingModeToString(mDesiredHeadTrackingMode));
        String modesString = "";
        int[] modes = getSupportedHeadTrackingModes();
        for (int mode : modes) {
            modesString += Spatializer.headtrackingModeToString(mode) + " ";
        }
        pw.println("\tsupports binaural:" + mBinauralSupported + " / transaural"
                + mTransauralSupported);
        pw.println("\tsupported head tracking modes:" + modesString);
        pw.println("\tmSpatOutput:" + mSpatOutput);
        pw.println("\tdevices:\n");
        for (SADeviceState device : mSADevices) {
            pw.println("\t\t" + device + "\n");
        }
    }

    private static final class SADeviceState {
        final int mDeviceType;
        final @Nullable String mDeviceAddress; // non-null for wireless devices
        boolean mEnabled = true;               // by default, SA is enabled on any device
        boolean mHasHeadTracker = false;
        boolean mHeadTrackerEnabled = true;    // by default, if head tracker is present, use it

        SADeviceState(int deviceType, @Nullable String address) {
            mDeviceType = deviceType;
            mDeviceAddress = address;
        }

        @Override
        public String toString() {
            return "type:" + mDeviceType + " addr:" + mDeviceAddress + " enabled:" + mEnabled
                    + " HT:" + mHasHeadTracker + " HTenabled:" + mHeadTrackerEnabled;
        }
    }

    private static boolean isWireless(int deviceType) {
        for (int type : WIRELESS_TYPES) {
            if (type == deviceType) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWirelessSpeaker(int deviceType) {
        for (int type: WIRELESS_SPEAKER_TYPES) {
            if (type == deviceType) {
                return true;
            }
        }
        return false;
    }

    private int getHeadSensorHandleUpdateTracker() {
        int headHandle = -1;
        UUID routingDeviceUuid = mAudioService.getDeviceSensorUuid(ROUTING_DEVICES[0]);
        List<Sensor> sensors = new ArrayList<Sensor>(0);
        sensors.addAll(mSensorManager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER));
        sensors.addAll(mSensorManager.getDynamicSensorList(Sensor.TYPE_DEVICE_PRIVATE_BASE));
        for (Sensor sensor : sensors) {
            if (sensor.getType() == Sensor.TYPE_HEAD_TRACKER
                    || sensor.getStringType().equals(HEADTRACKER_SENSOR)) {
                UUID uuid = sensor.getUuid();
                if (uuid.equals(routingDeviceUuid)) {
                    headHandle = sensor.getHandle();
                    if (!setHasHeadTracker(ROUTING_DEVICES[0])) {
                        headHandle = -1;
                    }
                    break;
                }
                if (uuid.equals(UuidUtils.STANDALONE_UUID)) {
                    headHandle = sensor.getHandle();
                    break;
                }
            }
        }
        return headHandle;
    }

    private int getScreenSensorHandle() {
        int screenHandle = -1;
        Sensor screenSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (screenSensor != null) {
            screenHandle = screenSensor.getHandle();
        }
        return screenHandle;
    }
}
