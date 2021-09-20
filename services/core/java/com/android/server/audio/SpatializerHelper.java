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
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.INativeSpatializerCallback;
import android.media.ISpatializer;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.Spatializer;
import android.media.SpatializerHeadTrackingMode;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A helper class to manage Spatializer related functionality
 */
public class SpatializerHelper {

    private static final String TAG = "AS.SpatializerHelper";
    private static final boolean DEBUG = true;

    private static void logd(String s) {
        if (DEBUG) {
            Log.i(TAG, s);
        }
    }

    private final @NonNull AudioSystemAdapter mASA;
    private final @NonNull AudioService mAudioService;

    //------------------------------------------------------------
    // Spatializer state machine
    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_NOT_SUPPORTED = 1;
    private static final int STATE_DISABLED_UNAVAILABLE = 3;
    private static final int STATE_ENABLED_UNAVAILABLE = 4;
    private static final int STATE_ENABLED_AVAILABLE = 5;
    private static final int STATE_DISABLED_AVAILABLE = 6;
    private int mState = STATE_UNINITIALIZED;

    /** current level as reported by native Spatializer in callback */
    private int mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    private int mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private int mDesiredHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
    private @Nullable ISpatializer mSpat;
    private @Nullable SpatializerCallback mSpatCallback;

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

    private final ArrayList<AudioDeviceAttributes> mCompatibleAudioDevices = new ArrayList<>(0);

    //------------------------------------------------------
    // initialization
    SpatializerHelper(@NonNull AudioService mother, @NonNull AudioSystemAdapter asa) {
        mAudioService = mother;
        mASA = asa;
    }

    synchronized void init() {
        Log.i(TAG, "Initializing");
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
                    logd("Setting Spatializer to LEVEL_MULTICHANNEL");
                    mCapableSpatLevel = level;
                    break;
                }
            }
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
        // note at this point mSpat is still not instantiated
    }

    /**
     * Like init() but resets the state and spatializer levels
     * @param featureEnabled
     */
    synchronized void reset(boolean featureEnabled) {
        Log.i(TAG, "Resetting");
        mState = STATE_UNINITIALIZED;
        mSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mCapableSpatLevel = Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        mActualHeadTrackingMode = Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED;
        init();
        setFeatureEnabled(featureEnabled);
    }

    //------------------------------------------------------
    // routing monitoring
    void onRoutingUpdated() {
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
        mASA.getDevicesForAttributes(DEFAULT_ATTRIBUTES).toArray(ROUTING_DEVICES);
        final boolean able =
                AudioSystem.canBeSpatialized(DEFAULT_ATTRIBUTES, DEFAULT_FORMAT, ROUTING_DEVICES);
        logd("onRoutingUpdated: can spatialize media 5.1:" + able
                + " on device:" + ROUTING_DEVICES[0]);
        setDispatchAvailableState(able);
    }

    //------------------------------------------------------
    // spatializer callback from native
    private final class SpatializerCallback extends INativeSpatializerCallback.Stub {

        public void onLevelChanged(byte level) {
            logd("SpatializerCallback.onLevelChanged level:" + level);
            synchronized (SpatializerHelper.this) {
                mSpatLevel = level;
            }
            // TODO use reported spat level to change state
        }

        public void onHeadTrackingModeChanged(byte mode)  {
            logd("SpatializerCallback.onHeadTrackingModeChanged mode:" + mode);
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
                Log.e(TAG, "SpatializerCallback.onHeadToStagePoseUpdated null transform");
                return;
            }
            if (headToStage.length != 6) {
                Log.e(TAG, "SpatializerCallback.onHeadToStagePoseUpdated invalid transform length"
                        + headToStage.length);
                return;
            }
            if (DEBUG) {
                // 6 values * (4 digits + 1 dot + 2 brackets) = 42 characters
                StringBuilder t = new StringBuilder(42);
                for (float val : headToStage) {
                    t.append("[").append(String.format(Locale.ENGLISH, "%.3f", val)).append("]");
                }
                logd("SpatializerCallback.onHeadToStagePoseUpdated headToStage:" + t);
            }
            dispatchPoseUpdate(headToStage);
        }
    };

    //------------------------------------------------------
    // compatible devices
    /**
     * @return a shallow copy of the list of compatible audio devices
     */
    synchronized @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        return (List<AudioDeviceAttributes>) mCompatibleAudioDevices.clone();
    }

    synchronized void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        if (!mCompatibleAudioDevices.contains(ada)) {
            mCompatibleAudioDevices.add(ada);
        }
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mCompatibleAudioDevices.remove(ada);
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

    synchronized void setFeatureEnabled(boolean enabled) {
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
     * precondition: mState = STATE_*
     *               isFeatureEnabled() != featureEnabled
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
        // TODO persist enabled state
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
            mSpat = AudioSystem.getSpatializer(mSpatCallback);
            try {
                mSpat.setLevel((byte)  Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL);
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
                mSpat.release();
                mSpat = null;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't set release spatializer cleanly", e);
            }
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
        AudioDeviceAttributes[] devices =
                // going through adapter to take advantage of routing cache
                (AudioDeviceAttributes[]) mASA.getDevicesForAttributes(attributes).toArray();
        final boolean able = AudioSystem.canBeSpatialized(attributes, format, devices);
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
        try {
            if (mode != mDesiredHeadTrackingMode) {
                mSpat.setDesiredHeadTrackingMode(spatializerIntToHeadTrackingModeType(mode));
                mDesiredHeadTrackingMode = mode;
                dispatchDesiredHeadTrackingMode(mode);
            }

        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDesiredHeadTrackingMode", e);
        }
    }

    private int headTrackingModeTypeToSpatializerInt(byte mode) {
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

    private byte spatializerIntToHeadTrackingModeType(int sdkMode) {
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
        return true;
    }

    private void dispatchActualHeadTrackingMode(int newMode) {
        final int nbCallbacks = mHeadTrackingModeCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mHeadTrackingModeCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerActualHeadTrackingModeChanged(newMode);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerActualHeadTrackingModeChanged", e);
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
                Log.e(TAG, "Error in dispatchSpatializerDesiredHeadTrackingModeChanged", e);
            }
        }
        mHeadTrackingModeCallbacks.finishBroadcast();
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
}
