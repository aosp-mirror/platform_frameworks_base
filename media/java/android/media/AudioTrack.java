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

import static android.media.AudioManager.AUDIO_SESSION_ID_GENERATE;
import static android.media.audio.Flags.FLAG_ROUTED_DEVICE_IDS;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.AttributionSource.ScopedParcelState;
import android.content.Context;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.media.metrics.LogSessionId;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.NioUtils;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

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
public class AudioTrack extends PlayerBase
                        implements AudioRouting
                                 , VolumeAutomation
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

    /** indicates AudioTrack state is stopped */
    public static final int PLAYSTATE_STOPPED = 1;  // matches SL_PLAYSTATE_STOPPED
    /** indicates AudioTrack state is paused */
    public static final int PLAYSTATE_PAUSED  = 2;  // matches SL_PLAYSTATE_PAUSED
    /** indicates AudioTrack state is playing */
    public static final int PLAYSTATE_PLAYING = 3;  // matches SL_PLAYSTATE_PLAYING
    /**
      * @hide
      * indicates AudioTrack state is stopping waiting for NATIVE_EVENT_STREAM_END to
      * transition to PLAYSTATE_STOPPED.
      * Only valid for offload mode.
      */
    private static final int PLAYSTATE_STOPPING = 4;
    /**
      * @hide
      * indicates AudioTrack state is paused from stopping state. Will transition to
      * PLAYSTATE_STOPPING if play() is called.
      * Only valid for offload mode.
      */
    private static final int PLAYSTATE_PAUSED_STOPPING = 5;

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
    /**
     * An error code indicating that the object reporting it is no longer valid and needs to
     * be recreated.
     */
    public  static final int ERROR_DEAD_OBJECT                     = AudioSystem.DEAD_OBJECT;
    /**
     * {@link #getTimestampWithStatus(AudioTimestamp)} is called in STOPPED or FLUSHED state,
     * or immediately after start/ACTIVE.
     * @hide
     */
    public  static final int ERROR_WOULD_BLOCK                     = AudioSystem.WOULD_BLOCK;

    // Error codes:
    // to keep in sync with frameworks/base/core/jni/android_media_AudioTrack.cpp
    private static final int ERROR_NATIVESETUP_AUDIOSYSTEM         = -16;
    private static final int ERROR_NATIVESETUP_INVALIDCHANNELMASK  = -17;
    private static final int ERROR_NATIVESETUP_INVALIDFORMAT       = -18;
    private static final int ERROR_NATIVESETUP_INVALIDSTREAMTYPE   = -19;
    private static final int ERROR_NATIVESETUP_NATIVEINITFAILED    = -20;

    // Events:
    // to keep in sync with frameworks/av/include/media/AudioTrack.h
    // Note: To avoid collisions with other event constants,
    // do not define an event here that is the same value as
    // AudioSystem.NATIVE_EVENT_ROUTING_CHANGE.

    /**
     * Event id denotes when playback head has reached a previously set marker.
     */
    private static final int NATIVE_EVENT_MARKER  = 3;
    /**
     * Event id denotes when previously set update period has elapsed during playback.
     */
    private static final int NATIVE_EVENT_NEW_POS = 4;
    /**
     * Callback for more data
     */
    private static final int NATIVE_EVENT_CAN_WRITE_MORE_DATA = 9;
    /**
     * IAudioTrack tear down for offloaded tracks
     * TODO: when received, java AudioTrack must be released
     */
    private static final int NATIVE_EVENT_NEW_IAUDIOTRACK = 6;
    /**
     * Event id denotes when all the buffers queued in AF and HW are played
     * back (after stop is called) for an offloaded track.
     */
    private static final int NATIVE_EVENT_STREAM_END = 7;
    /**
     * Event id denotes when the codec format changes.
     *
     * Note: Similar to a device routing change (AudioSystem.NATIVE_EVENT_ROUTING_CHANGE),
     * this event comes from the AudioFlinger Thread / Output Stream management
     * (not from buffer indications as above).
     */
    private static final int NATIVE_EVENT_CODEC_FORMAT_CHANGE = 100;

    private final static String TAG = "android.media.AudioTrack";

    /** @hide */
    @IntDef({
        ENCAPSULATION_MODE_NONE,
        ENCAPSULATION_MODE_ELEMENTARY_STREAM,
        // ENCAPSULATION_MODE_HANDLE, @SystemApi
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapsulationMode {}

    // Important: The ENCAPSULATION_MODE values must be kept in sync with native header files.
    /**
     * This mode indicates no metadata encapsulation,
     * which is the default mode for sending audio data
     * through {@code AudioTrack}.
     */
    public static final int ENCAPSULATION_MODE_NONE = 0;
    /**
     * This mode indicates metadata encapsulation with an elementary stream payload.
     * Both compressed and PCM format is allowed.
     */
    public static final int ENCAPSULATION_MODE_ELEMENTARY_STREAM = 1;
    /**
     * This mode indicates metadata encapsulation with a handle payload
     * and is set through {@link Builder#setEncapsulationMode(int)}.
     * The handle is a 64 bit long, provided by the Tuner API
     * in {@link android.os.Build.VERSION_CODES#R}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int ENCAPSULATION_MODE_HANDLE = 2;

    /**
     * Enumeration of metadata types permitted for use by
     * encapsulation mode audio streams.
     * @hide
     */
    @IntDef(prefix = {"ENCAPSULATION_METADATA_TYPE_"},
            value =
                    {
                            ENCAPSULATION_METADATA_TYPE_NONE, /* reserved */
                            ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER,
                            ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR,
                            ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapsulationMetadataType {}

    /**
     * Reserved do not use.
     * @hide
     */
    public static final int ENCAPSULATION_METADATA_TYPE_NONE = 0; // reserved

    /**
     * Encapsulation metadata type for framework tuner information.
     *
     * Refer to the Android Media TV Tuner API for details.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER = 1;

    /**
     * Encapsulation metadata type for DVB AD descriptor.
     *
     * This metadata is formatted per ETSI TS 101 154 Table E.1: AD_descriptor.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR = 2;

    /**
     * Encapsulation metadata type for placement of supplementary audio.
     *
     * A 32 bit integer constant, one of {@link #SUPPLEMENTARY_AUDIO_PLACEMENT_NORMAL}, {@link
     * #SUPPLEMENTARY_AUDIO_PLACEMENT_LEFT}, {@link #SUPPLEMENTARY_AUDIO_PLACEMENT_RIGHT}.
     */
    public static final int ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT = 3;

    /**
     * Enumeration of supplementary audio placement types.
     * @hide
     */
    @IntDef(prefix = {"SUPPLEMENTARY_AUDIO_PLACEMENT_"},
            value =
                    {
                            SUPPLEMENTARY_AUDIO_PLACEMENT_NORMAL,
                            SUPPLEMENTARY_AUDIO_PLACEMENT_LEFT,
                            SUPPLEMENTARY_AUDIO_PLACEMENT_RIGHT,
                    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SupplementaryAudioPlacement {}
    // Important: The SUPPLEMENTARY_AUDIO_PLACEMENT values must be kept in sync with native header
    // files.

    /**
     * Supplementary audio placement normal.
     */
    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_NORMAL = 0;

    /**
     * Supplementary audio placement left.
     */
    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_LEFT = 1;

    /**
     * Supplementary audio placement right.
     */
    public static final int SUPPLEMENTARY_AUDIO_PLACEMENT_RIGHT = 2;

    /* Dual Mono handling is used when a stereo audio stream
     * contains separate audio content on the left and right channels.
     * Such information about the content of the stream may be found, for example, in
     * ITU T-REC-J.94-201610 A.6.2.3 Component descriptor.
     */
    /** @hide */
    @IntDef({
        DUAL_MONO_MODE_OFF,
        DUAL_MONO_MODE_LR,
        DUAL_MONO_MODE_LL,
        DUAL_MONO_MODE_RR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DualMonoMode {}
    // Important: The DUAL_MONO_MODE values must be kept in sync with native header files.
    /**
     * This mode disables any Dual Mono presentation effect.
     *
     */
    public static final int DUAL_MONO_MODE_OFF = 0;

    /**
     * This mode indicates that a stereo stream should be presented
     * with the left and right audio channels blended together
     * and delivered to both channels.
     *
     * Behavior for non-stereo streams is implementation defined.
     * A suggested guideline is that the left-right stereo symmetric
     * channels are pairwise blended;
     * the other channels such as center are left alone.
     *
     * The Dual Mono effect occurs before volume scaling.
     */
    public static final int DUAL_MONO_MODE_LR = 1;

    /**
     * This mode indicates that a stereo stream should be presented
     * with the left audio channel replicated into the right audio channel.
     *
     * Behavior for non-stereo streams is implementation defined.
     * A suggested guideline is that all channels with left-right
     * stereo symmetry will have the left channel position replicated
     * into the right channel position.
     * The center channels (with no left/right symmetry) or unbalanced
     * channels are left alone.
     *
     * The Dual Mono effect occurs before volume scaling.
     */
    public static final int DUAL_MONO_MODE_LL = 2;

    /**
     * This mode indicates that a stereo stream should be presented
     * with the right audio channel replicated into the left audio channel.
     *
     * Behavior for non-stereo streams is implementation defined.
     * A suggested guideline is that all channels with left-right
     * stereo symmetry will have the right channel position replicated
     * into the left channel position.
     * The center channels (with no left/right symmetry) or unbalanced
     * channels are left alone.
     *
     * The Dual Mono effect occurs before volume scaling.
     */
    public static final int DUAL_MONO_MODE_RR = 3;

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

    /** @hide */
    @IntDef({
        PERFORMANCE_MODE_NONE,
        PERFORMANCE_MODE_LOW_LATENCY,
        PERFORMANCE_MODE_POWER_SAVING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PerformanceMode {}

    /**
     * Default performance mode for an {@link AudioTrack}.
     */
    public static final int PERFORMANCE_MODE_NONE = 0;

    /**
     * Low latency performance mode for an {@link AudioTrack}.
     * If the device supports it, this mode
     * enables a lower latency path through to the audio output sink.
     * Effects may no longer work with such an {@code AudioTrack} and
     * the sample rate must match that of the output sink.
     * <p>
     * Applications should be aware that low latency requires careful
     * buffer management, with smaller chunks of audio data written by each
     * {@code write()} call.
     * <p>
     * If this flag is used without specifying a {@code bufferSizeInBytes} then the
     * {@code AudioTrack}'s actual buffer size may be too small.
     * It is recommended that a fairly
     * large buffer should be specified when the {@code AudioTrack} is created.
     * Then the actual size can be reduced by calling
     * {@link #setBufferSizeInFrames(int)}. The buffer size can be optimized
     * by lowering it after each {@code write()} call until the audio glitches,
     * which is detected by calling
     * {@link #getUnderrunCount()}. Then the buffer size can be increased
     * until there are no glitches.
     * This tuning step should be done while playing silence.
     * This technique provides a compromise between latency and glitch rate.
     */
    public static final int PERFORMANCE_MODE_LOW_LATENCY = 1;

    /**
     * Power saving performance mode for an {@link AudioTrack}.
     * If the device supports it, this
     * mode will enable a lower power path to the audio output sink.
     * In addition, this lower power path typically will have
     * deeper internal buffers and better underrun resistance,
     * with a tradeoff of higher latency.
     * <p>
     * In this mode, applications should attempt to use a larger buffer size
     * and deliver larger chunks of audio data per {@code write()} call.
     * Use {@link #getBufferSizeInFrames()} to determine
     * the actual buffer size of the {@code AudioTrack} as it may have increased
     * to accommodate a deeper buffer.
     */
    public static final int PERFORMANCE_MODE_POWER_SAVING = 2;

    // keep in sync with system/media/audio/include/system/audio-base.h
    private static final int AUDIO_OUTPUT_FLAG_FAST = 0x4;
    private static final int AUDIO_OUTPUT_FLAG_DEEP_BUFFER = 0x8;

    // Size of HW_AV_SYNC track AV header.
    private static final float HEADER_V2_SIZE_BYTES = 20.0f;

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
     * Indicates that we are expecting an end of stream callback following a call
     * to setOffloadEndOfStream() in a gapless track transition context. The native track
     * will be restarted automatically.
     */
    private boolean mOffloadEosPending = false;

    /**
     * Lock to ensure mPlayState updates reflect the actual state of the object.
     */
    private final Object mPlayStateLock = new Object();
    /**
     * Sizes of the audio buffer.
     * These values are set during construction and can be stale.
     * To obtain the current audio buffer frame count use {@link #getBufferSizeInFrames()}.
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
     * Never {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED}.
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
    @UnsupportedAppUsage
    private int mStreamType = AudioManager.STREAM_MUSIC;

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
     * The AudioAttributes used in configuration.
     */
    private AudioAttributes mConfiguredAudioAttributes;
    /**
     * Audio session ID
     */
    private int mSessionId = AUDIO_SESSION_ID_GENERATE;
    /**
     * HW_AV_SYNC track AV Sync Header
     */
    private ByteBuffer mAvSyncHeader = null;
    /**
     * HW_AV_SYNC track audio data bytes remaining to write after current AV sync header
     */
    private int mAvSyncBytesRemaining = 0;
    /**
     * Offset of the first sample of the audio in byte from start of HW_AV_SYNC track AV header.
     */
    private int mOffset = 0;
    /**
     * Indicates whether the track is intended to play in offload mode.
     */
    private boolean mOffloaded = false;
    /**
     * When offloaded track: delay for decoder in frames
     */
    private int mOffloadDelayFrames = 0;
    /**
     * When offloaded track: padding for decoder in frames
     */
    private int mOffloadPaddingFrames = 0;

    /**
     * The log session id used for metrics.
     * {@link LogSessionId#LOG_SESSION_ID_NONE} here means it is not set.
     */
    @NonNull private LogSessionId mLogSessionId = LogSessionId.LOG_SESSION_ID_NONE;

    private AudioPolicy mAudioPolicy;

    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /**
     * @hide
     * Accessed by native methods: provides access to C++ AudioTrack object.
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    protected long mNativeTrackInJavaObj;
    /**
     * Accessed by native methods: provides access to the JNI data (i.e. resources used by
     * the native AudioTrack object, but not stored in it).
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
     *   {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED} means to use a route-dependent value
     *   which is usually the sample rate of the sink.
     *   {@link #getSampleRate()} can be used to retrieve the actual sample rate chosen.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_OUT_MONO} and
     *   {@link AudioFormat#CHANNEL_OUT_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT},
     *   {@link AudioFormat#ENCODING_PCM_8BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     * @param bufferSizeInBytes the total size (in bytes) of the internal buffer where audio data is
     *   read from for playback. This should be a nonzero multiple of the frame size in bytes.
     *   <p> If the track's creation mode is {@link #MODE_STATIC},
     *   this is the maximum length sample, or audio clip, that can be played by this instance.
     *   <p> If the track's creation mode is {@link #MODE_STREAM},
     *   this should be the desired buffer size
     *   for the <code>AudioTrack</code> to satisfy the application's
     *   latency requirements.
     *   If <code>bufferSizeInBytes</code> is less than the
     *   minimum buffer size for the output sink, it is increased to the minimum
     *   buffer size.
     *   The method {@link #getBufferSizeInFrames()} returns the
     *   actual size in frames of the buffer created, which
     *   determines the minimum frequency to write
     *   to the streaming <code>AudioTrack</code> to avoid underrun.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the estimated minimum buffer size
     *   for an AudioTrack instance in streaming mode.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @throws java.lang.IllegalArgumentException
     * @deprecated use {@link Builder} or
     *   {@link #AudioTrack(AudioAttributes, AudioFormat, int, int, int)} to specify the
     *   {@link AudioAttributes} instead of the stream type which is only for volume control.
     */
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode)
    throws IllegalArgumentException {
        this(streamType, sampleRateInHz, channelConfig, audioFormat,
                bufferSizeInBytes, mode, AUDIO_SESSION_ID_GENERATE);
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
     *   {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED} means to use a route-dependent value
     *   which is usually the sample rate of the sink.
     * @param channelConfig describes the configuration of the audio channels.
     *   See {@link AudioFormat#CHANNEL_OUT_MONO} and
     *   {@link AudioFormat#CHANNEL_OUT_STEREO}
     * @param audioFormat the format in which the audio data is represented.
     *   See {@link AudioFormat#ENCODING_PCM_16BIT} and
     *   {@link AudioFormat#ENCODING_PCM_8BIT},
     *   and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     * @param bufferSizeInBytes the total size (in bytes) of the internal buffer where audio data is
     *   read from for playback. This should be a nonzero multiple of the frame size in bytes.
     *   <p> If the track's creation mode is {@link #MODE_STATIC},
     *   this is the maximum length sample, or audio clip, that can be played by this instance.
     *   <p> If the track's creation mode is {@link #MODE_STREAM},
     *   this should be the desired buffer size
     *   for the <code>AudioTrack</code> to satisfy the application's
     *   latency requirements.
     *   If <code>bufferSizeInBytes</code> is less than the
     *   minimum buffer size for the output sink, it is increased to the minimum
     *   buffer size.
     *   The method {@link #getBufferSizeInFrames()} returns the
     *   actual size in frames of the buffer created, which
     *   determines the minimum frequency to write
     *   to the streaming <code>AudioTrack</code> to avoid underrun.
     *   You can write data into this buffer in smaller chunks than this size.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the estimated minimum buffer size
     *   for an AudioTrack instance in streaming mode.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @param sessionId Id of audio session the AudioTrack must be attached to
     * @throws java.lang.IllegalArgumentException
     * @deprecated use {@link Builder} or
     *   {@link #AudioTrack(AudioAttributes, AudioFormat, int, int, int)} to specify the
     *   {@link AudioAttributes} instead of the stream type which is only for volume control.
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
        deprecateStreamTypeForPlayback(streamType, "AudioTrack", "AudioTrack()");
    }

    /**
     * Class constructor with {@link AudioAttributes} and {@link AudioFormat}.
     * @param attributes a non-null {@link AudioAttributes} instance.
     * @param format a non-null {@link AudioFormat} instance describing the format of the data
     *     that will be played through this AudioTrack. See {@link AudioFormat.Builder} for
     *     configuring the audio format parameters such as encoding, channel mask and sample rate.
     * @param bufferSizeInBytes the total size (in bytes) of the internal buffer where audio data is
     *   read from for playback. This should be a nonzero multiple of the frame size in bytes.
     *   <p> If the track's creation mode is {@link #MODE_STATIC},
     *   this is the maximum length sample, or audio clip, that can be played by this instance.
     *   <p> If the track's creation mode is {@link #MODE_STREAM},
     *   this should be the desired buffer size
     *   for the <code>AudioTrack</code> to satisfy the application's
     *   latency requirements.
     *   If <code>bufferSizeInBytes</code> is less than the
     *   minimum buffer size for the output sink, it is increased to the minimum
     *   buffer size.
     *   The method {@link #getBufferSizeInFrames()} returns the
     *   actual size in frames of the buffer created, which
     *   determines the minimum frequency to write
     *   to the streaming <code>AudioTrack</code> to avoid underrun.
     *   See {@link #getMinBufferSize(int, int, int)} to determine the estimated minimum buffer size
     *   for an AudioTrack instance in streaming mode.
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
        this(null /* context */, attributes, format, bufferSizeInBytes, mode, sessionId,
                false /*offload*/, ENCAPSULATION_MODE_NONE, null /* tunerConfiguration */);
    }

    private AudioTrack(@Nullable Context context, AudioAttributes attributes, AudioFormat format,
            int bufferSizeInBytes, int mode, int sessionId, boolean offload, int encapsulationMode,
            @Nullable TunerConfiguration tunerConfiguration)
                    throws IllegalArgumentException {
        super(attributes, AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK);
        // mState already == STATE_UNINITIALIZED

        mConfiguredAudioAttributes = attributes; // object copy not needed, immutable.

        if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat");
        }

        // Check if we should enable deep buffer mode
        if (shouldEnablePowerSaving(mAttributes, format, bufferSizeInBytes, mode)) {
            mAttributes = new AudioAttributes.Builder(mAttributes)
                .replaceFlags((mAttributes.getAllFlags()
                        | AudioAttributes.FLAG_DEEP_BUFFER)
                        & ~AudioAttributes.FLAG_LOW_LATENCY)
                .build();
        }

        // remember which looper is associated with the AudioTrack instantiation
        Looper looper;
        if ((looper = Looper.myLooper()) == null) {
            looper = Looper.getMainLooper();
        }

        int rate = format.getSampleRate();
        if (rate == AudioFormat.SAMPLE_RATE_UNSPECIFIED) {
            rate = 0;
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
        mOffloaded = offload;
        mStreamType = AudioSystem.STREAM_DEFAULT;

        audioBuffSizeCheck(bufferSizeInBytes);

        mInitializationLooper = looper;

        if (sessionId < 0) {
            throw new IllegalArgumentException("Invalid audio session ID: "+sessionId);
        }

        int[] sampleRate = new int[] {mSampleRate};
        int[] session = new int[1];
        session[0] = resolvePlaybackSessionId(context, sessionId);

        AttributionSource attributionSource = context == null
                ? AttributionSource.myAttributionSource() : context.getAttributionSource();

        // native initialization
        try (ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState()) {
            int initResult = native_setup(new WeakReference<AudioTrack>(this), mAttributes,
                    sampleRate, mChannelMask, mChannelIndexMask, mAudioFormat,
                    mNativeBufferSizeInBytes, mDataLoadMode, session,
                    attributionSourceState.getParcel(), 0 /*nativeTrackInJavaObj*/, offload,
                    encapsulationMode, tunerConfiguration, getCurrentOpPackageName());
            if (initResult != SUCCESS) {
                loge("Error code " + initResult + " when initializing AudioTrack.");
                return; // with mState == STATE_UNINITIALIZED
            }
        }

        mSampleRate = sampleRate[0];
        mSessionId = session[0];

        // TODO: consider caching encapsulationMode and tunerConfiguration in the Java object.

        if ((mAttributes.getFlags() & AudioAttributes.FLAG_HW_AV_SYNC) != 0) {
            int frameSizeInBytes;
            if (AudioFormat.isEncodingLinearFrames(mAudioFormat)) {
                frameSizeInBytes = mChannelCount * AudioFormat.getBytesPerSample(mAudioFormat);
            } else {
                frameSizeInBytes = 1;
            }
            mOffset = ((int) Math.ceil(HEADER_V2_SIZE_BYTES / frameSizeInBytes)) * frameSizeInBytes;
        }

        if (mDataLoadMode == MODE_STATIC) {
            mState = STATE_NO_STATIC_DATA;
        } else {
            mState = STATE_INITIALIZED;
        }

        baseRegisterPlayer(mSessionId);
        native_setPlayerIId(mPlayerIId); // mPlayerIId now ready to send to native AudioTrack.
    }

    /**
     * A constructor which explicitly connects a Native (C++) AudioTrack. For use by
     * the AudioTrackRoutingProxy subclass.
     * @param nativeTrackInJavaObj a C/C++ pointer to a native AudioTrack
     * (associated with an OpenSL ES player).
     * IMPORTANT: For "N", this method is ONLY called to setup a Java routing proxy,
     * i.e. IAndroidConfiguration::AcquireJavaProxy(). If we call with a 0 in nativeTrackInJavaObj
     * it means that the OpenSL player interface hasn't been realized, so there is no native
     * Audiotrack to connect to. In this case wait to call deferred_connect() until the
     * OpenSLES interface is realized.
     */
    /*package*/ AudioTrack(long nativeTrackInJavaObj) {
        super(new AudioAttributes.Builder().build(),
                AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK);
        // "final"s
        mNativeTrackInJavaObj = 0;
        mJniData = 0;

        // remember which looper is associated with the AudioTrack instantiation
        Looper looper;
        if ((looper = Looper.myLooper()) == null) {
            looper = Looper.getMainLooper();
        }
        mInitializationLooper = looper;

        // other initialization...
        if (nativeTrackInJavaObj != 0) {
            baseRegisterPlayer(AudioSystem.AUDIO_SESSION_ALLOCATE);
            deferred_connect(nativeTrackInJavaObj);
        } else {
            mState = STATE_UNINITIALIZED;
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    /* package */ void deferred_connect(long nativeTrackInJavaObj) {
        if (mState != STATE_INITIALIZED) {
            // Note that for this native_setup, we are providing an already created/initialized
            // *Native* AudioTrack, so the attributes parameters to native_setup() are ignored.
            int[] session = { 0 };
            int[] rates = { 0 };
            try (ScopedParcelState attributionSourceState =
                         AttributionSource.myAttributionSource().asScopedParcelState()) {
                int initResult = native_setup(new WeakReference<AudioTrack>(this),
                        null /*mAttributes - NA*/,
                        rates /*sampleRate - NA*/,
                        0 /*mChannelMask - NA*/,
                        0 /*mChannelIndexMask - NA*/,
                        0 /*mAudioFormat - NA*/,
                        0 /*mNativeBufferSizeInBytes - NA*/,
                        0 /*mDataLoadMode - NA*/,
                        session,
                        attributionSourceState.getParcel(),
                        nativeTrackInJavaObj,
                        false /*offload*/,
                        ENCAPSULATION_MODE_NONE,
                        null /* tunerConfiguration */,
                        "" /* opPackagename */);
                if (initResult != SUCCESS) {
                    loge("Error code " + initResult + " when initializing AudioTrack.");
                    return; // with mState == STATE_UNINITIALIZED
                }
            }

            mSessionId = session[0];

            mState = STATE_INITIALIZED;
        }
    }

    /**
     * TunerConfiguration is used to convey tuner information
     * from the android.media.tv.Tuner API to AudioTrack construction.
     *
     * Use the Builder to construct the TunerConfiguration object,
     * which is then used by the {@link AudioTrack.Builder} to create an AudioTrack.
     * @hide
     */
    @SystemApi
    public static class TunerConfiguration {
        private final int mContentId;
        private final int mSyncId;

        /**
         * A special content id for {@link #TunerConfiguration(int, int)}
         * indicating audio is delivered
         * from an {@code AudioTrack} write, not tunneled from the tuner stack.
         */
        public static final int CONTENT_ID_NONE = 0;

        /**
         * Constructs a TunerConfiguration instance for use in {@link AudioTrack.Builder}
         *
         * @param contentId selects the audio stream to use.
         *     The contentId may be obtained from
         *     {@link android.media.tv.tuner.filter.Filter#getId()},
         *     such obtained id is always a positive number.
         *     If audio is to be delivered through an {@code AudioTrack} write
         *     then {@code CONTENT_ID_NONE} may be used.
         * @param syncId selects the clock to use for synchronization
         *     of audio with other streams such as video.
         *     The syncId may be obtained from
         *     {@link android.media.tv.tuner.Tuner#getAvSyncHwId()}.
         *     This is always a positive number.
         */
        @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
        public TunerConfiguration(
                @IntRange(from = 0) int contentId, @IntRange(from = 1)int syncId) {
            if (contentId < 0) {
                throw new IllegalArgumentException(
                        "contentId " + contentId + " must be positive or CONTENT_ID_NONE");
            }
            if (syncId < 1) {
                throw new IllegalArgumentException("syncId " + syncId + " must be positive");
            }
            mContentId = contentId;
            mSyncId = syncId;
        }

        /**
         * Returns the contentId.
         */
        @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
        public @IntRange(from = 1) int getContentId() {
            return mContentId; // The Builder ensures this is > 0.
        }

        /**
         * Returns the syncId.
         */
        @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
        public @IntRange(from = 1) int getSyncId() {
            return mSyncId;  // The Builder ensures this is > 0.
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
     *                  .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
     *                  .build())
     *         .setAudioFormat(new AudioFormat.Builder()
     *                 .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
     *                 .setSampleRate(44100)
     *                 .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
     *                 .build())
     *         .setBufferSizeInBytes(minBuffSize)
     *         .build();
     * </pre>
     * <p>
     * If the audio attributes are not set with {@link #setAudioAttributes(AudioAttributes)},
     * attributes comprising {@link AudioAttributes#USAGE_MEDIA} will be used.
     * <br>If the audio format is not specified or is incomplete, its channel configuration will be
     * {@link AudioFormat#CHANNEL_OUT_STEREO} and the encoding will be
     * {@link AudioFormat#ENCODING_PCM_16BIT}.
     * The sample rate will depend on the device actually selected for playback and can be queried
     * with {@link #getSampleRate()} method.
     * <br>If the buffer size is not specified with {@link #setBufferSizeInBytes(int)},
     * and the mode is {@link AudioTrack#MODE_STREAM}, the minimum buffer size is used.
     * <br>If the transfer mode is not specified with {@link #setTransferMode(int)},
     * <code>MODE_STREAM</code> will be used.
     * <br>If the session ID is not specified with {@link #setSessionId(int)}, a new one will
     * be generated.
     * <br>Offload is false by default.
     */
    public static class Builder {
        private Context mContext;
        private AudioAttributes mAttributes;
        private AudioFormat mFormat;
        private int mBufferSizeInBytes;
        private int mEncapsulationMode = ENCAPSULATION_MODE_NONE;
        private int mSessionId = AUDIO_SESSION_ID_GENERATE;
        private int mMode = MODE_STREAM;
        private int mPerformanceMode = PERFORMANCE_MODE_NONE;
        private boolean mOffload = false;
        private TunerConfiguration mTunerConfiguration;
        private int mCallRedirectionMode = AudioManager.CALL_REDIRECT_NONE;

        /**
         * Constructs a new Builder with the default values as described above.
         */
        public Builder() {
        }

        /**
         * Sets the context the track belongs to. This context will be used to pull information,
         * such as {@link android.content.AttributionSource} and device specific audio session ids,
         * which will be associated with the {@link AudioTrack}. However, the context itself will
         * not be retained by the {@link AudioTrack}.
         * @param context a non-null {@link Context} instance
         * @return the same Builder instance.
         */
        public @NonNull Builder setContext(@NonNull Context context) {
            mContext = Objects.requireNonNull(context);
            return this;
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
         * the estimated minimum buffer size for the creation of an AudioTrack instance
         * in streaming mode.
         * <br>If using the <code>AudioTrack</code> in static mode (see
         * {@link AudioTrack#MODE_STATIC}), this is the maximum size of the sound that will be
         * played by this instance.
         * @param bufferSizeInBytes
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setBufferSizeInBytes(@IntRange(from = 0) int bufferSizeInBytes)
                throws IllegalArgumentException {
            if (bufferSizeInBytes <= 0) {
                throw new IllegalArgumentException("Invalid buffer size " + bufferSizeInBytes);
            }
            mBufferSizeInBytes = bufferSizeInBytes;
            return this;
        }

        /**
         * Sets the encapsulation mode.
         *
         * Encapsulation mode allows metadata to be sent together with
         * the audio data payload in a {@code ByteBuffer}.
         * This requires a compatible hardware audio codec.
         *
         * @param encapsulationMode one of {@link AudioTrack#ENCAPSULATION_MODE_NONE},
         *        or {@link AudioTrack#ENCAPSULATION_MODE_ELEMENTARY_STREAM}.
         * @return the same Builder instance.
         */
        // Note: with the correct permission {@code AudioTrack#ENCAPSULATION_MODE_HANDLE}
        // may be used as well.
        public @NonNull Builder setEncapsulationMode(@EncapsulationMode int encapsulationMode) {
            switch (encapsulationMode) {
                case ENCAPSULATION_MODE_NONE:
                case ENCAPSULATION_MODE_ELEMENTARY_STREAM:
                case ENCAPSULATION_MODE_HANDLE:
                    mEncapsulationMode = encapsulationMode;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid encapsulation mode " + encapsulationMode);
            }
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
         *
         * Note, that if there's a device specific session id asociated with the context, explicitly
         * setting a session id using this method will override it
         * (see {@link Builder#setContext(Context)}).
         * @param sessionId a strictly positive ID number retrieved from another
         *     <code>AudioTrack</code> via {@link AudioTrack#getAudioSessionId()} or allocated by
         *     {@link AudioManager} via {@link AudioManager#generateAudioSessionId()}, or
         *     {@link AudioManager#AUDIO_SESSION_ID_GENERATE}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public @NonNull Builder setSessionId(@IntRange(from = 1) int sessionId)
                throws IllegalArgumentException {
            if ((sessionId != AUDIO_SESSION_ID_GENERATE) && (sessionId < 1)) {
                throw new IllegalArgumentException("Invalid audio session ID " + sessionId);
            }
            mSessionId = sessionId;
            return this;
        }

        /**
         * Sets the {@link AudioTrack} performance mode.  This is an advisory request which
         * may not be supported by the particular device, and the framework is free
         * to ignore such request if it is incompatible with other requests or hardware.
         *
         * @param performanceMode one of
         * {@link AudioTrack#PERFORMANCE_MODE_NONE},
         * {@link AudioTrack#PERFORMANCE_MODE_LOW_LATENCY},
         * or {@link AudioTrack#PERFORMANCE_MODE_POWER_SAVING}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException if {@code performanceMode} is not valid.
         */
        public @NonNull Builder setPerformanceMode(@PerformanceMode int performanceMode) {
            switch (performanceMode) {
                case PERFORMANCE_MODE_NONE:
                case PERFORMANCE_MODE_LOW_LATENCY:
                case PERFORMANCE_MODE_POWER_SAVING:
                    mPerformanceMode = performanceMode;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid performance mode " + performanceMode);
            }
            return this;
        }

        /**
         * Sets whether this track will play through the offloaded audio path.
         * When set to true, at build time, the audio format will be checked against
         * {@link AudioManager#isOffloadedPlaybackSupported(AudioFormat,AudioAttributes)}
         * to verify the audio format used by this track is supported on the device's offload
         * path (if any).
         * <br>Offload is only supported for media audio streams, and therefore requires that
         * the usage be {@link AudioAttributes#USAGE_MEDIA}.
         * @param offload true to require the offload path for playback.
         * @return the same Builder instance.
         */
        public @NonNull Builder setOffloadedPlayback(boolean offload) {
            mOffload = offload;
            return this;
        }

        /**
         * Sets the tuner configuration for the {@code AudioTrack}.
         *
         * The {@link AudioTrack.TunerConfiguration} consists of parameters obtained from
         * the Android TV tuner API which indicate the audio content stream id and the
         * synchronization id for the {@code AudioTrack}.
         *
         * @param tunerConfiguration obtained by {@link AudioTrack.TunerConfiguration.Builder}.
         * @return the same Builder instance.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
        public @NonNull Builder setTunerConfiguration(
                @NonNull TunerConfiguration tunerConfiguration) {
            if (tunerConfiguration == null) {
                throw new IllegalArgumentException("tunerConfiguration is null");
            }
            mTunerConfiguration = tunerConfiguration;
            return this;
        }

        /**
         * @hide
         * Sets the {@link AudioTrack} call redirection mode.
         * Used when creating an AudioTrack to inject audio to call uplink path. The mode
         * indicates if the call is a PSTN call or a VoIP call in which case a dynamic audio
         * policy is created to use this track as the source for all capture with voice
         * communication preset.
         *
         * @param callRedirectionMode one of
         * {@link AudioManager#CALL_REDIRECT_NONE},
         * {@link AudioManager#CALL_REDIRECT_PSTN},
         * or {@link AAudioManager#CALL_REDIRECT_VOIP}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException if {@code callRedirectionMode} is not valid.
         */
        public @NonNull Builder setCallRedirectionMode(
                @AudioManager.CallRedirectionMode int callRedirectionMode) {
            switch (callRedirectionMode) {
                case AudioManager.CALL_REDIRECT_NONE:
                case AudioManager.CALL_REDIRECT_PSTN:
                case AudioManager.CALL_REDIRECT_VOIP:
                    mCallRedirectionMode = callRedirectionMode;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid call redirection mode " + callRedirectionMode);
            }
            return this;
        }

        private @NonNull AudioTrack buildCallInjectionTrack() {
            AudioMixingRule audioMixingRule = new AudioMixingRule.Builder()
                    .addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                            new AudioAttributes.Builder()
                                   .setCapturePreset(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                                   .setForCallRedirection()
                                   .build())
                    .setTargetMixRole(AudioMixingRule.MIX_ROLE_INJECTOR)
                    .build();
            AudioMix audioMix = new AudioMix.Builder(audioMixingRule)
                    .setFormat(mFormat)
                    .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                    .build();
            AudioPolicy audioPolicy =
                    new AudioPolicy.Builder(/*context=*/ mContext).addMix(audioMix).build();

            if (AudioManager.registerAudioPolicyStatic(audioPolicy) != 0) {
                throw new UnsupportedOperationException("Error: could not register audio policy");
            }
            AudioTrack track = audioPolicy.createAudioTrackSource(audioMix);
            if (track == null) {
                throw new UnsupportedOperationException("Cannot create injection AudioTrack");
            }
            track.unregisterAudioPolicyOnRelease(audioPolicy);
            return track;
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
            switch (mPerformanceMode) {
            case PERFORMANCE_MODE_LOW_LATENCY:
                mAttributes = new AudioAttributes.Builder(mAttributes)
                    .replaceFlags((mAttributes.getAllFlags()
                            | AudioAttributes.FLAG_LOW_LATENCY)
                            & ~AudioAttributes.FLAG_DEEP_BUFFER)
                    .build();
                break;
            case PERFORMANCE_MODE_NONE:
                if (!shouldEnablePowerSaving(mAttributes, mFormat, mBufferSizeInBytes, mMode)) {
                    break; // do not enable deep buffer mode.
                }
                // permitted to fall through to enable deep buffer
            case PERFORMANCE_MODE_POWER_SAVING:
                mAttributes = new AudioAttributes.Builder(mAttributes)
                .replaceFlags((mAttributes.getAllFlags()
                        | AudioAttributes.FLAG_DEEP_BUFFER)
                        & ~AudioAttributes.FLAG_LOW_LATENCY)
                .build();
                break;
            }

            if (mFormat == null) {
                mFormat = new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        //.setSampleRate(AudioFormat.SAMPLE_RATE_UNSPECIFIED)
                        .setEncoding(AudioFormat.ENCODING_DEFAULT)
                        .build();
            }

            if (mCallRedirectionMode == AudioManager.CALL_REDIRECT_VOIP) {
                return buildCallInjectionTrack();
            } else if (mCallRedirectionMode == AudioManager.CALL_REDIRECT_PSTN) {
                mAttributes = new AudioAttributes.Builder(mAttributes)
                        .setForCallRedirection()
                        .build();
            }

            if (mOffload) {
                if (mPerformanceMode == PERFORMANCE_MODE_LOW_LATENCY) {
                    throw new UnsupportedOperationException(
                            "Offload and low latency modes are incompatible");
                }
                if (AudioSystem.getDirectPlaybackSupport(mFormat, mAttributes)
                        == AudioSystem.DIRECT_NOT_SUPPORTED) {
                    throw new UnsupportedOperationException(
                            "Cannot create AudioTrack, offload format / attributes not supported");
                }
            }

            // TODO: Check mEncapsulationMode compatibility with MODE_STATIC, etc?

            // If the buffer size is not specified in streaming mode,
            // use a single frame for the buffer size and let the
            // native code figure out the minimum buffer size.
            if (mMode == MODE_STREAM && mBufferSizeInBytes == 0) {
                int bytesPerSample = 1;
                if (AudioFormat.isEncodingLinearFrames(mFormat.getEncoding())) {
                    try {
                        bytesPerSample = mFormat.getBytesPerSample(mFormat.getEncoding());
                    } catch (IllegalArgumentException e) {
                        // do nothing
                    }
                }
                mBufferSizeInBytes = mFormat.getChannelCount() * bytesPerSample;
            }

            try {
                final AudioTrack track = new AudioTrack(
                        mContext, mAttributes, mFormat, mBufferSizeInBytes, mMode, mSessionId,
                        mOffload, mEncapsulationMode, mTunerConfiguration);
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

    /**
     * Sets an {@link AudioPolicy} to automatically unregister when the track is released.
     *
     * <p>This is to prevent users of the call audio injection API from having to manually
     * unregister the policy that was used to create the track.
     */
    private void unregisterAudioPolicyOnRelease(AudioPolicy audioPolicy) {
        mAudioPolicy = audioPolicy;
    }

    /**
     * Configures the delay and padding values for the current compressed stream playing
     * in offload mode.
     * This can only be used on a track successfully initialized with
     * {@link AudioTrack.Builder#setOffloadedPlayback(boolean)}. The unit is frames, where a
     * frame indicates the number of samples per channel, e.g. 100 frames for a stereo compressed
     * stream corresponds to 200 decoded interleaved PCM samples.
     * @param delayInFrames number of frames to be ignored at the beginning of the stream. A value
     *     of 0 indicates no delay is to be applied.
     * @param paddingInFrames number of frames to be ignored at the end of the stream. A value of 0
     *     of 0 indicates no padding is to be applied.
     */
    public void setOffloadDelayPadding(@IntRange(from = 0) int delayInFrames,
            @IntRange(from = 0) int paddingInFrames) {
        if (paddingInFrames < 0) {
            throw new IllegalArgumentException("Illegal negative padding");
        }
        if (delayInFrames < 0) {
            throw new IllegalArgumentException("Illegal negative delay");
        }
        if (!mOffloaded) {
            throw new IllegalStateException("Illegal use of delay/padding on non-offloaded track");
        }
        if (mState == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Uninitialized track");
        }
        mOffloadDelayFrames = delayInFrames;
        mOffloadPaddingFrames = paddingInFrames;
        native_set_delay_padding(delayInFrames, paddingInFrames);
    }

    /**
     * Return the decoder delay of an offloaded track, expressed in frames, previously set with
     * {@link #setOffloadDelayPadding(int, int)}, or 0 if it was never modified.
     * <p>This delay indicates the number of frames to be ignored at the beginning of the stream.
     * This value can only be queried on a track successfully initialized with
     * {@link AudioTrack.Builder#setOffloadedPlayback(boolean)}.
     * @return decoder delay expressed in frames.
     */
    public @IntRange(from = 0) int getOffloadDelay() {
        if (!mOffloaded) {
            throw new IllegalStateException("Illegal query of delay on non-offloaded track");
        }
        if (mState == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Illegal query of delay on uninitialized track");
        }
        return mOffloadDelayFrames;
    }

    /**
     * Return the decoder padding of an offloaded track, expressed in frames, previously set with
     * {@link #setOffloadDelayPadding(int, int)}, or 0 if it was never modified.
     * <p>This padding indicates the number of frames to be ignored at the end of the stream.
     * This value can only be queried on a track successfully initialized with
     * {@link AudioTrack.Builder#setOffloadedPlayback(boolean)}.
     * @return decoder padding expressed in frames.
     */
    public @IntRange(from = 0) int getOffloadPadding() {
        if (!mOffloaded) {
            throw new IllegalStateException("Illegal query of padding on non-offloaded track");
        }
        if (mState == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Illegal query of padding on uninitialized track");
        }
        return mOffloadPaddingFrames;
    }

    /**
     * Declares that the last write() operation on this track provided the last buffer of this
     * stream.
     * After the end of stream, previously set padding and delay values are ignored.
     * Can only be called only if the AudioTrack is opened in offload mode
     * {@see Builder#setOffloadedPlayback(boolean)}.
     * Can only be called only if the AudioTrack is in state {@link #PLAYSTATE_PLAYING}
     * {@see #getPlayState()}.
     * Use this method in the same thread as any write() operation.
     */
    public void setOffloadEndOfStream() {
        if (!mOffloaded) {
            throw new IllegalStateException("EOS not supported on non-offloaded track");
        }
        if (mState == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Uninitialized track");
        }
        if (mPlayState != PLAYSTATE_PLAYING) {
            throw new IllegalStateException("EOS not supported if not playing");
        }
        synchronized (mStreamEventCbLock) {
            if (mStreamEventCbInfoList.size() == 0) {
                throw new IllegalStateException("EOS not supported without StreamEventCallback");
            }
        }

        synchronized (mPlayStateLock) {
            native_stop();
            mOffloadEosPending = true;
            mPlayState = PLAYSTATE_STOPPING;
        }
    }

    /**
     * Returns whether the track was built with {@link Builder#setOffloadedPlayback(boolean)} set
     * to {@code true}.
     * @return true if the track is using offloaded playback.
     */
    public boolean isOffloadedPlayback() {
        return mOffloaded;
    }

    /**
     * Returns whether direct playback of an audio format with the provided attributes is
     * currently supported on the system.
     * <p>Direct playback means that the audio stream is not resampled or downmixed
     * by the framework. Checking for direct support can help the app select the representation
     * of audio content that most closely matches the capabilities of the device and peripherials
     * (e.g. A/V receiver) connected to it. Note that the provided stream can still be re-encoded
     * or mixed with other streams, if needed.
     * <p>Also note that this query only provides information about the support of an audio format.
     * It does not indicate whether the resources necessary for the playback are available
     * at that instant.
     * @param format a non-null {@link AudioFormat} instance describing the format of
     *   the audio data.
     * @param attributes a non-null {@link AudioAttributes} instance.
     * @return true if the given audio format can be played directly.
     * @deprecated Use {@link AudioManager#getDirectPlaybackSupport(AudioFormat, AudioAttributes)}
     *             instead.
     */
    @Deprecated
    public static boolean isDirectPlaybackSupported(@NonNull AudioFormat format,
            @NonNull AudioAttributes attributes) {
        if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat argument");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes argument");
        }
        return native_is_direct_output_supported(format.getEncoding(), format.getSampleRate(),
                format.getChannelMask(), format.getChannelIndexMask(),
                attributes.getContentType(), attributes.getUsage(), attributes.getFlags());
    }

    /*
     * The MAX_LEVEL should be exactly representable by an IEEE 754-2008 base32 float.
     * This means fractions must be divisible by a power of 2. For example,
     * 10.25f is OK as 0.25 is 1/4, but 10.1f is NOT OK as 1/10 is not expressable by
     * a finite binary fraction.
     *
     * 48.f is the nominal max for API level {@link android os.Build.VERSION_CODES#R}.
     * We use this to suggest a baseline range for implementation.
     *
     * The API contract specification allows increasing this value in a future
     * API release, but not decreasing this value.
     */
    private static final float MAX_AUDIO_DESCRIPTION_MIX_LEVEL = 48.f;

    private static boolean isValidAudioDescriptionMixLevel(float level) {
        return !(Float.isNaN(level) || level > MAX_AUDIO_DESCRIPTION_MIX_LEVEL);
    }

    /**
     * Sets the Audio Description mix level in dB.
     *
     * For AudioTracks incorporating a secondary Audio Description stream
     * (where such contents may be sent through an Encapsulation Mode
     * other than {@link #ENCAPSULATION_MODE_NONE}).
     * or internally by a HW channel),
     * the level of mixing of the Audio Description to the Main Audio stream
     * is controlled by this method.
     *
     * Such mixing occurs <strong>prior</strong> to overall volume scaling.
     *
     * @param level a floating point value between
     *     {@code Float.NEGATIVE_INFINITY} to {@code +48.f},
     *     where {@code Float.NEGATIVE_INFINITY} means the Audio Description is not mixed
     *     and a level of {@code 0.f} means the Audio Description is mixed without scaling.
     * @return true on success, false on failure.
     */
    public boolean setAudioDescriptionMixLeveldB(
            @FloatRange(to = 48.f, toInclusive = true) float level) {
        if (!isValidAudioDescriptionMixLevel(level)) {
            throw new IllegalArgumentException("level is out of range" + level);
        }
        return native_set_audio_description_mix_level_db(level) == SUCCESS;
    }

    /**
     * Returns the Audio Description mix level in dB.
     *
     * If Audio Description mixing is unavailable from the hardware device,
     * a value of {@code Float.NEGATIVE_INFINITY} is returned.
     *
     * @return the current Audio Description Mix Level in dB.
     *     A value of {@code Float.NEGATIVE_INFINITY} means
     *     that the audio description is not mixed or
     *     the hardware is not available.
     *     This should reflect the <strong>true</strong> internal device mix level;
     *     hence the application might receive any floating value
     *     except {@code Float.NaN}.
     */
    public float getAudioDescriptionMixLeveldB() {
        float[] level = { Float.NEGATIVE_INFINITY };
        try {
            final int status = native_get_audio_description_mix_level_db(level);
            if (status != SUCCESS || Float.isNaN(level[0])) {
                return Float.NEGATIVE_INFINITY;
            }
        } catch (Exception e) {
            return Float.NEGATIVE_INFINITY;
        }
        return level[0];
    }

    private static boolean isValidDualMonoMode(@DualMonoMode int dualMonoMode) {
        switch (dualMonoMode) {
            case DUAL_MONO_MODE_OFF:
            case DUAL_MONO_MODE_LR:
            case DUAL_MONO_MODE_LL:
            case DUAL_MONO_MODE_RR:
                return true;
            default:
                return false;
        }
    }

    /**
     * Sets the Dual Mono mode presentation on the output device.
     *
     * The Dual Mono mode is generally applied to stereo audio streams
     * where the left and right channels come from separate sources.
     *
     * For compressed audio, where the decoding is done in hardware,
     * Dual Mono presentation needs to be performed
     * by the hardware output device
     * as the PCM audio is not available to the framework.
     *
     * @param dualMonoMode one of {@link #DUAL_MONO_MODE_OFF},
     *     {@link #DUAL_MONO_MODE_LR},
     *     {@link #DUAL_MONO_MODE_LL},
     *     {@link #DUAL_MONO_MODE_RR}.
     *
     * @return true on success, false on failure if the output device
     *     does not support Dual Mono mode.
     */
    public boolean setDualMonoMode(@DualMonoMode int dualMonoMode) {
        if (!isValidDualMonoMode(dualMonoMode)) {
            throw new IllegalArgumentException(
                    "Invalid Dual Mono mode " + dualMonoMode);
        }
        return native_set_dual_mono_mode(dualMonoMode) == SUCCESS;
    }

    /**
     * Returns the Dual Mono mode presentation setting.
     *
     * If no Dual Mono presentation is available for the output device,
     * then {@link #DUAL_MONO_MODE_OFF} is returned.
     *
     * @return one of {@link #DUAL_MONO_MODE_OFF},
     *     {@link #DUAL_MONO_MODE_LR},
     *     {@link #DUAL_MONO_MODE_LL},
     *     {@link #DUAL_MONO_MODE_RR}.
     */
    public @DualMonoMode int getDualMonoMode() {
        int[] dualMonoMode = { DUAL_MONO_MODE_OFF };
        try {
            final int status = native_get_dual_mono_mode(dualMonoMode);
            if (status != SUCCESS || !isValidDualMonoMode(dualMonoMode[0])) {
                return DUAL_MONO_MODE_OFF;
            }
        } catch (Exception e) {
            return DUAL_MONO_MODE_OFF;
        }
        return dualMonoMode[0];
    }

    // mask of all the positional channels supported, however the allowed combinations
    // are further restricted by the matching left/right rule and
    // AudioSystem.OUT_CHANNEL_COUNT_MAX
    private static final int SUPPORTED_OUT_CHANNELS =
            AudioFormat.CHANNEL_OUT_FRONT_LEFT |
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT |
            AudioFormat.CHANNEL_OUT_FRONT_CENTER |
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY |
            AudioFormat.CHANNEL_OUT_BACK_LEFT |
            AudioFormat.CHANNEL_OUT_BACK_RIGHT |
            AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER |
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER |
            AudioFormat.CHANNEL_OUT_BACK_CENTER |
            AudioFormat.CHANNEL_OUT_SIDE_LEFT |
            AudioFormat.CHANNEL_OUT_SIDE_RIGHT |
            AudioFormat.CHANNEL_OUT_TOP_CENTER |
            AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT |
            AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER |
            AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT |
            AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT |
            AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER |
            AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT |
            AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT |
            AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT |
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT |
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER |
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT |
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2 |
            AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT |
            AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT;

    // Returns a boolean whether the attributes, format, bufferSizeInBytes, mode allow
    // power saving to be automatically enabled for an AudioTrack. Returns false if
    // power saving is already enabled in the attributes parameter.
    private static boolean shouldEnablePowerSaving(
            @Nullable AudioAttributes attributes, @Nullable AudioFormat format,
            int bufferSizeInBytes, int mode) {
        // If no attributes, OK
        // otherwise check attributes for USAGE_MEDIA and CONTENT_UNKNOWN, MUSIC, or MOVIE.
        // Only consider flags that are not compatible with FLAG_DEEP_BUFFER. We include
        // FLAG_DEEP_BUFFER because if set the request is explicit and
        // shouldEnablePowerSaving() should return false.
        final int flags = attributes.getAllFlags()
                & (AudioAttributes.FLAG_DEEP_BUFFER | AudioAttributes.FLAG_LOW_LATENCY
                    | AudioAttributes.FLAG_HW_AV_SYNC | AudioAttributes.FLAG_BEACON);

        if (attributes != null &&
                (flags != 0  // cannot have any special flags
                || attributes.getUsage() != AudioAttributes.USAGE_MEDIA
                || (attributes.getContentType() != AudioAttributes.CONTENT_TYPE_UNKNOWN
                    && attributes.getContentType() != AudioAttributes.CONTENT_TYPE_SPEECH
                    && attributes.getContentType() != AudioAttributes.CONTENT_TYPE_MUSIC
                    && attributes.getContentType() != AudioAttributes.CONTENT_TYPE_MOVIE))) {
            return false;
        }

        // Format must be fully specified and be linear pcm
        if (format == null
                || format.getSampleRate() == AudioFormat.SAMPLE_RATE_UNSPECIFIED
                || !AudioFormat.isEncodingLinearPcm(format.getEncoding())
                || !AudioFormat.isValidEncoding(format.getEncoding())
                || format.getChannelCount() < 1) {
            return false;
        }

        // Mode must be streaming
        if (mode != MODE_STREAM) {
            return false;
        }

        // A buffer size of 0 is always compatible with deep buffer (when called from the Builder)
        // but for app compatibility we only use deep buffer power saving for large buffer sizes.
        if (bufferSizeInBytes != 0) {
            final long BUFFER_TARGET_MODE_STREAM_MS = 100;
            final int MILLIS_PER_SECOND = 1000;
            final long bufferTargetSize =
                    BUFFER_TARGET_MODE_STREAM_MS
                    * format.getChannelCount()
                    * format.getBytesPerSample(format.getEncoding())
                    * format.getSampleRate()
                    / MILLIS_PER_SECOND;
            if (bufferSizeInBytes < bufferTargetSize) {
                return false;
            }
        }

        return true;
    }

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
        if ((sampleRateInHz < AudioFormat.SAMPLE_RATE_HZ_MIN ||
                sampleRateInHz > AudioFormat.SAMPLE_RATE_HZ_MAX) &&
                sampleRateInHz != AudioFormat.SAMPLE_RATE_UNSPECIFIED) {
            throw new IllegalArgumentException(sampleRateInHz
                    + "Hz is not a supported sample rate.");
        }
        mSampleRate = sampleRateInHz;

        if (audioFormat == AudioFormat.ENCODING_IEC61937
                && channelConfig != AudioFormat.CHANNEL_OUT_STEREO
                && AudioFormat.channelCountFromOutChannelMask(channelConfig) != 8) {
            Log.w(TAG, "ENCODING_IEC61937 is configured with channel mask as " + channelConfig
                    + ", which is not 2 or 8 channels");
        }

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
            if (!isMultichannelConfigSupported(channelConfig, audioFormat)) {
                throw new IllegalArgumentException(
                        "Unsupported channel mask configuration " + channelConfig
                        + " for encoding " + audioFormat);
            }
            mChannelMask = channelConfig;
            mChannelCount = AudioFormat.channelCountFromOutChannelMask(channelConfig);
        }
        // check the channel index configuration (if present)
        mChannelIndexMask = channelIndexMask;
        if (mChannelIndexMask != 0) {
            // As of S, we accept up to 24 channel index mask.
            final int fullIndexMask = (1 << AudioSystem.FCC_24) - 1;
            final int channelIndexCount = Integer.bitCount(channelIndexMask);
            final boolean accepted = (channelIndexMask & ~fullIndexMask) == 0
                    && (!AudioFormat.isEncodingLinearFrames(audioFormat)  // compressed OK
                            || channelIndexCount <= AudioSystem.OUT_CHANNEL_COUNT_MAX); // PCM
            if (!accepted) {
                throw new IllegalArgumentException(
                        "Unsupported channel index mask configuration " + channelIndexMask
                        + " for encoding " + audioFormat);
            }
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

    // General pair map
    private static final Map<String, Integer> CHANNEL_PAIR_MAP = Map.of(
            "front", AudioFormat.CHANNEL_OUT_FRONT_LEFT
                    | AudioFormat.CHANNEL_OUT_FRONT_RIGHT,
            "back", AudioFormat.CHANNEL_OUT_BACK_LEFT
                    | AudioFormat.CHANNEL_OUT_BACK_RIGHT,
            "front of center", AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
                    | AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER,
            "side", AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT,
            "top front", AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
                    | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT,
            "top back", AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT
                    | AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT,
            "top side", AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
                    | AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT,
            "bottom front", AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT
                    | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT,
            "front wide", AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT
                    | AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT);

    /**
     * Convenience method to check that the channel configuration (a.k.a channel mask) is supported
     * @param channelConfig the mask to validate
     * @return false if the AudioTrack can't be used with such a mask
     */
    private static boolean isMultichannelConfigSupported(int channelConfig, int encoding) {
        // check for unsupported channels
        if ((channelConfig & SUPPORTED_OUT_CHANNELS) != channelConfig) {
            loge("Channel configuration features unsupported channels");
            return false;
        }
        final int channelCount = AudioFormat.channelCountFromOutChannelMask(channelConfig);
        final int channelCountLimit;
        try {
            channelCountLimit = AudioFormat.isEncodingLinearFrames(encoding)
                    ? AudioSystem.OUT_CHANNEL_COUNT_MAX  // PCM limited to OUT_CHANNEL_COUNT_MAX
                    : AudioSystem.FCC_24;                // Compressed limited to 24 channels
        } catch (IllegalArgumentException iae) {
            loge("Unsupported encoding " + iae);
            return false;
        }
        if (channelCount > channelCountLimit) {
            loge("Channel configuration contains too many channels for encoding "
                    + encoding + "(" + channelCount + " > " + channelCountLimit + ")");
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
        // Check all pairs to see that they are matched (front duplicated here).
        for (Map.Entry<String, Integer> e : CHANNEL_PAIR_MAP.entrySet()) {
            final int positionPair = e.getValue();
            if ((channelConfig & positionPair) != 0
                    && (channelConfig & positionPair) != positionPair) {
                loge("Channel pair (" + e.getKey() + ") cannot be used independently");
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
        // NB: this section is only valid with PCM or IEC61937 data.
        //     To update when supporting compressed formats
        int frameSizeInBytes;
        if (AudioFormat.isEncodingLinearFrames(mAudioFormat)) {
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
        synchronized (mStreamEventCbLock){
            endStreamEventHandling();
        }
        // even though native_release() stops the native AudioTrack, we need to stop
        // AudioTrack subclasses too.
        try {
            stop();
        } catch(IllegalStateException ise) {
            // don't raise an exception, we're releasing the resources.
        }
        if (mAudioPolicy != null) {
            AudioManager.unregisterAudioPolicyAsyncStatic(mAudioPolicy);
            mAudioPolicy = null;
        }

        baseRelease();
        native_release();
        synchronized (mPlayStateLock) {
            mState = STATE_UNINITIALIZED;
            mPlayState = PLAYSTATE_STOPPED;
            mPlayStateLock.notify();
        }
    }

    @Override
    protected void finalize() {
        tryToDisableNativeRoutingCallback();
        baseRelease();
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
     * Returns the configured audio source sample rate in Hz.
     * The initial source sample rate depends on the constructor parameters,
     * but the source sample rate may change if {@link #setPlaybackRate(int)} is called.
     * If the constructor had a specific sample rate, then the initial sink sample rate is that
     * value.
     * If the constructor had {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED},
     * then the initial sink sample rate is a route-dependent default value based on the source [sic].
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
     * Returns the {@link AudioAttributes} used in configuration.
     * If a {@code streamType} is used instead of an {@code AudioAttributes}
     * to configure the AudioTrack
     * (the use of {@code streamType} for configuration is deprecated),
     * then the {@code AudioAttributes}
     * equivalent to the {@code streamType} is returned.
     * @return The {@code AudioAttributes} used to configure the AudioTrack.
     * @throws IllegalStateException If the track is not initialized.
     */
    public @NonNull AudioAttributes getAudioAttributes() {
        if (mState == STATE_UNINITIALIZED || mConfiguredAudioAttributes == null) {
            throw new IllegalStateException("track not initialized");
        }
        return mConfiguredAudioAttributes;
    }

    /**
     * Returns the configured audio data encoding. See {@link AudioFormat#ENCODING_PCM_8BIT},
     * {@link AudioFormat#ENCODING_PCM_16BIT}, and {@link AudioFormat#ENCODING_PCM_FLOAT}.
     */
    public int getAudioFormat() {
        return mAudioFormat;
    }

    /**
     * Returns the volume stream type of this AudioTrack.
     * Compare the result against {@link AudioManager#STREAM_VOICE_CALL},
     * {@link AudioManager#STREAM_SYSTEM}, {@link AudioManager#STREAM_RING},
     * {@link AudioManager#STREAM_MUSIC}, {@link AudioManager#STREAM_ALARM},
     * {@link AudioManager#STREAM_NOTIFICATION}, {@link AudioManager#STREAM_DTMF} or
     * {@link AudioManager#STREAM_ACCESSIBILITY}.
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
            switch (mPlayState) {
                case PLAYSTATE_STOPPING:
                    return PLAYSTATE_PLAYING;
                case PLAYSTATE_PAUSED_STOPPING:
                    return PLAYSTATE_PAUSED;
                default:
                    return mPlayState;
            }
        }
    }


    /**
     * Returns the effective size of the <code>AudioTrack</code> buffer
     * that the application writes to.
     * <p> This will be less than or equal to the result of
     * {@link #getBufferCapacityInFrames()}.
     * It will be equal if {@link #setBufferSizeInFrames(int)} has never been called.
     * <p> If the track is subsequently routed to a different output sink, the buffer
     * size and capacity may enlarge to accommodate.
     * <p> If the <code>AudioTrack</code> encoding indicates compressed data,
     * e.g. {@link AudioFormat#ENCODING_AC3}, then the frame count returned is
     * the size of the <code>AudioTrack</code> buffer in bytes.
     * <p> See also {@link AudioManager#getProperty(String)} for key
     * {@link AudioManager#PROPERTY_OUTPUT_FRAMES_PER_BUFFER}.
     * @return current size in frames of the <code>AudioTrack</code> buffer.
     * @throws IllegalStateException if track is not initialized.
     */
    public @IntRange (from = 0) int getBufferSizeInFrames() {
        return native_get_buffer_size_frames();
    }

    /**
     * Limits the effective size of the <code>AudioTrack</code> buffer
     * that the application writes to.
     * <p> A write to this AudioTrack will not fill the buffer beyond this limit.
     * If a blocking write is used then the write will block until the data
     * can fit within this limit.
     * <p>Changing this limit modifies the latency associated with
     * the buffer for this track. A smaller size will give lower latency
     * but there may be more glitches due to buffer underruns.
     * <p>The actual size used may not be equal to this requested size.
     * It will be limited to a valid range with a maximum of
     * {@link #getBufferCapacityInFrames()}.
     * It may also be adjusted slightly for internal reasons.
     * If bufferSizeInFrames is less than zero then {@link #ERROR_BAD_VALUE}
     * will be returned.
     * <p>This method is supported for PCM audio at all API levels.
     * Compressed audio is supported in API levels 33 and above.
     * For compressed streams the size of a frame is considered to be exactly one byte.
     *
     * @param bufferSizeInFrames requested buffer size in frames
     * @return the actual buffer size in frames or an error code,
     *    {@link #ERROR_BAD_VALUE}, {@link #ERROR_INVALID_OPERATION}
     * @throws IllegalStateException if track is not initialized.
     */
    public int setBufferSizeInFrames(@IntRange (from = 0) int bufferSizeInFrames) {
        if (mDataLoadMode == MODE_STATIC || mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        if (bufferSizeInFrames < 0) {
            return ERROR_BAD_VALUE;
        }
        return native_set_buffer_size_frames(bufferSizeInFrames);
    }

    /**
     *  Returns the maximum size of the <code>AudioTrack</code> buffer in frames.
     *  <p> If the track's creation mode is {@link #MODE_STATIC},
     *  it is equal to the specified bufferSizeInBytes on construction, converted to frame units.
     *  A static track's frame count will not change.
     *  <p> If the track's creation mode is {@link #MODE_STREAM},
     *  it is greater than or equal to the specified bufferSizeInBytes converted to frame units.
     *  For streaming tracks, this value may be rounded up to a larger value if needed by
     *  the target output sink, and
     *  if the track is subsequently routed to a different output sink, the
     *  frame count may enlarge to accommodate.
     *  <p> If the <code>AudioTrack</code> encoding indicates compressed data,
     *  e.g. {@link AudioFormat#ENCODING_AC3}, then the frame count returned is
     *  the size of the <code>AudioTrack</code> buffer in bytes.
     *  <p> See also {@link AudioManager#getProperty(String)} for key
     *  {@link AudioManager#PROPERTY_OUTPUT_FRAMES_PER_BUFFER}.
     *  @return maximum size in frames of the <code>AudioTrack</code> buffer.
     *  @throws IllegalStateException if track is not initialized.
     */
    public @IntRange (from = 0) int getBufferCapacityInFrames() {
        return native_get_buffer_capacity_frames();
    }

    /**
     * Sets the streaming start threshold for an <code>AudioTrack</code>.
     * <p> The streaming start threshold is the buffer level that the written audio
     * data must reach for audio streaming to start after {@link #play()} is called.
     * <p> For compressed streams, the size of a frame is considered to be exactly one byte.
     *
     * @param startThresholdInFrames the desired start threshold.
     * @return the actual start threshold in frames value. This is
     *         an integer between 1 to the buffer capacity
     *         (see {@link #getBufferCapacityInFrames()}),
     *         and might change if the output sink changes after track creation.
     * @throws IllegalStateException if the track is not initialized or the
     *         track transfer mode is not {@link #MODE_STREAM}.
     * @throws IllegalArgumentException if startThresholdInFrames is not positive.
     * @see #getStartThresholdInFrames()
     */
    public @IntRange(from = 1) int setStartThresholdInFrames(
            @IntRange (from = 1) int startThresholdInFrames) {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack is not initialized");
        }
        if (mDataLoadMode != MODE_STREAM) {
            throw new IllegalStateException("AudioTrack must be a streaming track");
        }
        if (startThresholdInFrames < 1) {
            throw new IllegalArgumentException("startThresholdInFrames "
                    + startThresholdInFrames + " must be positive");
        }
        return native_setStartThresholdInFrames(startThresholdInFrames);
    }

    /**
     * Returns the streaming start threshold of the <code>AudioTrack</code>.
     * <p> The streaming start threshold is the buffer level that the written audio
     * data must reach for audio streaming to start after {@link #play()} is called.
     * When an <code>AudioTrack</code> is created, the streaming start threshold
     * is the buffer capacity in frames. If the buffer size in frames is reduced
     * by {@link #setBufferSizeInFrames(int)} to a value smaller than the start threshold
     * then that value will be used instead for the streaming start threshold.
     * <p> For compressed streams, the size of a frame is considered to be exactly one byte.
     *
     * @return the current start threshold in frames value. This is
     *         an integer between 1 to the buffer capacity
     *         (see {@link #getBufferCapacityInFrames()}),
     *         and might change if the  output sink changes after track creation.
     * @throws IllegalStateException if the track is not initialized or the
     *         track is not {@link #MODE_STREAM}.
     * @see #setStartThresholdInFrames(int)
     */
    public @IntRange (from = 1) int getStartThresholdInFrames() {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack is not initialized");
        }
        if (mDataLoadMode != MODE_STREAM) {
            throw new IllegalStateException("AudioTrack must be a streaming track");
        }
        return native_getStartThresholdInFrames();
    }

    /**
     *  Returns the frame count of the native <code>AudioTrack</code> buffer.
     *  @return current size in frames of the <code>AudioTrack</code> buffer.
     *  @throws IllegalStateException
     *  @deprecated Use the identical public method {@link #getBufferSizeInFrames()} instead.
     */
    @Deprecated
    protected int getNativeFrameCount() {
        return native_get_buffer_capacity_frames();
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
    @UnsupportedAppUsage(trackingBug = 130237544)
    public int getLatency() {
        return native_get_latency();
    }

    /**
     * Returns the number of underrun occurrences in the application-level write buffer
     * since the AudioTrack was created.
     * An underrun occurs if the application does not write audio
     * data quickly enough, causing the buffer to underflow
     * and a potential audio glitch or pop.
     * <p>
     * Underruns are less likely when buffer sizes are large.
     * It may be possible to eliminate underruns by recreating the AudioTrack with
     * a larger buffer.
     * Or by using {@link #setBufferSizeInFrames(int)} to dynamically increase the
     * effective size of the buffer.
     */
    public int getUnderrunCount() {
        return native_get_underrun_count();
    }

    /**
     * Returns the current performance mode of the {@link AudioTrack}.
     *
     * @return one of {@link AudioTrack#PERFORMANCE_MODE_NONE},
     * {@link AudioTrack#PERFORMANCE_MODE_LOW_LATENCY},
     * or {@link AudioTrack#PERFORMANCE_MODE_POWER_SAVING}.
     * Use {@link AudioTrack.Builder#setPerformanceMode}
     * in the {@link AudioTrack.Builder} to enable a performance mode.
     * @throws IllegalStateException if track is not initialized.
     */
    public @PerformanceMode int getPerformanceMode() {
        final int flags = native_get_flags();
        if ((flags & AUDIO_OUTPUT_FLAG_FAST) != 0) {
            return PERFORMANCE_MODE_LOW_LATENCY;
        } else if ((flags & AUDIO_OUTPUT_FLAG_DEEP_BUFFER) != 0) {
            return PERFORMANCE_MODE_POWER_SAVING;
        } else {
            return PERFORMANCE_MODE_NONE;
        }
    }

    /**
     *  Returns the output sample rate in Hz for the specified stream type.
     */
    static public int getNativeOutputSampleRate(int streamType) {
        return native_get_output_sample_rate(streamType);
    }

    /**
     * Returns the estimated minimum buffer size required for an AudioTrack
     * object to be created in the {@link #MODE_STREAM} mode.
     * The size is an estimate because it does not consider either the route or the sink,
     * since neither is known yet.  Note that this size doesn't
     * guarantee a smooth playback under load, and higher values should be chosen according to
     * the expected frequency at which the buffer will be refilled with additional data to play.
     * For example, if you intend to dynamically set the source sample rate of an AudioTrack
     * to a higher value than the initial source sample rate, be sure to configure the buffer size
     * based on the highest planned sample rate.
     * @param sampleRateInHz the source sample rate expressed in Hz.
     *   {@link AudioFormat#SAMPLE_RATE_UNSPECIFIED} is not permitted.
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
            if (!isMultichannelConfigSupported(channelConfig, audioFormat)) {
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
        // Note: AudioFormat.SAMPLE_RATE_UNSPECIFIED is not allowed
        if ( (sampleRateInHz < AudioFormat.SAMPLE_RATE_HZ_MIN) ||
                (sampleRateInHz > AudioFormat.SAMPLE_RATE_HZ_MAX) ) {
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
    *         If a timestamp is available,
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

    /**
     * Poll for a timestamp on demand.
     * <p>
     * Same as {@link #getTimestamp(AudioTimestamp)} but with a more useful return code.
     *
     * @param timestamp a reference to a non-null AudioTimestamp instance allocated
     *        and owned by caller.
     * @return {@link #SUCCESS} if a timestamp is available
     *         {@link #ERROR_WOULD_BLOCK} if called in STOPPED or FLUSHED state, or if called
     *         immediately after start/ACTIVE, when the number of frames consumed is less than the
     *         overall hardware latency to physical output. In WOULD_BLOCK cases, one might poll
     *         again, or use {@link #getPlaybackHeadPosition}, or use 0 position and current time
     *         for the timestamp.
     *         {@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *         needs to be recreated.
     *         {@link #ERROR_INVALID_OPERATION} if current route does not support
     *         timestamps. In this case, the approximate frame position can be obtained
     *         using {@link #getPlaybackHeadPosition}.
     *
     *         The AudioTimestamp instance is filled in with a position in frame units, together
     *         with the estimated time when that frame was presented or is committed to
     *         be presented.
     * @hide
     */
     // Add this text when the "on new timestamp" API is added:
     //   Use if you need to get the most recent timestamp outside of the event callback handler.
     public int getTimestampWithStatus(AudioTimestamp timestamp)
     {
         if (timestamp == null) {
             throw new IllegalArgumentException();
         }
         // It's unfortunate, but we have to either create garbage every time or use synchronized
         long[] longArray = new long[2];
         int ret = native_get_timestamp(longArray);
         timestamp.framePosition = longArray[0];
         timestamp.nanoTime = longArray[1];
         return ret;
     }

    /**
     *  Return Metrics data about the current AudioTrack instance.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being handled by this instance of AudioTrack
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
    @Deprecated
    public int setStereoVolume(float leftGain, float rightGain) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }

        baseSetVolume(leftGain, rightGain);
        return SUCCESS;
    }

    @Override
    void playerSetVolume(boolean muting, float leftVolume, float rightVolume) {
        leftVolume = clampGainOrLevel(muting ? 0.0f : leftVolume);
        rightVolume = clampGainOrLevel(muting ? 0.0f : rightVolume);

        native_setVolume(leftVolume, rightVolume);
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

    @Override
    /* package */ int playerApplyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        return native_applyVolumeShaper(configuration, operation);
    }

    @Override
    /* package */ @Nullable VolumeShaper.State playerGetVolumeShaperState(int id) {
        return native_getVolumeShaperState(id);
    }

    @Override
    public @NonNull VolumeShaper createVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration) {
        return new VolumeShaper(configuration, this);
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
    public int setPlaybackHeadPosition(@IntRange (from = 0) int positionInFrames) {
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
    public int setLoopPoints(@IntRange (from = 0) int startInFrames,
            @IntRange (from = 0) int endInFrames, @IntRange (from = -1) int loopCount) {
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
     * Sets the audio presentation.
     * If the audio presentation is invalid then {@link #ERROR_BAD_VALUE} will be returned.
     * If a multi-stream decoder (MSD) is not present, or the format does not support
     * multiple presentations, then {@link #ERROR_INVALID_OPERATION} will be returned.
     * {@link #ERROR} is returned in case of any other error.
     * @param presentation see {@link AudioPresentation}. In particular, id should be set.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR},
     *    {@link #ERROR_BAD_VALUE}, {@link #ERROR_INVALID_OPERATION}
     * @throws IllegalArgumentException if the audio presentation is null.
     * @throws IllegalStateException if track is not initialized.
     */
    public int setPresentation(@NonNull AudioPresentation presentation) {
        if (presentation == null) {
            throw new IllegalArgumentException("audio presentation is null");
        }
        return native_setPresentation(presentation.getPresentationId(),
                presentation.getProgramId());
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
     *<p>
     * As of {@link android.os.Build.VERSION_CODES#S} the minimum level to start playing
     * can be obtained using {@link #getStartThresholdInFrames()} and set with
     * {@link #setStartThresholdInFrames(int)}.
     *
     * @throws IllegalStateException if the track isn't properly initialized
     */
    public void play()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException("play() called on uninitialized AudioTrack.");
        }
        //FIXME use lambda to pass startImpl to superclass
        final int delay = getStartDelayMs();
        if (delay == 0) {
            startImpl();
        } else {
            new Thread() {
                public void run() {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    baseSetStartDelayMs(0);
                    try {
                        startImpl();
                    } catch (IllegalStateException e) {
                        // fail silently for a state exception when it is happening after
                        // a delayed start, as the player state could have changed between the
                        // call to start() and the execution of startImpl()
                    }
                }
            }.start();
        }
    }

    private void startImpl() {
        synchronized (mRoutingChangeListeners) {
            if (!mEnableSelfRoutingMonitor) {
                mEnableSelfRoutingMonitor = testEnableNativeRoutingCallbacksLocked();
            }
        }
        synchronized(mPlayStateLock) {
            baseStart(0); // unknown device at this point
            native_start();
            // FIXME see b/179218630
            //baseStart(native_getRoutedDeviceId());
            if (mPlayState == PLAYSTATE_PAUSED_STOPPING) {
                mPlayState = PLAYSTATE_STOPPING;
            } else {
                mPlayState = PLAYSTATE_PLAYING;
                mOffloadEosPending = false;
            }
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
            baseStop();
            if (mOffloaded && mPlayState != PLAYSTATE_PAUSED_STOPPING) {
                mPlayState = PLAYSTATE_STOPPING;
            } else {
                mPlayState = PLAYSTATE_STOPPED;
                mOffloadEosPending = false;
                mAvSyncHeader = null;
                mAvSyncBytesRemaining = 0;
                mPlayStateLock.notify();
            }
        }
        tryToDisableNativeRoutingCallback();
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

        // pause playback
        synchronized(mPlayStateLock) {
            native_pause();
            basePause();
            if (mPlayState == PLAYSTATE_STOPPING) {
                mPlayState = PLAYSTATE_PAUSED_STOPPING;
            } else {
                mPlayState = PLAYSTATE_PAUSED;
            }
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
     * The format can be {@link AudioFormat#ENCODING_PCM_16BIT}, but this is deprecated.
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
     * @param offsetInBytes the offset expressed in bytes in audioData where the data to write
     *    starts.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInBytes the number of bytes to write in audioData after the offset.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @return zero or the positive number of bytes that were written, or one of the following
     *    error codes. The number of bytes will be a multiple of the frame size in bytes
     *    not to exceed sizeInBytes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
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
     * The format can be {@link AudioFormat#ENCODING_PCM_16BIT}, but this is deprecated.
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
     * @param offsetInBytes the offset expressed in bytes in audioData where the data to write
     *    starts.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInBytes the number of bytes to write in audioData after the offset.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of bytes that were written, or one of the following
     *    error codes. The number of bytes will be a multiple of the frame size in bytes
     *    not to exceed sizeInBytes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes,
            @WriteMode int writeMode) {
        // Note: we allow writes of extended integers and compressed formats from a byte array.
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

        if (!blockUntilOffloadDrain(writeMode)) {
            return 0;
        }

        final int ret = native_write_byte(audioData, offsetInBytes, sizeInBytes, mAudioFormat,
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
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInShorts the number of shorts to read in audioData after the offset.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @return zero or the positive number of shorts that were written, or one of the following
     *    error codes. The number of shorts will be a multiple of the channel count not to
     *    exceed sizeInShorts.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
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
     * @param audioData the array that holds the data to write.
     * @param offsetInShorts the offset expressed in shorts in audioData where the data to write
     *     starts.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInShorts the number of shorts to read in audioData after the offset.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of shorts that were written, or one of the following
     *    error codes. The number of shorts will be a multiple of the channel count not to
     *    exceed sizeInShorts.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
     */
    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
            @WriteMode int writeMode) {

        if (mState == STATE_UNINITIALIZED
                || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT
                // use ByteBuffer or byte[] instead for later encodings
                || mAudioFormat > AudioFormat.ENCODING_LEGACY_SHORT_ARRAY_THRESHOLD) {
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

        if (!blockUntilOffloadDrain(writeMode)) {
            return 0;
        }

        final int ret = native_write_short(audioData, offsetInShorts, sizeInShorts, mAudioFormat,
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
     * @param audioData the array that holds the data to write.
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
     *     in audioData where the data to write starts.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param sizeInFloats the number of floats to write in audioData after the offset.
     *    Must not be negative, or cause the data access to go out of bounds of the array.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <br>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <br>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of floats that were written, or one of the following
     *    error codes. The number of floats will be a multiple of the channel count not to
     *    exceed sizeInFloats.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
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

        if (!blockUntilOffloadDrain(writeMode)) {
            return 0;
        }

        final int ret = native_write_float(audioData, offsetInFloats, sizeInFloats, mAudioFormat,
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
     * @param audioData the buffer that holds the data to write, starting at the position reported
     *     by <code>audioData.position()</code>.
     *     <BR>Note that upon return, the buffer position (<code>audioData.position()</code>) will
     *     have been advanced to reflect the amount of data that was successfully written to
     *     the AudioTrack.
     * @param sizeInBytes number of bytes to write.  It is recommended but not enforced
     *     that the number of bytes requested be a multiple of the frame size (sample size in
     *     bytes multiplied by the channel count).
     *     <BR>Note this may differ from <code>audioData.remaining()</code>, but cannot exceed it.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}. It has no
     *     effect in static mode.
     *     <BR>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <BR>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @return zero or the positive number of bytes that were written, or one of the following
     *    error codes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
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

        if (!blockUntilOffloadDrain(writeMode)) {
            return 0;
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
     * @param audioData the buffer that holds the data to write, starting at the position reported
     *     by <code>audioData.position()</code>.
     *     <BR>Note that upon return, the buffer position (<code>audioData.position()</code>) will
     *     have been advanced to reflect the amount of data that was successfully written to
     *     the AudioTrack.
     * @param sizeInBytes number of bytes to write.  It is recommended but not enforced
     *     that the number of bytes requested be a multiple of the frame size (sample size in
     *     bytes multiplied by the channel count).
     *     <BR>Note this may differ from <code>audioData.remaining()</code>, but cannot exceed it.
     * @param writeMode one of {@link #WRITE_BLOCKING}, {@link #WRITE_NON_BLOCKING}.
     *     <BR>With {@link #WRITE_BLOCKING}, the write will block until all data has been written
     *         to the audio sink.
     *     <BR>With {@link #WRITE_NON_BLOCKING}, the write will return immediately after
     *     queuing as much audio data for playback as possible without blocking.
     * @param timestamp The timestamp, in nanoseconds, of the first decodable audio frame in the
     *     provided audioData.
     * @return zero or the positive number of bytes that were written, or one of the following
     *    error codes.
     * <ul>
     * <li>{@link #ERROR_INVALID_OPERATION} if the track isn't properly initialized</li>
     * <li>{@link #ERROR_BAD_VALUE} if the parameters don't resolve to valid data and indexes</li>
     * <li>{@link #ERROR_DEAD_OBJECT} if the AudioTrack is not valid anymore and
     *    needs to be recreated. The dead object error code is not returned if some data was
     *    successfully transferred. In this case, the error is returned at the next write()</li>
     * <li>{@link #ERROR} in case of other error</li>
     * </ul>
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

        if (!blockUntilOffloadDrain(writeMode)) {
            return 0;
        }

        // create timestamp header if none exists
        if (mAvSyncHeader == null) {
            mAvSyncHeader = ByteBuffer.allocate(mOffset);
            mAvSyncHeader.order(ByteOrder.BIG_ENDIAN);
            mAvSyncHeader.putInt(0x55550002);
        }

        if (mAvSyncBytesRemaining == 0) {
            mAvSyncHeader.putInt(4, sizeInBytes);
            mAvSyncHeader.putLong(8, timestamp);
            mAvSyncHeader.putInt(16, mOffset);
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

    /**
     * When an AudioTrack in offload mode is in STOPPING play state, wait until event STREAM_END is
     * received if blocking write or return with 0 frames written if non blocking mode.
     */
    private boolean blockUntilOffloadDrain(int writeMode) {
        synchronized (mPlayStateLock) {
            while (mPlayState == PLAYSTATE_STOPPING || mPlayState == PLAYSTATE_PAUSED_STOPPING) {
                if (writeMode == WRITE_NON_BLOCKING) {
                    return false;
                }
                try {
                    mPlayStateLock.wait();
                } catch (InterruptedException e) {
                }
            }
            return true;
        }
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
    public int setAuxEffectSendLevel(@FloatRange(from = 0.0) float level) {
        if (mState == STATE_UNINITIALIZED) {
            return ERROR_INVALID_OPERATION;
        }
        return baseSetAuxEffectSendLevel(level);
    }

    @Override
    int playerSetAuxEffectSendLevel(boolean muting, float level) {
        level = clampGainOrLevel(muting ? 0.0f : level);
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
    @Override
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
    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this AudioTrack.
     * Note: The query is only valid if the AudioTrack is currently playing. If it is not,
     * <code>getRoutedDevice()</code> will return null.
     * Audio may play on multiple devices simultaneously (e.g. an alarm playing on headphones and
     * speaker on a phone), so prefer using {@link #getRoutedDevices}.
     */
    @Override
    public AudioDeviceInfo getRoutedDevice() {
        int deviceId = native_getRoutedDeviceId();
        if (deviceId == 0) {
            return null;
        }
        return AudioManager.getDeviceForPortId(deviceId, AudioManager.GET_DEVICES_OUTPUTS);
    }

    /**
     * Returns a List of {@link AudioDeviceInfo} identifying the current routing of this
     * AudioTrack.
     * Note: The query is only valid if the AudioTrack is currently playing. If it is not,
     * <code>getRoutedDevices()</code> will return an empty list.
     */
    @Override
    @FlaggedApi(FLAG_ROUTED_DEVICE_IDS)
    public @NonNull List<AudioDeviceInfo> getRoutedDevices() {
        List<AudioDeviceInfo> audioDeviceInfos = new ArrayList<AudioDeviceInfo>();
        AudioDeviceInfo audioDeviceInfo = getRoutedDevice();
        if (audioDeviceInfo != null) {
            audioDeviceInfos.add(audioDeviceInfo);
        }
        return audioDeviceInfos;
    }

    private void tryToDisableNativeRoutingCallback() {
        synchronized (mRoutingChangeListeners) {
            if (mEnableSelfRoutingMonitor) {
                mEnableSelfRoutingMonitor = false;
                testDisableNativeRoutingCallbacksLocked();
            }
        }
    }

    /**
     * Call BEFORE adding a routing callback handler and when enabling self routing listener
     * @return returns true for success, false otherwise.
     */
    @GuardedBy("mRoutingChangeListeners")
    private boolean testEnableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0 && !mEnableSelfRoutingMonitor) {
            try {
                native_enableDeviceCallback();
                return true;
            } catch (IllegalStateException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "testEnableNativeRoutingCallbacks failed", e);
                }
            }
        }
        return false;
    }

    /*
     * Call AFTER removing a routing callback handler and when disabling self routing listener.
     */
    @GuardedBy("mRoutingChangeListeners")
    private void testDisableNativeRoutingCallbacksLocked() {
        if (mRoutingChangeListeners.size() == 0 && !mEnableSelfRoutingMonitor) {
            try {
                native_disableDeviceCallback();
            } catch (IllegalStateException e) {
                // Fail silently as track state could have changed in between stop
                // and disabling routing callback
            }
        }
    }

    //--------------------------------------------------------------------------
    // (Re)Routing Info
    //--------------------
    /**
     * The list of AudioRouting.OnRoutingChangedListener interfaces added (with
     * {@link #addOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener, Handler)}
     * by an app to receive (re)routing notifications.
     */
    @GuardedBy("mRoutingChangeListeners")
    private ArrayMap<AudioRouting.OnRoutingChangedListener,
            NativeRoutingEventHandlerDelegate> mRoutingChangeListeners = new ArrayMap<>();

    @GuardedBy("mRoutingChangeListeners")
    private boolean mEnableSelfRoutingMonitor;

   /**
    * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of routing
    * changes on this AudioTrack.
    * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
    * notifications of rerouting events.
    * @param handler  Specifies the {@link Handler} object for the thread on which to execute
    * the callback. If <code>null</code>, the {@link Handler} associated with the main
    * {@link Looper} will be used.
    */
    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
            Handler handler) {
        synchronized (mRoutingChangeListeners) {
            if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
                mEnableSelfRoutingMonitor = testEnableNativeRoutingCallbacksLocked();
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
            }
            testDisableNativeRoutingCallbacksLocked();
        }
    }

    //--------------------------------------------------------------------------
    // (Re)Routing Info
    //--------------------
    /**
     * Defines the interface by which applications can receive notifications of
     * routing changes for the associated {@link AudioTrack}.
     *
     * @deprecated users should switch to the general purpose
     *             {@link AudioRouting.OnRoutingChangedListener} class instead.
     */
    @Deprecated
    public interface OnRoutingChangedListener extends AudioRouting.OnRoutingChangedListener {
        /**
         * Called when the routing of an AudioTrack changes from either and
         * explicit or policy rerouting. Use {@link #getRoutedDevice()} to
         * retrieve the newly routed-to device.
         */
        public void onRoutingChanged(AudioTrack audioTrack);

        @Override
        default public void onRoutingChanged(AudioRouting router) {
            if (router instanceof AudioTrack) {
                onRoutingChanged((AudioTrack) router);
            }
        }
    }

    /**
     * Adds an {@link OnRoutingChangedListener} to receive notifications of routing changes
     * on this AudioTrack.
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
        baseUpdateDeviceId(getRoutedDevice());
        synchronized (mRoutingChangeListeners) {
            for (NativeRoutingEventHandlerDelegate delegate : mRoutingChangeListeners.values()) {
                delegate.notifyClient();
            }
        }
    }

    //--------------------------------------------------------------------------
    // Codec notifications
    //--------------------

    // OnCodecFormatChangedListener notifications uses an instance
    // of ListenerList to manage its listeners.

    private final Utils.ListenerList<AudioMetadataReadMap> mCodecFormatChangedListeners =
            new Utils.ListenerList();

    /**
     * Interface definition for a listener for codec format changes.
     */
    public interface OnCodecFormatChangedListener {
        /**
         * Called when the compressed codec format changes.
         *
         * @param audioTrack is the {@code AudioTrack} instance associated with the codec.
         * @param info is a {@link AudioMetadataReadMap} of values which contains decoded format
         *     changes reported by the codec.  Not all hardware
         *     codecs indicate codec format changes. Acceptable keys are taken from
         *     {@code AudioMetadata.Format.KEY_*} range, with the associated value type.
         */
        void onCodecFormatChanged(
                @NonNull AudioTrack audioTrack, @Nullable AudioMetadataReadMap info);
    }

    /**
     * Adds an {@link OnCodecFormatChangedListener} to receive notifications of
     * codec format change events on this {@code AudioTrack}.
     *
     * @param executor  Specifies the {@link Executor} object to control execution.
     *
     * @param listener The {@link OnCodecFormatChangedListener} interface to receive
     *     notifications of codec events.
     */
    public void addOnCodecFormatChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnCodecFormatChangedListener listener) { // NPE checks done by ListenerList.
        mCodecFormatChangedListeners.add(
                listener, /* key for removal */
                executor,
                (int eventCode, AudioMetadataReadMap readMap) -> {
                    // eventCode is unused by this implementation.
                    listener.onCodecFormatChanged(this, readMap);
                }
        );
    }

    /**
     * Removes an {@link OnCodecFormatChangedListener} which has been previously added
     * to receive codec format change events.
     *
     * @param listener The previously added {@link OnCodecFormatChangedListener} interface
     * to remove.
     */
    public void removeOnCodecFormatChangedListener(
            @NonNull OnCodecFormatChangedListener listener) {
        mCodecFormatChangedListeners.remove(listener);  // NPE checks done by ListenerList.
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

    /**
     * Abstract class to receive event notifications about the stream playback in offloaded mode.
     * See {@link AudioTrack#registerStreamEventCallback(Executor, StreamEventCallback)} to register
     * the callback on the given {@link AudioTrack} instance.
     */
    public abstract static class StreamEventCallback {
        /**
         * Called when an offloaded track is no longer valid and has been discarded by the system.
         * An example of this happening is when an offloaded track has been paused too long, and
         * gets invalidated by the system to prevent any other offload.
         * @param track the {@link AudioTrack} on which the event happened.
         */
        public void onTearDown(@NonNull AudioTrack track) { }
        /**
         * Called when all the buffers of an offloaded track that were queued in the audio system
         * (e.g. the combination of the Android audio framework and the device's audio hardware)
         * have been played after {@link AudioTrack#stop()} has been called.
         * @param track the {@link AudioTrack} on which the event happened.
         */
        public void onPresentationEnded(@NonNull AudioTrack track) { }
        /**
         * Called when more audio data can be written without blocking on an offloaded track.
         * @param track the {@link AudioTrack} on which the event happened.
         * @param sizeInFrames the number of frames available to write without blocking.
         *   Note that the frame size of a compressed stream is 1 byte.
         */
        public void onDataRequest(@NonNull AudioTrack track, @IntRange(from = 0) int sizeInFrames) {
        }
    }

    /**
     * Registers a callback for the notification of stream events.
     * This callback can only be registered for instances operating in offloaded mode
     * (see {@link AudioTrack.Builder#setOffloadedPlayback(boolean)} and
     * {@link AudioManager#isOffloadedPlaybackSupported(AudioFormat,AudioAttributes)} for
     * more details).
     * @param executor {@link Executor} to handle the callbacks.
     * @param eventCallback the callback to receive the stream event notifications.
     */
    public void registerStreamEventCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull StreamEventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null StreamEventCallback");
        }
        if (!mOffloaded) {
            throw new IllegalStateException(
                    "Cannot register StreamEventCallback on non-offloaded AudioTrack");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Illegal null Executor for the StreamEventCallback");
        }
        synchronized (mStreamEventCbLock) {
            // check if eventCallback already in list
            for (StreamEventCbInfo seci : mStreamEventCbInfoList) {
                if (seci.mStreamEventCb == eventCallback) {
                    throw new IllegalArgumentException(
                            "StreamEventCallback already registered");
                }
            }
            beginStreamEventHandling();
            mStreamEventCbInfoList.add(new StreamEventCbInfo(executor, eventCallback));
        }
    }

    /**
     * Unregisters the callback for notification of stream events, previously registered
     * with {@link #registerStreamEventCallback(Executor, StreamEventCallback)}.
     * @param eventCallback the callback to unregister.
     */
    public void unregisterStreamEventCallback(@NonNull StreamEventCallback eventCallback) {
        if (eventCallback == null) {
            throw new IllegalArgumentException("Illegal null StreamEventCallback");
        }
        if (!mOffloaded) {
            throw new IllegalStateException("No StreamEventCallback on non-offloaded AudioTrack");
        }
        synchronized (mStreamEventCbLock) {
            StreamEventCbInfo seciToRemove = null;
            for (StreamEventCbInfo seci : mStreamEventCbInfoList) {
                if (seci.mStreamEventCb == eventCallback) {
                    // ok to remove while iterating over list as we exit iteration
                    mStreamEventCbInfoList.remove(seci);
                    if (mStreamEventCbInfoList.size() == 0) {
                        endStreamEventHandling();
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("StreamEventCallback was not registered");
        }
    }

    //---------------------------------------------------------
    // Offload
    //--------------------
    private static class StreamEventCbInfo {
        final Executor mStreamEventExec;
        final StreamEventCallback mStreamEventCb;

        StreamEventCbInfo(Executor e, StreamEventCallback cb) {
            mStreamEventExec = e;
            mStreamEventCb = cb;
        }
    }

    private final Object mStreamEventCbLock = new Object();
    @GuardedBy("mStreamEventCbLock")
    @NonNull private LinkedList<StreamEventCbInfo> mStreamEventCbInfoList =
            new LinkedList<StreamEventCbInfo>();
    /**
     * Dedicated thread for handling the StreamEvent callbacks
     */
    private @Nullable HandlerThread mStreamEventHandlerThread;
    private @Nullable volatile StreamEventHandler mStreamEventHandler;

    /**
     * Called from native AudioTrack callback thread, filter messages if necessary
     * and repost event on AudioTrack message loop to prevent blocking native thread.
     * @param what event code received from native
     * @param arg optional argument for event
     */
    void handleStreamEventFromNative(int what, int arg) {
        if (mStreamEventHandler == null) {
            return;
        }
        switch (what) {
            case NATIVE_EVENT_CAN_WRITE_MORE_DATA:
                // replace previous CAN_WRITE_MORE_DATA messages with the latest value
                mStreamEventHandler.removeMessages(NATIVE_EVENT_CAN_WRITE_MORE_DATA);
                mStreamEventHandler.sendMessage(
                        mStreamEventHandler.obtainMessage(
                                NATIVE_EVENT_CAN_WRITE_MORE_DATA, arg, 0/*ignored*/));
                break;
            case NATIVE_EVENT_NEW_IAUDIOTRACK:
                mStreamEventHandler.sendMessage(
                        mStreamEventHandler.obtainMessage(NATIVE_EVENT_NEW_IAUDIOTRACK));
                break;
            case NATIVE_EVENT_STREAM_END:
                mStreamEventHandler.sendMessage(
                        mStreamEventHandler.obtainMessage(NATIVE_EVENT_STREAM_END));
                break;
        }
    }

    private class StreamEventHandler extends Handler {

        StreamEventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final LinkedList<StreamEventCbInfo> cbInfoList;
            synchronized (mStreamEventCbLock) {
                if (msg.what == NATIVE_EVENT_STREAM_END) {
                    synchronized (mPlayStateLock) {
                        if (mPlayState == PLAYSTATE_STOPPING) {
                            if (mOffloadEosPending) {
                                native_start();
                                mPlayState = PLAYSTATE_PLAYING;
                            } else {
                                mAvSyncHeader = null;
                                mAvSyncBytesRemaining = 0;
                                mPlayState = PLAYSTATE_STOPPED;
                            }
                            mOffloadEosPending = false;
                            mPlayStateLock.notify();
                        }
                    }
                }
                if (mStreamEventCbInfoList.size() == 0) {
                    return;
                }
                cbInfoList = new LinkedList<StreamEventCbInfo>(mStreamEventCbInfoList);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                for (StreamEventCbInfo cbi : cbInfoList) {
                    switch (msg.what) {
                        case NATIVE_EVENT_CAN_WRITE_MORE_DATA:
                            cbi.mStreamEventExec.execute(() ->
                                    cbi.mStreamEventCb.onDataRequest(AudioTrack.this, msg.arg1));
                            break;
                        case NATIVE_EVENT_NEW_IAUDIOTRACK:
                            // TODO also release track as it's not longer usable
                            cbi.mStreamEventExec.execute(() ->
                                    cbi.mStreamEventCb.onTearDown(AudioTrack.this));
                            break;
                        case NATIVE_EVENT_STREAM_END:
                            cbi.mStreamEventExec.execute(() ->
                                    cbi.mStreamEventCb.onPresentationEnded(AudioTrack.this));
                            break;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mStreamEventCbLock")
    private void beginStreamEventHandling() {
        if (mStreamEventHandlerThread == null) {
            mStreamEventHandlerThread = new HandlerThread(TAG + ".StreamEvent");
            mStreamEventHandlerThread.start();
            final Looper looper = mStreamEventHandlerThread.getLooper();
            if (looper != null) {
                mStreamEventHandler = new StreamEventHandler(looper);
            }
        }
    }

    @GuardedBy("mStreamEventCbLock")
    private void endStreamEventHandling() {
        if (mStreamEventHandlerThread != null) {
            mStreamEventHandlerThread.quit();
            mStreamEventHandlerThread = null;
        }
    }

    /**
     * Sets a {@link LogSessionId} instance to this AudioTrack for metrics collection.
     *
     * @param logSessionId a {@link LogSessionId} instance which is used to
     *        identify this object to the metrics service. Proper generated
     *        Ids must be obtained from the Java metrics service and should
     *        be considered opaque. Use
     *        {@link LogSessionId#LOG_SESSION_ID_NONE} to remove the
     *        logSessionId association.
     * @throws IllegalStateException if AudioTrack not initialized.
     *
     */
    public void setLogSessionId(@NonNull LogSessionId logSessionId) {
        Objects.requireNonNull(logSessionId);
        if (mState == STATE_UNINITIALIZED) {
            throw new IllegalStateException("track not initialized");
        }
        String stringId = logSessionId.getStringId();
        native_setLogSessionId(stringId);
        mLogSessionId = logSessionId;
    }

    /**
     * Returns the {@link LogSessionId}.
     */
    @NonNull
    public LogSessionId getLogSessionId() {
        return mLogSessionId;
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

    //---------------------------------------------------------
    // Methods for IPlayer interface
    //--------------------
    @Override
    void playerStart() {
        play();
    }

    @Override
    void playerPause() {
        pause();
    }

    @Override
    void playerStop() {
        stop();
    }

    //---------------------------------------------------------
    // Java methods called from the native side
    //--------------------
    @SuppressWarnings("unused")
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static void postEventFromNative(Object audiotrack_ref,
            int what, int arg1, int arg2, Object obj) {
        //logd("Event posted from the native side: event="+ what + " args="+ arg1+" "+arg2);
        final AudioTrack track = (AudioTrack) ((WeakReference) audiotrack_ref).get();
        if (track == null) {
            return;
        }

        if (what == AudioSystem.NATIVE_EVENT_ROUTING_CHANGE) {
            track.broadcastRoutingChange();
            return;
        }

        if (what == NATIVE_EVENT_CODEC_FORMAT_CHANGE) {
            ByteBuffer buffer = (ByteBuffer) obj;
            buffer.order(ByteOrder.nativeOrder());
            buffer.rewind();
            AudioMetadataReadMap audioMetaData = AudioMetadata.fromByteBuffer(buffer);
            if (audioMetaData == null) {
                Log.e(TAG, "Unable to get audio metadata from byte buffer");
                return;
            }
            track.mCodecFormatChangedListeners.notify(0 /* eventCode, unused */, audioMetaData);
            return;
        }

        if (what == NATIVE_EVENT_CAN_WRITE_MORE_DATA
                || what == NATIVE_EVENT_NEW_IAUDIOTRACK
                || what == NATIVE_EVENT_STREAM_END) {
            track.handleStreamEventFromNative(what, arg1);
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

    private static native boolean native_is_direct_output_supported(int encoding, int sampleRate,
            int channelMask, int channelIndexMask, int contentType, int usage, int flags);

    // post-condition: mStreamType is overwritten with a value
    //     that reflects the audio attributes (e.g. an AudioAttributes object with a usage of
    //     AudioAttributes.USAGE_MEDIA will map to AudioManager.STREAM_MUSIC
    private native final int native_setup(Object /*WeakReference<AudioTrack>*/ audiotrack_this,
            Object /*AudioAttributes*/ attributes,
            int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
            int buffSizeInBytes, int mode, int[] sessionId, @NonNull Parcel attributionSource,
            long nativeAudioTrack, boolean offload, int encapsulationMode,
            Object tunerConfiguration, @NonNull String opPackageName);

    private native final void native_finalize();

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public native final void native_release();

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

    private native final int native_write_native_bytes(ByteBuffer audioData,
            int positionInBytes, int sizeInBytes, int format, boolean blocking);

    private native final int native_reload_static();

    private native final int native_get_buffer_size_frames();
    private native final int native_set_buffer_size_frames(int bufferSizeInFrames);
    private native final int native_get_buffer_capacity_frames();

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

    private native final int native_get_underrun_count();

    private native final int native_get_flags();

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

    private native int native_applyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation);

    private native @Nullable VolumeShaper.State native_getVolumeShaperState(int id);
    private native final int native_setPresentation(int presentationId, int programId);

    private native int native_getPortId();

    private native void native_set_delay_padding(int delayInFrames, int paddingInFrames);

    private native int native_set_audio_description_mix_level_db(float level);
    private native int native_get_audio_description_mix_level_db(float[] level);
    private native int native_set_dual_mono_mode(int dualMonoMode);
    private native int native_get_dual_mono_mode(int[] dualMonoMode);
    private native void native_setLogSessionId(@Nullable String logSessionId);
    private native int native_setStartThresholdInFrames(int startThresholdInFrames);
    private native int native_getStartThresholdInFrames();

    /**
     * Sets the audio service Player Interface Id.
     *
     * The playerIId does not change over the lifetime of the client
     * Java AudioTrack and is set automatically on creation.
     *
     * This call informs the native AudioTrack for metrics logging purposes.
     *
     * @param id the value reported by AudioManager when registering the track.
     *           A value of -1 indicates invalid - the playerIId was never set.
     * @throws IllegalStateException if AudioTrack not initialized.
     */
    private native void native_setPlayerIId(int playerIId);

    //---------------------------------------------------------
    // Utility methods
    //------------------

    private static void logd(String msg) {
        Log.d(TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, msg);
    }

    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        // MM_PREFIX is slightly different than TAG, used to avoid cut-n-paste errors.
        private static final String MM_PREFIX = "android.media.audiotrack.";

        /**
         * Key to extract the stream type for this track
         * from the {@link AudioTrack#getMetrics} return value.
         * This value may not exist in API level {@link android.os.Build.VERSION_CODES#P}.
         * The value is a {@code String}.
         */
        public static final String STREAMTYPE = MM_PREFIX + "streamtype";

        /**
         * Key to extract the attribute content type for this track
         * from the {@link AudioTrack#getMetrics} return value.
         * The value is a {@code String}.
         */
        public static final String CONTENTTYPE = MM_PREFIX + "type";

        /**
         * Key to extract the attribute usage for this track
         * from the {@link AudioTrack#getMetrics} return value.
         * The value is a {@code String}.
         */
        public static final String USAGE = MM_PREFIX + "usage";

        /**
         * Key to extract the sample rate for this track in Hz
         * from the {@link AudioTrack#getMetrics} return value.
         * The value is an {@code int}.
         * @deprecated This does not work. Use {@link AudioTrack#getSampleRate()} instead.
         */
        @Deprecated
        public static final String SAMPLERATE = "android.media.audiorecord.samplerate";

        /**
         * Key to extract the native channel mask information for this track
         * from the {@link AudioTrack#getMetrics} return value.
         *
         * The value is a {@code long}.
         * @deprecated This does not work. Use {@link AudioTrack#getFormat()} and read from
         * the returned format instead.
         */
        @Deprecated
        public static final String CHANNELMASK = "android.media.audiorecord.channelmask";

        /**
         * Use for testing only. Do not expose.
         * The current sample rate.
         * The value is an {@code int}.
         * @hide
         */
        @TestApi
        public static final String SAMPLE_RATE = MM_PREFIX + "sampleRate";

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
         * The output audio data encoding.
         * The value is a {@code String}.
         * @hide
         */
        @TestApi
        public static final String ENCODING = MM_PREFIX + "encoding";

        /**
         * Use for testing only. Do not expose.
         * The port id of this track port in audioserver.
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
         * The actual track attributes used.
         * The value is a {@code String}.
         * @hide
         */
        @TestApi
        public static final String ATTRIBUTES = MM_PREFIX + "attributes";
    }
}
