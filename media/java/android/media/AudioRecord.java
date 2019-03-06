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

import android.annotation.CallbackExecutor;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioPolicy;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The AudioRecord class manages the audio resources for Java applications
 * to record audio from the audio input hardware of the platform. This is
 * achieved by "pulling" (reading) the data from the AudioRecord object. The
 * application is responsible for polling the AudioRecord object in time using one of
 * the following three methods:  {@link #read(byte[],int, int)}, {@link #read(short[], int, int)}
 * or {@link #read(ByteBuffer, int)}. The choice of which method to use will be based
 * on the audio data storage format that is the most convenient for the user of AudioRecord.
 * <p>Upon creation, an AudioRecord object initializes its associated audio buffer that it will
 * fill with the new audio data. The size of this buffer, specified during the construction,
 * determines how long an AudioRecord can record before "over-running" data that has not
 * been read yet. Data should be read from the audio hardware in chunks of sizes inferior to
 * the total recording buffer size.
 */
public class AudioRecord implements AudioRouting, MicrophoneDirection,
        AudioRecordingMonitor, AudioRecordingMonitorClient
{
    //---------------------------------------------------------
    // Constants
    //--------------------


    /**
     *  indicates AudioRecord state is not successfully initialized.
     */
    public static final int STATE_UNINITIALIZED = 0;
    /**
     *  indicates AudioRecord state is ready to be used
     */
    public static final int STATE_INITIALIZED   = 1;

    /**
     * indicates AudioRecord recording state is not recording
     */
    public static final int RECORDSTATE_STOPPED = 1;  // matches SL_RECORDSTATE_STOPPED
    /**
     * indicates AudioRecord recording state is recording
     */
    public static final int RECORDSTATE_RECORDING = 3;// matches SL_RECORDSTATE_RECORDING

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
    /**
     * An error code indicating that the object reporting it is no longer valid and needs to
     * be recreated.
     */
    public  static final int ERROR_DEAD_OBJECT                     = AudioSystem.DEAD_OBJECT;

    // Error codes:
    // to keep in sync with frameworks/base/core/jni/android_media_AudioRecord.cpp
    private static final int AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT      = -16;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK  = -17;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDFORMAT       = -18;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDSOURCE       = -19;
    private static final int AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED    = -20;

    // Events:
    // to keep in sync with frameworks/av/include/media/AudioRecord.h
    /**
     * Event id denotes when record head has reached a previously set marker.
     */
    private static final int NATIVE_EVENT_MARKER  = 2;
    /**
     * Event id denotes when previously set update period has elapsed during recording.
     */
    private static final int NATIVE_EVENT_NEW_POS = 3;

    private final static String TAG = "android.media.AudioRecord";

    /** @hide */
    public final static String SUBMIX_FIXED_VOLUME = "fixedVolume";

    /** @hide */
    @IntDef({
        READ_BLOCKING,
        READ_NON_BLOCKING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReadMode {}

    /**
     * The read mode indicating the read operation will block until all data
     * requested has been read.
     */
    public final static int READ_BLOCKING = 0;

    /**
     * The read mode indicating the read operation will return immediately after
     * reading as much audio data as possible without blocking.
     */
    public final static int READ_NON_BLOCKING = 1;

    //---------------------------------------------------------
    // Used exclusively by native code
    //--------------------
    /**
     * Accessed by native methods: provides access to C++ AudioRecord object
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private long mNativeRecorderInJavaObj;

    /**
     * Accessed by native methods: provides access to the callback data.
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private long mNativeCallbackCookie;

    /**
     * Accessed by native methods: provides access to the JNIDeviceCallback instance.
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private long mNativeDeviceCallback;


    //---------------------------------------------------------
    // Member variables
    //--------------------
    private AudioPolicy mAudioCapturePolicy;

    /**
     * The audio data sampling rate in Hz.
     * Never {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED}.
     */
    private int mSampleRate; // initialized by all constructors via audioParamCheck()
    /**
     * The number of input audio channels (1 is mono, 2 is stereo)
     */
    private int mChannelCount;
    /**
     * The audio channel position mask
     */
    private int mChannelMask;
    /**
     * The audio channel index mask
     */
    private int mChannelIndexMask;
    /**
     * The encoding of the audio samples.
     * @see AudioFormat#ENCODING_PCM_8BIT
     * @see AudioFormat#ENCODING_PCM_16BIT
     * @see AudioFormat#ENCODING_PCM_FLOAT
     */
    private int mAudioFormat;
    /**
     * Where the audio data is recorded from.
     */
    private int mRecordSource;
    /**
     * Indicates the state of the AudioRecord instance.
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Indicates the recording state of the AudioRecord instance.
     */
    private int mRecordingState = RECORDSTATE_STOPPED;
    /**
     * Lock to make sure mRecordingState updates are reflecting the actual state of the object.
     */
    private final Object mRecordingStateLock = new Object();
    /**
     * The listener the AudioRecord notifies when the record position reaches a marker
     * or for periodic updates during the progression of the record head.
     *  @see #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)
     *  @see #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)
     */
    private OnRecordPositionUpdateListener mPositionListener = null;
    /**
     * Lock to protect position listener updates against event notifications
     */
    private final Object mPositionListenerLock = new Object();
    /**
     * Handler for marker events coming from the native code
     */
    private NativeEventHandler mEventHandler = null;
    /**
     * Looper associated with the thread that creates the AudioRecord instance
     */
    @UnsupportedAppUsage
    private Looper mInitializationLooper = null;
    /**
     * Size of the native audio buffer.
     */
    private int mNativeBufferSizeInBytes = 0;
    /**
     * Audio session ID
     */
    private int mSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
    /**
     * AudioAttributes
     */
    @UnsupportedAppUsage
    private AudioAttributes mAudioAttributes;
    private boolean mIsSubmixFullVolume = false;

    //---------------------------------------------------------
    // Constructor, Finalize
    //--------------------
    /**
     * Class constructor.
     * Though some invalid parameters will result in an {@link IllegalArgumentException} exception,
     * other errors do not.  Thus you should call {@link #getState()} immediately after construction
     * to confirm that the object is usable.
     * @param audioSource the recording source.
     *   See {@link MediaRecorder.AudioSource} for the recording source definitions.
     * @param sampleRateInHz the sample rate expressed in Hertz. 44100Hz is currently the only
     *   rate that is guaranteed to work on all devices, but other rates such as 22050,
     *   16000, and 11025 may work on some devices.
     *   {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED} means to use a route-dependent value
     *   which is usually the sample rate of the source.
     *   {@link #getSampleRate()} can be used to retrieve the actual sample rate chosen.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_IN_MONO} and
     *   {@link AudioFormat#CHANNEL_IN_STEREO}.  {@link AudioFormat#CHANNEL_IN_MONO} is guaranteed
     *   to work on all devices.
     * @param audioFormat the format in which the audio data is to be returned.
     *   See {@link AudioFormat#ENCODING_PCM_8BIT}, {@link AudioFormat#ENCODING_PCM_16BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is written
     *   to during the recording. New audio data can be read from this buffer in smaller chunks
     *   than this size. See {@link #getMinBufferSize(int, int, int)} to determine the minimum
     *   required buffer size for the successful creation of an AudioRecord instance. Using values
     *   smaller than getMinBufferSize() will result in an initialization failure.
     * @throws java.lang.IllegalArgumentException
     */
    public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes)
    throws IllegalArgumentException {
        this((new AudioAttributes.Builder())
                    .setInternalCapturePreset(audioSource)
                    .build(),
                (new AudioFormat.Builder())
                    .setChannelMask(getChannelMaskFromLegacyConfig(channelConfig,
                                        true/*allow legacy configurations*/))
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRateInHz)
                    .build(),
                bufferSizeInBytes,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    /**
     * @hide
     * Class constructor with {@link AudioAttributes} and {@link AudioFormat}.
     * @param attributes a non-null {@link AudioAttributes} instance. Use
     *     {@link AudioAttributes.Builder#setAudioSource(int)} for configuring the audio
     *     source for this instance.
     * @param format a non-null {@link AudioFormat} instance describing the format of the data
     *     that will be recorded through this AudioRecord. See {@link AudioFormat.Builder} for
     *     configuring the audio format parameters such as encoding, channel mask and sample rate.
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is written
     *   to during the recording. New audio data can be read from this buffer in smaller chunks
     *   than this size. See {@link #getMinBufferSize(int, int, int)} to determine the minimum
     *   required buffer size for the successful creation of an AudioRecord instance. Using values
     *   smaller than getMinBufferSize() will result in an initialization failure.
     * @param sessionId ID of audio session the AudioRecord must be attached to, or
     *   {@link AudioManager#AUDIO_SESSION_ID_GENERATE} if the session isn't known at construction
     *   time. See also {@link AudioManager#generateAudioSessionId()} to obtain a session ID before
     *   construction.
     * @throws IllegalArgumentException
     */
    @SystemApi
    public AudioRecord(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes,
            int sessionId) throws IllegalArgumentException {
        mRecordingState = RECORDSTATE_STOPPED;

        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat");
        }

        // remember which looper is associated with the AudioRecord instanciation
        if ((mInitializationLooper = Looper.myLooper()) == null) {
            mInitializationLooper = Looper.getMainLooper();
        }

        // is this AudioRecord using REMOTE_SUBMIX at full volume?
        if (attributes.getCapturePreset() == MediaRecorder.AudioSource.REMOTE_SUBMIX) {
            final AudioAttributes.Builder filteredAttr = new AudioAttributes.Builder();
            final Iterator<String> tagsIter = attributes.getTags().iterator();
            while (tagsIter.hasNext()) {
                final String tag = tagsIter.next();
                if (tag.equalsIgnoreCase(SUBMIX_FIXED_VOLUME)) {
                    mIsSubmixFullVolume = true;
                    Log.v(TAG, "Will record from REMOTE_SUBMIX at full fixed volume");
                } else { // SUBMIX_FIXED_VOLUME: is not to be propagated to the native layers
                    filteredAttr.addTag(tag);
                }
            }
            filteredAttr.setInternalCapturePreset(attributes.getCapturePreset());
            mAudioAttributes = filteredAttr.build();
        } else {
            mAudioAttributes = attributes;
        }

        int rate = format.getSampleRate();
        if (rate == AudioFormat.SAMPLE_RATE_UNSPECIFIED) {
            rate = 0;
        }

        int encoding = AudioFormat.ENCODING_DEFAULT;
        if ((format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0)
        {
            encoding = format.getEncoding();
        }

        audioParamCheck(attributes.getCapturePreset(), rate, encoding);

        if ((format.getPropertySetMask()
                & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_INDEX_MASK) != 0) {
            mChannelIndexMask = format.getChannelIndexMask();
            mChannelCount = format.getChannelCount();
        }
        if ((format.getPropertySetMask()
                & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0) {
            mChannelMask = getChannelMaskFromLegacyConfig(format.getChannelMask(), false);
            mChannelCount = format.getChannelCount();
        } else if (mChannelIndexMask == 0) {
            mChannelMask = getChannelMaskFromLegacyConfig(AudioFormat.CHANNEL_IN_DEFAULT, false);
            mChannelCount =  AudioFormat.channelCountFromInChannelMask(mChannelMask);
        }

        audioBuffSizeCheck(bufferSizeInBytes);

        int[] sampleRate = new int[] {mSampleRate};
        int[] session = new int[1];
        session[0] = sessionId;
        //TODO: update native initialization when information about hardware init failure
        //      due to capture device already open is available.
        int initResult = native_setup( new WeakReference<AudioRecord>(this),
                mAudioAttributes, sampleRate, mChannelMask, mChannelIndexMask,
                mAudioFormat, mNativeBufferSizeInBytes,
                session, getCurrentOpPackageName(), 0 /*nativeRecordInJavaObj*/);
        if (initResult != SUCCESS) {
            loge("Error code "+initResult+" when initializing native AudioRecord object.");
            return; // with mState == STATE_UNINITIALIZED
        }

        mSampleRate = sampleRate[0];
        mSessionId = session[0];

        mState = STATE_INITIALIZED;
    }

    private String getCurrentOpPackageName() {
        String opPackageName = ActivityThread.currentOpPackageName();
        if (opPackageName != null) {
            return opPackageName;
        }
        // Command line utility
        return "uid:" + Binder.getCallingUid();
    }

    /**
     * A constructor which explicitly connects a Native (C++) AudioRecord. For use by
     * the AudioRecordRoutingProxy subclass.
     * @param nativeRecordInJavaObj A C/C++ pointer to a native AudioRecord
     * (associated with an OpenSL ES recorder). Note: the caller must ensure a correct
     * value here as no error checking is or can be done.
     */
    /*package*/ AudioRecord(long nativeRecordInJavaObj) {
        mNativeRecorderInJavaObj = 0;
        mNativeCallbackCookie = 0;
        mNativeDeviceCallback = 0;

        // other initialization...
        if (nativeRecordInJavaObj != 0) {
            deferred_connect(nativeRecordInJavaObj);
        } else {
            mState = STATE_UNINITIALIZED;
        }
    }

    /**
     * Sets an {@link AudioPolicy} to automatically unregister when the record is released.
     *
     * <p>This is to prevent users of the audio capture API from having to manually unregister the
     * policy that was used to create the record.
     */
    private void unregisterAudioPolicyOnRelease(AudioPolicy audioPolicy) {
        mAudioCapturePolicy = audioPolicy;
    }

    /**
     * @hide
     */
    /* package */ void deferred_connect(long  nativeRecordInJavaObj) {
        if (mState != STATE_INITIALIZED) {
            int[] session = { 0 };
            int[] rates = { 0 };
            //TODO: update native initialization when information about hardware init failure
            //      due to capture device already open is available.
            // Note that for this native_setup, we are providing an already created/initialized
            // *Native* AudioRecord, so the attributes parameters to native_setup() are ignored.
            int initResult = native_setup(new WeakReference<AudioRecord>(this),
                    null /*mAudioAttributes*/,
                    rates /*mSampleRates*/,
                    0 /*mChannelMask*/,
                    0 /*mChannelIndexMask*/,
                    0 /*mAudioFormat*/,
                    0 /*mNativeBufferSizeInBytes*/,
                    session,
                    ActivityThread.currentOpPackageName(),
                    nativeRecordInJavaObj);
            if (initResult != SUCCESS) {
                loge("Error code "+initResult+" when initializing native AudioRecord object.");
                return; // with mState == STATE_UNINITIALIZED
            }

            mSessionId = session[0];

            mState = STATE_INITIALIZED;
        }
    }

    /**
     * Builder class for {@link AudioRecord} objects.
     * Use this class to configure and create an <code>AudioRecord</code> instance. By setting the
     * recording source and audio format parameters, you indicate which of
     * those vary from the default behavior on the device.
     * <p> Here is an example where <code>Builder</code> is used to specify all {@link AudioFormat}
     * parameters, to be used by a new <code>AudioRecord</code> instance:
     *
     * <pre class="prettyprint">
     * AudioRecord recorder = new AudioRecord.Builder()
     *         .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
     *         .setAudioFormat(new AudioFormat.Builder()
     *                 .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
     *                 .setSampleRate(32000)
     *                 .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
     *                 .build())
     *         .setBufferSizeInBytes(2*minBuffSize)
     *         .build();
     * </pre>
     * <p>
     * If the audio source is not set with {@link #setAudioSource(int)},
     * {@link MediaRecorder.AudioSource#DEFAULT} is used.
     * <br>If the audio format is not specified or is incomplete, its channel configuration will be
     * {@link AudioFormat#CHANNEL_IN_MONO}, and the encoding will be
     * {@link AudioFormat#ENCODING_PCM_16BIT}.
     * The sample rate will depend on the device actually selected for capture and can be queried
     * with {@link #getSampleRate()} method.
     * <br>If the buffer size is not specified with {@link #setBufferSizeInBytes(int)},
     * the minimum buffer size for the source is used.
     */
    public static class Builder {

        private static final String ERROR_MESSAGE_SOURCE_MISMATCH =
                "Cannot both set audio source and set playback capture config";

        private AudioPlaybackCaptureConfiguration mAudioPlaybackCaptureConfiguration;
        private AudioAttributes mAttributes;
        private AudioFormat mFormat;
        private int mBufferSizeInBytes;
        private int mSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;

        /**
         * Constructs a new Builder with the default values as described above.
         */
        public Builder() {
        }

        /**
         * @param source the audio source.
         * See {@link MediaRecorder.AudioSource} for the supported audio source definitions.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder setAudioSource(int source) throws IllegalArgumentException {
            Preconditions.checkState(
                    mAudioPlaybackCaptureConfiguration == null,
                    ERROR_MESSAGE_SOURCE_MISMATCH);
            if ( (source < MediaRecorder.AudioSource.DEFAULT) ||
                    (source > MediaRecorder.getAudioSourceMax()) ) {
                throw new IllegalArgumentException("Invalid audio source " + source);
            }
            mAttributes = new AudioAttributes.Builder()
                    .setInternalCapturePreset(source)
                    .build();
            return this;
        }

        /**
         * @hide
         * To be only used by system components. Allows specifying non-public capture presets
         * @param attributes a non-null {@link AudioAttributes} instance that contains the capture
         *     preset to be used.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        @SystemApi
        public Builder setAudioAttributes(@NonNull AudioAttributes attributes)
                throws IllegalArgumentException {
            if (attributes == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            }
            if (attributes.getCapturePreset() == MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID) {
                throw new IllegalArgumentException(
                        "No valid capture preset in AudioAttributes argument");
            }
            // keep reference, we only copy the data when building
            mAttributes = attributes;
            return this;
        }

        /**
         * Sets the format of the audio data to be captured.
         * @param format a non-null {@link AudioFormat} instance
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder setAudioFormat(@NonNull AudioFormat format) throws IllegalArgumentException {
            if (format == null) {
                throw new IllegalArgumentException("Illegal null AudioFormat argument");
            }
            // keep reference, we only copy the data when building
            mFormat = format;
            return this;
        }

        /**
         * Sets the total size (in bytes) of the buffer where audio data is written
         * during the recording. New audio data can be read from this buffer in smaller chunks
         * than this size. See {@link #getMinBufferSize(int, int, int)} to determine the minimum
         * required buffer size for the successful creation of an AudioRecord instance.
         * Since bufferSizeInBytes may be internally increased to accommodate the source
         * requirements, use {@link #getBufferSizeInFrames()} to determine the actual buffer size
         * in frames.
         * @param bufferSizeInBytes a value strictly greater than 0
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder setBufferSizeInBytes(int bufferSizeInBytes) throws IllegalArgumentException {
            if (bufferSizeInBytes <= 0) {
                throw new IllegalArgumentException("Invalid buffer size " + bufferSizeInBytes);
            }
            mBufferSizeInBytes = bufferSizeInBytes;
            return this;
        }

        /**
         * Sets the {@link AudioRecord} to record audio played by other apps.
         *
         * @param config Defines what apps to record audio from (i.e., via either their uid or
         *               the type of audio).
         * @throws IllegalStateException if called in conjunction with {@link #setAudioSource(int)}.
         * @throws NullPointerException if {@code config} is null.
         */
        public @NonNull Builder setAudioPlaybackCaptureConfig(
                @NonNull AudioPlaybackCaptureConfiguration config) {
            Preconditions.checkNotNull(
                    config, "Illegal null AudioPlaybackCaptureConfiguration argument");
            Preconditions.checkState(
                    mAttributes == null,
                    ERROR_MESSAGE_SOURCE_MISMATCH);
            mAudioPlaybackCaptureConfiguration = config;
            return this;
        }

        /**
         * @hide
         * To be only used by system components.
         * @param sessionId ID of audio session the AudioRecord must be attached to, or
         *     {@link AudioManager#AUDIO_SESSION_ID_GENERATE} if the session isn't known at
         *     construction time.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        @SystemApi
        public Builder setSessionId(int sessionId) throws IllegalArgumentException {
            if (sessionId < 0) {
                throw new IllegalArgumentException("Invalid session ID " + sessionId);
            }
            mSessionId = sessionId;
            return this;
        }

        private @NonNull AudioRecord buildAudioPlaybackCaptureRecord() {
            AudioMix audioMix = mAudioPlaybackCaptureConfiguration.createAudioMix(mFormat);
            MediaProjection projection = mAudioPlaybackCaptureConfiguration.getMediaProjection();
            AudioPolicy audioPolicy = new AudioPolicy.Builder(/*context=*/ null)
                    .setMediaProjection(projection)
                    .addMix(audioMix).build();

            int error = AudioManager.registerAudioPolicyStatic(audioPolicy);
            if (error != 0) {
                throw new UnsupportedOperationException("Error: could not register audio policy");
            }

            AudioRecord record = audioPolicy.createAudioRecordSink(audioMix);
            if (record == null) {
                throw new UnsupportedOperationException("Cannot create AudioRecord");
            }
            record.unregisterAudioPolicyOnRelease(audioPolicy);
            return record;
        }

        /**
         * @return a new {@link AudioRecord} instance successfully initialized with all
         *     the parameters set on this <code>Builder</code>.
         * @throws UnsupportedOperationException if the parameters set on the <code>Builder</code>
         *     were incompatible, or if they are not supported by the device,
         *     or if the device was not available.
         */
        public AudioRecord build() throws UnsupportedOperationException {
            if (mAudioPlaybackCaptureConfiguration != null) {
                return buildAudioPlaybackCaptureRecord();
            }

            if (mFormat == null) {
                mFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
            } else {
                if (mFormat.getEncoding() == AudioFormat.ENCODING_INVALID) {
                    mFormat = new AudioFormat.Builder(mFormat)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build();
                }
                if (mFormat.getChannelMask() == AudioFormat.CHANNEL_INVALID
                        && mFormat.getChannelIndexMask() == AudioFormat.CHANNEL_INVALID) {
                    mFormat = new AudioFormat.Builder(mFormat)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build();
                }
            }
            if (mAttributes == null) {
                mAttributes = new AudioAttributes.Builder()
                        .setInternalCapturePreset(MediaRecorder.AudioSource.DEFAULT)
                        .build();
            }
            try {
                // If the buffer size is not specified,
                // use a single frame for the buffer size and let the
                // native code figure out the minimum buffer size.
                if (mBufferSizeInBytes == 0) {
                    mBufferSizeInBytes = mFormat.getChannelCount()
                            * mFormat.getBytesPerSample(mFormat.getEncoding());
                }
                final AudioRecord record = new AudioRecord(
                        mAttributes, mFormat, mBufferSizeInBytes, mSessionId);
                if (record.getState() == STATE_UNINITIALIZED) {
                    // release is not necessary
                    throw new UnsupportedOperationException("Cannot create AudioRecord");
                }
                return record;
            } catch (IllegalArgumentException e) {
                throw new UnsupportedOperationException(e.getMessage());
            }
        }
    }

    // Convenience method for the constructor's parameter checks.
    // This, getChannelMaskFromLegacyConfig and audioBuffSizeCheck are where constructor
    // IllegalArgumentException-s are thrown
    private static int getChannelMaskFromLegacyConfig(int inChannelConfig,
            boolean allowLegacyConfig) {
        int mask;
        switch (inChannelConfig) {
        case AudioFormat.CHANNEL_IN_DEFAULT: // AudioFormat.CHANNEL_CONFIGURATION_DEFAULT
        case AudioFormat.CHANNEL_IN_MONO:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            mask = AudioFormat.CHANNEL_IN_MONO;
            break;
        case AudioFormat.CHANNEL_IN_STEREO:
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            mask = AudioFormat.CHANNEL_IN_STEREO;
            break;
        case (AudioFormat.CHANNEL_IN_FRONT | AudioFormat.CHANNEL_IN_BACK):
            mask = inChannelConfig;
            break;
        default:
            throw new IllegalArgumentException("Unsupported channel configuration.");
        }

        if (!allowLegacyConfig && ((inChannelConfig == AudioFormat.CHANNEL_CONFIGURATION_MONO)
                || (inChannelConfig == AudioFormat.CHANNEL_CONFIGURATION_STEREO))) {
            // only happens with the constructor that uses AudioAttributes and AudioFormat
            throw new IllegalArgumentException("Unsupported deprecated configuration.");
        }

        return mask;
    }

    // postconditions:
    //    mRecordSource is valid
    //    mAudioFormat is valid
    //    mSampleRate is valid
    private void audioParamCheck(int audioSource, int sampleRateInHz, int audioFormat)
            throws IllegalArgumentException {

        //--------------
        // audio source
        if ( (audioSource < MediaRecorder.AudioSource.DEFAULT) ||
             ((audioSource > MediaRecorder.getAudioSourceMax()) &&
              (audioSource != MediaRecorder.AudioSource.RADIO_TUNER) &&
              (audioSource != MediaRecorder.AudioSource.HOTWORD)) )  {
            throw new IllegalArgumentException("Invalid audio source " + audioSource);
        }
        mRecordSource = audioSource;

        //--------------
        // sample rate
        if ((sampleRateInHz < AudioFormat.SAMPLE_RATE_HZ_MIN ||
                sampleRateInHz > AudioFormat.SAMPLE_RATE_HZ_MAX) &&
                sampleRateInHz != AudioFormat.SAMPLE_RATE_UNSPECIFIED) {
            throw new IllegalArgumentException(sampleRateInHz
                    + "Hz is not a supported sample rate.");
        }
        mSampleRate = sampleRateInHz;

        //--------------
        // audio format
        switch (audioFormat) {
        case AudioFormat.ENCODING_DEFAULT:
            mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
            break;
        case AudioFormat.ENCODING_PCM_FLOAT:
        case AudioFormat.ENCODING_PCM_16BIT:
        case AudioFormat.ENCODING_PCM_8BIT:
            mAudioFormat = audioFormat;
            break;
        default:
            throw new IllegalArgumentException("Unsupported sample encoding " + audioFormat
                    + ". Should be ENCODING_PCM_8BIT, ENCODING_PCM_16BIT, or ENCODING_PCM_FLOAT.");
        }
    }


    // Convenience method for the contructor's audio buffer size check.
    // preconditions:
    //    mChannelCount is valid
    //    mAudioFormat is AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT,
    //                 or AudioFormat.ENCODING_PCM_FLOAT
    // postcondition:
    //    mNativeBufferSizeInBytes is valid (multiple of frame size, positive)
    private void audioBuffSizeCheck(int audioBufferSize) throws IllegalArgumentException {
        // NB: this section is only valid with PCM data.
        // To update when supporting compressed formats
        int frameSizeInBytes = mChannelCount
            * (AudioFormat.getBytesPerSample(mAudioFormat));
        if ((audioBufferSize % frameSizeInBytes != 0) || (audioBufferSize < 1)) {
            throw new IllegalArgumentException("Invalid audio buffer size " + audioBufferSize
                    + " (frame size " + frameSizeInBytes + ")");
        }

        mNativeBufferSizeInBytes = audioBufferSize;
    }



    /**
     * Releases the native AudioRecord resources.
     * The object can no longer be used and the reference should be set to null
     * after a call to release()
     */
    public void release() {
        try {
            stop();
        } catch(IllegalStateException ise) {
            // don't raise an exception, we're releasing the resources.
        }
        if (mAudioCapturePolicy != null) {
            AudioManager.unregisterAudioPolicyAsyncStatic(mAudioCapturePolicy);
        }
        native_release();
        mState = STATE_UNINITIALIZED;
    }


    @Override
    protected void finalize() {
        // will cause stop() to be called, and if appropriate, will handle fixed volume recording
        release();
    }


    //--------------------------------------------------------------------------
    // Getters
    //--------------------
    /**
     * Returns the configured audio sink sample rate in Hz.
     * The sink sample rate never changes after construction.
     * If the constructor had a specific sample rate, then the sink sample rate is that value.
     * If the constructor had {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED},
     * then the sink sample rate is a route-dependent default value based on the source [sic].
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the audio recording source.
     * @see MediaRecorder.AudioSource
     */
    public int getAudioSource() {
        return mRecordSource;
    }

    /**
     * Returns the configured audio data encoding. See {@link AudioFormat#ENCODING_PCM_8BIT},
     * {@link AudioFormat#ENCODING_PCM_16BIT}, and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     */
    public int getAudioFormat() {
        return mAudioFormat;
    }

    /**
     * Returns the configured channel position mask.
     * <p> See {@link AudioFormat#CHANNEL_IN_MONO}
     * and {@link AudioFormat#CHANNEL_IN_STEREO}.
     * This method may return {@link AudioFormat#CHANNEL_INVALID} if
     * a channel index mask is used.
     * Consider {@link #getFormat()} instead, to obtain an {@link AudioFormat},
     * which contains both the channel position mask and the channel index mask.
     */
    public int getChannelConfiguration() {
        return mChannelMask;
    }

    /**
     * Returns the configured <code>AudioRecord</code> format.
     * @return an {@link AudioFormat} containing the
     * <code>AudioRecord</code> parameters at the time of configuration.
     */
    public @NonNull AudioFormat getFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder()
            .setSampleRate(mSampleRate)
            .setEncoding(mAudioFormat);
        if (mChannelMask != AudioFormat.CHANNEL_INVALID) {
            builder.setChannelMask(mChannelMask);
        }
        if (mChannelIndexMask != AudioFormat.CHANNEL_INVALID  /* 0 */) {
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
     * Returns the state of the AudioRecord instance. This is useful after the
     * AudioRecord instance has been created to check if it was initialized
     * properly. This ensures that the appropriate hardware resources have been
     * acquired.
     * @see AudioRecord#STATE_INITIALIZED
     * @see AudioRecord#STATE_UNINITIALIZED
     */
    public int getState() {
        return mState;
    }

    /**
     * Returns the recording state of the AudioRecord instance.
     * @see AudioRecord#RECORDSTATE_STOPPED
     * @see AudioRecord#RECORDSTATE_RECORDING
     */
    public int getRecordingState() {
        synchronized (mRecordingStateLock) {
            return mRecordingState;
        }
    }

    /**
     *  Returns the frame count of the native <code>AudioRecord</code> buffer.
     *  This is greater than or equal to the bufferSizeInBytes converted to frame units
     *  specified in the <code>AudioRecord</code> constructor or Builder.
     *  The native frame count may be enlarged to accommodate the requirements of the
     *  source on creation or if the <code>AudioRecord</code>
     *  is subsequently rerouted.
     *  @return current size in frames of the <code>AudioRecord</code> buffer.
     *  @throws IllegalStateException
     */
    public int getBufferSizeInFrames() {
        return native_get_buffer_size_in_frames();
    }

    /**
     * Returns the notification marker position expressed in frames.
     */
    public int getNotificationMarkerPosition() {
        return native_get_marker_pos();
    }

    /**
     * Returns the notification update period expressed in frames.
     */
    public int getPositionNotificationPeriod() {
        return native_get_pos_update_period();
    }

    /**
     * Poll for an {@link AudioTimestamp} on demand.
     * <p>
     * The AudioTimestamp reflects the frame delivery information at
     * the earliest point available in the capture pipeline.
     * <p>
     * Calling {@link #startRecording()} following a {@link #stop()} will reset
     * the frame count to 0.
     *
     * @param outTimestamp a caller provided non-null AudioTimestamp instance,
     *        which is updated with the AudioRecord frame delivery information upon success.
     * @param timebase one of
     *        {@link AudioTimestamp#TIMEBASE_BOOTTIME AudioTimestamp.TIMEBASE_BOOTTIME} or
     *        {@link AudioTimestamp#TIMEBASE_MONOTONIC AudioTimestamp.TIMEBASE_MONOTONIC},
     *        used to select the clock for the AudioTimestamp time.
     * @return {@link #SUCCESS} if a timestamp is available,
     *         or {@link #ERROR_INVALID_OPERATION} if a timestamp not available.
     */
     public int getTimestamp(@NonNull AudioTimestamp outTimestamp,
             @AudioTimestamp.Timebase int timebase)
     {
         if (outTimestamp == null ||
                 (timebase != AudioTimestamp.TIMEBASE_BOOTTIME
                 && timebase != AudioTimestamp.TIMEBASE_MONOTONIC)) {
             throw new IllegalArgumentException();
         }
         return native_get_timestamp(outTimestamp, timebase);
     }

    /**
     * Returns the minimum buffer size required for the successful creation of an AudioRecord
     * object, in byte units.
     * Note that this size doesn't guarantee a smooth recording under load, and higher values
     * should be chosen according to the expected frequency at which the AudioRecord instance
     * will be polled for new data.
     * See {@link #AudioRecord(int, int, int, int, int)} for more information on valid
     * configuration values.
     * @param sampleRateInHz the sample rate expressed in Hertz.
     *   {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED} is not permitted.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_IN_MONO} and
     *   {@link AudioFormat#CHANNEL_IN_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT}.
     * @return {@link #ERROR_BAD_VALUE} if the recording parameters are not supported by the
     *  hardware, or an invalid parameter was passed,
     *  or {@link #ERROR} if the implementation was unable to query the hardware for its
     *  input properties,
     *   or the minimum buffer size expressed in bytes.
     * @see #AudioRecord(int, int, int, int, int)
     */
    static public int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        int channelCount = 0;
        switch (channelConfig) {
        case AudioFormat.CHANNEL_IN_DEFAULT: // AudioFormat.CHANNEL_CONFIGURATION_DEFAULT
        case AudioFormat.CHANNEL_IN_MONO:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            channelCount = 1;
            break;
        case AudioFormat.CHANNEL_IN_STEREO:
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
        case (AudioFormat.CHANNEL_IN_FRONT | AudioFormat.CHANNEL_IN_BACK):
            channelCount = 2;
            break;
        case AudioFormat.CHANNEL_INVALID:
        default:
            loge("getMinBufferSize(): Invalid channel configuration.");
            return ERROR_BAD_VALUE;
        }

        int size = native_get_min_buff_size(sampleRateInHz, channelCount, audioFormat);
        if (size == 0) {
            return ERROR_BAD_VALUE;
        }
        else if (size == -1) {
            return ERROR;
        }
        else {
            return size;
        }
    }

    /**
     * Returns the audio session ID.
     *
     * @return the ID of the audio session this AudioRecord belongs to.
     */
    public int getAudioSessionId() {
        return mSessionId;
    }

    //---------------------------------------------------------
    // Transport control methods
    //--------------------
    /**
     * Starts recording from the AudioRecord instance.
     * @throws IllegalStateException
     */
    public void startRecording()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("startRecording() called on an "
                    + "uninitialized AudioRecord.");
        }

        // start recording
        synchronized(mRecordingStateLock) {
            if (native_start(MediaSyncEvent.SYNC_EVENT_NONE, 0) == SUCCESS) {
                handleFullVolumeRec(true);
                mRecordingState = RECORDSTATE_RECORDING;
            }
        }
    }

    /**
     * Starts recording from the AudioRecord instance when the specified synchronization event
     * occurs on the specified audio session.
     * @throws IllegalStateException
     * @param syncEvent event that triggers the capture.
     * @see MediaSyncEvent
     */
    public void startRecording(MediaSyncEvent syncEvent)
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("startRecording() called on an "
                    + "uninitialized AudioRecord.");
        }

        // start recording
        synchronized(mRecordingStateLock) {
            if (native_start(syncEvent.getType(), syncEvent.getAudioSessionId()) == SUCCESS) {
                handleFullVolumeRec(true);
                mRecordingState = RECORDSTATE_RECORDING;
            }
        }
    }

    /**
     * Stops recording.
     * @throws IllegalStateException
     */
    public void stop()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("stop() called on an uninitialized AudioRecord.");
        }

        // stop recording
        synchronized(mRecordingStateLock) {
            handleFullVolumeRec(false);
            native_stop();
            mRecordingState = RECORDSTATE_STOPPED;
        }
    }

    private final IBinder mICallBack = new Binder();
    private void handleFullVolumeRec(boolean starting) {
        if (!mIsSubmixFullVolume) {
            return;
        }
        final IBinder b = ServiceManager.getService(android.content.Context.AUDIO_SERVICE);
        final IAudioService ias = IAudioService.Stub.asInterface(b);
        try {
            ias.forceRemoteSubmixFullVolume(starting, mICallBack);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to AudioService when handling full submix volume", e);
        }
    }

    //---------------------------------------------------------
    // Audio data supply
    //--------------------
    /**
     * Reads audio data from the audio hardware for recording into a byte array.
     * The format specified in the AudioRecord constructor should be
     * {@link AudioFormat#ENCODING_PCM_8BIT} to correspond to the data in the array.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInBytes index in audioData from which the data is written expressed in bytes.
     * @param sizeInBytes the number of requested bytes.
     * @return zero or the positive number of bytes that were read, or one of the following
     *    error codes. The number of bytes will not exceed sizeInBytes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return read(audioData, offsetInBytes, sizeInBytes, READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a byte array.
     * The format specified in the AudioRecord constructor should be
     * {@link AudioFormat#ENCODING_PCM_8BIT} to correspond to the data in the array.
     * The format can be {@link AudioFormat#ENCODING_PCM_16BIT}, but this is deprecated.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInBytes index in audioData to which the data is written expressed in bytes.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInBytes the number of requested bytes.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param readMode one of {@link #READ_BLOCKING}, {@link #READ_NON_BLOCKING}.
     *     <br>With {@link #READ_BLOCKING}, the read will block until all the requested data
     *     is read.
     *     <br>With {@link #READ_NON_BLOCKING}, the read will return immediately after
     *     reading as much audio data as possible without blocking.
     * @return zero or the positive number of bytes that were read, or one of the following
     *    error codes. The number of bytes will be a multiple of the frame size in bytes
     *    not to exceed sizeInBytes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes,
            @ReadMode int readMode) {
        if (mState != STATE_INITIALIZED  || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return ERROR_INVALID_OPERATION;
        }

        if ((readMode != READ_BLOCKING) && (readMode != READ_NON_BLOCKING)) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInBytes < 0 ) || (sizeInBytes < 0)
                || (offsetInBytes + sizeInBytes < 0)  // detect integer overflow
                || (offsetInBytes + sizeInBytes > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_byte_array(audioData, offsetInBytes, sizeInBytes,
                readMode == READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a short array.
     * The format specified in the AudioRecord constructor should be
     * {@link AudioFormat#ENCODING_PCM_16BIT} to correspond to the data in the array.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInShorts index in audioData to which the data is written expressed in shorts.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInShorts the number of requested shorts.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @return zero or the positive number of shorts that were read, or one of the following
     *    error codes. The number of shorts will be a multiple of the channel count not to exceed
     *    sizeInShorts.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
        return read(audioData, offsetInShorts, sizeInShorts, READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a short array.
     * The format specified in the AudioRecord constructor should be
     * {@link AudioFormat#ENCODING_PCM_16BIT} to correspond to the data in the array.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInShorts index in audioData from which the data is written expressed in shorts.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInShorts the number of requested shorts.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param readMode one of {@link #READ_BLOCKING}, {@link #READ_NON_BLOCKING}.
     *     <br>With {@link #READ_BLOCKING}, the read will block until all the requested data
     *     is read.
     *     <br>With {@link #READ_NON_BLOCKING}, the read will return immediately after
     *     reading as much audio data as possible without blocking.
     * @return zero or the positive number of shorts that were read, or one of the following
     *    error codes. The number of shorts will be a multiple of the channel count not to exceed
     *    sizeInShorts.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
            @ReadMode int readMode) {
        if (mState != STATE_INITIALIZED || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return ERROR_INVALID_OPERATION;
        }

        if ((readMode != READ_BLOCKING) && (readMode != READ_NON_BLOCKING)) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInShorts < 0 ) || (sizeInShorts < 0)
                || (offsetInShorts + sizeInShorts < 0)  // detect integer overflow
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_short_array(audioData, offsetInShorts, sizeInShorts,
                readMode == READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a float array.
     * The format specified in the AudioRecord constructor should be
     * {@link AudioFormat#ENCODING_PCM_FLOAT} to correspond to the data in the array.
     * @param audioData the array to which the recorded audio data is written.
     * @param offsetInFloats index in audioData from which the data is written.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInFloats the number of requested floats.
     *        Must not be negative, or cause the data access to go out of bounds of the array.
     * @param readMode one of {@link #READ_BLOCKING}, {@link #READ_NON_BLOCKING}.
     *     <br>With {@link #READ_BLOCKING}, the read will block until all the requested data
     *     is read.
     *     <br>With {@link #READ_NON_BLOCKING}, the read will return immediately after
     *     reading as much audio data as possible without blocking.
     * @return zero or the positive number of floats that were read, or one of the following
     *    error codes. The number of floats will be a multiple of the channel count not to exceed
     *    sizeInFloats.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull float[] audioData, int offsetInFloats, int sizeInFloats,
            @ReadMode int readMode) {
        if (mState == STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioRecord.read() called in invalid state STATE_UNINITIALIZED");
            return ERROR_INVALID_OPERATION;
        }

        if (mAudioFormat != AudioFormat.ENCODING_PCM_FLOAT) {
            Log.e(TAG, "AudioRecord.read(float[] ...) requires format ENCODING_PCM_FLOAT");
            return ERROR_INVALID_OPERATION;
        }

        if ((readMode != READ_BLOCKING) && (readMode != READ_NON_BLOCKING)) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ((audioData == null) || (offsetInFloats < 0) || (sizeInFloats < 0)
                || (offsetInFloats + sizeInFloats < 0)  // detect integer overflow
                || (offsetInFloats + sizeInFloats > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_float_array(audioData, offsetInFloats, sizeInFloats,
                readMode == READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a direct buffer. If this buffer
     * is not a direct buffer, this method will always return 0.
     * Note that the value returned by {@link java.nio.Buffer#position()} on this buffer is
     * unchanged after a call to this method.
     * The representation of the data in the buffer will depend on the format specified in
     * the AudioRecord constructor, and will be native endian.
     * @param audioBuffer the direct buffer to which the recorded audio data is written.
     * Data is written to audioBuffer.position().
     * @param sizeInBytes the number of requested bytes. It is recommended but not enforced
     *    that the number of bytes requested be a multiple of the frame size (sample size in
     *    bytes multiplied by the channel count).
     * @return zero or the positive number of bytes that were read, or one of the following
     *    error codes. The number of bytes will not exceed sizeInBytes and will be truncated to be
     *    a multiple of the frame size.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull ByteBuffer audioBuffer, int sizeInBytes) {
        return read(audioBuffer, sizeInBytes, READ_BLOCKING);
    }

    /**
     * Reads audio data from the audio hardware for recording into a direct buffer. If this buffer
     * is not a direct buffer, this method will always return 0.
     * Note that the value returned by {@link java.nio.Buffer#position()} on this buffer is
     * unchanged after a call to this method.
     * The representation of the data in the buffer will depend on the format specified in
     * the AudioRecord constructor, and will be native endian.
     * @param audioBuffer the direct buffer to which the recorded audio data is written.
     * Data is written to audioBuffer.position().
     * @param sizeInBytes the number of requested bytes. It is recommended but not enforced
     *    that the number of bytes requested be a multiple of the frame size (sample size in
     *    bytes multiplied by the channel count).
     * @param readMode one of {@link #READ_BLOCKING}, {@link #READ_NON_BLOCKING}.
     *     <br>With {@link #READ_BLOCKING}, the read will block until all the requested data
     *     is read.
     *     <br>With {@link #READ_NON_BLOCKING}, the read will return immediately after
     *     reading as much audio data as possible without blocking.
     * @return zero or the positive number of bytes that were read, or one of the following
     *    error codes. The number of bytes will not exceed sizeInBytes and will be truncated to be
     *    a multiple of the frame size.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the object isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the object is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next read()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int read(@NonNull ByteBuffer audioBuffer, int sizeInBytes, @ReadMode int readMode) {
        if (mState != STATE_INITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        if ((readMode != READ_BLOCKING) && (readMode != READ_NON_BLOCKING)) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioBuffer == null) || (sizeInBytes < 0) ) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_direct_buffer(audioBuffer, sizeInBytes, readMode == READ_BLOCKING);
    }

    /**
     *  Return Metrics data about the current AudioTrack instance.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being handled by this instance of AudioRecord
     * The attributes are descibed in {@link MetricsConstants}.
     *
     * Additional vendor-specific fields may also be present in
     * the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();

    //--------------------------------------------------------------------------
    // Initialization / configuration
    //--------------------
    /**
     * Sets the listener the AudioRecord notifies when a previously set marker is reached or
     * for each periodic record head position update.
     * @param listener
     */
    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener) {
        setRecordPositionUpdateListener(listener, null);
    }

    /**
     * Sets the listener the AudioRecord notifies when a previously set marker is reached or
     * for each periodic record head position update.
     * Use this method to receive AudioRecord events in the Handler associated with another
     * thread than the one in which you created the AudioRecord instance.
     * @param listener
     * @param handler the Handler that will receive the event notification messages.
     */
    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener,
                                                    Handler handler) {
        synchronized (mPositionListenerLock) {

            mPositionListener = listener;

            if (listener != null) {
                if (handler != null) {
                    mEventHandler = new NativeEventHandler(this, handler.getLooper());
                } else {
                    // no given handler, use the looper the AudioRecord was created in
                    mEventHandler = new NativeEventHandler(this, mInitializationLooper);
                }
            } else {
                mEventHandler = null;
            }
        }

    }


    /**
     * Sets the marker position at which the listener is called, if set with
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)} or
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)}.
     * @param markerInFrames marker position expressed in frames
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
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this AudioRecord.
     * Note: The query is only valid if the AudioRecord is currently recording. If it is not,
     * <code>getRoutedDevice()</code> will return null.
     */
    @Override
    public AudioDeviceInfo getRoutedDevice() {
        int deviceId = native_getRoutedDeviceId();
        if (deviceId == 0) {
            return null;
        }
        AudioDeviceInfo[] devices =
                AudioManager.getDevicesStatic(AudioManager.GET_DEVICES_INPUTS);
        for (int i = 0; i < devices.length; i++) {
            if (devices[i].getId() == deviceId) {
                return devices[i];
            }
        }
        return null;
    }

    /*
     * Call BEFORE adding a routing callback handler.
     */
    @GuardedBy("mRoutingChangeListeners")
    private void testEnableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0) {
            native_enableDeviceCallback();
        }
    }

    /*
     * Call AFTER removing a routing callback handler.
     */
    @GuardedBy("mRoutingChangeListeners")
    private void testDisableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0) {
            native_disableDeviceCallback();
        }
    }

    //--------------------------------------------------------------------------
    // (Re)Routing Info
    //--------------------
    /**
     * The list of AudioRouting.OnRoutingChangedListener interfaces added (with
     * {@link AudioRecord#addOnRoutingChangedListener} by an app to receive
     * (re)routing notifications.
     */
    @GuardedBy("mRoutingChangeListeners")
    private ArrayMap<AudioRouting.OnRoutingChangedListener,
            NativeRoutingEventHandlerDelegate> mRoutingChangeListeners = new ArrayMap<>();

    /**
     * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of
     * routing changes on this AudioRecord.
     * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
     * notifications of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
            android.os.Handler handler) {
        synchronized (mRoutingChangeListeners) {
            if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
                testEnableNativeRoutingCallbacksLocked();
                mRoutingChangeListeners.put(
                        listener, new NativeRoutingEventHandlerDelegate(this, listener,
                                handler != null ? handler : new Handler(mInitializationLooper)));
            }
        }
    }

    /**
     * Removes an {@link AudioRouting.OnRoutingChangedListener} which has been previously added
    * to receive rerouting notifications.
    * @param listener The previously added {@link AudioRouting.OnRoutingChangedListener} interface
    * to remove.
    */
    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        synchronized (mRoutingChangeListeners) {
            if (mRoutingChangeListeners.containsKey(listener)) {
                mRoutingChangeListeners.remove(listener);
                testDisableNativeRoutingCallbacksLocked();
            }
        }
    }

    //--------------------------------------------------------------------------
    // (Re)Routing Info
    //--------------------
    /**
     * Defines the interface by which applications can receive notifications of
     * routing changes for the associated {@link AudioRecord}.
     *
     * @deprecated users should switch to the general purpose
     *             {@link AudioRouting.OnRoutingChangedListener} class instead.
     */
    @Deprecated
    public interface OnRoutingChangedListener extends AudioRouting.OnRoutingChangedListener {
        /**
         * Called when the routing of an AudioRecord changes from either and
         * explicit or policy rerouting. Use {@link #getRoutedDevice()} to
         * retrieve the newly routed-from device.
         */
        public void onRoutingChanged(AudioRecord audioRecord);

        @Override
        default public void onRoutingChanged(AudioRouting router) {
            if (router instanceof AudioRecord) {
                onRoutingChanged((AudioRecord) router);
            }
        }
    }

    /**
     * Adds an {@link OnRoutingChangedListener} to receive notifications of routing changes
     * on this AudioRecord.
     * @param listener The {@link OnRoutingChangedListener} interface to receive notifications
     * of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     * @deprecated users should switch to the general purpose
     *             {@link AudioRouting.OnRoutingChangedListener} class instead.
     */
    @Deprecated
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener,
            android.os.Handler handler) {
        addOnRoutingChangedListener((AudioRouting.OnRoutingChangedListener) listener, handler);
    }

    /**
      * Removes an {@link OnRoutingChangedListener} which has been previously added
     * to receive rerouting notifications.
     * @param listener The previously added {@link OnRoutingChangedListener} interface to remove.
     * @deprecated users should switch to the general purpose
     *             {@link AudioRouting.OnRoutingChangedListener} class instead.
     */
    @Deprecated
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        removeOnRoutingChangedListener((AudioRouting.OnRoutingChangedListener) listener);
    }

    /**
     * Sends device list change notification to all listeners.
     */
    private void broadcastRoutingChange() {
        AudioManager.resetAudioPortGeneration();
        synchronized (mRoutingChangeListeners) {
            for (NativeRoutingEventHandlerDelegate delegate : mRoutingChangeListeners.values()) {
                delegate.notifyClient();
            }
        }
    }

    /**
     * Sets the period at which the listener is called, if set with
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener)} or
     * {@link #setRecordPositionUpdateListener(OnRecordPositionUpdateListener, Handler)}.
     * It is possible for notifications to be lost if the period is too small.
     * @param periodInFrames update period expressed in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_INVALID_OPERATION}
     */
    public int setPositionNotificationPeriod(int periodInFrames) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return native_set_pos_update_period(periodInFrames);
    }

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the input to this AudioRecord.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio source.
     *  If deviceInfo is null, default routing is restored.
     * @return true if successful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio input device.
     */
    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        // Do some validation....
        if (deviceInfo != null && !deviceInfo.isSource()) {
            return false;
        }

        int preferredDeviceId = deviceInfo != null ? deviceInfo.getId() : 0;
        boolean status = native_setInputDevice(preferredDeviceId);
        if (status == true) {
            synchronized (this) {
                mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    /**
     * Returns the selected input specified by {@link #setPreferredDevice}. Note that this
     * is not guarenteed to correspond to the actual device being used for recording.
     */
    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    //--------------------------------------------------------------------------
    // Microphone information
    //--------------------
    /**
     * Returns a lists of {@link MicrophoneInfo} representing the active microphones.
     * By querying channel mapping for each active microphone, developer can know how
     * the microphone is used by each channels or a capture stream.
     * Note that the information about the active microphones may change during a recording.
     * See {@link AudioManager#registerAudioDeviceCallback} to be notified of changes
     * in the audio devices, querying the active microphones then will return the latest
     * information.
     *
     * @return a lists of {@link MicrophoneInfo} representing the active microphones.
     * @throws IOException if an error occurs
     */
    public List<MicrophoneInfo> getActiveMicrophones() throws IOException {
        ArrayList<MicrophoneInfo> activeMicrophones = new ArrayList<>();
        int status = native_get_active_microphones(activeMicrophones);
        if (status != AudioManager.SUCCESS) {
            if (status != AudioManager.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "getActiveMicrophones failed:" + status);
            }
            Log.i(TAG, "getActiveMicrophones failed, fallback on routed device info");
        }
        AudioManager.setPortIdForMicrophones(activeMicrophones);

        // Use routed device when there is not information returned by hal.
        if (activeMicrophones.size() == 0) {
            AudioDeviceInfo device = getRoutedDevice();
            if (device != null) {
                MicrophoneInfo microphone = AudioManager.microphoneInfoFromAudioDeviceInfo(device);
                ArrayList<Pair<Integer, Integer>> channelMapping = new ArrayList<>();
                for (int i = 0; i < mChannelCount; i++) {
                    channelMapping.add(new Pair(i, MicrophoneInfo.CHANNEL_MAPPING_DIRECT));
                }
                microphone.setChannelMapping(channelMapping);
                activeMicrophones.add(microphone);
            }
        }
        return activeMicrophones;
    }

    //--------------------------------------------------------------------------
    // Implementation of AudioRecordingMonitor interface
    //--------------------

    AudioRecordingMonitorImpl mRecordingInfoImpl =
            new AudioRecordingMonitorImpl((AudioRecordingMonitorClient) this);

    /**
     * Register a callback to be notified of audio capture changes via a
     * {@link AudioManager.AudioRecordingCallback}. A callback is received when the capture path
     * configuration changes (pre-processing, format, sampling rate...) or capture is
     * silenced/unsilenced by the system.
     * @param executor {@link Executor} to handle the callbacks.
     * @param cb non-null callback to register
     */
    public void registerAudioRecordingCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AudioManager.AudioRecordingCallback cb) {
        mRecordingInfoImpl.registerAudioRecordingCallback(executor, cb);
    }

    /**
     * Unregister an audio recording callback previously registered with
     * {@link #registerAudioRecordingCallback(Executor, AudioManager.AudioRecordingCallback)}.
     * @param cb non-null callback to unregister
     */
    public void unregisterAudioRecordingCallback(@NonNull AudioManager.AudioRecordingCallback cb) {
        mRecordingInfoImpl.unregisterAudioRecordingCallback(cb);
    }

    /**
     * Returns the current active audio recording for this audio recorder.
     * @return a valid {@link AudioRecordingConfiguration} if this recorder is active
     * or null otherwise.
     * @see AudioRecordingConfiguration
     */
    public @Nullable AudioRecordingConfiguration getActiveRecordingConfiguration() {
        return mRecordingInfoImpl.getActiveRecordingConfiguration();
    }

    //---------------------------------------------------------
    // Implementation of AudioRecordingMonitorClient interface
    //--------------------
    /**
     * @hide
     */
    public int getPortId() {
        return native_getPortId();
    }

    //--------------------------------------------------------------------------
    // MicrophoneDirection
    //--------------------
    /**
     * Specifies the logical microphone (for processing).
     *
     * @param direction Direction constant.
     * @return true if sucessful.
     */
    public boolean setMicrophoneDirection(int direction) {
        return native_set_microphone_direction(direction) == 0;
    }

    /**
     * Specifies the zoom factor (i.e. the field dimension) for the selected microphone
     * (for processing). The selected microphone is determined by the use-case for the stream.
     *
     * @param zoom the desired field dimension of microphone capture. Range is from -1 (wide angle),
     * though 0 (no zoom) to 1 (maximum zoom).
     * @return true if sucessful.
     */
    public boolean setMicrophoneFieldDimension(@FloatRange(from = -1.0, to = 1.0) float zoom) {
        return native_set_microphone_field_dimension(zoom) == 0;
    }

    //---------------------------------------------------------
    // Interface definitions
    //--------------------
    /**
     * Interface definition for a callback to be invoked when an AudioRecord has
     * reached a notification marker set by {@link AudioRecord#setNotificationMarkerPosition(int)}
     * or for periodic updates on the progress of the record head, as set by
     * {@link AudioRecord#setPositionNotificationPeriod(int)}.
     */
    public interface OnRecordPositionUpdateListener  {
        /**
         * Called on the listener to notify it that the previously set marker has been reached
         * by the recording head.
         */
        void onMarkerReached(AudioRecord recorder);

        /**
         * Called on the listener to periodically notify it that the record head has reached
         * a multiple of the notification period.
         */
        void onPeriodicNotification(AudioRecord recorder);
    }



    //---------------------------------------------------------
    // Inner classes
    //--------------------

    /**
     * Helper class to handle the forwarding of native events to the appropriate listener
     * (potentially) handled in a different thread
     */
    private class NativeEventHandler extends Handler {
        private final AudioRecord mAudioRecord;

        NativeEventHandler(AudioRecord recorder, Looper looper) {
            super(looper);
            mAudioRecord = recorder;
        }

        @Override
        public void handleMessage(Message msg) {
            OnRecordPositionUpdateListener listener = null;
            synchronized (mPositionListenerLock) {
                listener = mAudioRecord.mPositionListener;
            }

            switch (msg.what) {
            case NATIVE_EVENT_MARKER:
                if (listener != null) {
                    listener.onMarkerReached(mAudioRecord);
                }
                break;
            case NATIVE_EVENT_NEW_POS:
                if (listener != null) {
                    listener.onPeriodicNotification(mAudioRecord);
                }
                break;
            default:
                loge("Unknown native event type: " + msg.what);
                break;
            }
        }
    }

    //---------------------------------------------------------
    // Java methods called from the native side
    //--------------------
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private static void postEventFromNative(Object audiorecord_ref,
            int what, int arg1, int arg2, Object obj) {
        //logd("Event posted from the native side: event="+ what + " args="+ arg1+" "+arg2);
        AudioRecord recorder = (AudioRecord)((WeakReference)audiorecord_ref).get();
        if (recorder == null) {
            return;
        }

        if (what == AudioSystem.NATIVE_EVENT_ROUTING_CHANGE) {
            recorder.broadcastRoutingChange();
            return;
        }

        if (recorder.mEventHandler != null) {
            Message m =
                recorder.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            recorder.mEventHandler.sendMessage(m);
        }

    }


    //---------------------------------------------------------
    // Native methods called from the Java side
    //--------------------

    @UnsupportedAppUsage
    private native final int native_setup(Object audiorecord_this,
            Object /*AudioAttributes*/ attributes,
            int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
            int buffSizeInBytes, int[] sessionId, String opPackageName,
            long nativeRecordInJavaObj);

    // TODO remove: implementation calls directly into implementation of native_release()
    private native final void native_finalize();

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public native final void native_release();

    private native final int native_start(int syncEvent, int sessionId);

    private native final void native_stop();

    private native final int native_read_in_byte_array(byte[] audioData,
            int offsetInBytes, int sizeInBytes, boolean isBlocking);

    private native final int native_read_in_short_array(short[] audioData,
            int offsetInShorts, int sizeInShorts, boolean isBlocking);

    private native final int native_read_in_float_array(float[] audioData,
            int offsetInFloats, int sizeInFloats, boolean isBlocking);

    private native final int native_read_in_direct_buffer(Object jBuffer,
            int sizeInBytes, boolean isBlocking);

    private native final int native_get_buffer_size_in_frames();

    private native final int native_set_marker_pos(int marker);
    private native final int native_get_marker_pos();

    private native final int native_set_pos_update_period(int updatePeriod);
    private native final int native_get_pos_update_period();

    static private native final int native_get_min_buff_size(
            int sampleRateInHz, int channelCount, int audioFormat);

    private native final boolean native_setInputDevice(int deviceId);
    private native final int native_getRoutedDeviceId();
    private native final void native_enableDeviceCallback();
    private native final void native_disableDeviceCallback();

    private native final int native_get_timestamp(@NonNull AudioTimestamp outTimestamp,
            @AudioTimestamp.Timebase int timebase);

    private native final int native_get_active_microphones(
            ArrayList<MicrophoneInfo> activeMicrophones);

    private native int native_getPortId();

    private native int native_set_microphone_direction(int direction);
    private native int native_set_microphone_field_dimension(float zoom);

    //---------------------------------------------------------
    // Utility methods
    //------------------

    private static void logd(String msg) {
        Log.d(TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, msg);
    }

    public static final class MetricsConstants
    {
        private MetricsConstants() {}

        // MM_PREFIX is slightly different than TAG, used to avoid cut-n-paste errors.
        private static final String MM_PREFIX = "android.media.audiorecord.";

        /**
         * Key to extract the audio data encoding for this track
         * from the {@link AudioRecord#getMetrics} return value.
         * The value is a {@code String}.
         */
        public static final String ENCODING = MM_PREFIX + "encoding";

        /**
         * Key to extract the source type for this track
         * from the {@link AudioRecord#getMetrics} return value.
         * The value is a {@code String}.
         */
        public static final String SOURCE = MM_PREFIX + "source";

        /**
         * Key to extract the estimated latency through the recording pipeline
         * from the {@link AudioRecord#getMetrics} return value.
         * This is in units of milliseconds.
         * The value is an {@code int}.
         * @deprecated Not properly supported in the past.
         */
        @Deprecated
        public static final String LATENCY = MM_PREFIX + "latency";

        /**
         * Key to extract the sink sample rate for this record track in Hz
         * from the {@link AudioRecord#getMetrics} return value.
         * The value is an {@code int}.
         */
        public static final String SAMPLERATE = MM_PREFIX + "samplerate";

        /**
         * Key to extract the number of channels being recorded in this record track
         * from the {@link AudioRecord#getMetrics} return value.
         * The value is an {@code int}.
         */
        public static final String CHANNELS = MM_PREFIX + "channels";

        /**
         * Use for testing only. Do not expose.
         * The native channel mask.
         * The value is a {@code long}.
         * @hide
         */
        @TestApi
        public static final String CHANNEL_MASK = MM_PREFIX + "channelMask";


        /**
         * Use for testing only. Do not expose.
         * The port id of this input port in audioserver.
         * The value is an {@code int}.
         * @hide
         */
        @TestApi
        public static final String PORT_ID = MM_PREFIX + "portId";

        /**
         * Use for testing only. Do not expose.
         * The buffer frameCount.
         * The value is an {@code int}.
         * @hide
         */
        @TestApi
        public static final String FRAME_COUNT = MM_PREFIX + "frameCount";

        /**
         * Use for testing only. Do not expose.
         * The actual record track attributes used.
         * The value is a {@code String}.
         * @hide
         */
        @TestApi
        public static final String ATTRIBUTES = MM_PREFIX + "attributes";

        /**
         * Use for testing only. Do not expose.
         * The buffer frameCount
         * The value is a {@code double}.
         * @hide
         */
        @TestApi
        public static final String DURATION_MS = MM_PREFIX + "durationMs";

        /**
         * Use for testing only. Do not expose.
         * The number of times the record track has started
         * The value is a {@code long}.
         * @hide
         */
        @TestApi
        public static final String START_COUNT = MM_PREFIX + "startCount";
    }
}
