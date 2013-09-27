/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.lang.ref.WeakReference;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;


/**
 * The AudioTrack class manages and plays a single audio resource for Java applications.
 * It allows streaming of PCM audio buffers to the audio sink for playback. This is
 * achieved by "pushing" the data to the AudioTrack object using one of the
 *  {@link #write(byte[], int, int)} and {@link #write(short[], int, int)} methods.
 *
 * <p>An AudioTrack instance can operate under two modes: static or streaming.<br>
 * In Streaming mode, the application writes a continuous stream of data to the AudioTrack, using
 * one of the {@code write()} methods. These are blocking and return when the data has been
 * transferred from the Java layer to the native layer and queued for playback. The streaming
 * mode is most useful when playing blocks of audio data that for instance are:
 *
 * <ul>
 *   <li>too big to fit in memory because of the duration of the sound to play,</li>
 *   <li>too big to fit in memory because of the characteristics of the audio data
 *         (high sampling rate, bits per sample ...)</li>
 *   <li>received or generated while previously queued audio is playing.</li>
 * </ul>
 *
 * The static mode should be chosen when dealing with short sounds that fit in memory and
 * that need to be played with the smallest latency possible. The static mode will
 * therefore be preferred for UI and game sounds that are played often, and with the
 * smallest overhead possible.
 *
 * <p>Upon creation, an AudioTrack object initializes its associated audio buffer.
 * The size of this buffer, specified during the construction, determines how long an AudioTrack
 * can play before running out of data.<br>
 * For an AudioTrack using the static mode, this size is the maximum size of the sound that can
 * be played from it.<br>
 * For the streaming mode, data will be written to the audio sink in chunks of
 * sizes less than or equal to the total buffer size.
 *
 * AudioTrack is not final and thus permits subclasses, but such use is not recommended.
 */
public class AudioTrack
{
    //---------------------------------------------------------
    // Constants
    //--------------------
    /** Minimum value for a channel volume */
    private static final float VOLUME_MIN = 0.0f;
    /** Maximum value for a channel volume */
    private static final float VOLUME_MAX = 1.0f;

    /** Minimum value for sample rate */
    private static final int SAMPLE_RATE_HZ_MIN = 4000;
    /** Maximum value for sample rate */
    private static final int SAMPLE_RATE_HZ_MAX = 48000;

    /** indicates AudioTrack state is stopped */
    public static final int PLAYSTATE_STOPPED = 1;  // matches SL_PLAYSTATE_STOPPED
    /** indicates AudioTrack state is paused */
    public static final int PLAYSTATE_PAUSED  = 2;  // matches SL_PLAYSTATE_PAUSED
    /** indicates AudioTrack state is playing */
    public static final int PLAYSTATE_PLAYING = 3;  // matches SL_PLAYSTATE_PLAYING

    // keep these values in sync with android_media_AudioTrack.cpp
    /**
     * Creation mode where audio data is transferred from Java to the native layer
     * only once before the audio starts playing.
     */
    public static final int MODE_STATIC = 0;
    /**
     * Creation mode where audio data is streamed from Java to the native layer
     * as the audio is playing.
     */
    public static final int MODE_STREAM = 1;

    /**
     * State of an AudioTrack that was not successfully initialized upon creation.
     */
    public static final int STATE_UNINITIALIZED = 0;
    /**
     * State of an AudioTrack that is ready to be used.
     */
    public static final int STATE_INITIALIZED   = 1;
    /**
     * State of a successfully initialized AudioTrack that uses static data,
     * but that hasn't received that data yet.
     */
    public static final int STATE_NO_STATIC_DATA = 2;

    // Error codes:
    // to keep in sync with frameworks/base/core/jni/android_media_AudioTrack.cpp
    /**
     * Denotes a successful operation.
     */
    public  static final int SUCCESS                               = 0;
    /**
     * Denotes a generic operation failure.
     */
    public  static final int ERROR                                 = -1;
    /**
     * Denotes a failure due to the use of an invalid value.
     */
    public  static final int ERROR_BAD_VALUE                       = -2;
    /**
     * Denotes a failure due to the improper use of a method.
     */
    public  static final int ERROR_INVALID_OPERATION               = -3;

    private static final int ERROR_NATIVESETUP_AUDIOSYSTEM         = -16;
    private static final int ERROR_NATIVESETUP_INVALIDCHANNELMASK  = -17;
    private static final int ERROR_NATIVESETUP_INVALIDFORMAT       = -18;
    private static final int ERROR_NATIVESETUP_INVALIDSTREAMTYPE   = -19;
    private static final int ERROR_NATIVESETUP_NATIVEINITFAILED    = -20;

    // Events:
    // to keep in sync with frameworks/av/include/media/AudioTrack.h
    /**
     * Event id denotes when playback head has reached a previously set marker.
     */
    private static final int NATIVE_EVENT_MARKER  = 3;
    /**
     * Event id denotes when previously set update period has elapsed during playback.
     */
    private static final int NATIVE_EVENT_NEW_POS = 4;

    private final static String TAG = "android.media.AudioTrack";


    //--------------------------------------------------------------------------
    // Member variables
    //--------------------
    /**
     * Indicates the state of the AudioTrack instance.
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Indicates the play state of the AudioTrack instance.
     */
    private int mPlayState = PLAYSTATE_STOPPED;
    /**
     * Lock to make sure mPlayState updates are reflecting the actual state of the object.
     */
    private final Object mPlayStateLock = new Object();
    /**
     * Sizes of the native audio buffer.
     */
    private int mNativeBufferSizeInBytes = 0;
    private int mNativeBufferSizeInFrames = 0;
    /**
     * Handler for events coming from the native code.
     */
    private NativeEventHandlerDelegate mEventHandlerDelegate;
    /**
     * Looper associated with the thread that creates the AudioTrack instance.
     */
    private final Looper mInitializationLooper;
    /**
     * The audio data source sampling rate in Hz.
     */
    private int mSampleRate; // initialized by all constructors
    /**
     * The number of audio output channels (1 is mono, 2 is stereo).
     */
    private int mChannelCount = 1;
    /**
     * The audio channel mask.
     */
    private int mChannels = AudioFormat.CHANNEL_OUT_MONO;

