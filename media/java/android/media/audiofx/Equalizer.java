/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.util.StringTokenizer;


/**
 * An Equalizer is used to alter the frequency response of a particular music source or of the main
 * output mix.
 * <p>An application creates an Equalizer object to instantiate and control an Equalizer engine
 * in the audio framework. The application can either simply use predefined presets or have a more
 * precise control of the gain in each frequency band controlled by the equalizer.
 * <p>The methods, parameter types and units exposed by the Equalizer implementation are directly
 * mapping those defined by the OpenSL ES 1.0.1 Specification (http://www.khronos.org/opensles/)
 * for the SLEqualizerItf interface. Please refer to this specification for more details.
 * <p>To attach the Equalizer to a particular AudioTrack or MediaPlayer, specify the audio session
 * ID of this AudioTrack or MediaPlayer when constructing the Equalizer.
 * <p>NOTE: attaching an Equalizer to the global audio output mix by use of session 0 is deprecated.
 * <p>See {@link android.media.MediaPlayer#getAudioSessionId()} for details on audio sessions.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on controlling audio
 * effects.
 */

public class Equalizer extends AudioEffect {

    private final static String TAG = "Equalizer";

    // These constants must be synchronized with those in
    // frameworks/base/include/media/EffectEqualizerApi.h
    /**
     * Number of bands. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_NUM_BANDS = 0;
    /**
     * Band level range. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_LEVEL_RANGE = 1;
    /**
     * Band level. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_BAND_LEVEL = 2;
    /**
     * Band center frequency. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_CENTER_FREQ = 3;
    /**
     * Band frequency range. Parameter ID for
     * {@link android.media.audiofx.Equalizer.OnParameterChangeListener}
     */
    public static final int PARAM_BAND_FREQ_RANGE = 4;
    /**
     * Band for a given frequency. Parameter ID for OnParameterChangeListener
     *
     */
    public static final int PARAM_GET_BAND = 5;
    /**
     * Current preset. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_CURRENT_PRESET = 6;
    /**
     * Request number of presets. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_GET_NUM_OF_PRESETS = 7;
    /**
     * Request preset name. Parameter ID for OnParameterChangeListener
     */
    public static final int PARAM_GET_PRESET_NAME = 8;
    // used by setProperties()/getProperties
    private static final int PARAM_PROPERTIES = 9;
    /**
     * Maximum size for preset name
     */
    public static final int PARAM_STRING_SIZE_MAX = 32;

    /**
     * Number of bands implemented by Equalizer engine
     */
    private short mNumBands = 0;

    /**
     * Number of presets implemented by Equalizer engine
     */
    private int mNumPresets;
    /**
     * Names of presets implemented by Equalizer engine
     */
    private String[] mPresetNames;

    /**
     * Registered listener for parameter changes.
     */
    private OnParameterChangeListener mParamListener = null;

    /**
     * Listener used internally to to receive raw parameter change event from AudioEffect super class
     */
    private BaseParameterListener mBaseParamListener = null;

    /**
     * Lock for access to mParamListener
     */
    private final Object mParamListenerLock = new Object();

    /**
     * Class constructor.
     * @param priority the priority level requested by the application for controlling the Equalizer
     * engine. As the same engine can be shared by several applications, this parameter indicates
     * how much the requesting application needs control of effect parameters. The normal priority
     * is 0, above normal is a positive number, below normal a negative number.
     * @param audioSession  system wide unique audio session identifier. The Equalizer will be
     * attached to the MediaPlayer or AudioTrack in the same audio session.
     *
     * @throws java.lang.IllegalStateException
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public Equalizer(int priority, int audioSession)
    throws IllegalStateException, IllegalArgumentException,
           UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_EQUALIZER, EFFECT_TYPE_NULL, priority, audioSession);

        if (audioSession == 0) {
            Log.w(TAG, "WARNING: attaching an Equalizer to global output mix is deprecated!");
        }

        getNumberOfBands();

        mNumPresets = (int)getNumberOfPresets();

        if (mNumPresets != 0) {
            mPresetNames = new String[mNumPresets];
            byte[] value = new byte[PARAM_STRING_SIZE_MAX];
            int[] param = new int[2];
            param[0] = PARAM_GET_PRESET_NAME;
            for (int i = 0; i < mNumPresets; i++) {
                param[1] = i;
                checkStatus(getParameter(param, value));
                int length = 0;
                while (value[length] != 0) length++;
                try {
                    mPresetNames[i] = new String(value, 0, length, "ISO-8859-1");
                } catch (java.io.UnsupportedEncodingException e) {
                    Log.e(TAG, "preset name decode error");
                }
            }
        }
    }

    /**
     * Gets the number of frequency bands supported by the Equalizer engine.
     * @return the number of bands
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getNumberOfBands()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        if (mNumBands != 0) {
            return mNumBands;
        }
        int[] param = new int[1];
        param[0] = PARAM_NUM_BANDS;
        short[] result = new short[1];
        checkStatus(getParameter(param, result));
        mNumBands = result[0];
        return mNumBands;
    }

    /**
     * Gets the level range for use by {@link #setBandLevel(short,short)}. The level is expressed in
     * milliBel.
     * @return the band level range in an array of short integers. The first element is the lower
     * limit of the range, the second element the upper limit.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short[] getBandLevelRange()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        short[] result = new short[2];
        checkStatus(getParameter(PARAM_LEVEL_RANGE, result));
        return result;
    }

    /**
     * Sets the given equalizer band to the given gain value.
     * @param band frequency band that will have the new gain. The numbering of the bands starts
     * from 0 and ends at (number of bands - 1).
     * @param level new gain in millibels that will be set to the given band. getBandLevelRange()
     * will define the maximum and minimum values.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     * @see #getNumberOfBands()
     */
    public void setBandLevel(short band, short level)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        short[] value = new short[1];

