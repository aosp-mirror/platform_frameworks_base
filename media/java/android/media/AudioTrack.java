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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.NioUtils;
import java.util.Collection;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.app.IAppOpsService;


/**
 * The AudioTrack class manages and plays a single audio resource for Java applications.
 * It allows streaming of PCM audio buffers to the audio sink for playback. This is
 * achieved by "pushing" the data to the AudioTrack object using one of the
 *  {@link #write(byte[], int, int)}, {@link #write(short[], int, int)},
 *  and {@link #write(float[], int, int, int)} methods.
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
    /** Minimum value for a linear gain or auxiliary effect level.
     *  This value must be exactly equal to 0.0f; do not change it.
     */
    private static final float GAIN_MIN = 0.0f;
    /** Maximum value for a linear gain or auxiliary effect level.
     *  This value must be greater than or equal to 1.0f.
     */
    private static final float GAIN_MAX = 1.0f;

    /** Minimum value for sample rate */
    private static final int SAMPLE_RATE_HZ_MIN = 4000;
    /** Maximum value for sample rate */
    private static final int SAMPLE_RATE_HZ_MAX = 192000;

    // FCC_8
    /** Maximum value for AudioTrack channel count */
    private static final int CHANNEL_COUNT_MAX = 8;

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

    /** @hide */
    @IntDef({
        MODE_STATIC,
        MODE_STREAM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransferMode {}

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

    /**
     * Denotes a successful operation.
     */
    public  static final int SUCCESS                               = AudioSystem.SUCCESS;
    /**
     * Denotes a generic operation failure.
     */
    public  static final int ERROR                                 = AudioSystem.ERROR;
    /**
     * Denotes a failure due to the use of an invalid value.
     */
    public  static final int ERROR_BAD_VALUE                       = AudioSystem.BAD_VALUE;
    /**
     * Denotes a failure due to the improper use of a method.
     */
    public  static final int ERROR_INVALID_OPERATION               = AudioSystem.INVALID_OPERATION;

    // Error codes:
    // to keep in sync with frameworks/base/core/jni/android_media_AudioTrack.cpp
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


    /** @hide */
    @IntDef({
        WRITE_BLOCKING,
        WRITE_NON_BLOCKING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WriteMode {}

    /**
     * The write mode indicating the write operation will block until all data has been written,
     * to be used as the actual value of the writeMode parameter in
     * {@link #write(byte[], int, int, int)}, {@link #write(short[], int, int, int)},
     * {@link #write(float[], int, int, int)}, {@link #write(ByteBuffer, int, int)}, and
     * {@link #write(ByteBuffer, int, int, long)}.
     */
    public final static int WRITE_BLOCKING = 0;

    /**
     * The write mode indicating the write operation will return immediately after
     * queuing as much audio data for playback as possible without blocking,
     * to be used as the actual value of the writeMode parameter in
     * {@link #write(ByteBuffer, int, int)}, {@link #write(short[], int, int, int)},
     * {@link #write(float[], int, int, int)}, {@link #write(ByteBuffer, int, int)}, and
     * {@link #write(ByteBuffer, int, int, long)}.
     */
    public final static int WRITE_NON_BLOCKING = 1;

    //--------------------------------------------------------------------------
    // Member variables
    //--------------------
    /**
     * Indicates the state of the AudioTrack instance.
     * One of STATE_UNINITIALIZED, STATE_INITIALIZED, or STATE_NO_STATIC_DATA.
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Indicates the play state of the AudioTrack instance.
     * One of PLAYSTATE_STOPPED, PLAYSTATE_PAUSED, or PLAYSTATE_PLAYING.
     */
    private int mPlayState = PLAYSTATE_STOPPED;
    /**
     * Lock to ensure mPlayState updates reflect the actual state of the object.
     */
    private final Object mPlayStateLock = new Object();
    /**
     * Sizes of the native audio buffer.
     * These values are set during construction and can be stale.
     * To obtain the current native audio buffer frame count use {@link #getBufferSizeInFrames()}.
     */
    private int mNativeBufferSizeInBytes = 0;
    private int mNativeBufferSizeInFrames = 0;
    /**
     * Handler for events coming from the native code.
     */
    private NativePositionEventHandlerDelegate mEventHandlerDelegate;
    /**
     * Looper associated with the thread that creates the AudioTrack instance.
     */
    private final Looper mInitializationLooper;
    /**
     * The audio data source sampling rate in Hz.
     */
    private int mSampleRate; // initialized by all constructors via audioParamCheck()
    /**
     * The number of audio output channels (1 is mono, 2 is stereo, etc.).
     */
    private int mChannelCount = 1;
    /**
     * The audio channel mask used for calling native AudioTrack
     */
    private int mChannelMask = AudioFormat.CHANNEL_OUT_MONO;

    /**
     * The type of the audio stream to play. See
     *   {@link AudioManager#STREAM_VOICE_CALL}, {@link AudioManager#STREAM_SYSTEM},
     *   {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_MUSIC},
     *   {@link AudioManager#STREAM_ALARM}, {@link AudioManager#STREAM_NOTIFICATION}, and
     *   {@link AudioManager#STREAM_DTMF}.
     */
    private int mStreamType = AudioManager.STREAM_MUSIC;

    private final AudioAttributes mAttributes;
    /**
     * The way audio is consumed by the audio sink, one of MODE_STATIC or MODE_STREAM.
     */
    private int mDataLoadMode = MODE_STREAM;
    /**
     * The current channel position mask, as specified on AudioTrack creation.
     * Can be set simultaneously with channel index mask {@link #mChannelIndexMask}.
     * May be set to {@link AudioFormat#CHANNEL_INVALID} if a channel index mask is specified.
     */
    private int mChannelConfiguration = AudioFormat.CHANNEL_OUT_MONO;
    /**
     * The channel index mask if specified, otherwise 0.
     */
    private int mChannelIndexMask = 0;
    /**
     * The encoding of the audio samples.
     * @see AudioFormat#ENCODING_PCM_8BIT
     * @see AudioFormat#ENCODING_PCM_16BIT
     * @see AudioFormat#ENCODING_PCM_FLOAT
     */
    private int mAudioFormat;   // initialized by all constructors via audioParamCheck()
    /**
     * Audio session ID
     */
    private int mSessionId = AudioSystem.AUDIO_SESSION_ALLOCATE;
    /**
     * Reference to the app-ops service.
     */
    private final IAppOpsService mAppOps;
    /**
     * HW_AV_SYNC track AV Sync Header
     */
    private ByteBuffer mAvSyncHeader = null;
    /**
     * HW_AV_SYNC track audio data bytes remaining to write after current AV sync header
     */
    private int mAvSyncBytesRemaining = 0;

    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /**
     * Accessed by native methods: provides access to C++ AudioTrack object.
     */
    @SuppressWarnings("unused")
    private long mNativeTrackInJavaObj;
    /**
     * Accessed by native methods: provides access to the JNI data (i.e. resources used by
     * the native AudioTrack object, but not stored in it).
     */
    @SuppressWarnings("unused")
    private long mJniData;


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
     *   See {@link AudioFormat#ENCODING_PCM_16BIT},
     *   {@link AudioFormat#ENCODING_PCM_8BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     * @param bufferSizeInBytes the total size (in bytes) of the internal buffer where audio data is
     *   read from for playback. This should be a multiple of the frame size in bytes.
     *   <p> If the track's creation mode is {@link #MODE_STATIC},
     *   this is the maximum length sample, or audio clip, that can be played by this instance.
     *   <p> If the track's creation mode is {@link #MODE_STREAM},
     *   this should be the desired buffer size
     *   for the <code>AudioTrack</code> to satisfy the application's
     *   natural latency requirements.
     *   If <code>bufferSizeInBytes</code> is less than the
     *   minimum buffer size for the output sink, it is automatically increased to the minimum
     *   buffer size.
     *   The method {@link #getBufferSizeInFrames()} returns the
     *   actual size in frames of the native buffer created, which
     *   determines the frequency to write
     *   to the streaming <code>AudioTrack</code> to avoid underrun.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @throws java.lang.IllegalArgumentException
     */
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode)
    throws IllegalArgumentException {
        this(streamType, sampleRateInHz, channelConfig, audioFormat,
                bufferSizeInBytes, mode, AudioSystem.AUDIO_SESSION_ALLOCATE);
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
     *   {@link AudioFormat#ENCODING_PCM_8BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
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
        this((new AudioAttributes.Builder())
                    .setLegacyStreamType(streamType)
                    .build(),
                (new AudioFormat.Builder())
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRateInHz)
                    .build(),
                bufferSizeInBytes,
                mode, sessionId);
    }

    /**
     * Class constructor with {@link AudioAttributes} and {@link AudioFormat}.
     * @param attributes a non-null {@link AudioAttributes} instance.
     * @param format a non-null {@link AudioFormat} instance describing the format of the data
     *     that will be played through this AudioTrack. See {@link AudioFormat.Builder} for
     *     configuring the audio format parameters such as encoding, channel mask and sample rate.
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is read
     *   from for playback. If using the AudioTrack in streaming mode, you can write data into
     *   this buffer in smaller chunks than this size. If using the AudioTrack in static mode,
     *   this is the maximum size of the sound that will be played for this instance.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the minimum required buffer size
     *   for the successful creation of an AudioTrack instance in streaming mode. Using values
     *   smaller than getMinBufferSize() will result in an initialization failure.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}.
     * @param sessionId ID of audio session the AudioTrack must be attached to, or
     *   {@link AudioManager#AUDIO_SESSION_ID_GENERATE} if the session isn't known at construction
     *   time. See also {@link AudioManager#generateAudioSessionId()} to obtain a session ID before
     *   construction.
     * @throws IllegalArgumentException
     */
    public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes,
            int mode, int sessionId)
                    throws IllegalArgumentException {
        // mState already == STATE_UNINITIALIZED

        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat");
        }

        // remember which looper is associated with the AudioTrack instantiation
        Looper looper;
        if ((looper = Looper.myLooper()) == null) {
            looper = Looper.getMainLooper();
        }

        int rate = 0;
        if ((format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE) != 0)
        {
            rate = format.getSampleRate();
        } else {
            rate = AudioSystem.getPrimaryOutputSamplingRate();
            if (rate <= 0) {
                rate = 44100;
            }
        }
        int channelIndexMask = 0;
        if ((format.getPropertySetMask()
                & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK) != 0) {
            channelIndexMask = format.getChannelIndexMask();
        }
        int channelMask = 0;
        if ((format.getPropertySetMask()
                & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0) {
            channelMask = format.getChannelMask();
        } else if (channelIndexMask == 0) { // if no masks at all, use stereo
            channelMask = AudioFormat.CHANNEL_OUT_FRONT_LEFT
                    | AudioFormat.CHANNEL_OUT_FRONT_RIGHT;
        }
        int encoding = AudioFormat.ENCODING_DEFAULT;
        if ((format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0) {
            encoding = format.getEncoding();
        }
        audioParamCheck(rate, channelMask, channelIndexMask, encoding, mode);
        mStreamType = AudioSystem.STREAM_DEFAULT;

        audioBuffSizeCheck(bufferSizeInBytes);

        mInitializationLooper = looper;
        IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
        mAppOps = IAppOpsService.Stub.asInterface(b);

        mAttributes = new AudioAttributes.Builder(attributes).build();

        if (sessionId < 0) {
            throw new IllegalArgumentException("Invalid audio session ID: "+sessionId);
        }

        int[] session = new int[1];
        session[0] = sessionId;
        // native initialization
        int initResult = native_setup(new WeakReference<AudioTrack>(this), mAttributes,
                mSampleRate, mChannelMask, mChannelIndexMask, mAudioFormat,
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

    /**
     * Builder class for {@link AudioTrack} objects.
     * Use this class to configure and create an <code>AudioTrack</code> instance. By setting audio
     * attributes and audio format parameters, you indicate which of those vary from the default
     * behavior on the device.
     * <p> Here is an example where <code>Builder</code> is used to specify all {@link AudioFormat}
     * parameters, to be used by a new <code>AudioTrack</code> instance:
     *
     * <pre class="prettyprint">
     * AudioTrack player = new AudioTrack.Builder()
     *         .setAudioAttributes(new AudioAttributes.Builder()
     *                  .setUsage(AudioAttributes.USAGE_ALARM)
     *                  .setContentType(CONTENT_TYPE_MUSIC)
     *                  .build())
     *         .setAudioFormat(new AudioFormat.Builder()
     *                 .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
     *                 .setSampleRate(441000)
     *                 .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
     *                 .build())
     *         .setBufferSize(minBuffSize)
     *         .build();
     * </pre>
     * <p>
     * If the audio attributes are not set with {@link #setAudioAttributes(AudioAttributes)},
     * attributes comprising {@link AudioAttributes#USAGE_MEDIA} will be used.
     * <br>If the audio format is not specified or is incomplete, its sample rate will be the
     * default output sample rate of the device (see
     * {@link AudioManager#PROPERTY_OUTPUT_SAMPLE_RATE}), its channel configuration will be
     * {@link AudioFormat#CHANNEL_OUT_STEREO} and the encoding will be
     * {@link AudioFormat#ENCODING_PCM_16BIT}.
     * <br>If the buffer size is not specified with {@link #setBufferSizeInBytes(int)},
     * and the mode is {@link AudioTrack#MODE_STREAM}, the minimum buffer size is used.
     * <br>If the transfer mode is not specified with {@link #setTransferMode(int)},
     * <code>MODE_STREAM</code> will be used.
     * <br>If the session ID is not specified with {@link #setSessionId(int)}, a new one will
     * be generated.
     */
    public static class Builder {
        private AudioAttributes mAttributes;
        private AudioFormat mFormat;
        private int mBufferSizeInBytes;
        private int mSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
        private int mMode = MODE_STREAM;

        /**
         * Constructs a new Builder with the default values as described above.
         */
        public Builder() {
        }

        /**
         * Sets the {@link AudioAttributes}.
         * @param attributes a non-null {@link AudioAttributes} instance that describes the audio
         *     data to be played.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setAudioAttributes(@NonNull AudioAttributes attributes)
                throws IllegalArgumentException {
            if (attributes == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            }
            // keep reference, we only copy the data when building
            mAttributes = attributes;
            return this;
        }

        /**
         * Sets the format of the audio data to be played by the {@link AudioTrack}.
         * See {@link AudioFormat.Builder} for configuring the audio format parameters such
         * as encoding, channel mask and sample rate.
         * @param format a non-null {@link AudioFormat} instance.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setAudioFormat(@NonNull AudioFormat format)
                throws IllegalArgumentException {
            if (format == null) {
                throw new IllegalArgumentException("Illegal null AudioFormat argument");
            }
            // keep reference, we only copy the data when building
            mFormat = format;
            return this;
        }

        /**
         * Sets the total size (in bytes) of the buffer where audio data is read from for playback.
         * If using the {@link AudioTrack} in streaming mode
         * (see {@link AudioTrack#MODE_STREAM}, you can write data into this buffer in smaller
         * chunks than this size. See {@link #getMinBufferSize(int, int, int)} to determine
         * the minimum required buffer size for the successful creation of an AudioTrack instance
         * in streaming mode. Using values smaller than <code>getMinBufferSize()</code> will result
         * in an exception when trying to build the <code>AudioTrack</code>.
         * <br>If using the <code>AudioTrack</code> in static mode (see
         * {@link AudioTrack#MODE_STATIC}), this is the maximum size of the sound that will be
         * played by this instance.
         * @param bufferSizeInBytes
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setBufferSizeInBytes(int bufferSizeInBytes)
                throws IllegalArgumentException {
            if (bufferSizeInBytes <= 0) {
                throw new IllegalArgumentException("Invalid buffer size " + bufferSizeInBytes);
            }
            mBufferSizeInBytes = bufferSizeInBytes;
            return this;
        }

        /**
         * Sets the mode under which buffers of audio data are transferred from the
         * {@link AudioTrack} to the framework.
         * @param mode one of {@link AudioTrack#MODE_STREAM}, {@link AudioTrack#MODE_STATIC}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setTransferMode(@TransferMode int mode)
                throws IllegalArgumentException {
            switch(mode) {
                case MODE_STREAM:
                case MODE_STATIC:
                    mMode = mode;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid transfer mode " + mode);
            }
            return this;
        }

        /**
         * Sets the session ID the {@link AudioTrack} will be attached to.
         * @param sessionId a strictly positive ID number retrieved from another
         *     <code>AudioTrack</code> via {@link AudioTrack#getAudioSessionId()} or allocated by
         *     {@link AudioManager} via {@link AudioManager#generateAudioSessionId()}, or
         *     {@link AudioManager#AUDIO_SESSION_ID_GENERATE}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setSessionId(int sessionId)
                throws IllegalArgumentException {
            if ((sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE) && (sessionId < 1)) {
                throw new IllegalArgumentException("Invalid audio session ID " + sessionId);
            }
            mSessionId = sessionId;
            return this;
        }

        /**
         * Builds an {@link AudioTrack} instance initialized with all the parameters set
         * on this <code>Builder</code>.
         * @return a new successfully initialized {@link AudioTrack} instance.
         * @throws UnsupportedOperationException if the parameters set on the <code>Builder</code>
         *     were incompatible, or if they are not supported by the device,
         *     or if the device was not available.
         */
        public @NonNull AudioTrack build() throws UnsupportedOperationException {
            if (mAttributes == null) {
                mAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build();
            }
            if (mFormat == null) {
                mFormat = new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setSampleRate(AudioSystem.getPrimaryOutputSamplingRate())
                        .setEncoding(AudioFormat.ENCODING_DEFAULT)
                        .build();
            }
            try {
                // If the buffer size is not specified in streaming mode,
                // use a single frame for the buffer size and let the
                // native code figure out the minimum buffer size.
                if (mMode == MODE_STREAM && mBufferSizeInBytes == 0) {
                    mBufferSizeInBytes = mFormat.getChannelCount()
                            * mFormat.getBytesPerSample(mFormat.getEncoding());
                }
                final AudioTrack track = new AudioTrack(
                        mAttributes, mFormat, mBufferSizeInBytes, mMode, mSessionId);
                if (track.getState() == STATE_UNINITIALIZED) {
                    // release is not necessary
                    throw new UnsupportedOperationException("Cannot create AudioTrack");
                }
                return track;
            } catch (IllegalArgumentException e) {
                throw new UnsupportedOperationException(e.getMessage());
            }
        }
    }

    // mask of all the positional channels supported, however the allowed combinations
    // are further restricted by the matching left/right rule and CHANNEL_COUNT_MAX
    private static final int SUPPORTED_OUT_CHANNELS =
            AudioFormat.CHANNEL_OUT_FRONT_LEFT |
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT |
            AudioFormat.CHANNEL_OUT_FRONT_CENTER |
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY |
            AudioFormat.CHANNEL_OUT_BACK_LEFT |
            AudioFormat.CHANNEL_OUT_BACK_RIGHT |
            AudioFormat.CHANNEL_OUT_BACK_CENTER |
            AudioFormat.CHANNEL_OUT_SIDE_LEFT |
            AudioFormat.CHANNEL_OUT_SIDE_RIGHT;

    // Convenience method for the constructor's parameter checks.
    // This is where constructor IllegalArgumentException-s are thrown
    // postconditions:
    //    mChannelCount is valid
    //    mChannelMask is valid
    //    mAudioFormat is valid
    //    mSampleRate is valid
    //    mDataLoadMode is valid
    private void audioParamCheck(int sampleRateInHz, int channelConfig, int channelIndexMask,
                                 int audioFormat, int mode) {
        //--------------
        // sample rate, note these values are subject to change
        if (sampleRateInHz < SAMPLE_RATE_HZ_MIN || sampleRateInHz > SAMPLE_RATE_HZ_MAX) {
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
            mChannelMask = AudioFormat.CHANNEL_OUT_MONO;
            break;
        case AudioFormat.CHANNEL_OUT_STEREO:
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            mChannelCount = 2;
            mChannelMask = AudioFormat.CHANNEL_OUT_STEREO;
            break;
        default:
            if (channelConfig == AudioFormat.CHANNEL_INVALID && channelIndexMask != 0) {
                mChannelCount = 0;
                break; // channel index configuration only
            }
            if (!isMultichannelConfigSupported(channelConfig)) {
                // input channel configuration features unsupported channels
                throw new IllegalArgumentException("Unsupported channel configuration.");
            }
            mChannelMask = channelConfig;
            mChannelCount = AudioFormat.channelCountFromOutChannelMask(channelConfig);
        }
        // check the channel index configuration (if present)
        mChannelIndexMask = channelIndexMask;
        if (mChannelIndexMask != 0) {
            // restrictive: indexMask could allow up to AUDIO_CHANNEL_BITS_LOG2
            final int indexMask = (1 << CHANNEL_COUNT_MAX) - 1;
            if ((channelIndexMask & ~indexMask) != 0) {
                throw new IllegalArgumentException("Unsupported channel index configuration "
                        + channelIndexMask);
            }
            int channelIndexCount = Integer.bitCount(channelIndexMask);
            if (mChannelCount == 0) {
                 mChannelCount = channelIndexCount;
            } else if (mChannelCount != channelIndexCount) {
                throw new IllegalArgumentException("Channel count must match");
            }
        }

        //--------------
        // audio format
        if (audioFormat == AudioFormat.ENCODING_DEFAULT) {
            audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        }

        if (!AudioFormat.isPublicEncoding(audioFormat)) {
            throw new IllegalArgumentException("Unsupported audio encoding.");
        }
        mAudioFormat = audioFormat;

        //--------------
        // audio load mode
        if (((mode != MODE_STREAM) && (mode != MODE_STATIC)) ||
                ((mode != MODE_STREAM) && !AudioFormat.isEncodingLinearPcm(mAudioFormat))) {
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
        final int channelCount = AudioFormat.channelCountFromOutChannelMask(channelConfig);
        if (channelCount > CHANNEL_COUNT_MAX) {
            loge("Channel configuration contains too many channels " +
                    channelCount + ">" + CHANNEL_COUNT_MAX);
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
        final int sidePair =
                AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT;
        if ((channelConfig & sidePair) != 0
                && (channelConfig & sidePair) != sidePair) {
            loge("Side channels can't be used independently");
            return false;
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
        int frameSizeInBytes;
        if (AudioFormat.isEncodingLinearPcm(mAudioFormat)) {
            frameSizeInBytes = mChannelCount * AudioFormat.getBytesPerSample(mAudioFormat);
        } else {
            frameSizeInBytes = 1;
        }
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
     * Returns the minimum gain value, which is the constant 0.0.
     * Gain values less than 0.0 will be clamped to 0.0.
     * <p>The word "volume" in the API name is historical; this is actually a linear gain.
     * @return the minimum value, which is the constant 0.0.
     */
    static public float getMinVolume() {
        return GAIN_MIN;
    }

    /**
     * Returns the maximum gain value, which is greater than or equal to 1.0.
     * Gain values greater than the maximum will be clamped to the maximum.
     * <p>The word "volume" in the API name is historical; this is actually a gain.
     * expressed as a linear multiplier on sample values, where a maximum value of 1.0
     * corresponds to a gain of 0 dB (sample values left unmodified).
     * @return the maximum value, which is greater than or equal to 1.0.
     */
    static public float getMaxVolume() {
        return GAIN_MAX;
    }

    /**
     * Returns the configured audio data sample rate in Hz
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the current playback sample rate rate in Hz.
     */
    public int getPlaybackRate() {
        return native_get_playback_rate();
    }

    /**
     * Returns the current playback parameters.
     * See {@link #setPlaybackParams(PlaybackParams)} to set playback parameters
     * @return current {@link PlaybackParams}.
     * @throws IllegalStateException if track is not initialized.
     */
    public @NonNull PlaybackParams getPlaybackParams() {
        return native_get_playback_params();
    }

    /**
     * Returns the configured audio data encoding. See {@link AudioFormat#ENCODING_PCM_8BIT},
     * {@link AudioFormat#ENCODING_PCM_16BIT}, and {@link AudioFormat#ENCODING_PCM_FLOAT}.
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
     * Returns the configured channel position mask.
     * <p> For example, refer to {@link AudioFormat#CHANNEL_OUT_MONO},
     * {@link AudioFormat#CHANNEL_OUT_STEREO}, {@link AudioFormat#CHANNEL_OUT_5POINT1}.
     * This method may return {@link AudioFormat#CHANNEL_INVALID} if
     * a channel index mask was used. Consider
     * {@link #getFormat()} instead, to obtain an {@link AudioFormat},
     * which contains both the channel position mask and the channel index mask.
     */
    public int getChannelConfiguration() {
        return mChannelConfiguration;
    }

    /**
     * Returns the configured <code>AudioTrack</code> format.
     * @return an {@link AudioFormat} containing the
     * <code>AudioTrack</code> parameters at the time of configuration.
     */
    public @NonNull AudioFormat getFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder()
            .setSampleRate(mSampleRate)
            .setEncoding(mAudioFormat);
        if (mChannelConfiguration != AudioFormat.CHANNEL_INVALID) {
            builder.setChannelMask(mChannelConfiguration);
        }
        if (mChannelIndexMask != AudioFormat.CHANNEL_INVALID /* 0 */) {
            builder.setChannelIndexMask(mChannelIndexMask);
        }
        return builder.build();
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
     * @see #STATE_UNINITIALIZED
     * @see #STATE_INITIALIZED
     * @see #STATE_NO_STATIC_DATA
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
     *  Returns the frame count of the native <code>AudioTrack</code> buffer.
     *  <p> If the track's creation mode is {@link #MODE_STATIC},
     *  it is equal to the specified bufferSizeInBytes on construction, converted to frame units.
     *  A static track's native frame count will not change.
     *  <p> If the track's creation mode is {@link #MODE_STREAM},
     *  it is greater than or equal to the specified bufferSizeInBytes converted to frame units.
     *  For streaming tracks, this value may be rounded up to a larger value if needed by
     *  the target output sink, and
     *  if the track is subsequently routed to a different output sink, the native
     *  frame count may enlarge to accommodate.
     *  <p> If the <code>AudioTrack</code> encoding indicates compressed data,
     *  e.g. {@link AudioFormat#ENCODING_AC3}, then the frame count returned is
     *  the size of the native <code>AudioTrack</code> buffer in bytes.
     *  <p> See also {@link AudioManager#getProperty(String)} for key
     *  {@link AudioManager#PROPERTY_OUTPUT_FRAMES_PER_BUFFER}.
     *  @return current size in frames of the <code>AudioTrack</code> buffer.
     *  @throws IllegalStateException
     */
    public int getBufferSizeInFrames() {
        return native_get_native_frame_count();
    }

    /**
     *  Returns the frame count of the native <code>AudioTrack</code> buffer.
     *  @return current size in frames of the <code>AudioTrack</code> buffer.
     *  @throws IllegalStateException
     *  @deprecated Use the identical public method {@link #getBufferSizeInFrames()} instead.
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
     * It is reset to zero by {@link #flush()}, {@link #reloadStaticData()}, and {@link #stop()}.
     * If the track's creation mode is {@link #MODE_STATIC}, the return value indicates
     * the total number of frames played since reset,
     * <i>not</i> the current offset within the buffer.
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
     *   {@link AudioFormat#ENCODING_PCM_8BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
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
            if (!isMultichannelConfigSupported(channelConfig)) {
                loge("getMinBufferSize(): Invalid channel configuration.");
                return ERROR_BAD_VALUE;
            } else {
                channelCount = AudioFormat.channelCountFromOutChannelMask(channelConfig);
            }
        }

        if (!AudioFormat.isPublicEncoding(audioFormat)) {
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
    * <p>
    * If you need to track timestamps during initial warmup or after a routing or mode change,
    * you should request a new timestamp periodically until the reported timestamps
    * show that the frame position is advancing, or until it becomes clear that
    * timestamps are unavailable for this route.
    * <p>
    * After the clock is advancing at a stable rate,
    * query for a new timestamp approximately once every 10 seconds to once per minute.
    * Calling this method more often is inefficient.
    * It is also counter-productive to call this method more often than recommended,
    * because the short-term differences between successive timestamp reports are not meaningful.
    * If you need a high-resolution mapping between frame position and presentation time,
    * consider implementing that at application level, based on low-resolution timestamps.
    * <p>
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
    *         A timestamp may be temporarily unavailable while the audio clock is stabilizing,
    *         or during and immediately after a route change.
    *         A timestamp is permanently unavailable for a given route if the route does not support
    *         timestamps.  In this case, the approximate frame position can be obtained
    *         using {@link #getPlaybackHeadPosition}.
    *         However, it may be useful to continue to query for
    *         timestamps occasionally, to recover after a route change.
    */
    // Add this text when the "on new timestamp" API is added:
    //   Use if you need to get the most recent timestamp outside of the event callback handler.
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
            mEventHandlerDelegate = new NativePositionEventHandlerDelegate(this, listener, handler);
        } else {
            mEventHandlerDelegate = null;
        }
    }


    private static float clampGainOrLevel(float gainOrLevel) {
        if (Float.isNaN(gainOrLevel)) {
            throw new IllegalArgumentException();
        }
        if (gainOrLevel < GAIN_MIN) {
            gainOrLevel = GAIN_MIN;
        } else if (gainOrLevel > GAIN_MAX) {
            gainOrLevel = GAIN_MAX;
        }
        return gainOrLevel;
    }


     /**
     * Sets the specified left and right output gain values on the AudioTrack.
     * <p>Gain values are clamped to the closed interval [0.0, max] where
     * max is the value of {@link #getMaxVolume}.
     * A value of 0.0 results in zero gain (silence), and
     * a value of 1.0 means unity gain (signal unchanged).
     * The default value is 1.0 meaning unity gain.
     * <p>The word "volume" in the API name is historical; this is actually a linear gain.
     * @param leftGain output gain for the left channel.
     * @param rightGain output gain for the right channel
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}
     * @deprecated Applications should use {@link #setVolume} instead, as it
     * more gracefully scales down to mono, and up to multi-channel content beyond stereo.
     */
    public int setStereoVolume(float leftGain, float rightGain) {
        if (isRestricted()) {
            return SUCCESS;
        }
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        leftGain = clampGainOrLevel(leftGain);
        rightGain = clampGainOrLevel(rightGain);

        native_setVolume(leftGain, rightGain);

        return SUCCESS;
    }


    /**
     * Sets the specified output gain value on all channels of this track.
     * <p>Gain values are clamped to the closed interval [0.0, max] where
     * max is the value of {@link #getMaxVolume}.
     * A value of 0.0 results in zero gain (silence), and
     * a value of 1.0 means unity gain (signal unchanged).
     * The default value is 1.0 meaning unity gain.
     * <p>This API is preferred over {@link #setStereoVolume}, as it
     * more gracefully scales down to mono, and up to multi-channel content beyond stereo.
     * <p>The word "volume" in the API name is historical; this is actually a linear gain.
     * @param gain output gain for all channels.
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setVolume(float gain) {
        return setStereoVolume(gain, gain);
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
     * Use {@link #setPlaybackParams(PlaybackParams)} for speed control.
     * <p> This method may also be used to repurpose an existing <code>AudioTrack</code>
     * for playback of content of differing sample rate,
     * but with identical encoding and channel mask.
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
     * Sets the playback parameters.
     * This method returns failure if it cannot apply the playback parameters.
     * One possible cause is that the parameters for speed or pitch are out of range.
     * Another possible cause is that the <code>AudioTrack</code> is streaming
     * (see {@link #MODE_STREAM}) and the
     * buffer size is too small. For speeds greater than 1.0f, the <code>AudioTrack</code> buffer
     * on configuration must be larger than the speed multiplied by the minimum size
     * {@link #getMinBufferSize(int, int, int)}) to allow proper playback.
     * @param params see {@link PlaybackParams}. In particular,
     * speed, pitch, and audio mode should be set.
     * @throws IllegalArgumentException if the parameters are invalid or not accepted.
     * @throws IllegalStateException if track is not initialized.
     */
    public void setPlaybackParams(@NonNull PlaybackParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params is null");
        }
        native_set_playback_params(params);
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
     * @param periodInFrames update period expressed in frames.
     * Zero period means no position updates.  A negative period is not allowed.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_INVALID_OPERATION}
     */
    public int setPositionNotificationPeriod(int periodInFrames) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_set_pos_update_period(periodInFrames);
    }


    /**
     * Sets the playback head position within the static buffer.
     * The track must be stopped or paused for the position to be changed,
     * and must use the {@link #MODE_STATIC} mode.
     * @param positionInFrames playback head position within buffer, expressed in frames.
     * Zero corresponds to start of buffer.
     * The position must not be greater than the buffer size in frames, or negative.
     * Though this method and {@link #getPlaybackHeadPosition()} have similar names,
     * the position values have different meanings.
     * <br>
     * If looping is currently enabled and the new position is greater than or equal to the
     * loop end marker, the behavior varies by API level:
     * as of {@link android.os.Build.VERSION_CODES#M},
     * the looping is first disabled and then the position is set.
     * For earlier API levels, the behavior is unspecified.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setPlaybackHeadPosition(int positionInFrames) {
        if (mDataLoadMode == MODE_STREAM || mState == STATE_UNINITIALIZED ||
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
     * @param startInFrames loop start marker expressed in frames.
     * Zero corresponds to start of buffer.
     * The start marker must not be greater than or equal to the buffer size in frames, or negative.
     * @param endInFrames loop end marker expressed in frames.
     * The total buffer size in frames corresponds to end of buffer.
     * The end marker must not be greater than the buffer size in frames.
     * For looping, the end marker must not be less than or equal to the start marker,
     * but to disable looping
     * it is permitted for start marker, end marker, and loop count to all be 0.
     * If any input parameters are out of range, this method returns {@link #ERROR_BAD_VALUE}.
     * If the loop period (endInFrames - startInFrames) is too small for the implementation to
     * support,
     * {@link #ERROR_BAD_VALUE} is returned.
     * The loop range is the interval [startInFrames, endInFrames).
     * <br>
     * As of {@link android.os.Build.VERSION_CODES#M}, the position is left unchanged,
     * unless it is greater than or equal to the loop end marker, in which case
     * it is forced to the loop start marker.
     * For earlier API levels, the effect on position is unspecified.
     * @param loopCount the number of times the loop is looped; must be greater than or equal to -1.
     *    A value of -1 means infinite looping, and 0 disables looping.
     *    A value of positive N means to "loop" (go back) N times.  For example,
     *    a value of one means to play the region two times in total.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *    {@link #ERROR_INVALID_OPERATION}
     */
    public int setLoopPoints(int startInFrames, int endInFrames, int loopCount) {
        if (mDataLoadMode == MODE_STREAM || mState == STATE_UNINITIALIZED ||
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
     * <p>
     * If track's creation mode is {@link #MODE_STATIC}, you must have called one of
     * the write methods ({@link #write(byte[], int, int)}, {@link #write(byte[], int, int, int)},
     * {@link #write(short[], int, int)}, {@link #write(short[], int, int, int)},
     * {@link #write(float[], int, int, int)}, or {@link #write(ByteBuffer, int, int)}) prior to
     * play().
     * <p>
     * If the mode is {@link #MODE_STREAM}, you can optionally prime the data path prior to
     * calling play(), by writing up to <code>bufferSizeInBytes</code> (from constructor).
     * If you don't call write() first, or if you call write() but with an insufficient amount of
     * data, then the track will be in underrun state at play().  In this case,
     * playback will not actually start playing until the data path is filled to a
     * device-specific minimum level.  This requirement for the path to be filled
     * to a minimum level is also true when resuming audio playback after calling stop().
     * Similarly the buffer will need to be filled up again after
     * the track underruns due to failure to call write() in a timely manner with sufficient data.
     * For portability, an application should prime the data path to the maximum allowed
     * by writing data until the write() method returns a short transfer count.
     * This allows play() to start immediately, and reduces the chance of underrun.
     *
     * @throws IllegalStateException if the track isn't properly initialized
     */
    public void play()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("play() called on uninitialized AudioTrack.");
        }
        if (isRestricted()) {
            setVolume(0);
        }
        synchronized(mPlayStateLock) {
            native_start();
            mPlayState = PLAYSTATE_PLAYING;
        }
    }

    private boolean isRestricted() {
        if ((mAttributes.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
            return false;
        }
        try {
            final int usage = AudioAttributes.usageForLegacyStreamType(mStreamType);
            final int mode = mAppOps.checkAudioOperation(AppOpsManager.OP_PLAY_AUDIO, usage,
                    Process.myUid(), ActivityThread.currentPackageName());
            return mode != AppOpsManager.MODE_ALLOWED;
        } catch (RemoteException e) {
            return false;
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
            mAvSyncHeader = null;
            mAvSyncBytesRemaining = 0;
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
     * been written but not yet presented will be discarded.  No-op if not stopped or paused,
     * or if the track's creation mode is not {@link #MODE_STREAM}.
     * <BR> Note that although data written but not yet presented is discarded, there is no
     * guarantee that all of the buffer space formerly used by that data
     * is available for a subsequent write.
     * For example, a call to {@link #write(byte[], int, int)} with <code>sizeInBytes</code>
     * less than or equal to the total buffer size
     * may return a short actual transfer count.
     */
    public void flush() {
        if (mState == STATE_INITIALIZED) {
            // flush the data in native layer
            native_flush();
            mAvSyncHeader = null;
            mAvSyncBytesRemaining = 0;
        }

    }

    /**
     * Writes the audio data to the audio sink for playback (streaming mode),
     * or copies audio data for later playback (static buffer mode).
     * The format specified in the AudioTrack constructor should be
     * {@link AudioFormat#ENCODING_PCM_8BIT} to correspond to the data in the array.
     * <p>
     * In streaming mode, the write will normally block until all the data has been enqueued for
     * playback, and will return a full transfer count.  However, if the track is stopped or paused
     * on entry, or another thread interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInBytes the offset expressed in bytes in audioData where the data to play
     *    starts.
     * @param sizeInBytes the number of bytes to read in audioData after the offset.
     * @return zero or the positive number of bytes that were written, or
     *    {@link #ERROR_INVALID_OPERATION}
     *    if the track isn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes, or
     *    {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated.
     *    The dead object error code is not returned if some data was successfully transferred.
     *    In this case, the error is returned at the next write().
     *
     * This is equivalent to {@link #write(byte[], int, int, int)} with <code>writeMode</code>
     * set to  {@link #WRITE_BLOCKING}.
     */
    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
    }

    /**
     * Writes the audio data to the audio sink for playback (streaming mode),
     * or copies audio data for later playback (static buffer mode).
     * The format specified in the AudioTrack constructor should be
     * {@link AudioFormat#ENCODING_PCM_8BIT} to correspond to the data in the array.
     * <p>
     * In streaming mode, the blocking behavior depends on the write mode.  If the write mode is
     * {@link #WRITE_BLOCKING}, the write will normally block until all the data has been enqueued
     * for playback, and will return a full transfer count.  However, if the write mode is
     * {@link #WRITE_NON_BLOCKING}, or the track is stopped or paused on entry, or another thread
     * interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0,
     * and the write mode is ignored.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInBytes the offset expressed in bytes in audioData where the data to play
     *    starts.
     * @param sizeInBytes the number of bytes to read in audioData after the offset.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of bytes that were written, or
     *    {@link #ERROR_INVALID_OPERATION}
     *    if the track isn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes, or
     *    {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated.
     *    The dead object error code is not returned if some data was successfully transferred.
     *    In this case, the error is returned at the next write().
     */
    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes,
            @WriteMode int writeMode) {

        if (mState == STATE_UNINITIALIZED || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return ERROR_INVALID_OPERATION;
        }

        if ((writeMode != WRITE_BLOCKING) && (writeMode != WRITE_NON_BLOCKING)) {
            Log.e(TAG, "AudioTrack.write() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInBytes < 0 ) || (sizeInBytes < 0)
                || (offsetInBytes + sizeInBytes < 0)    // detect integer overflow
                || (offsetInBytes + sizeInBytes > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        int ret = native_write_byte(audioData, offsetInBytes, sizeInBytes, mAudioFormat,
                writeMode == WRITE_BLOCKING);

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
     * The format specified in the AudioTrack constructor should be
     * {@link AudioFormat#ENCODING_PCM_16BIT} to correspond to the data in the array.
     * <p>
     * In streaming mode, the write will normally block until all the data has been enqueued for
     * playback, and will return a full transfer count.  However, if the track is stopped or paused
     * on entry, or another thread interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInShorts the offset expressed in shorts in audioData where the data to play
     *     starts.
     * @param sizeInShorts the number of shorts to read in audioData after the offset.
     * @return zero or the positive number of shorts that were written, or
     *    {@link #ERROR_INVALID_OPERATION}
     *    if the track isn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes, or
     *    {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated.
     *    The dead object error code is not returned if some data was successfully transferred.
     *    In this case, the error is returned at the next write().
     *
     * This is equivalent to {@link #write(short[], int, int, int)} with <code>writeMode</code>
     * set to  {@link #WRITE_BLOCKING}.
     */
    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
        return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
    }

    /**
     * Writes the audio data to the audio sink for playback (streaming mode),
     * or copies audio data for later playback (static buffer mode).
     * The format specified in the AudioTrack constructor should be
     * {@link AudioFormat#ENCODING_PCM_16BIT} to correspond to the data in the array.
     * <p>
     * In streaming mode, the blocking behavior depends on the write mode.  If the write mode is
     * {@link #WRITE_BLOCKING}, the write will normally block until all the data has been enqueued
     * for playback, and will return a full transfer count.  However, if the write mode is
     * {@link #WRITE_NON_BLOCKING}, or the track is stopped or paused on entry, or another thread
     * interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the array that holds the data to play.
     * @param offsetInShorts the offset expressed in shorts in audioData where the data to play
     *     starts.
     * @param sizeInShorts the number of shorts to read in audioData after the offset.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of shorts that were written, or
     *    {@link #ERROR_INVALID_OPERATION}
     *    if the track isn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes, or
     *    {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated.
     *    The dead object error code is not returned if some data was successfully transferred.
     *    In this case, the error is returned at the next write().
     */
    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
            @WriteMode int writeMode) {

        if (mState == STATE_UNINITIALIZED || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return ERROR_INVALID_OPERATION;
        }

        if ((writeMode != WRITE_BLOCKING) && (writeMode != WRITE_NON_BLOCKING)) {
            Log.e(TAG, "AudioTrack.write() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInShorts < 0 ) || (sizeInShorts < 0)
                || (offsetInShorts + sizeInShorts < 0)  // detect integer overflow
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        int ret = native_write_short(audioData, offsetInShorts, sizeInShorts, mAudioFormat,
                writeMode == WRITE_BLOCKING);

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
     * The format specified in the AudioTrack constructor should be
     * {@link AudioFormat#ENCODING_PCM_FLOAT} to correspond to the data in the array.
     * <p>
     * In streaming mode, the blocking behavior depends on the write mode.  If the write mode is
     * {@link #WRITE_BLOCKING}, the write will normally block until all the data has been enqueued
     * for playback, and will return a full transfer count.  However, if the write mode is
     * {@link #WRITE_NON_BLOCKING}, or the track is stopped or paused on entry, or another thread
     * interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0,
     * and the write mode is ignored.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the array that holds the data to play.
     *     The implementation does not clip for sample values within the nominal range
     *     [-1.0f, 1.0f], provided that all gains in the audio pipeline are
     *     less than or equal to unity (1.0f), and in the absence of post-processing effects
     *     that could add energy, such as reverb.  For the convenience of applications
     *     that compute samples using filters with non-unity gain,
     *     sample values +3 dB beyond the nominal range are permitted.
     *     However such values may eventually be limited or clipped, depending on various gains
     *     and later processing in the audio path.  Therefore applications are encouraged
     *     to provide samples values within the nominal range.
     * @param offsetInFloats the offset, expressed as a number of floats,
     *     in audioData where the data to play starts.
     * @param sizeInFloats the number of floats to read in audioData after the offset.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of floats that were written, or
     *    {@link #ERROR_INVALID_OPERATION}
     *    if the track isn't properly initialized, or {@link #ERROR_BAD_VALUE} if
     *    the parameters don't resolve to valid data and indexes, or
     *    {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated.
     *    The dead object error code is not returned if some data was successfully transferred.
     *    In this case, the error is returned at the next write().
     */
    public int write(@NonNull float[] audioData, int offsetInFloats, int sizeInFloats,
            @WriteMode int writeMode) {

        if (mState == STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioTrack.write() called in invalid state STATE_UNINITIALIZED");
            return ERROR_INVALID_OPERATION;
        }

        if (mAudioFormat != AudioFormat.ENCODING_PCM_FLOAT) {
            Log.e(TAG, "AudioTrack.write(float[] ...) requires format ENCODING_PCM_FLOAT");
            return ERROR_INVALID_OPERATION;
        }

        if ((writeMode != WRITE_BLOCKING) && (writeMode != WRITE_NON_BLOCKING)) {
            Log.e(TAG, "AudioTrack.write() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInFloats < 0 ) || (sizeInFloats < 0)
                || (offsetInFloats + sizeInFloats < 0)  // detect integer overflow
                || (offsetInFloats + sizeInFloats > audioData.length)) {
            Log.e(TAG, "AudioTrack.write() called with invalid array, offset, or size");
            return ERROR_BAD_VALUE;
        }

        int ret = native_write_float(audioData, offsetInFloats, sizeInFloats, mAudioFormat,
                writeMode == WRITE_BLOCKING);

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
     * The audioData in ByteBuffer should match the format specified in the AudioTrack constructor.
     * <p>
     * In streaming mode, the blocking behavior depends on the write mode.  If the write mode is
     * {@link #WRITE_BLOCKING}, the write will normally block until all the data has been enqueued
     * for playback, and will return a full transfer count.  However, if the write mode is
     * {@link #WRITE_NON_BLOCKING}, or the track is stopped or paused on entry, or another thread
     * interrupts the write by calling stop or pause, or an I/O error
     * occurs during the write, then the write may return a short transfer count.
     * <p>
     * In static buffer mode, copies the data to the buffer starting at offset 0,
     * and the write mode is ignored.
     * Note that the actual playback of this data might occur after this function returns.
     *
     * @param audioData the buffer that holds the data to play, starting at the position reported
     *     by <code>audioData.position()</code>.
     *     <BR>Note that upon return, the buffer position (<code>audioData.position()</code>) will
     *     have been advanced to reflect the amount of data that was successfully written to
     *     the AudioTrack.
     * @param sizeInBytes number of bytes to write.
     *     <BR>Note this may differ from <code>audioData.remaining()</code>, but cannot exceed it.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <BR>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <BR>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of bytes that were written, or
     *     {@link #ERROR_BAD_VALUE}, {@link #ERROR_INVALID_OPERATION}, or
     *     {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *     needs to be recreated.
     *     The dead object error code is not returned if some data was successfully transferred.
     *     In this case, the error is returned at the next write().
     */
    public int write(@NonNull ByteBuffer audioData, int sizeInBytes,
            @WriteMode int writeMode) {

        if (mState == STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioTrack.write() called in invalid state STATE_UNINITIALIZED");
            return ERROR_INVALID_OPERATION;
        }

        if ((writeMode != WRITE_BLOCKING) && (writeMode != WRITE_NON_BLOCKING)) {
            Log.e(TAG, "AudioTrack.write() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (sizeInBytes < 0) || (sizeInBytes > audioData.remaining())) {
            Log.e(TAG, "AudioTrack.write() called with invalid size (" + sizeInBytes + ") value");
            return ERROR_BAD_VALUE;
        }

        int ret = 0;
        if (audioData.isDirect()) {
            ret = native_write_native_bytes(audioData,
                    audioData.position(), sizeInBytes, mAudioFormat,
                    writeMode == WRITE_BLOCKING);
        } else {
            ret = native_write_byte(NioUtils.unsafeArray(audioData),
                    NioUtils.unsafeArrayOffset(audioData) + audioData.position(),
                    sizeInBytes, mAudioFormat,
                    writeMode == WRITE_BLOCKING);
        }

        if ((mDataLoadMode == MODE_STATIC)
                && (mState == STATE_NO_STATIC_DATA)
                && (ret > 0)) {
            // benign race with respect to other APIs that read mState
            mState = STATE_INITIALIZED;
        }

        if (ret > 0) {
            audioData.position(audioData.position() + ret);
        }

        return ret;
    }

    /**
     * Writes the audio data to the audio sink for playback in streaming mode on a HW_AV_SYNC track.
     * The blocking behavior will depend on the write mode.
     * @param audioData the buffer that holds the data to play, starting at the position reported
     *     by <code>audioData.position()</code>.
     *     <BR>Note that upon return, the buffer position (<code>audioData.position()</code>) will
     *     have been advanced to reflect the amount of data that was successfully written to
     *     the AudioTrack.
     * @param sizeInBytes number of bytes to write.
     *     <BR>Note this may differ from <code>audioData.remaining()</code>, but cannot exceed it.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}.
     *     <BR>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <BR>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @param timestamp The timestamp of the first decodable audio frame in the provided audioData.
     * @return zero or a positive number of bytes that were written, or
     *     {@link #ERROR_BAD_VALUE}, {@link #ERROR_INVALID_OPERATION}, or
     *     {@link AudioManager#ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *     needs to be recreated.
     *     The dead object error code is not returned if some data was successfully transferred.
     *     In this case, the error is returned at the next write().
     */
    public int write(@NonNull ByteBuffer audioData, int sizeInBytes,
            @WriteMode int writeMode, long timestamp) {

        if (mState == STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioTrack.write() called in invalid state STATE_UNINITIALIZED");
            return ERROR_INVALID_OPERATION;
        }

        if ((writeMode != WRITE_BLOCKING) && (writeMode != WRITE_NON_BLOCKING)) {
            Log.e(TAG, "AudioTrack.write() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if (mDataLoadMode != MODE_STREAM) {
            Log.e(TAG, "AudioTrack.write() with timestamp called for non-streaming mode track");
            return ERROR_INVALID_OPERATION;
        }

        if ((mAttributes.getFlags() & AudioAttributes.FLAG_HW_AV_SYNC) == 0) {
            Log.d(TAG, "AudioTrack.write() called on a regular AudioTrack. Ignoring pts...");
            return write(audioData, sizeInBytes, writeMode);
        }

        if ((audioData == null) || (sizeInBytes < 0) || (sizeInBytes > audioData.remaining())) {
            Log.e(TAG, "AudioTrack.write() called with invalid size (" + sizeInBytes + ") value");
            return ERROR_BAD_VALUE;
        }

        // create timestamp header if none exists
        if (mAvSyncHeader == null) {
            mAvSyncHeader = ByteBuffer.allocate(16);
            mAvSyncHeader.order(ByteOrder.BIG_ENDIAN);
            mAvSyncHeader.putInt(0x55550001);
            mAvSyncHeader.putInt(sizeInBytes);
            mAvSyncHeader.putLong(timestamp);
            mAvSyncHeader.position(0);
            mAvSyncBytesRemaining = sizeInBytes;
        }

        // write timestamp header if not completely written already
        int ret = 0;
        if (mAvSyncHeader.remaining() != 0) {
            ret = write(mAvSyncHeader, mAvSyncHeader.remaining(), writeMode);
            if (ret < 0) {
                Log.e(TAG, "AudioTrack.write() could not write timestamp header!");
                mAvSyncHeader = null;
                mAvSyncBytesRemaining = 0;
                return ret;
            }
            if (mAvSyncHeader.remaining() > 0) {
                Log.v(TAG, "AudioTrack.write() partial timestamp header written.");
                return 0;
            }
        }

        // write audio data
        int sizeToWrite = Math.min(mAvSyncBytesRemaining, sizeInBytes);
        ret = write(audioData, sizeToWrite, writeMode);
        if (ret < 0) {
            Log.e(TAG, "AudioTrack.write() could not write audio data!");
            mAvSyncHeader = null;
            mAvSyncBytesRemaining = 0;
            return ret;
        }

        mAvSyncBytesRemaining -= ret;
        if (mAvSyncBytesRemaining == 0) {
            mAvSyncHeader = null;
        }

        return ret;
    }


    /**
     * Sets the playback head position within the static buffer to zero,
     * that is it rewinds to start of static buffer.
     * The track must be stopped or paused, and
     * the track's creation mode must be {@link #MODE_STATIC}.
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#M}, also resets the value returned by
     * {@link #getPlaybackHeadPosition()} to zero.
     * For earlier API levels, the reset behavior is unspecified.
     * <p>
     * Use {@link #setPlaybackHeadPosition(int)} with a zero position
     * if the reset of <code>getPlaybackHeadPosition()</code> is not needed.
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
     * {@link #attachAuxEffect(int)}.  Effect levels
     * are clamped to the closed interval [0.0, max] where
     * max is the value of {@link #getMaxVolume}.
     * A value of 0.0 results in no effect, and a value of 1.0 is full send.
     * <p>By default the send level is 0.0f, so even if an effect is attached to the player
     * this method must be called for the effect to be applied.
     * <p>Note that the passed level value is a linear scalar. UI controls should be scaled
     * logarithmically: the gain applied by audio framework ranges from -72dB to at least 0dB,
     * so an appropriate conversion from linear UI input x to level is:
     * x == 0 -&gt; level = 0
     * 0 &lt; x &lt;= R -&gt; level = 10^(72*(x-R)/20/R)
     *
     * @param level linear send level
     * @return error code or success, see {@link #SUCCESS},
     *    {@link #ERROR_INVALID_OPERATION}, {@link #ERROR}
     */
    public int setAuxEffectSendLevel(float level) {
        if (isRestricted()) {
            return SUCCESS;
        }
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        level = clampGainOrLevel(level);
        int err = native_setAuxEffectSendLevel(level);
        return err == 0 ? SUCCESS : ERROR;
    }

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the output from this AudioTrack.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio sink.
     *  If deviceInfo is null, default routing is restored.
     * @return true if succesful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio output device.
     */
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        // Do some validation....
        if (deviceInfo != null && !deviceInfo.isSink()) {
            return false;
        }
        int preferredDeviceId = deviceInfo != null ? deviceInfo.getId() : 0;
        boolean status = native_setOutputDevice(preferredDeviceId);
        if (status == true) {
            synchronized (this) {
                mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    /**
     * Returns the selected output specified by {@link #setPreferredDevice}. Note that this
     * is not guaranteed to correspond to the actual device being used for playback.
     */
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    //--------------------------------------------------------------------------
    // (Re)Routing Info
    //--------------------
    /**
     * Defines the interface by which applications can receive notifications of routing
     * changes for the associated {@link AudioTrack}.
     */
    public interface OnRoutingChangedListener {
        /**
         * Called when the routing of an AudioTrack changes from either and explicit or
         * policy rerouting.  Use {@link #getRoutedDevice()} to retrieve the newly routed-to
         * device.
         */
        public void onRoutingChanged(AudioTrack audioTrack);
    }

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this AudioTrack.
     * Note: The query is only valid if the AudioTrack is currently playing. If it is not,
     * <code>getRoutedDevice()</code> will return null.
     */
    public AudioDeviceInfo getRoutedDevice() {
        int deviceId = native_getRoutedDeviceId();
        if (deviceId == 0) {
            return null;
        }
        AudioDeviceInfo[] devices =
                AudioManager.getDevicesStatic(AudioManager.GET_DEVICES_OUTPUTS);
        for (int i = 0; i < devices.length; i++) {
            if (devices[i].getId() == deviceId) {
                return devices[i];
            }
        }
        return null;
    }

    /**
     * The list of AudioTrack.OnRoutingChangedListener interfaces added (with
     * {@link AudioTrack#addOnRoutingChangedListener(OnRoutingChangedListener, android.os.Handler)}
     * by an app to receive (re)routing notifications.
     */
    private ArrayMap<OnRoutingChangedListener, NativeRoutingEventHandlerDelegate>
        mRoutingChangeListeners =
            new ArrayMap<OnRoutingChangedListener, NativeRoutingEventHandlerDelegate>();

    /**
     * Adds an {@link OnRoutingChangedListener} to receive notifications of routing changes
     * on this AudioTrack.
     * @param listener The {@link OnRoutingChangedListener} interface to receive notifications
     * of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener,
            android.os.Handler handler) {
        if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
            synchronized (mRoutingChangeListeners) {
                if (mRoutingChangeListeners.size() == 0) {
                    native_enableDeviceCallback();
                }
                mRoutingChangeListeners.put(
                    listener, new NativeRoutingEventHandlerDelegate(this, listener,
                            handler != null ? handler : new Handler(mInitializationLooper)));
            }
        }
    }

    /**
     * Removes an {@link OnRoutingChangedListener} which has been previously added
     * to receive rerouting notifications.
     * @param listener The previously added {@link OnRoutingChangedListener} interface to remove.
     */
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        synchronized (mRoutingChangeListeners) {
            if (mRoutingChangeListeners.containsKey(listener)) {
                mRoutingChangeListeners.remove(listener);
            }
            if (mRoutingChangeListeners.size() == 0) {
                native_disableDeviceCallback();
            }
        }
    }

    /**
     * Sends device list change notification to all listeners.
     */
    private void broadcastRoutingChange() {
        Collection<NativeRoutingEventHandlerDelegate> values;
        synchronized (mRoutingChangeListeners) {
            values = mRoutingChangeListeners.values();
        }
        AudioManager.resetAudioPortGeneration();
        for(NativeRoutingEventHandlerDelegate delegate : values) {
            Handler handler = delegate.getHandler();
            if (handler != null) {
                handler.sendEmptyMessage(AudioSystem.NATIVE_EVENT_ROUTING_CHANGE);
            }
        }
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
    private class NativePositionEventHandlerDelegate {
        private final Handler mHandler;

        NativePositionEventHandlerDelegate(final AudioTrack track,
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

    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread
     */
    private class NativeRoutingEventHandlerDelegate {
        private final Handler mHandler;

        NativeRoutingEventHandlerDelegate(final AudioTrack track,
                                   final OnRoutingChangedListener listener,
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
                        case AudioSystem.NATIVE_EVENT_ROUTING_CHANGE:
                            if (listener != null) {
                                listener.onRoutingChanged(track);
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

        if (what == AudioSystem.NATIVE_EVENT_ROUTING_CHANGE) {
            track.broadcastRoutingChange();
            return;
        }
        NativePositionEventHandlerDelegate delegate = track.mEventHandlerDelegate;
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

    // post-condition: mStreamType is overwritten with a value
    //     that reflects the audio attributes (e.g. an AudioAttributes object with a usage of
    //     AudioAttributes.USAGE_MEDIA will map to AudioManager.STREAM_MUSIC
    private native final int native_setup(Object /*WeakReference<AudioTrack>*/ audiotrack_this,
            Object /*AudioAttributes*/ attributes,
            int sampleRate, int channelMask, int channelIndexMask, int audioFormat,
            int buffSizeInBytes, int mode, int[] sessionId);

    private native final void native_finalize();

    private native final void native_release();

    private native final void native_start();

    private native final void native_stop();

    private native final void native_pause();

    private native final void native_flush();

    private native final int native_write_byte(byte[] audioData,
                                               int offsetInBytes, int sizeInBytes, int format,
                                               boolean isBlocking);

    private native final int native_write_short(short[] audioData,
                                                int offsetInShorts, int sizeInShorts, int format,
                                                boolean isBlocking);

    private native final int native_write_float(float[] audioData,
                                                int offsetInFloats, int sizeInFloats, int format,
                                                boolean isBlocking);

    private native final int native_write_native_bytes(Object audioData,
            int positionInBytes, int sizeInBytes, int format, boolean blocking);

    private native final int native_reload_static();

    private native final int native_get_native_frame_count();

    private native final void native_setVolume(float leftVolume, float rightVolume);

    private native final int native_set_playback_rate(int sampleRateInHz);
    private native final int native_get_playback_rate();

    private native final void native_set_playback_params(@NonNull PlaybackParams params);
    private native final @NonNull PlaybackParams native_get_playback_params();

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
    private native final int native_setAuxEffectSendLevel(float level);

    private native final boolean native_setOutputDevice(int deviceId);
    private native final int native_getRoutedDeviceId();
    private native final void native_enableDeviceCallback();
    private native final void native_disableDeviceCallback();

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
