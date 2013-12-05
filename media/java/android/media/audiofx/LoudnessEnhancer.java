/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media.audiofx;

import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.util.StringTokenizer;


/**
 * LoudnessEnhancer is an audio effect for increasing audio loudness.
 * The processing is parametrized by a target gain value, which determines the maximum amount
 * by which an audio signal will be amplified; signals amplified outside of the sample
 * range supported by the platform are compressed.
 * An application creates a LoudnessEnhancer object to instantiate and control a
 * this audio effect in the audio framework.
 * To attach the LoudnessEnhancer to a particular AudioTrack or MediaPlayer,
 * specify the audio session ID of this AudioTrack or MediaPlayer when constructing the effect
 * (see {@link AudioTrack#getAudioSessionId()} and {@link MediaPlayer#getAudioSessionId()}).
 */

public class LoudnessEnhancer extends AudioEffect {

    private final static String TAG = "LoudnessEnhancer";

    // These parameter constants must be synchronized with those in
    // /system/media/audio_effects/include/audio_effects/effect_loudnessenhancer.h
    /**
     * The maximum gain applied applied to the signal to process.
     * It is expressed in millibels (100mB = 1dB) where 0mB corresponds to no amplification.
     */
    public static final int PARAM_TARGET_GAIN_MB = 0;

    /**
     * Registered listener for parameter changes.
     */
    private OnParameterChangeListener mParamListener = null;

    /**
     * Listener used internally to to receive raw parameter change events
     * from AudioEffect super class
     */
    private BaseParameterListener mBaseParamListener = null;

    /**
     * Lock for access to mParamListener
     */
    private final Object mParamListenerLock = new Object();

    /**
     * Class constructor.
     * @param audioSession system-wide unique audio session identifier. The LoudnessEnhancer
     * will be attached to the MediaPlayer or AudioTrack in the same audio session.
     *
     * @throws java.lang.IllegalStateException
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public LoudnessEnhancer(int audioSession)
            throws IllegalStateException, IllegalArgumentException,
                UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_LOUDNESS_ENHANCER, EFFECT_TYPE_NULL, 0, audioSession);

        if (audioSession == 0) {
            Log.w(TAG, "WARNING: attaching a LoudnessEnhancer to global output mix is deprecated!");
        }
    }

    /**
     * @hide
     * Class constructor for the LoudnessEnhancer audio effect.
     * @param priority the priority level requested by the application for controlling the
     * LoudnessEnhancer engine. As the same engine can be shared by several applications,
     * this parameter indicates how much the requesting application needs control of effect
     * parameters. The normal priority is 0, above normal is a positive number, below normal a
     * negative number.
     * @param audioSession system-wide unique audio session identifier. The LoudnessEnhancer
     * will be attached to the MediaPlayer or AudioTrack in the same audio session.
     *
     * @throws java.lang.IllegalStateException
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public LoudnessEnhancer(int priority, int audioSession)
            throws IllegalStateException, IllegalArgumentException,
                UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_LOUDNESS_ENHANCER, EFFECT_TYPE_NULL, priority, audioSession);

        if (audioSession == 0) {
            Log.w(TAG, "WARNING: attaching a LoudnessEnhancer to global output mix is deprecated!");
        }
    }

    /**
     * Set the target gain for the audio effect.
     * The target gain is the maximum value by which a sample value will be amplified when the
     * effect is enabled.
     * @param gainmB the effect target gain expressed in mB. 0mB corresponds to no amplification.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setTargetGain(int gainmB)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_TARGET_GAIN_MB, gainmB));
    }

    /**
     * Return the target gain.
     * @return the effect target gain expressed in mB.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public float getTargetGain()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] value = new int[1];
        checkStatus(getParameter(PARAM_TARGET_GAIN_MB, value));
        return value[0];
    }

    /**
     * @hide
     * The OnParameterChangeListener interface defines a method called by the LoudnessEnhancer
     * when a parameter value has changed.
     */
    public interface OnParameterChangeListener  {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * LoudnessEnhancer engine.
         * @param effect the LoudnessEnhancer on which the interface is registered.
         * @param param ID of the modified parameter. See {@link #PARAM_GENERIC_PARAM1} ...
         * @param value the new parameter value.
         */
        void onParameterChange(LoudnessEnhancer effect, int param, int value);
    }

