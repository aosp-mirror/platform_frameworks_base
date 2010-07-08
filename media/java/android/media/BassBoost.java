/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import android.media.AudioEffect;

/**
 * Bass boost is an audio effect to boost or amplify low frequencies of the sound. It is comparable
 * to an simple equalizer but limited to one band amplification in the low frequency range.
 * <p>An application creates a BassBoost object to instantiate and control a bass boost engine
 * in the audio framework.
 * <p>The methods, parameter types and units exposed by the BassBoost implementation are directly
 * mapping those defined by the OpenSL ES 1.0.1 Specification (http://www.khronos.org/opensles/)
 * for the SLBassBoostItf interface. Please refer to this specification for more details.
 * <p>To attach the BassBoost to a particular AudioTrack or MediaPlayer, specify the audio session
 * ID of this AudioTrack or MediaPlayer when constructing the BassBoost. If the audio session ID 0
 * is specified, the BassBoost applies to the main audio output mix.
 // TODO when AudioEffect is unhidden
 // <p> See {_at_link android.media.AudioEffect} class for more details on controlling audio effects.
 *
 * {@hide Pending API council review}
 */

public class BassBoost extends AudioEffect {

    private final static String TAG = "BassBoost";

    // These constants must be synchronized with those in
    // frameworks/base/include/media/EffectBassBoostApi.h
    /**
     * Is strength parameter supported by bass boost engine. Parameter ID for getParameter().
     */
    public static final int PARAM_STRENGTH_SUPPORTED = 0;
    /**
     * Bass boost effect strength. Parameter ID for
     * {@link android.media.BassBoost.OnParameterChangeListener}
     */
    public static final int PARAM_STRENGTH = 1;

    /**
     * Indicates if strength parameter is supported by the bass boost engine
     */
    private boolean mStrengthSupported = false;

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
     * @param priority the priority level requested by the application for controlling the BassBoost
     * engine. As the same engine can be shared by several applications, this parameter indicates
     * how much the requesting application needs control of effect parameters. The normal priority
     * is 0, above normal is a positive number, below normal a negative number.
     * @param audioSession  System wide unique audio session identifier. If audioSession
     *  is not 0, the BassBoost will be attached to the MediaPlayer or AudioTrack in the
     *  same audio session. Otherwise, the BassBoost will apply to the output mix.
     *
     * @throws java.lang.IllegalStateException
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public BassBoost(int priority, int audioSession)
    throws IllegalStateException, IllegalArgumentException,
           UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_BASS_BOOST, EFFECT_TYPE_NULL, priority, audioSession);

        short[] value = new short[1];
        checkStatus(getParameter(PARAM_STRENGTH_SUPPORTED, value));
        mStrengthSupported = (value[0] != 0);
    }

    /**
     * Indicates whether setting strength is supported. If this method returns false, only one
     * strength is supported and the setStrength() method always rounds to that value.
     * @return true is strength parameter is supported, false otherwise
     */
    public boolean getStrengthSupported() {
       return mStrengthSupported;
    }

    /**
     * Sets the strength of the bass boost effect. If the implementation does not support per mille
     * accuracy for setting the strength, it is allowed to round the given strength to the nearest
     * supported value. You can use the {@link #getRoundedStrength()} method to query the
     * (possibly rounded) value that was actually set.
     * @param strength Strength of the effect. The valid range for strength strength is [0, 1000],
     * where 0 per mille designates the mildest effect and 1000 per mille designates the strongest.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setStrength(short strength)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_STRENGTH, strength));
    }

    /**
     * Gets the current strength of the effect.
     * @return The strength of the effect. The valid range for strength is [0, 1000], where 0 per
     * mille designates the mildest effect and 1000 per mille the strongest
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getRoundedStrength()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        short[] value = new short[1];
        checkStatus(getParameter(PARAM_STRENGTH, value));
        return value[0];
    }

    /**
     * The OnParameterChangeListener interface defines a method called by the BassBoost when a
     * parameter value has changed.
     */
    public interface OnParameterChangeListener  {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * BassBoost engine.
         * @param effect the BassBoost on which the interface is registered.
         * @param status status of the set parameter operation.
         // TODO when AudioEffect is unhidden
         // See {_at_link android.media.AudioEffect#setParameter(byte[], byte[])}.
         * @param param ID of the modified parameter. See {@link #PARAM_STRENGTH} ...
         * @param value the new parameter value.
         */
        void onParameterChange(BassBoost effect, int status, int param, short value);
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
                int p = -1;
                short v = -1;

                if (param.length == 4) {
                    p = byteArrayToInt(param, 0);
                }
                if (value.length == 2) {
                    v = byteArrayToShort(value, 0);
                }
                if (p != -1 && v != -1) {
                    l.onParameterChange(BassBoost.this, status, p, v);
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
}
