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

import android.annotation.IntDef;
import android.media.AudioDevice;
import android.media.AudioFormat;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.StringTokenizer;


/**
 * An audio virtualizer is a general name for an effect to spatialize audio channels. The exact
 * behavior of this effect is dependent on the number of audio input channels and the types and
 * number of audio output channels of the device. For example, in the case of a stereo input and
 * stereo headphone output, a stereo widening effect is used when this effect is turned on.
 * <p>An application creates a Virtualizer object to instantiate and control a virtualizer engine
 * in the audio framework.
 * <p>The methods, parameter types and units exposed by the Virtualizer implementation are directly
 * mapping those defined by the OpenSL ES 1.0.1 Specification (http://www.khronos.org/opensles/)
 * for the SLVirtualizerItf interface. Please refer to this specification for more details.
 * <p>To attach the Virtualizer to a particular AudioTrack or MediaPlayer, specify the audio session
 * ID of this AudioTrack or MediaPlayer when constructing the Virtualizer.
 * <p>NOTE: attaching a Virtualizer to the global audio output mix by use of session 0 is
 * deprecated.
 * <p>See {@link android.media.MediaPlayer#getAudioSessionId()} for details on audio sessions.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on controlling
 * audio effects.
 */

public class Virtualizer extends AudioEffect {

    private final static String TAG = "Virtualizer";
    private final static boolean DEBUG = false;

    // These constants must be synchronized with those in
    //        system/media/audio_effects/include/audio_effects/effect_virtualizer.h
    /**
     * Is strength parameter supported by virtualizer engine. Parameter ID for getParameter().
     */
    public static final int PARAM_STRENGTH_SUPPORTED = 0;
    /**
     * Virtualizer effect strength. Parameter ID for
     * {@link android.media.audiofx.Virtualizer.OnParameterChangeListener}
     */
    public static final int PARAM_STRENGTH = 1;
    /**
     * @hide
     * Parameter ID to query the virtual speaker angles for a channel mask / device configuration.
     */
    public static final int PARAM_VIRTUAL_SPEAKER_ANGLES = 2;
    /**
     * @hide
     * Parameter ID to force the virtualization mode to be that of a specific device
     */
    public static final int PARAM_FORCE_VIRTUALIZATION_MODE = 3;
    /**
     * @hide
     * Parameter ID to query the current virtualization mode.
     */
    public static final int PARAM_VIRTUALIZATION_MODE = 4;