        param[0] = PARAM_BAND_LEVEL;
        param[1] = (int)band;
        value[0] = level;
        try {
            checkStatus(setParameter(param, value));
        } catch(IllegalArgumentException e) {
        }
    }

    /**
     * Gets the gain set for the given equalizer band.
     * @param band frequency band whose gain is requested. The numbering of the bands starts
     * from 0 and ends at (number of bands - 1).
     * @return the gain in millibels of the given band.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getBandLevel(short band)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        short[] result = new short[1];

        param[0] = PARAM_BAND_LEVEL;
        param[1] = (int)band;
        try {
            checkStatus(getParameter(param, result));
            return result[0];
        } catch(IllegalArgumentException e) {
            return 0;
        }
    }


    /**
     * Gets the center frequency of the given band.
     * @param band frequency band whose center frequency is requested. The numbering of the bands
     * starts from 0 and ends at (number of bands - 1).
     * @return the center frequency in milliHertz
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public int getCenterFreq(short band)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        int[] result = new int[1];

        param[0] = PARAM_CENTER_FREQ;
        param[1] = (int)band;
        checkStatus(getParameter(param, result));

        return result[0];
    }

    /**
     * Gets the frequency range of the given frequency band.
     * @param band frequency band whose frequency range is requested. The numbering of the bands
     * starts from 0 and ends at (number of bands - 1).
     * @return the frequency range in millHertz in an array of integers. The first element is the
     * lower limit of the range, the second element the upper limit.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public int[] getBandFreqRange(short band)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        int[] result = new int[2];
        param[0] = PARAM_BAND_FREQ_RANGE;
        param[1] = (int)band;
        checkStatus(getParameter(param, result));

        return result;
    }

    /**
     * Gets the band that has the most effect on the given frequency.
     * @param frequency frequency in milliHertz which is to be equalized via the returned band.
     * @return the frequency band that has most effect on the given frequency.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getBand(int frequency)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[2];
        short[] result = new short[1];

        param[0] = PARAM_GET_BAND;
        param[1] = frequency;
        checkStatus(getParameter(param, result));

        return result[0];
    }

    /**
     * Gets current preset.
     * @return the preset that is set at the moment.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getCurrentPreset()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        short[] result = new short[1];
        checkStatus(getParameter(PARAM_CURRENT_PRESET, result));
        return result[0];
    }

    /**
     * Sets the equalizer according to the given preset.
     * @param preset new preset that will be taken into use. The valid range is [0,
     * number of presets-1].
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     * @see #getNumberOfPresets()
     */
    public void usePreset(short preset)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_CURRENT_PRESET, preset));
    }

    /**
     * Gets the total number of presets the equalizer supports. The presets will have indices
     * [0, number of presets-1].
     * @return the number of presets the equalizer supports.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getNumberOfPresets()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        short[] result = new short[1];
        checkStatus(getParameter(PARAM_GET_NUM_OF_PRESETS, result));
        return result[0];
    }

    /**
     * Gets the preset name based on the index.
     * @param preset index of the preset. The valid range is [0, number of presets-1].
     * @return a string containing the name of the given preset.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public String getPresetName(short preset)
    {
        if (preset >= 0 && preset < mNumPresets) {
            return mPresetNames[preset];
        } else {
            return "";
        }
    }

    /**
     * The OnParameterChangeListener interface defines a method called by the Equalizer when a
     * parameter value has changed.
     */
    public interface OnParameterChangeListener  {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * Equalizer engine.
         * @param effect the Equalizer on which the interface is registered.
         * @param status status of the set parameter operation.
         * @param param1 ID of the modified parameter. See {@link #PARAM_BAND_LEVEL} ...
         * @param param2 additional parameter qualifier (e.g the band for band level parameter).
         * @param value the new parameter value.
         */
        void onParameterChange(Equalizer effect, int status, int param1, int param2, int value);
    }

    /**
     * Listener used internally to receive unformatted parameter change events from AudioEffect
     * super class.
     */
    private class BaseParameterListener implements AudioEffect.OnParameterChangeListener {
        private BaseParameterListener() {

        }
        public void onParameterChange(AudioEffect effect, int status, byte[] param, byte[] value) {
            OnParameterChangeListener l = null;

            synchronized (mParamListenerLock) {
                if (mParamListener != null) {
                    l = mParamListener;
                }
            }
            if (l != null) {
                int p1 = -1;
                int p2 = -1;
                int v = -1;

                if (param.length >= 4) {
                    p1 = byteArrayToInt(param, 0);
                    if (param.length >= 8) {
                        p2 = byteArrayToInt(param, 4);
                    }
                }
                if (value.length == 2) {
                    v = (int)byteArrayToShort(value, 0);;
                } else if (value.length == 4) {
                    v = byteArrayToInt(value, 0);
                }

                if (p1 != -1 && v != -1) {
                    l.onParameterChange(Equalizer.this, status, p1, p2, v);
                }
            }
        }
    }

    /**
     * Registers an OnParameterChangeListener interface.
     * @param listener OnParameterChangeListener interface registered
     */
    public void setParameterListener(OnParameterChangeListener listener) {
        synchronized (mParamListenerLock) {
            if (mParamListener == null) {
                mParamListener = listener;
                mBaseParamListener = new BaseParameterListener();
                super.setParameterListener(mBaseParamListener);
            }
        }
    }

    /**
     * The Settings class regroups all equalizer parameters. It is used in
     * conjuntion with getProperties() and setProperties() methods to backup and restore
     * all parameters in a single call.
     */
    public static class Settings {
        public short curPreset;
        public short numBands = 0;
        public short[] bandLevels = null;

        public Settings() {
        }

        /**
         * Settings class constructor from a key=value; pairs formatted string. The string is
         * typically returned by Settings.toString() method.
         * @throws IllegalArgumentException if the string is not correctly formatted.
         */
        public Settings(String settings) {
            StringTokenizer st = new StringTokenizer(settings, "=;");
            int tokens = st.countTokens();
            if (st.countTokens() < 5) {
                throw new IllegalArgumentException("settings: " + settings);
            }
            String key = st.nextToken();
            if (!key.equals("Equalizer")) {
                throw new IllegalArgumentException(
                        "invalid settings for Equalizer: " + key);
            }
            try {
                key = st.nextToken();
                if (!key.equals("curPreset")) {
                    throw new IllegalArgumentException("invalid key name: " + key);
                }
                curPreset = Short.parseShort(st.nextToken());
                key = st.nextToken();
                if (!key.equals("numBands")) {
                    throw new IllegalArgumentException("invalid key name: " + key);
                }
                numBands = Short.parseShort(st.nextToken());
                if (st.countTokens() != numBands*2) {
                    throw new IllegalArgumentException("settings: " + settings);
                }
                bandLevels = new short[numBands];
                for (int i = 0; i < numBands; i++) {
                    key = st.nextToken();
                    if (!key.equals("band"+(i+1)+"Level")) {
                        throw new IllegalArgumentException("invalid key name: " + key);
                    }
                    bandLevels[i] = Short.parseShort(st.nextToken());
                }
             } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid value for key: " + key);
            }
        }

        @Override
        public String toString() {

            String str = new String (
                    "Equalizer"+
                    ";curPreset="+Short.toString(curPreset)+
                    ";numBands="+Short.toString(numBands)
                    );
            for (int i = 0; i < numBands; i++) {
                str = str.concat(";band"+(i+1)+"Level="+Short.toString(bandLevels[i]));
            }
            return str;
        }
    };


    /**
     * Gets the equalizer properties. This method is useful when a snapshot of current
     * equalizer settings must be saved by the application.
     * @return an Equalizer.Settings object containing all current parameters values
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public Equalizer.Settings getProperties()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        byte[] param = new byte[4 + mNumBands * 2];
        checkStatus(getParameter(PARAM_PROPERTIES, param));
        Settings settings = new Settings();
        settings.curPreset = byteArrayToShort(param, 0);
        settings.numBands = byteArrayToShort(param, 2);
        settings.bandLevels = new short[mNumBands];
        for (int i = 0; i < mNumBands; i++) {
            settings.bandLevels[i] = byteArrayToShort(param, 4 + 2*i);
        }
        return settings;
    }

    /**
     * Sets the equalizer properties. This method is useful when equalizer settings have to
     * be applied from a previous backup.
     * @param settings an Equalizer.Settings object containing the properties to apply
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setProperties(Equalizer.Settings settings)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        if (settings.numBands != settings.bandLevels.length ||
            settings.numBands != mNumBands) {
            throw new IllegalArgumentException("settings invalid band count: " +settings.numBands);
        }

        byte[] param = concatArrays(shortToByteArray(settings.curPreset),
                                    shortToByteArray(mNumBands));
        for (int i = 0; i < mNumBands; i++) {
            param = concatArrays(param,
                                 shortToByteArray(settings.bandLevels[i]));
        }
        checkStatus(setParameter(PARAM_PROPERTIES, param));
    }
}