    /**
     * The type of the audio stream to play. See
     *   {@link AudioManager#STREAM_VOICE_CALL}, {@link AudioManager#STREAM_SYSTEM},
     *   {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_MUSIC},
     *   {@link AudioManager#STREAM_ALARM}, {@link AudioManager#STREAM_NOTIFICATION}, and
     *   {@link AudioManager#STREAM_DTMF}.
     */
    private int mStreamType = AudioManager.STREAM_MUSIC;
    /**
     * The way audio is consumed by the audio sink, streaming or static.
     */
    private int mDataLoadMode = MODE_STREAM;
    /**
     * The current audio channel configuration.
     */
    private int mChannelConfiguration = AudioFormat.CHANNEL_OUT_MONO;
    /**
     * The encoding of the audio samples.
     * @see AudioFormat#ENCODING_PCM_8BIT
     * @see AudioFormat#ENCODING_PCM_16BIT
     */
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    /**
     * Audio session ID
     */
    private int mSessionId = 0;


    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /**
     * Accessed by native methods: provides access to C++ AudioTrack object.
     */
    @SuppressWarnings("unused")
    private int mNativeTrackInJavaObj;
    /**
     * Accessed by native methods: provides access to the JNI data (i.e. resources used by
     * the native AudioTrack object, but not stored in it).
     */
    @SuppressWarnings("unused")
    private int mJniData;