    /**
     * Indicates if strength parameter is supported by the virtualizer engine
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
     * @param priority the priority level requested by the application for controlling the Virtualizer
     * engine. As the same engine can be shared by several applications, this parameter indicates
     * how much the requesting application needs control of effect parameters. The normal priority
     * is 0, above normal is a positive number, below normal a negative number.
     * @param audioSession  system wide unique audio session identifier. The Virtualizer will
     * be attached to the MediaPlayer or AudioTrack in the same audio session.
     *
     * @throws java.lang.IllegalStateException
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public Virtualizer(int priority, int audioSession)
    throws IllegalStateException, IllegalArgumentException,
           UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_VIRTUALIZER, EFFECT_TYPE_NULL, priority, audioSession);

        if (audioSession == 0) {
            Log.w(TAG, "WARNING: attaching a Virtualizer to global output mix is deprecated!");
        }

        int[] value = new int[1];
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
     * Sets the strength of the virtualizer effect. If the implementation does not support per mille
     * accuracy for setting the strength, it is allowed to round the given strength to the nearest
     * supported value. You can use the {@link #getRoundedStrength()} method to query the
     * (possibly rounded) value that was actually set.
     * @param strength strength of the effect. The valid range for strength strength is [0, 1000],
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
     * @return the strength of the effect. The valid range for strength is [0, 1000], where 0 per
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
     * Checks if a configuration is supported, and query the virtual speaker angles.
     * @param inputChannelMask
     * @param deviceType
     * @param angles if non-null: array in which the angles will be written. If null, no angles
     *    are returned
     * @return true if the combination of channel mask and output device type is supported, false
     *    otherwise
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    private boolean getAnglesInt(int inputChannelMask, int deviceType, int[] angles)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        // parameter check
        if (inputChannelMask == AudioFormat.CHANNEL_INVALID) {
            throw (new IllegalArgumentException(
                    "Virtualizer: illegal CHANNEL_INVALID channel mask"));
        }
        int channelMask = inputChannelMask == AudioFormat.CHANNEL_OUT_DEFAULT ?
                AudioFormat.CHANNEL_OUT_STEREO : inputChannelMask;
        int nbChannels = AudioFormat.channelCountFromOutChannelMask(channelMask);
        if ((angles != null) && (angles.length < (nbChannels * 3))) {
            Log.e(TAG, "Size of array for angles cannot accomodate number of channels in mask ("
                    + nbChannels + ")");
            throw (new IllegalArgumentException(
                    "Virtualizer: array for channel / angle pairs is too small: is " + angles.length
                    + ", should be " + (nbChannels * 3)));
        }

        ByteBuffer paramsConverter = ByteBuffer.allocate(3 /* param + mask + device*/ * 4);
        paramsConverter.order(ByteOrder.nativeOrder());
        paramsConverter.putInt(PARAM_VIRTUAL_SPEAKER_ANGLES);
        // convert channel mask to internal native representation
        paramsConverter.putInt(AudioFormat.convertChannelOutMaskToNativeMask(channelMask));
        // convert Java device type to internal representation
        paramsConverter.putInt(AudioDevice.convertDeviceTypeToInternalDevice(deviceType));
        // allocate an array to store the results
        byte[] result = new byte[nbChannels * 4/*int to byte*/ * 3/*for mask, azimuth, elevation*/];

        // call into the effect framework
        int status = getParameter(paramsConverter.array(), result);
        if (DEBUG) {
            Log.v(TAG, "getAngles(0x" + Integer.toHexString(inputChannelMask) + ", 0x"
                    + Integer.toHexString(deviceType) + ") returns " + status);
        }

        if (status >= 0) {
            if (angles != null) {
                // convert and copy the results
                ByteBuffer resultConverter = ByteBuffer.wrap(result);
                resultConverter.order(ByteOrder.nativeOrder());
                for (int i = 0 ; i < nbChannels ; i++) {
                    // write the channel mask
                    angles[3 * i] = AudioFormat.convertNativeChannelMaskToOutMask(
                            resultConverter.getInt((i * 4 * 3)));
                    // write the azimuth
                    angles[3 * i + 1] = resultConverter.getInt(i * 4 * 3 + 4);
                    // write the elevation
                    angles[3 * i + 2] = resultConverter.getInt(i * 4 * 3 + 8);
                    if (DEBUG) {
                        Log.v(TAG, "channel 0x" + Integer.toHexString(angles[3*i]).toUpperCase()
                                + " at az=" + angles[3*i+1] + "deg"
                                + " elev="  + angles[3*i+2] + "deg");
                    }
                }
            }
            return true;
        } else if (status == AudioEffect.ERROR_BAD_VALUE) {
            // a BAD_VALUE return from getParameter indicates the configuration is not supported
            // don't throw an exception, just return false
            return false;
        } else {
            // something wrong may have happened
            checkStatus(status);
        }
        // unexpected virtualizer behavior
        Log.e(TAG, "unexpected status code " + status
                + " after getParameter(PARAM_VIRTUAL_SPEAKER_ANGLES)");
        return false;
    }

    /**
     * A virtualization mode indicating virtualization processing is not active.
     * See {@link #getVirtualizationMode()} as one of the possible return value.
     */
    public static final int VIRTUALIZATION_MODE_OFF = 0;

    /**
     * A virtualization mode used to indicate the virtualizer effect must stop forcing the
     * processing to a particular mode in {@link #forceVirtualizationMode(int)}.
     */
    public static final int VIRTUALIZATION_MODE_AUTO = 1;
    /**
     * A virtualization mode typically used over headphones.
     * Binaural virtualization describes an audio processing configuration for virtualization
     * where the left and right channels are respectively reaching the left and right ear of the
     * user, without also feeding the opposite ear (as is the case when listening over speakers).
     * <p>Such a mode is therefore meant to be used when audio is playing over stereo wired
     * headphones or headsets, but also stereo headphones through a wireless A2DP Bluetooth link.
     * <p>See {@link #canVirtualize(int, int)} to verify this mode is supported by this Virtualizer.
     */
    public final static int VIRTUALIZATION_MODE_BINAURAL = 2;

    /**
     * A virtualization mode typically used over speakers.
     * Transaural virtualization describes an audio processing configuration that differs from
     * binaural (as described in {@link #VIRTUALIZATION_MODE_BINAURAL} in that cross-talk is
     * present, i.e. audio played from the left channel also reaches the right ear of the user,
     * and vice-versa.
     * <p>When supported, such a mode is therefore meant to be used when audio is playing over the
     * built-in stereo speakers of a device, if they are featured.
     * <p>See {@link #canVirtualize(int, int)} to verify this mode is supported by this Virtualizer.
     */
    public final static int VIRTUALIZATION_MODE_TRANSAURAL = 3;

    /** @hide */
    @IntDef( {
        VIRTUALIZATION_MODE_BINAURAL,
        VIRTUALIZATION_MODE_TRANSAURAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VirtualizationMode {}

    /** @hide */
    @IntDef( {
        VIRTUALIZATION_MODE_AUTO,
        VIRTUALIZATION_MODE_BINAURAL,
        VIRTUALIZATION_MODE_TRANSAURAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForceVirtualizationMode {}

    private static int getDeviceForModeQuery(@VirtualizationMode int virtualizationMode)
            throws IllegalArgumentException {
        switch (virtualizationMode) {
            case VIRTUALIZATION_MODE_BINAURAL:
                return AudioDevice.TYPE_WIRED_HEADPHONES;
            case VIRTUALIZATION_MODE_TRANSAURAL:
                return AudioDevice.TYPE_BUILTIN_SPEAKER;
            default:
                throw (new IllegalArgumentException(
                        "Virtualizer: illegal virtualization mode " + virtualizationMode));
        }
    }

    private static int getDeviceForModeForce(@ForceVirtualizationMode int virtualizationMode)
            throws IllegalArgumentException {
        if (virtualizationMode == VIRTUALIZATION_MODE_AUTO) {
            return AudioDevice.TYPE_UNKNOWN;
        } else {
            return getDeviceForModeQuery(virtualizationMode);
        }
    }

    private static int deviceToMode(int deviceType) {
        switch (deviceType) {
            case AudioDevice.TYPE_WIRED_HEADSET:
            case AudioDevice.TYPE_WIRED_HEADPHONES:
            case AudioDevice.TYPE_BLUETOOTH_SCO:
            case AudioDevice.TYPE_BUILTIN_EARPIECE:
                return VIRTUALIZATION_MODE_BINAURAL;
            case AudioDevice.TYPE_BUILTIN_SPEAKER:
            case AudioDevice.TYPE_LINE_ANALOG:
            case AudioDevice.TYPE_LINE_DIGITAL:
            case AudioDevice.TYPE_BLUETOOTH_A2DP:
            case AudioDevice.TYPE_HDMI:
            case AudioDevice.TYPE_HDMI_ARC:
            case AudioDevice.TYPE_USB_DEVICE:
            case AudioDevice.TYPE_USB_ACCESSORY:
            case AudioDevice.TYPE_DOCK:
            case AudioDevice.TYPE_FM:
            case AudioDevice.TYPE_AUX_LINE:
                return VIRTUALIZATION_MODE_TRANSAURAL;
            case AudioDevice.TYPE_UNKNOWN:
            default:
                return VIRTUALIZATION_MODE_OFF;
        }
    }

    /**
     * Checks if the combination of a channel mask and virtualization mode is supported by this
     * virtualizer.
     * Some virtualizer implementations may only support binaural processing (i.e. only support
     * headphone output, see {@link #VIRTUALIZATION_MODE_BINAURAL}), some may support transaural
     * processing (i.e. for speaker output, see {@link #VIRTUALIZATION_MODE_TRANSAURAL}) for the
     * built-in speakers. Use this method to query the virtualizer implementation capabilities.
     * @param inputChannelMask the channel mask of the content to virtualize.
     * @param virtualizationMode the mode for which virtualization processing is to be performed,
     *    one of {@link #VIRTUALIZATION_MODE_BINAURAL}, {@link #VIRTUALIZATION_MODE_TRANSAURAL}.
     * @return true if the combination of channel mask and virtualization mode is supported, false
     *    otherwise.
     *    <br>An indication that a certain channel mask is not supported doesn't necessarily mean
     *    you cannot play content with that channel mask, it more likely implies the content will
     *    be downmixed before being virtualized. For instance a virtualizer that only supports a
     *    mask such as {@link AudioFormat#CHANNEL_OUT_STEREO}
     *    will still be able to process content with a mask of
     *    {@link AudioFormat#CHANNEL_OUT_5POINT1}, but will downmix the content to stereo first, and
     *    then will virtualize, as opposed to virtualizing each channel individually.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public boolean canVirtualize(int inputChannelMask, @VirtualizationMode int virtualizationMode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        return getAnglesInt(inputChannelMask, getDeviceForModeQuery(virtualizationMode), null);
    }

    /**
     * Queries the virtual speaker angles (azimuth and elevation) for a combination of a channel
     * mask and virtualization mode.
     * If the virtualization configuration (mask and mode) is supported (see
     * {@link #canVirtualize(int, int)}, the array angles will contain upon return the
     * definition of each virtual speaker and its azimuth and elevation angles relative to the
     * listener.
     * <br>Note that in some virtualizer implementations, the angles may be strength-dependent.
     * @param inputChannelMask the channel mask of the content to virtualize.
     * @param virtualizationMode the mode for which virtualization processing is to be performed,
     *    one of {@link #VIRTUALIZATION_MODE_BINAURAL}, {@link #VIRTUALIZATION_MODE_TRANSAURAL}.
     * @param angles a non-null array whose length is 3 times the number of channels in the channel
     *    mask.
     *    If the method indicates the configuration is supported, the array will contain upon return
     *    triplets of values: for each channel <code>i</code> among the channels of the mask:
     *    <ul>
     *      <li>the element at index <code>3*i</code> in the array contains the speaker
     *          identification (e.g. {@link AudioFormat#CHANNEL_OUT_FRONT_LEFT}),</li>
     *      <li>the element at index <code>3*i+1</code> contains its corresponding azimuth angle
     *          expressed in degrees, where 0 is the direction the listener faces, 180 is behind
     *          the listener, and -90 is to her/his left,</li>
     *      <li>the element at index <code>3*i+2</code> contains its corresponding elevation angle
     *          where +90 is directly above the listener, 0 is the horizontal plane, and -90 is
     *          directly below the listener.</li>
     * @return true if the combination of channel mask and virtualization mode is supported, false
     *    otherwise.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public boolean getSpeakerAngles(int inputChannelMask,
            @VirtualizationMode int virtualizationMode, int[] angles)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        if (angles == null) {
            throw (new IllegalArgumentException(
                    "Virtualizer: illegal null channel / angle array"));
        }

        return getAnglesInt(inputChannelMask, getDeviceForModeQuery(virtualizationMode), angles);
    }

    /**
     * Forces the virtualizer effect to use the given processing mode.
     * The effect must be enabled for the forced mode to be applied.
     * @param virtualizationMode one of {@link #VIRTUALIZATION_MODE_BINAURAL},
     *     {@link #VIRTUALIZATION_MODE_TRANSAURAL} to force a particular processing mode, or
     *     {@value #VIRTUALIZATION_MODE_AUTO} to stop forcing a mode.
     * @return true if the processing mode is supported, and it is successfully set, or
     *     forcing was successfully disabled, false otherwise.
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public boolean forceVirtualizationMode(@ForceVirtualizationMode int virtualizationMode)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        // convert Java device type to internal representation
        int deviceType = getDeviceForModeForce(virtualizationMode);
        int internalDevice = AudioDevice.convertDeviceTypeToInternalDevice(deviceType);

        int status = setParameter(PARAM_FORCE_VIRTUALIZATION_MODE, internalDevice);

        if (status >= 0) {
            return true;
        } else if (status == AudioEffect.ERROR_BAD_VALUE) {
            // a BAD_VALUE return from setParameter indicates the mode can't be forced
            // don't throw an exception, just return false
            return false;
        } else {
            // something wrong may have happened
            checkStatus(status);
        }
        // unexpected virtualizer behavior
        Log.e(TAG, "unexpected status code " + status
                + " after setParameter(PARAM_FORCE_VIRTUALIZATION_MODE)");
        return false;
    }

    /**
     * Return the virtualization mode being used, if any.
     * @return the virtualization mode being used.
     *     If virtualization is not active, the virtualization mode will be
     *     {@link #VIRTUALIZATION_MODE_OFF}. Otherwise the value will be
     *     {@link #VIRTUALIZATION_MODE_BINAURAL} or {@link #VIRTUALIZATION_MODE_TRANSAURAL}.
     *     Virtualization may not be active either because the effect is not enabled or
     *     because the current output device is not compatible with this virtualization
     *     implementation.
     * @throws IllegalStateException
     * @throws UnsupportedOperationException
     */
    public int getVirtualizationMode()
            throws IllegalStateException, UnsupportedOperationException {
        int[] value = new int[1];
        int status = getParameter(PARAM_VIRTUALIZATION_MODE, value);
        if (status >= 0) {
            return deviceToMode(AudioDevice.convertInternalDeviceToDeviceType(value[0]));
        } else if (status == AudioEffect.ERROR_BAD_VALUE) {
            return VIRTUALIZATION_MODE_OFF;
        } else {
            // something wrong may have happened
            checkStatus(status);
        }
        // unexpected virtualizer behavior
        Log.e(TAG, "unexpected status code " + status
                + " after getParameter(PARAM_VIRTUALIZATION_MODE)");
        return VIRTUALIZATION_MODE_OFF;
    }

    /**
     * The OnParameterChangeListener interface defines a method called by the Virtualizer when a
     * parameter value has changed.
     */
    public interface OnParameterChangeListener  {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * Virtualizer engine.
         * @param effect the Virtualizer on which the interface is registered.
         * @param status status of the set parameter operation.
         * @param param ID of the modified parameter. See {@link #PARAM_STRENGTH} ...
         * @param value the new parameter value.
         */
        void onParameterChange(Virtualizer effect, int status, int param, short value);
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
                    l.onParameterChange(Virtualizer.this, status, p, v);
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
     * The Settings class regroups all virtualizer parameters. It is used in
     * conjuntion with getProperties() and setProperties() methods to backup and restore
     * all parameters in a single call.
     */
    public static class Settings {
        public short strength;

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
            if (st.countTokens() != 3) {
                throw new IllegalArgumentException("settings: " + settings);
            }
            String key = st.nextToken();
            if (!key.equals("Virtualizer")) {
                throw new IllegalArgumentException(
                        "invalid settings for Virtualizer: " + key);
            }
            try {
                key = st.nextToken();
                if (!key.equals("strength")) {
                    throw new IllegalArgumentException("invalid key name: " + key);
                }
                strength = Short.parseShort(st.nextToken());
             } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid value for key: " + key);
            }
        }

        @Override
        public String toString() {
            String str = new String (
                    "Virtualizer"+
                    ";strength="+Short.toString(strength)
                    );
            return str;
        }
    };


    /**
     * Gets the virtualizer properties. This method is useful when a snapshot of current
     * virtualizer settings must be saved by the application.
     * @return a Virtualizer.Settings object containing all current parameters values
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public Virtualizer.Settings getProperties()
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        Settings settings = new Settings();
        short[] value = new short[1];
        checkStatus(getParameter(PARAM_STRENGTH, value));
        settings.strength = value[0];
        return settings;
    }

    /**
     * Sets the virtualizer properties. This method is useful when virtualizer settings have to
     * be applied from a previous backup.
     * @param settings a Virtualizer.Settings object containing the properties to apply
     * @throws IllegalStateException
     * @throws IllegalArgumentException
     * @throws UnsupportedOperationException
     */
    public void setProperties(Virtualizer.Settings settings)
    throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException {
        checkStatus(setParameter(PARAM_STRENGTH, settings.strength));
    }
}