    /**
     * Listener used internally to receive unformatted parameter change events from AudioEffect
     * super class.
     */
    private class BaseParameterListener implements AudioEffect.OnParameterChangeListener {
        private BaseParameterListener() {

        }
        public void onParameterChange(AudioEffect effect, int status, byte[] param, byte[] value) {
            // only notify when the parameter was successfully change
            if (status != AudioEffect.SUCCESS) {
                return;
            }
            OnParameterChangeListener l = null;
            synchronized (mParamListenerLock) {
                if (mParamListener != null) {
                    l = mParamListener;
                }
            }
            if (l != null) {
                int p = -1;
                int v = Integer.MIN_VALUE;

                if (param.length == 4) {
                    p = byteArrayToInt(param, 0);
                }
                if (value.length == 4) {
                    v = byteArrayToInt(value, 0);
                }
                if (p != -1 && v != Integer.MIN_VALUE) {
                    l.onParameterChange(LoudnessEnhancer.this, p, v);
                }
            }
        }
    }

    /**
     * @hide
     * Registers an OnParameterChangeListener interface.
     * @param listener OnParameterChangeListener interface registered
     */
    public void setParameterListener(OnParameterChangeListener listener) {
        synchronized (mParamListenerLock) {
            if (mParamListener == null) {
                mBaseParamListener = new BaseParameterListener();
                super.setParameterListener(mBaseParamListener);
            }
            mParamListener = listener;
        }
    }

    /**
     * @hide
     * The Settings class regroups the LoudnessEnhancer parameters. It is used in
     * conjunction with the getProperties() and setProperties() methods to backup and restore
     * all parameters in a single call.
     */
    public static class Settings {
        public int targetGainmB;

        public Settings() {
        }

        /**
         * Settings class constructor from a key=value; pairs formatted string. The string is
         * typically returned by Settings.toString() method.
         * @throws IllegalArgumentException if the string is not correctly formatted.
         */
        public Settings(String settings) {
            StringTokenizer st = new StringTokenizer(settings, "=;");
            //int tokens = st.countTokens();
            if (st.countTokens() != 3) {
                throw new IllegalArgumentException("settings: " + settings);
            }
            String key = st.nextToken();
            if (!key.equals("LoudnessEnhancer")) {
                throw new IllegalArgumentException(
                        "invalid settings for LoudnessEnhancer: " + key);
            }
            try {
                key = st.nextToken();
                if (!key.equals("targetGainmB")) {
                    throw new IllegalArgumentException("invalid key name: " + key);
                }
                targetGainmB = Integer.parseInt(st.nextToken());
             } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid value for key: " + key);
            }
        }

        @Override
        public String toString() {
            String str = new String (
                    "LoudnessEnhancer"+
                    ";targetGainmB="+Integer.toString(targetGainmB)
                    );
            return str;
        }
    };


    /**
     * @hide
     * Gets the LoudnessEnhancer properties. This method is useful when a snapshot of current
     * effect settings must be saved by the application.
     * @return a LoudnessEnhancer.Settings object containing all current parameters values
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public LoudnessEnhancer.Settings getProperties()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        Settings settings = new Settings();
        int[] value = new int[1];
        checkStatus(getParameter(PARAM_TARGET_GAIN_MB, value));
        settings.targetGainmB = value[0];
        return settings;
    }

    /**
     * @hide
     * Sets the LoudnessEnhancer properties. This method is useful when bass boost settings
     * have to be applied from a previous backup.
     * @param settings a LoudnessEnhancer.Settings object containing the properties to apply
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setProperties(LoudnessEnhancer.Settings settings)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_TARGET_GAIN_MB, settings.targetGainmB));
    }
}