    //--------------------------------------------------------------------------
    // Constructor, Finalize
    //--------------------
    /**
     * Class constructor.
     * @param streamType the type of the audio stream. See
     *   {@link AudioManager#STREAM_VOICE_CALL}, {@link AudioManager#STREAM_SYSTEM},
     *   {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_MUSIC},
     *   {@link AudioManager#STREAM_ALARM}, and {@link AudioManager#STREAM_NOTIFICATION}.
     * @param sampleRateInHz the initial source sample rate expressed in Hz.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_OUT_MONO} and
     *   {@link AudioFormat#CHANNEL_OUT_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT} and
     *   {@link AudioFormat#ENCODING_PCM_8BIT}
     * @param bufferSizeInBytes the total size (in bytes) of the internal buffer where audio data is
     *   read from for playback.
     *   If track's creation mode is {@link #MODE_STREAM}, you can write data into
     *   this buffer in chunks less than or equal to this size, and it is typical to use
     *   chunks of 1/2 of the total size to permit double-buffering.
     *   If the track's creation mode is {@link #MODE_STATIC},
     *   this is the maximum length sample, or audio clip, that can be played by this instance.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the minimum required buffer size
     *   for the successful creation of an AudioTrack instance in streaming mode. Using values
     *   smaller than getMinBufferSize() will result in an initialization failure.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @throws java.lang.IllegalArgumentException
     */
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode)
    throws IllegalArgumentException {
        this(streamType, sampleRateInHz, channelConfig, audioFormat,
                bufferSizeInBytes, mode, 0 /*session*/);
    }

    /**
     * Class constructor with audio session. Use this constructor when the AudioTrack must be
     * attached to a particular audio session. The primary use of the audio session ID is to
     * associate audio effects to a particular instance of AudioTrack: if an audio session ID
     * is provided when creating an AudioEffect, this effect will be applied only to audio tracks
     * and media players in the same session and not to the output mix.
     * When an AudioTrack is created without specifying a session, it will create its own session
     * which can be retrieved by calling the {@link #getAudioSessionId()} method.
     * If a non-zero session ID is provided, this AudioTrack will share effects attached to this
     * session
     * with all other media players or audio tracks in the same session, otherwise a new session
     * will be created for this track if none is supplied.
     * @param streamType the type of the audio stream. See
     *   {@link AudioManager#STREAM_VOICE_CALL}, {@link AudioManager#STREAM_SYSTEM},
     *   {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_MUSIC},
     *   {@link AudioManager#STREAM_ALARM}, and {@link AudioManager#STREAM_NOTIFICATION}.
     * @param sampleRateInHz the initial source sample rate expressed in Hz.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_OUT_MONO} and
     *   {@link AudioFormat#CHANNEL_OUT_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT} and
     *   {@link AudioFormat#ENCODING_PCM_8BIT}
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is read
     *   from for playback. If using the AudioTrack in streaming mode, you can write data into
     *   this buffer in smaller chunks than this size. If using the AudioTrack in static mode,
     *   this is the maximum size of the sound that will be played for this instance.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the minimum required buffer size
     *   for the successful creation of an AudioTrack instance in streaming mode. Using values
     *   smaller than getMinBufferSize() will result in an initialization failure.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @param sessionId Id of audio session the AudioTrack must be attached to
     * @throws java.lang.IllegalArgumentException
     */
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode, int sessionId)
    throws IllegalArgumentException {
        // mState already == STATE_UNINITIALIZED

        // remember which looper is associated with the AudioTrack instantiation
        Looper looper;
        if ((looper = Looper.myLooper()) == null) {
            looper = Looper.getMainLooper();
        }
        mInitializationLooper = looper;

        audioParamCheck(streamType, sampleRateInHz, channelConfig, audioFormat, mode);

        audioBuffSizeCheck(bufferSizeInBytes);

        if (sessionId < 0) {
            throw new IllegalArgumentException("Invalid audio session ID: "+sessionId);
        }

        int[] session = new int[1];
        session[0] = sessionId;
        // native initialization
        int initResult = native_setup(new WeakReference<AudioTrack>(this),
                mStreamType, mSampleRate, mChannels, mAudioFormat,
                mNativeBufferSizeInBytes, mDataLoadMode, session);
        if (initResult != SUCCESS) {
            loge("Error code "+initResult+" when initializing AudioTrack.");
            return; // with mState == STATE_UNINITIALIZED
        }

        mSessionId = session[0];

        if (mDataLoadMode == MODE_STATIC) {
            mState = STATE_NO_STATIC_DATA;
        } else {
            mState = STATE_INITIALIZED;
        }
    }

    // mask of all the channels supported by this implementation
    private static final int SUPPORTED_OUT_CHANNELS =
            AudioFormat.CHANNEL_OUT_FRONT_LEFT |
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT |
            AudioFormat.CHANNEL_OUT_FRONT_CENTER |
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY |
            AudioFormat.CHANNEL_OUT_BACK_LEFT |
            AudioFormat.CHANNEL_OUT_BACK_RIGHT |
            AudioFormat.CHANNEL_OUT_BACK_CENTER;

    // Convenience method for the constructor's parameter checks.
    // This is where constructor IllegalArgumentException-s are thrown
    // postconditions:
    //    mStreamType is valid
    //    mChannelCount is valid
    //    mChannels is valid
    //    mAudioFormat is valid
    //    mSampleRate is valid
    //    mDataLoadMode is valid
    private void audioParamCheck(int streamType, int sampleRateInHz,
                                 int channelConfig, int audioFormat, int mode) {

        //--------------
        // stream type
        if( (streamType != AudioManager.STREAM_ALARM) && (streamType != AudioManager.STREAM_MUSIC)
           && (streamType != AudioManager.STREAM_RING) && (streamType != AudioManager.STREAM_SYSTEM)
           && (streamType != AudioManager.STREAM_VOICE_CALL)
           && (streamType != AudioManager.STREAM_NOTIFICATION)
           && (streamType != AudioManager.STREAM_BLUETOOTH_SCO)
           && (streamType != AudioManager.STREAM_DTMF)) {
            throw new IllegalArgumentException("Invalid stream type.");
        }
        mStreamType = streamType;

        //--------------
        // sample rate, note these values are subject to change
        if ( (sampleRateInHz < 4000) || (sampleRateInHz > 48000) ) {
            throw new IllegalArgumentException(sampleRateInHz
                    + "Hz is not a supported sample rate.");
        }
        mSampleRate = sampleRateInHz;

        //--------------
        // channel config
        mChannelConfiguration = channelConfig;

        switch (channelConfig) {
        case AudioFormat.CHANNEL_OUT_DEFAULT: //AudioFormat.CHANNEL_CONFIGURATION_DEFAULT
        case AudioFormat.CHANNEL_OUT_MONO:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            mChannelCount = 1;
            mChannels = AudioFormat.CHANNEL_OUT_MONO;
            break;
        case AudioFormat.CHANNEL_OUT_STEREO:
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            mChannelCount = 2;
            mChannels = AudioFormat.CHANNEL_OUT_STEREO;
            break;
        default:
            if (!isMultichannelConfigSupported(channelConfig)) {
                // input channel configuration features unsupported channels
                throw new IllegalArgumentException("Unsupported channel configuration.");
            }
            mChannels = channelConfig;
            mChannelCount = Integer.bitCount(channelConfig);
        }

        //--------------
        // audio format
        switch (audioFormat) {
        case AudioFormat.ENCODING_DEFAULT:
            mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
            break;
        case AudioFormat.ENCODING_PCM_16BIT:
        case AudioFormat.ENCODING_PCM_8BIT:
        case AudioFormat.ENCODING_AMRNB:
        case AudioFormat.ENCODING_AMRWB:
        case AudioFormat.ENCODING_EVRC:
        case AudioFormat.ENCODING_EVRCB:
        case AudioFormat.ENCODING_EVRCWB:
            mAudioFormat = audioFormat;
            break;
        default:
            throw new IllegalArgumentException("Unsupported sample encoding."
                + " Should be ENCODING_PCM_8BIT or ENCODING_PCM_16BIT.");
        }

        //--------------
        // audio load mode
        if ( (mode != MODE_STREAM) && (mode != MODE_STATIC) ) {
            throw new IllegalArgumentException("Invalid mode.");
        }
        mDataLoadMode = mode;
    }

    /**
     * Convenience method to check that the channel configuration (a.k.a channel mask) is supported
     * @param channelConfig the mask to validate
     * @return false if the AudioTrack can't be used with such a mask
     */
    private static boolean isMultichannelConfigSupported(int channelConfig) {
        // check for unsupported channels
        if ((channelConfig & SUPPORTED_OUT_CHANNELS) != channelConfig) {
            loge("Channel configuration features unsupported channels");
            return false;
        }
        // check for unsupported multichannel combinations:
        // - FL/FR must be present
        // - L/R channels must be paired (e.g. no single L channel)
        final int frontPair =
                AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT;
        if ((channelConfig & frontPair) != frontPair) {
                loge("Front channels must be present in multichannel configurations");
                return false;
        }
        final int backPair =
                AudioFormat.CHANNEL_OUT_BACK_LEFT | AudioFormat.CHANNEL_OUT_BACK_RIGHT;
        if ((channelConfig & backPair) != 0) {
            if ((channelConfig & backPair) != backPair) {
                loge("Rear channels can't be used independently");
                return false;
            }
        }
        return true;
    }


    // Convenience method for the constructor's audio buffer size check.
    // preconditions:
    //    mChannelCount is valid
    //    mAudioFormat is valid
    // postcondition:
    //    mNativeBufferSizeInBytes is valid (multiple of frame size, positive)
    private void audioBuffSizeCheck(int audioBufferSize) {
        // NB: this section is only valid with PCM data.
        //     To update when supporting compressed formats
        int frameSizeInBytes = mChannelCount
                * (mAudioFormat == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
        if ((audioBufferSize % frameSizeInBytes != 0) || (audioBufferSize < 1)) {
            throw new IllegalArgumentException("Invalid audio buffer size.");
        }

        mNativeBufferSizeInBytes = audioBufferSize;
        mNativeBufferSizeInFrames = audioBufferSize / frameSizeInBytes;
    }


    /**
     * Releases the native AudioTrack resources.
     */
    public void release() {
        // even though native_release() stops the native AudioTrack, we need to stop
        // AudioTrack subclasses too.
        try {
            stop();
        } catch(IllegalStateException ise) {
            // don't raise an exception, we're releasing the resources.
        }
        native_release();
        mState = STATE_UNINITIALIZED;
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    //--------------------------------------------------------------------------
    // Getters
    //--------------------
    /**
     * Returns the minimum valid volume value. Volume values set under this one will
     * be clamped at this value.
     * @return the minimum volume expressed as a linear attenuation.
     */
    static public float getMinVolume() {
        return VOLUME_MIN;
    }

    /**
     * Returns the maximum valid volume value. Volume values set above this one will
     * be clamped at this value.
     * @return the maximum volume expressed as a linear attenuation.
     */
    static public float getMaxVolume() {
        return VOLUME_MAX;
    }

    /**
     * Returns the configured audio data sample rate in Hz
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the current playback rate in Hz.
     */
    public int getPlaybackRate() {
        return native_get_playback_rate();
    }

    /**
     * Returns the configured audio data format. See {@link AudioFormat#ENCODING_PCM_16BIT}
     * and {@link AudioFormat#ENCODING_PCM_8BIT}.
     */
    public int getAudioFormat() {
        return mAudioFormat;
    }

    /**
     * Returns the type of audio stream this AudioTrack is configured for.
     * Compare the result against {@link AudioManager#STREAM_VOICE_CALL},
     * {@link AudioManager#STREAM_SYSTEM}, {@link AudioManager#STREAM_RING},
     * {@link AudioManager#STREAM_MUSIC}, {@link AudioManager#STREAM_ALARM},
     * {@link AudioManager#STREAM_NOTIFICATION}, or {@link AudioManager#STREAM_DTMF}.
     */
    public int getStreamType() {
        return mStreamType;
    }

    /**
     * Returns the configured channel configuration.
     * See {@link AudioFormat#CHANNEL_OUT_MONO}
     * and {@link AudioFormat#CHANNEL_OUT_STEREO}.
     */
    public int getChannelConfiguration() {
        return mChannelConfiguration;
    }

    /**
     * Returns the configured number of channels.
     */
    public int getChannelCount() {
        return mChannelCount;
    }

    /**
     * Returns the state of the AudioTrack instance. This is useful after the
     * AudioTrack instance has been created to check if it was initialized
     * properly. This ensures that the appropriate resources have been acquired.
     * @see #STATE_INITIALIZED
     * @see #STATE_NO_STATIC_DATA
     * @see #STATE_UNINITIALIZED
     */
    public int getState() {
        return mState;
    }

    /**
     * Returns the playback state of the AudioTrack instance.
     * @see #PLAYSTATE_STOPPED
     * @see #PLAYSTATE_PAUSED
     * @see #PLAYSTATE_PLAYING
     */
    public int getPlayState() {
        synchronized (mPlayStateLock) {
            return mPlayState;
        }
    }

    /**
     *  Returns the "native frame count", derived from the bufferSizeInBytes specified at
     *  creation time and converted to frame units.
     *  If track's creation mode is {@link #MODE_STATIC},
     *  it is equal to the specified bufferSizeInBytes converted to frame units.
     *  If track's creation mode is {@link #MODE_STREAM},
     *  it is typically greater than or equal to the specified bufferSizeInBytes converted to frame
     *  units; it may be rounded up to a larger value if needed by the target device implementation.
     *  @deprecated Only accessible by subclasses, which are not recommended for AudioTrack.
     *  See {@link AudioManager#getProperty(String)} for key
     *  {@link AudioManager#PROPERTY_OUTPUT_FRAMES_PER_BUFFER}.
     */
    @Deprecated
    protected int getNativeFrameCount() {
        return native_get_native_frame_count();
    }

    /**
     * Returns marker position expressed in frames.
     * @return marker position in wrapping frame units similar to {@link #getPlaybackHeadPosition},
     * or zero if marker is disabled.
     */
    public int getNotificationMarkerPosition() {
        return native_get_marker_pos();
    }

    /**
     * Returns the notification update period expressed in frames.
     * Zero means that no position update notifications are being delivered.
     */
    public int getPositionNotificationPeriod() {
        return native_get_pos_update_period();
    }

    /**
     * Returns the playback head position expressed in frames.
     * Though the "int" type is signed 32-bits, the value should be reinterpreted as if it is
     * unsigned 32-bits.  That is, the next position after 0x7FFFFFFF is (int) 0x80000000.
     * This is a continuously advancing counter.  It will wrap (overflow) periodically,
     * for example approximately once every 27:03:11 hours:minutes:seconds at 44.1 kHz.
     * It is reset to zero by flush(), reload(), and stop().
     */
    public int getPlaybackHeadPosition() {
        return native_get_position();
    }

    /**
     * Returns this track's estimated latency in milliseconds. This includes the latency due
     * to AudioTrack buffer size, AudioMixer (if any) and audio hardware driver.
     *
     * DO NOT UNHIDE. The existing approach for doing A/V sync has too many problems. We need
     * a better solution.
     * @hide
     */
    public int getLatency() {
        return native_get_latency();
    }

    /**
     *  Returns the output sample rate in Hz for the specified stream type.
     */
    static public int getNativeOutputSampleRate(int streamType) {
        return native_get_output_sample_rate(streamType);
    }

    /**
     * Returns the minimum buffer size required for the successful creation of an AudioTrack
     * object to be created in the {@link #MODE_STREAM} mode. Note that this size doesn't
     * guarantee a smooth playback under load, and higher values should be chosen according to
     * the expected frequency at which the buffer will be refilled with additional data to play.
     * For example, if you intend to dynamically set the source sample rate of an AudioTrack
     * to a higher value than the initial source sample rate, be sure to configure the buffer size
     * based on the highest planned sample rate.
     * @param sampleRateInHz the source sample rate expressed in Hz.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_OUT_MONO} and
     *   {@link AudioFormat#CHANNEL_OUT_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT} and
     *   {@link AudioFormat#ENCODING_PCM_8BIT}
     * @return {@link #ERROR_BAD_VALUE} if an invalid parameter was passed,
     *   or {@link #ERROR} if unable to query for output properties,
     *   or the minimum buffer size expressed in bytes.
     */
    static public int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        int channelCount = 0;
        switch(channelConfig) {
        case AudioFormat.CHANNEL_OUT_MONO:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            channelCount = 1;
            break;
        case AudioFormat.CHANNEL_OUT_STEREO:
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            channelCount = 2;
            break;
        default:
            if ((channelConfig & SUPPORTED_OUT_CHANNELS) != channelConfig) {
                // input channel configuration features unsupported channels
                loge("getMinBufferSize(): Invalid channel configuration.");
                return ERROR_BAD_VALUE;
            } else {
                channelCount = Integer.bitCount(channelConfig);
            }
        }

        if ((audioFormat != AudioFormat.ENCODING_PCM_16BIT)
            && (audioFormat != AudioFormat.ENCODING_PCM_8BIT)
            && (audioFormat != AudioFormat.ENCODING_AMRNB)
            && (audioFormat != AudioFormat.ENCODING_AMRWB)
            && (audioFormat != AudioFormat.ENCODING_EVRC)
            && (audioFormat != AudioFormat.ENCODING_EVRCB)
            && (audioFormat != AudioFormat.ENCODING_EVRCWB)) {
            loge("getMinBufferSize(): Invalid audio format.");
            return ERROR_BAD_VALUE;
        }

        // sample rate, note these values are subject to change
        if ( (sampleRateInHz < SAMPLE_RATE_HZ_MIN) || (sampleRateInHz > SAMPLE_RATE_HZ_MAX) ) {
            loge("getMinBufferSize(): " + sampleRateInHz + " Hz is not a supported sample rate.");
            return ERROR_BAD_VALUE;
        }

        int size = native_get_min_buff_size(sampleRateInHz, channelCount, audioFormat);
        if (size <= 0) {
            loge("getMinBufferSize(): error querying hardware");
            return ERROR;
        }
        else {
            return size;
        }
    }

    /**
     * Returns the audio session ID.
     *
     * @return the ID of the audio session this AudioTrack belongs to.
     */
    public int getAudioSessionId() {
        return mSessionId;
    }

   /**
    * Poll for a timestamp on demand.
    *
    * Use if you need to get the most recent timestamp outside of the event callback handler.
    * Calling this method too often may be inefficient;
    * if you need a high-resolution mapping between frame position and presentation time,
    * consider implementing that at application level, based on low-resolution timestamps.
    * The audio data at the returned position may either already have been
    * presented, or may have not yet been presented but is committed to be presented.
    * It is not possible to request the time corresponding to a particular position,
    * or to request the (fractional) position corresponding to a particular time.
    * If you need such features, consider implementing them at application level.
    *
    * @param timestamp a reference to a non-null AudioTimestamp instance allocated
    *        and owned by caller.
    * @return true if a timestamp is available, or false if no timestamp is available.
    *         If a timestamp if available,
    *         the AudioTimestamp instance is filled in with a position in frame units, together
    *         with the estimated time when that frame was presented or is committed to
    *         be presented.
    *         In the case that no timestamp is available, any supplied instance is left unaltered.
    */
    public boolean getTimestamp(AudioTimestamp timestamp)
    {
        if (timestamp == null) {
            throw new IllegalArgumentException();
        }
        // It's unfortunate, but we have to either create garbage every time or use synchronized
        long[] longArray = new long[2];
        int ret = native_get_timestamp(longArray);
        if (ret != SUCCESS) {
            return false;
        }
        timestamp.framePosition = longArray[0];
        timestamp.nanoTime = longArray[1];
        return true;
    }


    //--------------------------------------------------------------------------
    // Initialization / configuration
    //--------------------
    /**
     * Sets the listener the AudioTrack notifies when a previously set marker is reached or
     * for each periodic playback head position update.
     * Notifications will be received in the same thread as the one in which the AudioTrack
     * instance was created.
     * @param listener
     */
    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener) {
        setPlaybackPositionUpdateListener(listener, null);
    }

    /**
     * Sets the listener the AudioTrack notifies when a previously set marker is reached or
     * for each periodic playback head position update.
     * Use this method to receive AudioTrack events in the Handler associated with another
     * thread than the one in which you created the AudioTrack instance.
     * @param listener
     * @param handler the Handler that will receive the event notification messages.
     */
    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener,
                                                    Handler handler) {
        if (listener != null) {
            mEventHandlerDelegate = new NativeEventHandlerDelegate(this, listener, handler);
        } else {
            mEventHandlerDelegate = null;
        }
    }



     /**
     * Sets the specified left/right output volume values on the AudioTrack. Values are clamped
     * to the ({@link #getMinVolume()}, {@link #getMaxVolume()}) interval if outside this range.
     * @param leftVolume output attenuation for the left channel. A value of 0.0f is silence,
     *      a value of 1.0f is no attenuation.
     * @param rightVolume output attenuation for the right channel
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setStereoVolume(float leftVolume, float rightVolume) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        // clamp the volumes
        if (leftVolume < getMinVolume()) {
            leftVolume = getMinVolume();
        }
        if (leftVolume > getMaxVolume()) {
            leftVolume = getMaxVolume();
        }
        if (rightVolume < getMinVolume()) {
            rightVolume = getMinVolume();
        }
        if (rightVolume > getMaxVolume()) {
            rightVolume = getMaxVolume();
        }

        native_setVolume(leftVolume, rightVolume);

        return SUCCESS;
    }


    /**
     * Similar, except set volume of all channels to same value.
     * @hide
     */
    public int setVolume(float volume) {
        return setStereoVolume(volume, volume);
    }


    /**
     * Sets the playback sample rate for this track. This sets the sampling rate at which
     * the audio data will be consumed and played back
     * (as set by the sampleRateInHz parameter in the
     * {@link #AudioTrack(int, int, int, int, int, int)} constructor),
     * not the original sampling rate of the
     * content. For example, setting it to half the sample rate of the content will cause the
     * playback to last twice as long, but will also result in a pitch shift down by one octave.
     * The valid sample rate range is from 1 Hz to twice the value returned by
     * {@link #getNativeOutputSampleRate(int)}.
     * @param sampleRateInHz the sample rate expressed in Hz
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setPlaybackRate(int sampleRateInHz) {
        if (mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        if (sampleRateInHz <= 0) {
            return ERROR_BAD_VALUE;
        }
        return native_set_playback_rate(sampleRateInHz);
    }


    /**
     * Sets the position of the notification marker.  At most one marker can be active.
     * @param markerInFrames marker position in wrapping frame units similar to
     * {@link #getPlaybackHeadPosition}, or zero to disable the marker.
     * To set a marker at a position which would appear as zero due to wraparound,
     * a workaround is to use a non-zero position near zero, such as -1 or 1.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *  {@link #ERROR_INVALID_OPERATION}
     */
    public int setNotificationMarkerPosition(int markerInFrames) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_set_marker_pos(markerInFrames);
    }


    /**
     * Sets the period for the periodic notification event.
     * @param periodInFrames update period expressed in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_INVALID_OPERATION}
     */
    public int setPositionNotificationPeriod(int periodInFrames) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_set_pos_update_period(periodInFrames);
    }


    /**
     * Sets the playback head position.
     * The track must be stopped or paused for the position to be changed,
     * and must use the {@link #MODE_STATIC} mode.
     * @param positionInFrames playback head position expressed in frames
     * Zero corresponds to start of buffer.
     * The position must not be greater than the buffer size in frames, or negative.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setPlaybackHeadPosition(int positionInFrames) {
        if (mDataLoadMode == MODE_STREAM || mState != STATE_INITIALIZED ||
                getPlayState() == PLAYSTATE_PLAYING) {
            return ERROR_INVALID_OPERATION;
        }
        if (!(0 <= positionInFrames && positionInFrames <= mNativeBufferSizeInFrames)) {
            return ERROR_BAD_VALUE;
        }
        return native_set_position(positionInFrames);
    }

    /**
     * Sets the loop points and the loop count. The loop can be infinite.
     * Similarly to setPlaybackHeadPosition,
     * the track must be stopped or paused for the loop points to be changed,
     * and must use the {@link #MODE_STATIC} mode.
     * @param startInFrames loop start marker expressed in frames
     * Zero corresponds to start of buffer.
     * The start marker must not be greater than or equal to the buffer size in frames, or negative.
     * @param endInFrames loop end marker expressed in frames
     * The total buffer size in frames corresponds to end of buffer.
     * The end marker must not be greater than the buffer size in frames.
     * For looping, the end marker must not be less than or equal to the start marker,
     * but to disable looping
     * it is permitted for start marker, end marker, and loop count to all be 0.
     * @param loopCount the number of times the loop is looped.
     *    A value of -1 means infinite looping, and 0 disables looping.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setLoopPoints(int startInFrames, int endInFrames, int loopCount) {
        if (mDataLoadMode == MODE_STREAM || mState != STATE_INITIALIZED ||
                getPlayState() == PLAYSTATE_PLAYING) {
            return ERROR_INVALID_OPERATION;
        }
        if (loopCount == 0) {
            ;   // explicitly allowed as an exception to the loop region range check
        } else if (!(0 <= startInFrames && startInFrames < mNativeBufferSizeInFrames &&
                startInFrames < endInFrames && endInFrames <= mNativeBufferSizeInFrames)) {
            return ERROR_BAD_VALUE;
        }
        return native_set_loop(startInFrames, endInFrames, loopCount);
    }

    /**
     * Sets the initialization state of the instance. This method was originally intended to be used
     * in an AudioTrack subclass constructor to set a subclass-specific post-initialization state.
     * However, subclasses of AudioTrack are no longer recommended, so this method is obsolete.
     * @param state the state of the AudioTrack instance
     * @deprecated Only accessible by subclasses, which are not recommended for AudioTrack.
     */
    @Deprecated
    protected void setState(int state) {
        mState = state;
    }


    //---------------------------------------------------------
    // Transport control methods
    //--------------------
    /**
     * Starts playing an AudioTrack.
     * If track's creation mode is {@link #MODE_STATIC}, you must have called write() prior.
     *
     * @throws IllegalStateException
     */
    public void play()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("play() called on uninitialized AudioTrack.");
        }

        synchronized(mPlayStateLock) {
            native_start();
            mPlayState = PLAYSTATE_PLAYING;
        }
    }

    /**
     * Stops playing the audio data.
     * When used on an instance created in {@link #MODE_STREAM} mode, audio will stop playing
     * after the last buffer that was written has been played. For an immediate stop, use
     * {@link #pause()}, followed by {@link #flush()} to discard audio data that hasn't been played
     * back yet.
     * @throws IllegalStateException
     */
    public void stop()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("stop() called on uninitialized AudioTrack.");
        }

        // stop playing
        synchronized(mPlayStateLock) {
            native_stop();
            mPlayState = PLAYSTATE_STOPPED;
        }
    }

    /**
     * Pauses the playback of the audio data. Data that has not been played
     * back will not be discarded. Subsequent calls to {@link #play} will play
     * this data back. See {@link #flush()} to discard this data.
     *
     * @throws IllegalStateException
     */
    public void pause()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("pause() called on uninitialized AudioTrack.");
        }
        //logd("pause()");

        // pause playback
        synchronized(mPlayStateLock) {
            native_pause();
            mPlayState = PLAYSTATE_PAUSED;
        }
    }


    //---------------------------------------------------------
    // Audio data supply
    //--------------------

    /**
     * Flushes the audio data currently queued for playback. Any data that has
     * not been played back will be discarded.  No-op if not stopped or paused,
     * or if the track's creation mode is not {@link #MODE_STREAM}.
     */
    public void flush() {
        if (mState == STATE_INITIALIZED) {
            // flush the data in native layer
            native_flush();
        }

    }

    /**
     * Writes the audio data to the audio sink for playback (streaming mode),
     * or copies audio data for later playback (static buffer mode).
     * In streaming mode, will block until all data has been written to the audio sink.
     * In static buffer mode, copies the data to the buffer starting at offset 0.
     * Note that the actual playback of this data might occur after this function
     * returns. This function is thread safe with respect to {@link #stop} calls,
     * in which case all of the specified data might not be written to the audio sink.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInBytes the offset expressed in bytes in audioData where the data to play
     *    starts.
     * @param sizeInBytes the number of bytes to read in audioData after the offset.
     * @return the number of bytes that were written or {@link #ERROR_INVALID_OPERATION}
     *    if the object wasn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes.
     */

    public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {

        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        if ( (audioData == null) || (offsetInBytes < 0 ) || (sizeInBytes < 0)
                || (offsetInBytes + sizeInBytes < 0)    // detect integer overflow
                || (offsetInBytes + sizeInBytes > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        int ret = native_write_byte(audioData, offsetInBytes, sizeInBytes, mAudioFormat);

        if ((mDataLoadMode == MODE_STATIC)
                && (mState == STATE_NO_STATIC_DATA)
                && (ret > 0)) {
            // benign race with respect to other APIs that read mState
            mState = STATE_INITIALIZED;
        }

        return ret;
    }


    /**
     * Writes the audio data to the audio sink for playback (streaming mode),
     * or copies audio data for later playback (static buffer mode).
     * In streaming mode, will block until all data has been written to the audio sink.
     * In static buffer mode, copies the data to the buffer starting at offset 0.
     * Note that the actual playback of this data might occur after this function
     * returns. This function is thread safe with respect to {@link #stop} calls,
     * in which case all of the specified data might not be written to the audio sink.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInShorts the offset expressed in shorts in audioData where the data to play
     *     starts.
     * @param sizeInShorts the number of shorts to read in audioData after the offset.
     * @return the number of shorts that were written or {@link #ERROR_INVALID_OPERATION}
      *    if the object wasn't properly initialized, or {@link #ERROR_BAD_VALUE} if
      *    the parameters don't resolve to valid data and indexes.
     */

    public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {

        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        if ( (audioData == null) || (offsetInShorts < 0 ) || (sizeInShorts < 0)
                || (offsetInShorts + sizeInShorts < 0)  // detect integer overflow
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        int ret = native_write_short(audioData, offsetInShorts, sizeInShorts, mAudioFormat);

        if ((mDataLoadMode == MODE_STATIC)
                && (mState == STATE_NO_STATIC_DATA)
                && (ret > 0)) {
            // benign race with respect to other APIs that read mState
            mState = STATE_INITIALIZED;
        }

        return ret;
    }


    /**
     * Notifies the native resource to reuse the audio data already loaded in the native
     * layer, that is to rewind to start of buffer.
     * The track's creation mode must be {@link #MODE_STATIC}.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *  {@link #ERROR_INVALID_OPERATION}
     */
    public int reloadStaticData() {
        if (mDataLoadMode == MODE_STREAM || mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_reload_static();
    }

    //--------------------------------------------------------------------------
    // Audio effects management
    //--------------------

    /**
     * Attaches an auxiliary effect to the audio track. A typical auxiliary
     * effect is a reverberation effect which can be applied on any sound source
     * that directs a certain amount of its energy to this effect. This amount
     * is defined by setAuxEffectSendLevel().
     * {@see #setAuxEffectSendLevel(float)}.
     * <p>After creating an auxiliary effect (e.g.
     * {@link android.media.audiofx.EnvironmentalReverb}), retrieve its ID with
     * {@link android.media.audiofx.AudioEffect#getId()} and use it when calling
     * this method to attach the audio track to the effect.
     * <p>To detach the effect from the audio track, call this method with a
     * null effect id.
     *
     * @param effectId system wide unique id of the effect to attach
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}, {@link #ERROR_BAD_VALUE}
     */
    public int attachAuxEffect(int effectId) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_attachAuxEffect(effectId);
    }

    /**
     * Sets the send level of the audio track to the attached auxiliary effect
     * {@link #attachAuxEffect(int)}.  The level value range is 0.0f to 1.0f.
     * Values are clamped to the (0.0f, 1.0f) interval if outside this range.
     * <p>By default the send level is 0.0f, so even if an effect is attached to the player
     * this method must be called for the effect to be applied.
     * <p>Note that the passed level value is a raw scalar. UI controls should be scaled
     * logarithmically: the gain applied by audio framework ranges from -72dB to 0dB,
     * so an appropriate conversion from linear UI input x to level is:
     * x == 0 -&gt; level = 0
     * 0 &lt; x &lt;= R -&gt; level = 10^(72*(x-R)/20/R)
     *
     * @param level send level scalar
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setAuxEffectSendLevel(float level) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        // clamp the level
        if (level < getMinVolume()) {
            level = getMinVolume();
        }
        if (level > getMaxVolume()) {
            level = getMaxVolume();
        }
        native_setAuxEffectSendLevel(level);
        return SUCCESS;
    }

    //---------------------------------------------------------
    // Interface definitions
    //--------------------
    /**
     * Interface definition for a callback to be invoked when the playback head position of
     * an AudioTrack has reached a notification marker or has increased by a certain period.
     */
    public interface OnPlaybackPositionUpdateListener  {
        /**
         * Called on the listener to notify it that the previously set marker has been reached
         * by the playback head.
         */
        void onMarkerReached(AudioTrack track);

        /**
         * Called on the listener to periodically notify it that the playback head has reached
         * a multiple of the notification period.
         */
        void onPeriodicNotification(AudioTrack track);
    }


    //---------------------------------------------------------
    // Inner classes
    //--------------------
    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread
     */
    private class NativeEventHandlerDelegate {
        private final Handler mHandler;

        NativeEventHandlerDelegate(final AudioTrack track,
                                   final OnPlaybackPositionUpdateListener listener,
                                   Handler handler) {
            // find the looper for our new event handler
            Looper looper;
            if (handler != null) {
                looper = handler.getLooper();
            } else {
                // no given handler, use the looper the AudioTrack was created in
                looper = mInitializationLooper;
            }

            // construct the event handler with this looper
            if (looper != null) {
                // implement the event handler delegate
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (track == null) {
                            return;
                        }
                        switch(msg.what) {
                        case NATIVE_EVENT_MARKER:
                            if (listener != null) {
                                listener.onMarkerReached(track);
                            }
                            break;
                        case NATIVE_EVENT_NEW_POS:
                            if (listener != null) {
                                listener.onPeriodicNotification(track);
                            }
                            break;
                        default:
                            loge("Unknown native event type: " + msg.what);
                            break;
                        }
                    }
                };
            } else {
                mHandler = null;
            }
        }

        Handler getHandler() {
            return mHandler;
        }
    }


    //---------------------------------------------------------
    // Java methods called from the native side
    //--------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object audiotrack_ref,
            int what, int arg1, int arg2, Object obj) {
        //logd("Event posted from the native side: event="+ what + " args="+ arg1+" "+arg2);
        AudioTrack track = (AudioTrack)((WeakReference)audiotrack_ref).get();
        if (track == null) {
            return;
        }

        NativeEventHandlerDelegate delegate = track.mEventHandlerDelegate;
        if (delegate != null) {
            Handler handler = delegate.getHandler();
            if (handler != null) {
                Message m = handler.obtainMessage(what, arg1, arg2, obj);
                handler.sendMessage(m);
            }
        }

    }


    //---------------------------------------------------------
    // Native methods called from the Java side
    //--------------------

    private native final int native_setup(Object audiotrack_this,
            int streamType, int sampleRate, int nbChannels, int audioFormat,
            int buffSizeInBytes, int mode, int[] sessionId);

    private native final void native_finalize();

    private native final void native_release();

    private native final void native_start();

    private native final void native_stop();

    private native final void native_pause();

    private native final void native_flush();

    private native final int native_write_byte(byte[] audioData,
                                               int offsetInBytes, int sizeInBytes, int format);

    private native final int native_write_short(short[] audioData,
                                                int offsetInShorts, int sizeInShorts, int format);

    private native final int native_reload_static();

    private native final int native_get_native_frame_count();

    private native final void native_setVolume(float leftVolume, float rightVolume);

    private native final int native_set_playback_rate(int sampleRateInHz);
    private native final int native_get_playback_rate();

    private native final int native_set_marker_pos(int marker);
    private native final int native_get_marker_pos();

    private native final int native_set_pos_update_period(int updatePeriod);
    private native final int native_get_pos_update_period();

    private native final int native_set_position(int position);
    private native final int native_get_position();

    private native final int native_get_latency();

    // longArray must be a non-null array of length >= 2
    // [0] is assigned the frame position
    // [1] is assigned the time in CLOCK_MONOTONIC nanoseconds
    private native final int native_get_timestamp(long[] longArray);

    private native final int native_set_loop(int start, int end, int loopCount);

    static private native final int native_get_output_sample_rate(int streamType);
    static private native final int native_get_min_buff_size(
            int sampleRateInHz, int channelConfig, int audioFormat);

    private native final int native_attachAuxEffect(int effectId);
    private native final void native_setAuxEffectSendLevel(float level);

    //---------------------------------------------------------
    // Utility methods
    //------------------

    private static void logd(String msg) {
        Log.d(TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, msg);
    }

}
