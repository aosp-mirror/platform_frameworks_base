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

import android.media.AudioEffect;

/**
 * A sound generated within a room travels in many directions. The listener first hears the
 * direct sound from the source itself. Later, he or she hears discrete echoes caused by sound
 * bouncing off nearby walls, the ceiling and the floor. As sound waves arrive after
 * undergoing more and more reflections, individual reflections become indistinguishable and
 * the listener hears continuous reverberation that decays over time.
 * Reverb is vital for modeling a listener's environment. It can be used in music applications
 * to simulate music being played back in various environments, or in games to immerse the
 * listener within the game's environment.
 * The PresetReverb class allows an application to configure the global reverb using a reverb preset.
 * This is primarily used for adding some reverb in a music playback context. Applications
 * requiring control over a more advanced environmental reverb are advised to use the
 // TODO when EnvironmentalReverb is unhidden
 // {_at_link android.media.EnvironmentalReverb} class.
 * <p>An application creates a PresetReverb object to instantiate and control a reverb engine in the
 * audio framework.
 * <p>The methods, parameter types and units exposed by the PresetReverb implementation are
 * directly mapping those defined by the OpenSL ES 1.0.1 Specification
 * (http://www.khronos.org/opensles/) for the SLPresetReverbItf interface.
 * Please refer to this specification for more details.
 * <p>The PresetReverb is an output mix auxiliary effect and should be created on
 * Audio session 0. In order for a MediaPlayer or AudioTrack to be fed into this effect,
 * they must be explicitely attached to it and a send level must be specified. Use the effect ID
 * returned by getId() method to designate this particular effect when attaching it to the
 * MediaPlayer or AudioTrack.
 // TODO when AudioEffect is unhidden
 // <p> See {_at_link android.media.AudioEffect} class for more details on controlling audio effects.
 *
 * {@hide Pending API council review}
 */

public class PresetReverb extends AudioEffect {

    private final static String TAG = "PresetReverb";

    // These constants must be synchronized with those in
    // frameworks/base/include/media/EffectPresetReverbApi.h

    /**
     * Preset. Parameter ID for
     * {@link android.media.PresetReverb.OnParameterChangeListener}
     */
    public static final int PARAM_PRESET = 0;

    /**
     * Room level. Parameter ID for
     * {@link android.media.PresetReverb.OnParameterChangeListener}
     */
    public static final int PRESET_NONE        = 0;
    public static final int PRESET_SMALLROOM   = 1;
    public static final int PRESET_MEDIUMROOM  = 2;
    public static final int PRESET_LARGEROOM   = 3;
    public static final int PRESET_MEDIUMHALL  = 4;
    public static final int PRESET_LARGEHALL   = 5;
    public static final int PRESET_PLATE       = 6;

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
     * @param priority the priority level requested by the application for controlling the
     * PresetReverb engine. As the same engine can be shared by several applications, this
     * parameter indicates how much the requesting application needs control of effect parameters.
     * The normal priority is 0, above normal is a positive number, below normal a negative number.
     * @param audioSession  System wide unique audio session identifier. If audioSession
     *  is not 0, the PresetReverb will be attached to the MediaPlayer or AudioTrack in the
     *  same audio session. Otherwise, the PresetReverb will apply to the output mix.
     *  As the PresetReverb is an auxiliary effect it is recommended to instantiate it on
     *  audio session 0 and to attach it to the MediaPLayer auxiliary output.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public PresetReverb(int priority, int audioSession)
    throws IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_PRESET_REVERB, EFFECT_TYPE_NULL, priority, audioSession);
        Log.e(TAG, "contructor");
    }

    /**
     *  Enables a preset on the reverb.
     *  <p>The reverb PRESET_NONE disables any reverb from the current output but does not free the
     *  resources associated with the reverb. For an application to signal to the implementation
     *  to free the resources, it must call the release() method.
     * @param preset This must be one of the the preset constants defined in this class.
     * e.g. {@link #PRESET_SMALLROOM}
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setPreset(short preset)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_PRESET, preset));
    }

    /**
     * Gets current reverb preset.
     * @return Preset that is set at the moment.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public short getPreset()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        int[] param = new int[1];
        param[0] = PARAM_PRESET;
        short[] value = new short[1];
        checkStatus(getParameter(param, value));
        return value[0];
    }

    /**
     * The OnParameterChangeListener interface defines a method called by the PresetReverb
     * when a parameter value has changed.
     */
    public interface OnParameterChangeListener  {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * PresetReverb engine.
         * @param effect the PresetReverb on which the interface is registered.
         * @param status status of the set parameter operation.
         // TODO when AudioEffect is unhidden
         // See {_at_link android.media.AudioEffect#setParameter(byte[], byte[])}.
         * @param param ID of the modified parameter. See {@link #PARAM_PRESET} ...
         * @param value the new parameter value.
         */
        void onParameterChange(PresetReverb effect, int status, int param, short value);
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
                    l.onParameterChange(PresetReverb.this, status, p, v);
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
