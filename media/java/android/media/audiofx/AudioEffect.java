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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * AudioEffect is the base class for controlling audio effects provided by the android audio
 * framework.
 * <p>Applications should not use the AudioEffect class directly but one of its derived classes to
 * control specific effects:
 * <ul>
 *   <li> {@link android.media.audiofx.Equalizer}</li>
 *   <li> {@link android.media.audiofx.Virtualizer}</li>
 *   <li> {@link android.media.audiofx.BassBoost}</li>
 *   <li> {@link android.media.audiofx.PresetReverb}</li>
 *   <li> {@link android.media.audiofx.EnvironmentalReverb}</li>
 * </ul>
 * <p>If the audio effect is to be applied to a specific AudioTrack or MediaPlayer instance,
 * the application must specify the audio session ID of that instance when creating the AudioEffect.
 * (see {@link android.media.MediaPlayer#getAudioSessionId()} for details on audio sessions).
 * To apply an effect to the global audio output mix, session 0 must be specified when creating the
 * AudioEffect.
 * <p>Creating an effect on the output mix (audio session 0) requires permission
 * {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS}
 * <p>Creating an AudioEffect object will create the corresponding effect engine in the audio
 * framework if no instance of the same effect type exists in the specified audio session.
 * If one exists, this instance will be used.
 * <p>The application creating the AudioEffect object (or a derived class) will either receive
 * control of the effect engine or not depending on the priority parameter. If priority is higher
 * than the priority used by the current effect engine owner, the control will be transfered to the
 * new object. Otherwise control will remain with the previous object. In this case, the new
 * application will be notified of changes in effect engine state or control ownership by the
 * appropiate listener.
 */

public class AudioEffect {
    static {
        System.loadLibrary("audioeffect_jni");
        native_init();
    }

    private final static String TAG = "AudioEffect-JAVA";

    // effect type UUIDs are taken from hardware/libhardware/include/hardware/audio_effect.h

    /**
     * The following UUIDs define effect types corresponding to standard audio
     * effects whose implementation and interface conform to the OpenSL ES
     * specification. The definitions match the corresponding interface IDs in
     * OpenSLES_IID.h
     */

    /**
     * UUID for environmental reverb effect
     * @hide
     */
    public static final UUID EFFECT_TYPE_ENV_REVERB = UUID
            .fromString("c2e5d5f0-94bd-4763-9cac-4e234d06839e");
    /**
     * UUID for preset reverb effect
     * @hide
     */
    public static final UUID EFFECT_TYPE_PRESET_REVERB = UUID
            .fromString("47382d60-ddd8-11db-bf3a-0002a5d5c51b");
    /**
     * UUID for equalizer effect
     * @hide
     */
    public static final UUID EFFECT_TYPE_EQUALIZER = UUID
            .fromString("0bed4300-ddd6-11db-8f34-0002a5d5c51b");
    /**
     * UUID for bass boost effect
     * @hide
     */
    public static final UUID EFFECT_TYPE_BASS_BOOST = UUID
            .fromString("0634f220-ddd4-11db-a0fc-0002a5d5c51b");
    /**
     * UUID for virtualizer effect
     * @hide
     */
    public static final UUID EFFECT_TYPE_VIRTUALIZER = UUID
            .fromString("37cc2c00-dddd-11db-8577-0002a5d5c51b");

    /**
     * UUID for Automatic Gain Control (AGC) audio pre-processing
     * @hide
     */
    public static final UUID EFFECT_TYPE_AGC = UUID
            .fromString("0a8abfe0-654c-11e0-ba26-0002a5d5c51b");

    /**
     * UUID for Acoustic Echo Canceler (AEC) audio pre-processing
     * @hide
     */
    public static final UUID EFFECT_TYPE_AEC = UUID
            .fromString("7b491460-8d4d-11e0-bd61-0002a5d5c51b");

    /**
     * UUID for Noise Suppressor (NS) audio pre-processing
     * @hide
     */
    public static final UUID EFFECT_TYPE_NS = UUID
            .fromString("58b4b260-8e06-11e0-aa8e-0002a5d5c51b");

    /**
     * Null effect UUID. Used when the UUID for effect type of
     * @hide
     */
    public static final UUID EFFECT_TYPE_NULL = UUID
            .fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    /**
     * State of an AudioEffect object that was not successfully initialized upon
     * creation
     * @hide
     */
    public static final int STATE_UNINITIALIZED = 0;
    /**
     * State of an AudioEffect object that is ready to be used.
     * @hide
     */
    public static final int STATE_INITIALIZED = 1;

    // to keep in sync with
    // frameworks/base/include/media/AudioEffect.h
    /**
     * Event id for engine control ownership change notification.
     * @hide
     */
    public static final int NATIVE_EVENT_CONTROL_STATUS = 0;
    /**
     * Event id for engine state change notification.
     * @hide
     */
    public static final int NATIVE_EVENT_ENABLED_STATUS = 1;
    /**
     * Event id for engine parameter change notification.
     * @hide
     */
    public static final int NATIVE_EVENT_PARAMETER_CHANGED = 2;

    /**
     * Successful operation.
     */
    public static final int SUCCESS = 0;
    /**
     * Unspecified error.
     */
    public static final int ERROR = -1;
    /**
     * Internal operation status. Not returned by any method.
     */
    public static final int ALREADY_EXISTS = -2;
    /**
     * Operation failed due to bad object initialization.
     */
    public static final int ERROR_NO_INIT = -3;
    /**
     * Operation failed due to bad parameter value.
     */
    public static final int ERROR_BAD_VALUE = -4;
    /**
     * Operation failed because it was requested in wrong state.
     */
    public static final int ERROR_INVALID_OPERATION = -5;
    /**
     * Operation failed due to lack of memory.
     */
    public static final int ERROR_NO_MEMORY = -6;
    /**
     * Operation failed due to dead remote object.
     */
    public static final int ERROR_DEAD_OBJECT = -7;

    /**
     * The effect descriptor contains information on a particular effect implemented in the
     * audio framework:<br>
     * <ul>
     *  <li>type: UUID corresponding to the OpenSL ES interface implemented by this effect</li>
     *  <li>uuid: UUID for this particular implementation</li>
     *  <li>connectMode: {@link #EFFECT_INSERT}, {@link #EFFECT_AUXILIARY} or
     *  {at_link #EFFECT_PRE_PROCESSING}</li>
     *  <li>name: human readable effect name</li>
     *  <li>implementor: human readable effect implementor name</li>
     * </ul>
     * The method {@link #queryEffects()} returns an array of Descriptors to facilitate effects
     * enumeration.
     */
    public static class Descriptor {

        public Descriptor() {
        }

        public Descriptor(String type, String uuid, String connectMode,
                String name, String implementor) {
            this.type = UUID.fromString(type);
            this.uuid = UUID.fromString(uuid);
            this.connectMode = connectMode;
            this.name = name;
            this.implementor = implementor;
        }

        /**
         *  Indicates the generic type of the effect (Equalizer, Bass boost ...). The UUID
         *  corresponds to the OpenSL ES Interface ID for this type of effect.
         */
        public UUID type;
        /**
         *  Indicates the particular implementation of the effect in that type. Several effects
         *  can have the same type but this uuid is unique to a given implementation.
         */
        public UUID uuid;
        /**
         *  Indicates if the effect is of insert category {@link #EFFECT_INSERT}, auxiliary
         *  category {@link #EFFECT_AUXILIARY} or pre processing category
         *  {at_link #EFFECT_PRE_PROCESSING}. Insert effects (Typically an Equalizer) are applied
         *  to the entire audio source and usually not shared by several sources. Auxiliary effects
         *  (typically a reverberator) are applied to part of the signal (wet) and the effect output
         *  is added to the original signal (dry).
         *  Audio pre processing are applied to audio captured on a particular AudioRecord.
         */
        public String connectMode;
        /**
         * Human readable effect name
         */
        public String name;
        /**
         * Human readable effect implementor name
         */
        public String implementor;
    };

    /**
     * Effect connection mode is insert. Specifying an audio session ID when creating the effect
     * will insert this effect after all players in the same audio session.
     */
    public static final String EFFECT_INSERT = "Insert";
    /**
     * Effect connection mode is auxiliary.
     * <p>Auxiliary effects must be created on session 0 (global output mix). In order for a
     * MediaPlayer or AudioTrack to be fed into this effect, they must be explicitely attached to
     * this effect and a send level must be specified.
     * <p>Use the effect ID returned by {@link #getId()} to designate this particular effect when
     * attaching it to the MediaPlayer or AudioTrack.
     */
    public static final String EFFECT_AUXILIARY = "Auxiliary";
    /**
     * Effect connection mode is pre processing.
     * The audio pre processing effects are attached to an audio input (AudioRecord).
     * @hide
     */
    public static final String EFFECT_PRE_PROCESSING = "Pre Processing";

    // --------------------------------------------------------------------------
    // Member variables
    // --------------------
    /**
     * Indicates the state of the AudioEffect instance
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Lock to synchronize access to mState
     */
    private final Object mStateLock = new Object();
    /**
     * System wide unique effect ID
     */
    private int mId;

    // accessed by native methods
    private int mNativeAudioEffect;
    private int mJniData;

    /**
     * Effect descriptor
     */
    private Descriptor mDescriptor;

    /**
     * Listener for effect engine state change notifications.
     *
     * @see #setEnableStatusListener(OnEnableStatusChangeListener)
     */
    private OnEnableStatusChangeListener mEnableStatusChangeListener = null;
    /**
     * Listener for effect engine control ownership change notifications.
     *
     * @see #setControlStatusListener(OnControlStatusChangeListener)
     */
    private OnControlStatusChangeListener mControlChangeStatusListener = null;
    /**
     * Listener for effect engine control ownership change notifications.
     *
     * @see #setParameterListener(OnParameterChangeListener)
     */
    private OnParameterChangeListener mParameterChangeListener = null;
    /**
     * Lock to protect listeners updates against event notifications
     * @hide
     */
    public final Object mListenerLock = new Object();
    /**
     * Handler for events coming from the native code
     * @hide
     */
    public NativeEventHandler mNativeEventHandler = null;

    // --------------------------------------------------------------------------
    // Constructor, Finalize
    // --------------------
    /**
     * Class constructor.
     *
     * @param type type of effect engine created. See {@link #EFFECT_TYPE_ENV_REVERB},
     *            {@link #EFFECT_TYPE_EQUALIZER} ... Types corresponding to
     *            built-in effects are defined by AudioEffect class. Other types
     *            can be specified provided they correspond an existing OpenSL
     *            ES interface ID and the corresponsing effect is available on
     *            the platform. If an unspecified effect type is requested, the
     *            constructor with throw the IllegalArgumentException. This
     *            parameter can be set to {@link #EFFECT_TYPE_NULL} in which
     *            case only the uuid will be used to select the effect.
     * @param uuid unique identifier of a particular effect implementation.
     *            Must be specified if the caller wants to use a particular
     *            implementation of an effect type. This parameter can be set to
     *            {@link #EFFECT_TYPE_NULL} in which case only the type will
     *            be used to select the effect.
     * @param priority the priority level requested by the application for
     *            controlling the effect engine. As the same effect engine can
     *            be shared by several applications, this parameter indicates
     *            how much the requesting application needs control of effect
     *            parameters. The normal priority is 0, above normal is a
     *            positive number, below normal a negative number.
     * @param audioSession system wide unique audio session identifier. If audioSession
     *            is not 0, the effect will be attached to the MediaPlayer or
     *            AudioTrack in the same audio session. Otherwise, the effect
     *            will apply to the output mix.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     * @hide
     */

    public AudioEffect(UUID type, UUID uuid, int priority, int audioSession)
            throws IllegalArgumentException, UnsupportedOperationException,
            RuntimeException {
        int[] id = new int[1];
        Descriptor[] desc = new Descriptor[1];
        // native initialization
        int initResult = native_setup(new WeakReference<AudioEffect>(this),
                type.toString(), uuid.toString(), priority, audioSession, id,
                desc);
        if (initResult != SUCCESS && initResult != ALREADY_EXISTS) {
            Log.e(TAG, "Error code " + initResult
                    + " when initializing AudioEffect.");
            switch (initResult) {
            case ERROR_BAD_VALUE:
                throw (new IllegalArgumentException("Effect type: " + type
                        + " not supported."));
            case ERROR_INVALID_OPERATION:
                throw (new UnsupportedOperationException(
                        "Effect library not loaded"));
            default:
                throw (new RuntimeException(
                        "Cannot initialize effect engine for type: " + type
                                + "Error: " + initResult));
            }
        }
        mId = id[0];
        mDescriptor = desc[0];
        synchronized (mStateLock) {
            mState = STATE_INITIALIZED;
        }
    }

    /**
     * Releases the native AudioEffect resources. It is a good practice to
     * release the effect engine when not in use as control can be returned to
     * other applications or the native resources released.
     */
    public void release() {
        synchronized (mStateLock) {
            native_release();
            mState = STATE_UNINITIALIZED;
        }
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    /**
     * Get the effect descriptor.
     *
     * @see android.media.audiofx.AudioEffect.Descriptor
     * @throws IllegalStateException
     */
    public Descriptor getDescriptor() throws IllegalStateException {
        checkState("getDescriptor()");
        return mDescriptor;
    }

    // --------------------------------------------------------------------------
    // Effects Enumeration
    // --------------------

    /**
     * Query all effects available on the platform. Returns an array of
     * {@link android.media.audiofx.AudioEffect.Descriptor} objects
     *
     * @throws IllegalStateException
     */

    static public Descriptor[] queryEffects() {
        return (Descriptor[]) native_query_effects();
    }

    /**
     * Query all audio pre processing effects applied to the AudioRecord with the supplied
     * audio session ID. Returns an array of {@link android.media.audiofx.AudioEffect.Descriptor}
     * objects.
     * @param audioSession system wide unique audio session identifier.
     * @throws IllegalStateException
     * @hide
     */

    static public Descriptor[] queryPreProcessings(int audioSession) {
        return (Descriptor[]) native_query_pre_processing(audioSession);
    }

    // --------------------------------------------------------------------------
    // Control methods
    // --------------------

    /**
     * Enable or disable the effect.
     * Creating an audio effect does not automatically apply this effect on the audio source. It
     * creates the resources necessary to process this effect but the audio signal is still bypassed
     * through the effect engine. Calling this method will make that the effect is actually applied
     * or not to the audio content being played in the corresponding audio session.
     *
     * @param enabled the requested enable state
     * @return {@link #SUCCESS} in case of success, {@link #ERROR_INVALID_OPERATION}
     *         or {@link #ERROR_DEAD_OBJECT} in case of failure.
     * @throws IllegalStateException
     */
    public int setEnabled(boolean enabled) throws IllegalStateException {
        checkState("setEnabled()");
        return native_setEnabled(enabled);
    }

    /**
     * Set effect parameter. The setParameter method is provided in several
     * forms addressing most common parameter formats. This form is the most
     * generic one where the parameter and its value are both specified as an
     * array of bytes. The parameter and value type and length are therefore
     * totally free. For standard effect defined by OpenSL ES, the parameter
     * format and values must match the definitions in the corresponding OpenSL
     * ES interface.
     *
     * @param param the identifier of the parameter to set
     * @param value the new value for the specified parameter
     * @return {@link #SUCCESS} in case of success, {@link #ERROR_BAD_VALUE},
     *         {@link #ERROR_NO_MEMORY}, {@link #ERROR_INVALID_OPERATION} or
     *         {@link #ERROR_DEAD_OBJECT} in case of failure
     * @throws IllegalStateException
     * @hide
     */
    public int setParameter(byte[] param, byte[] value)
            throws IllegalStateException {
        checkState("setParameter()");
        return native_setParameter(param.length, param, value.length, value);
    }

    /**
     * Set effect parameter. The parameter and its value are integers.
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int param, int value) throws IllegalStateException {
        byte[] p = intToByteArray(param);
        byte[] v = intToByteArray(value);
        return setParameter(p, v);
    }

    /**
     * Set effect parameter. The parameter is an integer and the value is a
     * short integer.
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int param, short value)
            throws IllegalStateException {
        byte[] p = intToByteArray(param);
        byte[] v = shortToByteArray(value);
        return setParameter(p, v);
    }

    /**
     * Set effect parameter. The parameter is an integer and the value is an
     * array of bytes.
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int param, byte[] value)
            throws IllegalStateException {
        byte[] p = intToByteArray(param);
        return setParameter(p, value);
    }

    /**
     * Set effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is also an array of 1 or 2 integers
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int[] param, int[] value)
            throws IllegalStateException {
        if (param.length > 2 || value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }
        byte[] v = intToByteArray(value[0]);
        if (value.length > 1) {
            byte[] v2 = intToByteArray(value[1]);
            v = concatArrays(v, v2);
        }
        return setParameter(p, v);
    }

    /**
     * Set effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is an array of 1 or 2 short integers
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int[] param, short[] value)
            throws IllegalStateException {
        if (param.length > 2 || value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }

        byte[] v = shortToByteArray(value[0]);
        if (value.length > 1) {
            byte[] v2 = shortToByteArray(value[1]);
            v = concatArrays(v, v2);
        }
        return setParameter(p, v);
    }

    /**
     * Set effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is an array of bytes
     *
     * @see #setParameter(byte[], byte[])
     * @hide
     */
    public int setParameter(int[] param, byte[] value)
            throws IllegalStateException {
        if (param.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }
        return setParameter(p, value);
    }

    /**
     * Get effect parameter. The getParameter method is provided in several
     * forms addressing most common parameter formats. This form is the most
     * generic one where the parameter and its value are both specified as an
     * array of bytes. The parameter and value type and length are therefore
     * totally free.
     *
     * @param param the identifier of the parameter to set
     * @param value the new value for the specified parameter
     * @return the number of meaningful bytes in value array in case of success or
     *  {@link #ERROR_BAD_VALUE}, {@link #ERROR_NO_MEMORY}, {@link #ERROR_INVALID_OPERATION}
     *  or {@link #ERROR_DEAD_OBJECT} in case of failure.
     * @throws IllegalStateException
     * @hide
     */
    public int getParameter(byte[] param, byte[] value)
            throws IllegalStateException {
        checkState("getParameter()");
        return native_getParameter(param.length, param, value.length, value);
    }

    /**
     * Get effect parameter. The parameter is an integer and the value is an
     * array of bytes.
     *
     * @see #getParameter(byte[], byte[])
     * @hide
     */
    public int getParameter(int param, byte[] value)
            throws IllegalStateException {
        byte[] p = intToByteArray(param);

        return getParameter(p, value);
    }

    /**
     * Get effect parameter. The parameter is an integer and the value is an
     * array of 1 or 2 integers
     *
     * @see #getParameter(byte[], byte[])
     * In case of success, returns the number of meaningful integers in value array.
     * @hide
     */
    public int getParameter(int param, int[] value)
            throws IllegalStateException {
        if (value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param);

        byte[] v = new byte[value.length * 4];

        int status = getParameter(p, v);

        if (status == 4 || status == 8) {
            value[0] = byteArrayToInt(v);
            if (status == 8) {
                value[1] = byteArrayToInt(v, 4);
            }
            status /= 4;
        } else {
            status = ERROR;
        }
        return status;
    }

    /**
     * Get effect parameter. The parameter is an integer and the value is an
     * array of 1 or 2 short integers
     *
     * @see #getParameter(byte[], byte[])
     * In case of success, returns the number of meaningful short integers in value array.
     * @hide
     */
    public int getParameter(int param, short[] value)
            throws IllegalStateException {
        if (value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param);

        byte[] v = new byte[value.length * 2];

        int status = getParameter(p, v);

        if (status == 2 || status == 4) {
            value[0] = byteArrayToShort(v);
            if (status == 4) {
                value[1] = byteArrayToShort(v, 2);
            }
            status /= 2;
        } else {
            status = ERROR;
        }
        return status;
    }

    /**
     * Get effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is also an array of 1 or 2 integers
     *
     * @see #getParameter(byte[], byte[])
     * In case of success, the returns the number of meaningful integers in value array.
     * @hide
     */
    public int getParameter(int[] param, int[] value)
            throws IllegalStateException {
        if (param.length > 2 || value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }
        byte[] v = new byte[value.length * 4];

        int status = getParameter(p, v);

        if (status == 4 || status == 8) {
            value[0] = byteArrayToInt(v);
            if (status == 8) {
                value[1] = byteArrayToInt(v, 4);
            }
            status /= 4;
        } else {
            status = ERROR;
        }
        return status;
    }

    /**
     * Get effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is an array of 1 or 2 short integers
     *
     * @see #getParameter(byte[], byte[])
     * In case of success, returns the number of meaningful short integers in value array.
     * @hide
     */
    public int getParameter(int[] param, short[] value)
            throws IllegalStateException {
        if (param.length > 2 || value.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }
        byte[] v = new byte[value.length * 2];

        int status = getParameter(p, v);

        if (status == 2 || status == 4) {
            value[0] = byteArrayToShort(v);
            if (status == 4) {
                value[1] = byteArrayToShort(v, 2);
            }
            status /= 2;
        } else {
            status = ERROR;
        }
        return status;
    }

    /**
     * Get effect parameter. The parameter is an array of 1 or 2 integers and
     * the value is an array of bytes
     *
     * @see #getParameter(byte[], byte[])
     * @hide
     */
    public int getParameter(int[] param, byte[] value)
            throws IllegalStateException {
        if (param.length > 2) {
            return ERROR_BAD_VALUE;
        }
        byte[] p = intToByteArray(param[0]);
        if (param.length > 1) {
            byte[] p2 = intToByteArray(param[1]);
            p = concatArrays(p, p2);
        }

        return getParameter(p, value);
    }

    /**
     * Send a command to the effect engine. This method is intended to send
     * proprietary commands to a particular effect implementation.
     * In case of success, returns the number of meaningful bytes in reply array.
     * In case of failure, the returned value is negative and implementation specific.
     * @hide
     */
    public int command(int cmdCode, byte[] command, byte[] reply)
            throws IllegalStateException {
        checkState("command()");
        return native_command(cmdCode, command.length, command, reply.length, reply);
    }

    // --------------------------------------------------------------------------
    // Getters
    // --------------------

    /**
     * Returns effect unique identifier. This system wide unique identifier can
     * be used to attach this effect to a MediaPlayer or an AudioTrack when the
     * effect is an auxiliary effect (Reverb)
     *
     * @return the effect identifier.
     * @throws IllegalStateException
     */
    public int getId() throws IllegalStateException {
        checkState("getId()");
        return mId;
    }

    /**
     * Returns effect enabled state
     *
     * @return true if the effect is enabled, false otherwise.
     * @throws IllegalStateException
     */
    public boolean getEnabled() throws IllegalStateException {
        checkState("getEnabled()");
        return native_getEnabled();
    }

    /**
     * Checks if this AudioEffect object is controlling the effect engine.
     *
     * @return true if this instance has control of effect engine, false
     *         otherwise.
     * @throws IllegalStateException
     */
    public boolean hasControl() throws IllegalStateException {
        checkState("hasControl()");
        return native_hasControl();
    }

    // --------------------------------------------------------------------------
    // Initialization / configuration
    // --------------------
    /**
     * Sets the listener AudioEffect notifies when the effect engine is enabled
     * or disabled.
     *
     * @param listener
     */
    public void setEnableStatusListener(OnEnableStatusChangeListener listener) {
        synchronized (mListenerLock) {
            mEnableStatusChangeListener = listener;
        }
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }

    /**
     * Sets the listener AudioEffect notifies when the effect engine control is
     * taken or returned.
     *
     * @param listener
     */
    public void setControlStatusListener(OnControlStatusChangeListener listener) {
        synchronized (mListenerLock) {
            mControlChangeStatusListener = listener;
        }
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }

    /**
     * Sets the listener AudioEffect notifies when a parameter is changed.
     *
     * @param listener
     * @hide
     */
    public void setParameterListener(OnParameterChangeListener listener) {
        synchronized (mListenerLock) {
            mParameterChangeListener = listener;
        }
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }

    // Convenience method for the creation of the native event handler
    // It is called only when a non-null event listener is set.
    // precondition:
    // mNativeEventHandler is null
    private void createNativeEventHandler() {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mNativeEventHandler = new NativeEventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mNativeEventHandler = new NativeEventHandler(this, looper);
        } else {
            mNativeEventHandler = null;
        }
    }

    // ---------------------------------------------------------
    // Interface definitions
    // --------------------
    /**
     * The OnEnableStatusChangeListener interface defines a method called by the AudioEffect
     * when a the enabled state of the effect engine was changed by the controlling application.
     */
    public interface OnEnableStatusChangeListener {
        /**
         * Called on the listener to notify it that the effect engine has been
         * enabled or disabled.
         * @param effect the effect on which the interface is registered.
         * @param enabled new effect state.
         */
        void onEnableStatusChange(AudioEffect effect, boolean enabled);
    }

    /**
     * The OnControlStatusChangeListener interface defines a method called by the AudioEffect
     * when a the control of the effect engine is gained or lost by the application
     */
    public interface OnControlStatusChangeListener {
        /**
         * Called on the listener to notify it that the effect engine control
         * has been taken or returned.
         * @param effect the effect on which the interface is registered.
         * @param controlGranted true if the application has been granted control of the effect
         * engine, false otherwise.
         */
        void onControlStatusChange(AudioEffect effect, boolean controlGranted);
    }

    /**
     * The OnParameterChangeListener interface defines a method called by the AudioEffect
     * when a parameter is changed in the effect engine by the controlling application.
     * @hide
     */
    public interface OnParameterChangeListener {
        /**
         * Called on the listener to notify it that a parameter value has changed.
         * @param effect the effect on which the interface is registered.
         * @param status status of the set parameter operation.
         * @param param ID of the modified parameter.
         * @param value the new parameter value.
         */
        void onParameterChange(AudioEffect effect, int status, byte[] param,
                byte[] value);
    }


    // -------------------------------------------------------------------------
    // Audio Effect Control panel intents
    // -------------------------------------------------------------------------

    /**
     *  Intent to launch an audio effect control panel UI.
     *  <p>The goal of this intent is to enable separate implementations of music/media player
     *  applications and audio effect control application or services.
     *  This will allow platform vendors to offer more advanced control options for standard effects
     *  or control for platform specific effects.
     *  <p>The intent carries a number of extras used by the player application to communicate
     *  necessary pieces of information to the control panel application.
     *  <p>The calling application must use the
     *  {@link android.app.Activity#startActivityForResult(Intent, int)} method to launch the
     *  control panel so that its package name is indicated and used by the control panel
     *  application to keep track of changes for this particular application.
     *  <p>The {@link #EXTRA_AUDIO_SESSION} extra will indicate an audio session to which the
     *  audio effects should be applied. If no audio session is specified, either one of the
     *  follownig will happen:
     *  <p>- If an audio session was previously opened by the calling application with
     *  {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION} intent, the effect changes will
     *  be applied to that session.
     *  <p>- If no audio session is opened, the changes will be stored in the package specific
     *  storage area and applied whenever a new audio session is opened by this application.
     *  <p>The {@link #EXTRA_CONTENT_TYPE} extra will help the control panel application
     *  customize both the UI layout and the default audio effect settings if none are already
     *  stored for the calling application.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL =
        "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL";

    /**
     *  Intent to signal to the effect control application or service that a new audio session
     *  is opened and requires audio effects to be applied.
     *  <p>This is different from {@link #ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL} in that no
     *  UI should be displayed in this case. Music player applications can broadcast this intent
     *  before starting playback to make sure that any audio effect settings previously selected
     *  by the user are applied.
     *  <p>The effect control application receiving this intent will look for previously stored
     *  settings for the calling application, create all required audio effects and apply the
     *  effect settings to the specified audio session.
     *  <p>The calling package name is indicated by the {@link #EXTRA_PACKAGE_NAME} extra and the
     *  audio session ID by the {@link #EXTRA_AUDIO_SESSION} extra. Both extras are mandatory.
     *  <p>If no stored settings are found for the calling application, default settings for the
     *  content type indicated by {@link #EXTRA_CONTENT_TYPE} will be applied. The default settings
     *  for a given content type are platform specific.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION =
        "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION";

    /**
     *  Intent to signal to the effect control application or service that an audio session
     *  is closed and that effects should not be applied anymore.
     *  <p>The effect control application receiving this intent will delete all effects on
     *  this session and store current settings in package specific storage.
     *  <p>The calling package name is indicated by the {@link #EXTRA_PACKAGE_NAME} extra and the
     *  audio session ID by the {@link #EXTRA_AUDIO_SESSION} extra. Both extras are mandatory.
     *  <p>It is good practice for applications to broadcast this intent when music playback stops
     *  and/or when exiting to free system resources consumed by audio effect engines.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION =
        "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION";

    /**
     * Contains the ID of the audio session the effects should be applied to.
     * <p>This extra is for use with {@link #ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL},
     * {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION} and
     * {@link #ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION} intents.
     * <p>The extra value is of type int and is the audio session ID.
     *  @see android.media.MediaPlayer#getAudioSessionId() for details on audio sessions.
     */
     public static final String EXTRA_AUDIO_SESSION = "android.media.extra.AUDIO_SESSION";

    /**
     * Contains the package name of the calling application.
     * <p>This extra is for use with {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION} and
     * {@link #ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION} intents.
     * <p>The extra value is a string containing the full package name.
     */
    public static final String EXTRA_PACKAGE_NAME = "android.media.extra.PACKAGE_NAME";

    /**
     * Indicates which type of content is played by the application.
     * <p>This extra is for use with {@link #ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL} and
     * {@link #ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION} intents.
     * <p>This information is used by the effect control application to customize UI and select
     * appropriate default effect settings. The content type is one of the following:
     * <ul>
     *   <li>{@link #CONTENT_TYPE_MUSIC}</li>
     *   <li>{@link #CONTENT_TYPE_MOVIE}</li>
     *   <li>{@link #CONTENT_TYPE_GAME}</li>
     *   <li>{@link #CONTENT_TYPE_VOICE}</li>
     * </ul>
     * If omitted, the content type defaults to {@link #CONTENT_TYPE_MUSIC}.
     */
    public static final String EXTRA_CONTENT_TYPE = "android.media.extra.CONTENT_TYPE";

    /**
     * Value for {@link #EXTRA_CONTENT_TYPE} when the type of content played is music
     */
    public static final int  CONTENT_TYPE_MUSIC = 0;
    /**
     * Value for {@link #EXTRA_CONTENT_TYPE} when the type of content played is video or movie
     */
    public static final int  CONTENT_TYPE_MOVIE = 1;
    /**
     * Value for {@link #EXTRA_CONTENT_TYPE} when the type of content played is game audio
     */
    public static final int  CONTENT_TYPE_GAME = 2;
    /**
     * Value for {@link #EXTRA_CONTENT_TYPE} when the type of content played is voice audio
     */
    public static final int  CONTENT_TYPE_VOICE = 3;


    // ---------------------------------------------------------
    // Inner classes
    // --------------------
    /**
     * Helper class to handle the forwarding of native events to the appropriate
     * listeners
     */
    private class NativeEventHandler extends Handler {
        private AudioEffect mAudioEffect;

        public NativeEventHandler(AudioEffect ae, Looper looper) {
            super(looper);
            mAudioEffect = ae;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mAudioEffect == null) {
                return;
            }
            switch (msg.what) {
            case NATIVE_EVENT_ENABLED_STATUS:
                OnEnableStatusChangeListener enableStatusChangeListener = null;
                synchronized (mListenerLock) {
                    enableStatusChangeListener = mAudioEffect.mEnableStatusChangeListener;
                }
                if (enableStatusChangeListener != null) {
                    enableStatusChangeListener.onEnableStatusChange(
                            mAudioEffect, (boolean) (msg.arg1 != 0));
                }
                break;
            case NATIVE_EVENT_CONTROL_STATUS:
                OnControlStatusChangeListener controlStatusChangeListener = null;
                synchronized (mListenerLock) {
                    controlStatusChangeListener = mAudioEffect.mControlChangeStatusListener;
                }
                if (controlStatusChangeListener != null) {
                    controlStatusChangeListener.onControlStatusChange(
                            mAudioEffect, (boolean) (msg.arg1 != 0));
                }
                break;
            case NATIVE_EVENT_PARAMETER_CHANGED:
                OnParameterChangeListener parameterChangeListener = null;
                synchronized (mListenerLock) {
                    parameterChangeListener = mAudioEffect.mParameterChangeListener;
                }
                if (parameterChangeListener != null) {
                    // arg1 contains offset of parameter value from start of
                    // byte array
                    int vOffset = msg.arg1;
                    byte[] p = (byte[]) msg.obj;
                    // See effect_param_t in EffectApi.h for psize and vsize
                    // fields offsets
                    int status = byteArrayToInt(p, 0);
                    int psize = byteArrayToInt(p, 4);
                    int vsize = byteArrayToInt(p, 8);
                    byte[] param = new byte[psize];
                    byte[] value = new byte[vsize];
                    System.arraycopy(p, 12, param, 0, psize);
                    System.arraycopy(p, vOffset, value, 0, vsize);

                    parameterChangeListener.onParameterChange(mAudioEffect,
                            status, param, value);
                }
                break;

            default:
                Log.e(TAG, "handleMessage() Unknown event type: " + msg.what);
                break;
            }
        }
    }

    // ---------------------------------------------------------
    // Java methods called from the native side
    // --------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object effect_ref, int what,
            int arg1, int arg2, Object obj) {
        AudioEffect effect = (AudioEffect) ((WeakReference) effect_ref).get();
        if (effect == null) {
            return;
        }
        if (effect.mNativeEventHandler != null) {
            Message m = effect.mNativeEventHandler.obtainMessage(what, arg1,
                    arg2, obj);
            effect.mNativeEventHandler.sendMessage(m);
        }

    }

    // ---------------------------------------------------------
    // Native methods called from the Java side
    // --------------------

    private static native final void native_init();

    private native final int native_setup(Object audioeffect_this, String type,
            String uuid, int priority, int audioSession, int[] id, Object[] desc);

    private native final void native_finalize();

    private native final void native_release();

    private native final int native_setEnabled(boolean enabled);

    private native final boolean native_getEnabled();

    private native final boolean native_hasControl();

    private native final int native_setParameter(int psize, byte[] param,
            int vsize, byte[] value);

    private native final int native_getParameter(int psize, byte[] param,
            int vsize, byte[] value);

    private native final int native_command(int cmdCode, int cmdSize,
            byte[] cmdData, int repSize, byte[] repData);

    private static native Object[] native_query_effects();

    private static native Object[] native_query_pre_processing(int audioSession);

    // ---------------------------------------------------------
    // Utility methods
    // ------------------

    /**
    * @hide
    */
    public void checkState(String methodName) throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState != STATE_INITIALIZED) {
                throw (new IllegalStateException(methodName
                        + " called on uninitialized AudioEffect."));
            }
        }
    }

    /**
     * @hide
     */
    public void checkStatus(int status) {
        if (isError(status)) {
            switch (status) {
            case AudioEffect.ERROR_BAD_VALUE:
                throw (new IllegalArgumentException(
                        "AudioEffect: bad parameter value"));
            case AudioEffect.ERROR_INVALID_OPERATION:
                throw (new UnsupportedOperationException(
                        "AudioEffect: invalid parameter operation"));
            default:
                throw (new RuntimeException("AudioEffect: set/get parameter error"));
            }
        }
    }

    /**
     * @hide
     */
    public static boolean isError(int status) {
        return (status < 0);
    }

    /**
     * @hide
     */
    public int byteArrayToInt(byte[] valueBuf) {
        return byteArrayToInt(valueBuf, 0);

    }

    /**
     * @hide
     */
    public int byteArrayToInt(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getInt(offset);

    }

    /**
     * @hide
     */
    public byte[] intToByteArray(int value) {
        ByteBuffer converter = ByteBuffer.allocate(4);
        converter.order(ByteOrder.nativeOrder());
        converter.putInt(value);
        return converter.array();
    }

    /**
     * @hide
     */
    public short byteArrayToShort(byte[] valueBuf) {
        return byteArrayToShort(valueBuf, 0);
    }

    /**
     * @hide
     */
    public short byteArrayToShort(byte[] valueBuf, int offset) {
        ByteBuffer converter = ByteBuffer.wrap(valueBuf);
        converter.order(ByteOrder.nativeOrder());
        return converter.getShort(offset);

    }

    /**
     * @hide
     */
    public byte[] shortToByteArray(short value) {
        ByteBuffer converter = ByteBuffer.allocate(2);
        converter.order(ByteOrder.nativeOrder());
        short sValue = (short) value;
        converter.putShort(sValue);
        return converter.array();
    }

    /**
     * @hide
     */
    public byte[] concatArrays(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) {
            len += a.length;
        }
        byte[] b = new byte[len];

        int offs = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, b, offs, a.length);
            offs += a.length;
        }
        return b;
    }
}
