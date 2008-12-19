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
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.media.AudioManager;
import android.util.Log;


/**
 * The AudioTrack class manages and plays a single audio resource for Java applications.
 * It allows to stream PCM audio buffers to the audio hardware for playback. This is
 * be achieved by "pushing" the data to the AudioTrack object using the
 *  {@link #write(byte[], int, int)} or {@link #write(short[], int, int)} method.
 * During construction, an AudioTrack object can be initialized with a given buffer.
 * This size determines how long an AudioTrack can play before running out of data.
 * 
 * {@hide Pending API council review}
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
    
    /** state of an AudioTrack this is stopped */
    public static final int PLAYSTATE_STOPPED = 1;  // matches SL_PLAYSTATE_STOPPED
    /** state of an AudioTrack this is paused */
    public static final int PLAYSTATE_PAUSED  = 2;  // matches SL_PLAYSTATE_PAUSED
    /** state of an AudioTrack this is playing */
    public static final int PLAYSTATE_PLAYING = 3;  // matches SL_PLAYSTATE_PLAYING
    
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
     * State of an AudioTrack that was not successfully initialized upon creation 
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

    
    // to keep in sync with libs/android_runtime/android_media_AudioTrack.cpp
    //    error codes
    /**
     * Denotes a successful operation.
     */
    public  static final int SUCCESS                               = 0;
    /**
     * Denotes a generic operation failure.
     */
    public  static final int ERROR                                 = -1;
    private static final int ERROR_NATIVESETUP_AUDIOSYSTEM         = -2;
    private static final int ERROR_NATIVESETUP_INVALIDCHANNELCOUNT = -3;
    private static final int ERROR_NATIVESETUP_INVALIDFORMAT       = -4;
    private static final int ERROR_NATIVESETUP_INVALIDSTREAMTYPE   = -5;
    private static final int ERROR_NATIVESETUP_NATIVEINITFAILED    = -6;
    /**
     * Denotes a failure due to the use of an invalid value.
     */
    public  static final int ERROR_BAD_VALUE                       = -7;
    /**
     * Denotes a failure due to the improper use of a method.
     */
    public  static final int ERROR_INVALID_OPERATION               = -8;
    //    events
    /**
     * Event id for when the playback head has reached a previously set marker.
     */
    protected static final int NATIVE_EVENT_MARKER  = 3;
    /**
     * Event id for when the previously set update period has passed during playback.
     */
    protected static final int NATIVE_EVENT_NEW_POS = 4;
    
    private final static String TAG = "AudioTrack-Java";

    
    //--------------------------------------------------------------------------
    // Member variables
    //--------------------
    /**
     * Indicates the state of the AudioTrack instance
     */
    protected int mState = STATE_UNINITIALIZED;
    /**
     * Indicates the play state of the AudioTrack instance
     */
    protected int mPlayState = PLAYSTATE_STOPPED;
    /**
     * Lock to make sure mPlayState updates are reflecting the actual state of the object.
     */
    protected final Object mPlayStateLock = new Object();
    /**
     * The listener the AudioTrack notifies previously set marker is reached.
     *  @see #setMarkerReachedListener(OnMarkerReachedListener)
     */
    protected OnMarkerReachedListener mMarkerListener = null;
    /**
     * Lock to protect marker listener updates against event notifications
     */
    protected final Object mMarkerListenerLock = new Object();
    /**
     * The listener the AudioTrack notifies periodically during playback.
     *  @see #setPeriodicNotificationListener(OnPeriodicNotificationListener)
     */
    protected OnPeriodicNotificationListener mPeriodicListener = null;
    /**
     * Lock to protect periodic listener updates against event notifications
     */
    protected final Object mPeriodicListenerLock = new Object();
    /**
     * Size of the native audio buffer.
     */
    protected int mNativeBufferSizeInBytes = 0;
    /**
     * Handler for events coming from the native code
     */
    protected NativeEventHandler mNativeEventHandler = null;
    /**
     * The audio data sampling rate in Hz.
     */
    protected int mSampleRate = 22050;
    /**
     * The number of input audio channels (1 is mono, 2 is stereo)
     */
    protected int mChannelCount = 1;
    /**
     * The type of the audio stream to play. See
     *   {@link AudioManager.STREAM_VOICE_CALL}, {@link AudioManager.STREAM_SYSTEM},
     *   {@link AudioManager.STREAM_RING}, {@link AudioManager.STREAM_MUSIC} and
     *   {@link AudioManager.STREAM_ALARM}
     */
    protected int mStreamType = AudioManager.STREAM_MUSIC;
    /**
     * The way audio is consumed by the hardware, streaming or static.
     */
    protected int mDataLoadMode = MODE_STREAM;
    /**
     * The current audio channel configuration
     */
    protected int mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    /**
     * The encoding of the audio samples.
     * @see #AudioFormat.ENCODING_PCM_8BIT
     * @see #AudioFormat.ENCODING_PCM_16BIT
     */
    protected int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;


    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /** 
     * Accessed by native methods: provides access to C++ AudioTrack object 
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
     *   {@link AudioSystem.STREAM_VOICE_CALL}, {@link AudioSystem.STREAM_SYSTEM},
     *   {@link AudioSystem.STREAM_RING}, {@link AudioSystem.STREAM_MUSIC} and
     *   {@link AudioSystem.STREAM_ALARM}
     * @param sampleRateInHz the sample rate expressed in Hertz. Examples of rates are (but
     *   not limited to) 44100, 22050 and 11025.
     * @param channelConfig describes the configuration of the audio channels. 
     *   See {@link AudioFormat.CHANNEL_CONFIGURATION_MONO} and
     *   {@link AudioFormat.CHANNEL_CONFIGURATION_STEREO}
     * @param audioFormat the format in which the audio data is represented. 
     *   See {@link AudioFormat.ENCODING_PCM_16BIT} and 
     *   {@link AudioFormat.ENCODING_PCM_8BIT}
     * @param bufferSizeInBytes the total size (in bytes) of the buffer where audio data is read
     *   from for playback. If using the AudioTrack in streaming mode, you can write data into 
     *   this buffer in smaller chunks than this size. If using the AudioTrack in static mode,
     *   this is the maximum size of the sound that will be played for this instance.
     * @param mode streaming or static buffer. See {@link #MODE_STATIC} and {@link #MODE_STREAM}
     * @throws java.lang.IllegalArgumentException
     */
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat,
            int bufferSizeInBytes, int mode)
    throws IllegalArgumentException {   
        mState = STATE_UNINITIALIZED;

        audioParamCheck(streamType, sampleRateInHz, channelConfig, audioFormat, mode);

        audioBuffSizeCheck(bufferSizeInBytes);

        // native initialization
        int initResult = native_setup(new WeakReference<AudioTrack>(this), 
                mStreamType, mSampleRate, mChannelCount, mAudioFormat, 
                mNativeBufferSizeInBytes, mDataLoadMode);
        if (initResult != SUCCESS) {
            loge("Error code "+initResult+" when initializing AudioTrack.");
            return; // with mState == STATE_UNINITIALIZED
        }

        if (mDataLoadMode == MODE_STATIC) {
            mState = STATE_NO_STATIC_DATA;
        } else {
            mState = STATE_INITIALIZED;
        }
    }
    
    
    // Convenience method for the constructor's parameter checks.
    // This is where constructor IllegalArgumentException-s are thrown
    // postconditions:
    //    mStreamType is valid
    //    mChannelCount is valid
    //    mAudioFormat is valid
    //    mSampleRate is valid
    //    mDataLoadMode is valid
    private void audioParamCheck(int streamType, int sampleRateInHz, 
                                 int channelConfig, int audioFormat, int mode) {
      
        //--------------
        // stream type
        if( (streamType != AudioManager.STREAM_ALARM) && (streamType != AudioManager.STREAM_MUSIC)
           && (streamType != AudioManager.STREAM_RING) && (streamType != AudioManager.STREAM_SYSTEM)
           && (streamType != AudioManager.STREAM_VOICE_CALL) ) {
            throw (new IllegalArgumentException("Invalid stream type."));
        } else {
            mStreamType = streamType;
        }
        
        //--------------
        // sample rate
        if ( (sampleRateInHz < 4000) || (sampleRateInHz > 48000) ) {
            throw (new IllegalArgumentException(sampleRateInHz
                    + "Hz is not a supported sample rate."));
        } else { 
            mSampleRate = sampleRateInHz;
        }

        //--------------
        // channel config
        switch (channelConfig) {
        case AudioFormat.CHANNEL_CONFIGURATION_DEFAULT:
        case AudioFormat.CHANNEL_CONFIGURATION_MONO:
            mChannelCount = 1;
            mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
            break;
        case AudioFormat.CHANNEL_CONFIGURATION_STEREO:
            mChannelCount = 2;
            mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_STEREO;
            break;
        default:
            mChannelCount = 0;
            mChannelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_INVALID;
            throw(new IllegalArgumentException("Unsupported channel configuration."));
        }

        //--------------
        // audio format
        switch (audioFormat) {
        case AudioFormat.ENCODING_DEFAULT:
            mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
            break;
        case AudioFormat.ENCODING_PCM_16BIT:
        case AudioFormat.ENCODING_PCM_8BIT:
            mAudioFormat = audioFormat;
            break;
        default:
            mAudioFormat = AudioFormat.ENCODING_INVALID;
            throw(new IllegalArgumentException("Unsupported sample encoding." 
                + " Should be ENCODING_PCM_8BIT or ENCODING_PCM_16BIT."));
        }
        
        //--------------
        // audio load mode
        if ( (mode != MODE_STREAM) && (mode != MODE_STATIC) ) {
            throw(new IllegalArgumentException("Invalid mode."));
        } else {
            mDataLoadMode = mode;
        }
    }
    
    
    // Convenience method for the contructor's audio buffer size check.
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
            throw (new IllegalArgumentException("Invalid audio buffer size."));
        }
        
        mNativeBufferSizeInBytes = audioBufferSize;
    }
    
    
    // Convenience method for the creation of the native event handler
    // It is called only when a non-null event listener is set.
    // precondition:
    //    mNativeEventHandler is null
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
    
    
    /**
     * Releases the native AudioTrack resources.
     */
    public void release() {
        // even though native_release() stops the native AudioTrack, we need to stop
        // AudioTrack subclasses too.
        stop();
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
        return AudioTrack.VOLUME_MIN;
    }
    
    /**
     * Returns the maximum valid volume value. Volume values set above this one will
     * be clamped at this value.
     * @return the maximum volume expressed as a linear attenuation.
     */
    static public float getMaxVolume() {
        return AudioTrack.VOLUME_MAX;
    }    
    
    /**
     * Returns the configured audio data sample rate in Hz
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the configured audio data format. See {@link #AudioFormat.ENCODING_PCM_16BIT}
     * and {@link #AudioFormat.ENCODING_PCM_8BIT}.
     */
    public int getAudioFormat() {
        return mAudioFormat;
    }
    
    /**
     * Returns the type of audio stream this AudioTrack is configured for.
     * Compare the result against {@link AudioManager.STREAM_VOICE_CALL}, 
     * {@link AudioManager.STREAM_SYSTEM}, {@link AudioManager.STREAM_RING}, 
     * {@link AudioManager.STREAM_MUSIC} or {@link AudioManager.STREAM_ALARM}
     */
    public int getStreamType() {
        return mStreamType;
    }

    /**
     * Returns the configured channel configuration. 
     * See {@link #AudioFormat.CHANNEL_CONFIGURATION_MONO}
     * and {@link #AudioFormat.CHANNEL_CONFIGURATION_STEREO}.
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
     * properly. This ensures that the appropriate hardware resources have been
     * acquired.
     */
    public int getState() {
        return mState;
    }
    
    /**
     * Returns the playback state of the AudioTrack instance.
     * @see AudioTrack.PLAYSTATE_STOPPED
     * @see AudioTrack.PLAYSTATE_PAUSED
     * @see AudioTrack.PLAYSTATE_PLAYING
     */
    public int getPlayState() {
        return mPlayState;
    }

    /**
     *  Returns the native frame count used by the hardware
     */
    protected int getNativeFrameCount() {
        return native_get_native_frame_count();
    }

    /**
     * @return marker position in frames
     */
    public int getNotificationMarkerPosition() {
        return native_get_marker_pos();
    }

    /**
     * @return update period in frames
     */
    public int getPositionNotificationPeriod() {
        return native_get_pos_update_period();
    }

    /**
     * @return playback head position in frames
     */
    public int getPlaybackHeadPosition() {
        return native_get_position();
    }

    /**
     *  Returns the hardware output sample rate
     */
    static public int getNativeOutputSampleRate() {
        return native_get_output_sample_rate();
    }

    
    //--------------------------------------------------------------------------
    // Initialization / configuration
    //--------------------  
    /**
     * Sets the listener the AudioTrack notifies when a previously set marker is reached.
     * @param listener
     */
    public void setMarkerReachedListener(OnMarkerReachedListener listener) {
        synchronized (mMarkerListenerLock) {
            mMarkerListener = listener;
        }
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }
    
    
    /**
     * Sets the listener the AudioTrack notifies periodically during playback.
     * @param listener
     */
    public void setPeriodicNotificationListener(OnPeriodicNotificationListener listener) {
        synchronized (mPeriodicListenerLock) {
            mPeriodicListener = listener;
        }
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }
    
    
     /** 
     * Sets the specified left/right output volume values on the AudioTrack. Values are clamped 
     * to the ({@link #getMinVolume()}, {@link #getMaxVolume()}) interval if outside this range.
     * @param leftVolume output attenuation for the left channel. A value of 0.0f is silence, 
     *      a value of 1.0f is no attenuation.
     * @param rightVolume output attenuation for the right channel
     * @return {@link #SUCCESS}
     * @throws IllegalStateException
     */
    public int setStereoVolume(float leftVolume, float rightVolume)
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("setStereoVolume() called on an "+
                "uninitialized AudioTrack."));
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
     * Sets the playback sample rate for this track. This sets the sampling rate at which
     * the audio data will be consumed and played back, not the original sampling rate of the
     * content. Setting it to half the sample rate of the content will cause the playback to
     * last twice as long, but will also result result in a negative pitch shift.
     * The current implementation supports a maximum sample rate of twice the hardware output
     * sample rate (see {@link #getNativeOutputSampleRate()}). Use {@link #getSampleRate()} to
     * check the rate actually used in hardware after potential clamping.
     * @param sampleRateInHz
     * @return {@link #SUCCESS}
     */
    public int setPlaybackRate(int sampleRateInHz) {
        native_set_playback_rate(sampleRateInHz);
        return SUCCESS;
    }
    
    
    /**
     * 
     * @param markerInFrames marker in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *  {@link #ERROR_INVALID_OPERATION} 
     */
    public int setNotificationMarkerPosition(int markerInFrames) {
        return native_set_marker_pos(markerInFrames);
    }
    
    
    /**
     * @param periodInFrames update period in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_INVALID_OPERATION}
     */
    public int setPositionNotificationPeriod(int periodInFrames) {
        return native_set_pos_update_period(periodInFrames);
    }
    
    
    /**
     * Sets the playback head position. The track must be stopped for the position to be changed.
     * @param positionInFrames playback head position in frames
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE}
     * @throws java.lang.IllegalStateException if the track is not in 
     *    the {@link #PLAYSTATE_STOPPED} state.
     */
    public int setPlaybackHeadPosition(int positionInFrames)
    throws IllegalStateException {
        synchronized(mPlayStateLock) {
            if(mPlayState == PLAYSTATE_STOPPED) {
                return native_set_position(positionInFrames);
            }
        }
        throw(new IllegalStateException("setPlaybackHeadPosition() called on a track that is "+
                "not in the PLAYSTATE_STOPPED play state."));
    }
    
    /**
     * Sets the loop points and the loop count. The loop can be infinite.
     * @param startInFrames loop start marker in frames
     * @param endInFrames loop end marker in frames
     * @param loopCount the number of times the loop is looped. 
     *    A value of -1 means infinite looping.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE}
     */
    public int setLoopPoints(int startInFrames, int endInFrames, int loopCount) {
        return native_set_loop(startInFrames, endInFrames, loopCount);
    }


    //---------------------------------------------------------
    // Transport control methods
    //--------------------
    /**
     * Starts playing an AudioTrack. 
     * @throws IllegalStateException
     */
    public void play()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("play() called on uninitialized AudioTrack."));
        }
        
        synchronized(mPlayStateLock) {
            native_start();
            mPlayState = PLAYSTATE_PLAYING;
        }
    }

    /**
     * Stops playing the audio data.
     * @throws IllegalStateException
     */
    public void stop()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("stop() called on uninitialized AudioTrack."));
        }

        // stop playing
        synchronized(mPlayStateLock) {
            native_stop();
            mPlayState = PLAYSTATE_STOPPED;
        }
    }

    /**
     * Pauses the playback of the audio data.
     * @throws IllegalStateException
     */    
    public void pause()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("pause() called on uninitialized AudioTrack."));
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
     * Flushes the audio data currently queued for playback.
     * @throws IllegalStateException
     */    
    public void flush()
    throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("flush() called on uninitialized AudioTrack."));
        }
        //logd("flush()");

        // flush the data in native layer
        native_flush();

    }

    /**
     * Writes the audio data to the audio hardware for playback.
     * @param audioData the array that holds the data to play.
     * @param offsetInBytes the offset in audioData where the data to play starts.
     * @param sizeInBytes the number of bytes to read in audioData after the offset.
     * @return the number of bytes that were written.
     * @throws IllegalStateException
     */    
    public int write(byte[] audioData,int offsetInBytes, int sizeInBytes)
    throws IllegalStateException {
        if ((mDataLoadMode == MODE_STATIC) 
                && (mState == STATE_NO_STATIC_DATA)
                && (sizeInBytes > 0)) {
            mState = STATE_INITIALIZED;
        }
        //TODO check if future writes should be forbidden for static tracks
        //     or: how to update data for static tracks?

        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("write() called on uninitialized AudioTrack."));
        }

        return native_write_byte(audioData, offsetInBytes, sizeInBytes);
    }
    
    
    /**
     * Writes the audio data to the audio hardware for playback.
     * @param audioData the array that holds the data to play.
     * @param offsetInShorts the offset in audioData where the data to play starts.
     * @param sizeInShorts the number of bytes to read in audioData after the offset.
     * @return the number of shorts that were written.
     * @throws IllegalStateException
     */    
    public int write(short[] audioData, int offsetInShorts, int sizeInShorts)
    throws IllegalStateException {
        if ((mDataLoadMode == MODE_STATIC) 
                && (mState == STATE_NO_STATIC_DATA)
                && (sizeInShorts > 0)) {
            mState = STATE_INITIALIZED;
        }
        //TODO check if future writes should be forbidden for static tracks
        //     or: how to update data for static tracks?

        if (mState != STATE_INITIALIZED) {
            throw(new IllegalStateException("write() called on uninitialized AudioTrack."));
        }

        return native_write_short(audioData, offsetInShorts, sizeInShorts);
    }
    
    
    /**
     * Notifies the native resource to reuse the audio data already loaded in the native
     * layer. This call is only valid with AudioTrack instances that don't use the streaming
     * model.
     * @return error code or success, see {@link #SUCCESS}, {@link #ERROR_BAD_VALUE},
     *  {@link #ERROR_INVALID_OPERATION}
     */
    public int reloadStaticData() {
        if (mDataLoadMode == MODE_STREAM) {
            return ERROR_INVALID_OPERATION;
        }
        return native_reload_static();
    }


    //---------------------------------------------------------
    // Interface definitions
    //--------------------
    /**
     * Interface definition for a callback to be invoked when an AudioTrack has
     * reached a notification marker set by setNotificationMarkerPosition().
     */
    public interface OnMarkerReachedListener  {
        /**
         * Called on the listener to notify it that the previously set marker has been reached
         * by the playback head.
         */
        void onMarkerReached(AudioTrack track);
    }


    /**
     * Interface definition for a callback to be invoked for each periodic AudioTrack 
     * update during playback. The update interval is set by setPositionNotificationPeriod().
     */
    public interface OnPeriodicNotificationListener  {
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
     * Helper class to handle the forwarding of native events to the appropriate listeners
     */
    private class NativeEventHandler extends Handler
    {
        private AudioTrack mAudioTrack;

        public NativeEventHandler(AudioTrack mp, Looper looper) {
            super(looper);
            mAudioTrack = mp;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mAudioTrack == null) {
                return;
            }
            switch(msg.what) {
            case NATIVE_EVENT_MARKER:
                synchronized (mMarkerListenerLock) {
                    if (mAudioTrack.mMarkerListener != null) {
                        mAudioTrack.mMarkerListener.onMarkerReached(mAudioTrack);
                    }
                }
                break;
            case NATIVE_EVENT_NEW_POS:
                synchronized (mPeriodicListenerLock) {
                    if (mAudioTrack.mPeriodicListener != null) {
                        mAudioTrack.mPeriodicListener.onPeriodicNotification(mAudioTrack);
                    }
                }
                break;
            default:
                Log.e(TAG, "[ android.media.AudioTrack.NativeEventHandler ] " +
                        "Unknown event type: " + msg.what);
                break;
            }
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
        
        if (track.mNativeEventHandler != null) {
            Message m = track.mNativeEventHandler.obtainMessage(what, arg1, arg2, obj);
            track.mNativeEventHandler.sendMessage(m);
        }

    }
    
    
    //---------------------------------------------------------
    // Native methods called from the Java side
    //--------------------

    private native final int native_setup(Object audiotrack_this, 
            int streamType, int sampleRate, int nbChannels, int audioFormat, 
            int buffSizeInBytes, int mode);

    private native final void native_finalize();
    
    private native final void native_release();

    private native final void native_start();  

    private native final void native_stop();

    private native final void native_pause();

    private native final void native_flush();

    private native final int native_write_byte(byte[] audioData, 
                                               int offsetInBytes, int sizeInBytes);
    
    private native final int native_write_short(short[] audioData, 
                                                int offsetInShorts, int sizeInShorts);
    
    private native final int native_reload_static();

    private native final int native_get_native_frame_count();

    private native final void native_setVolume(float leftVolume, float rightVolume);
    
    private native final void native_set_playback_rate(int sampleRateInHz);
    private native final int  native_get_playback_rate();
    
    private native final int native_set_marker_pos(int marker);
    private native final int native_get_marker_pos();
    
    private native final int native_set_pos_update_period(int updatePeriod);
    private native final int native_get_pos_update_period();
    
    private native final int native_set_position(int position);
    private native final int native_get_position();
    
    private native final int native_set_loop(int start, int end, int loopCount);
    
    static private native final int native_get_output_sample_rate();
    

    //---------------------------------------------------------
    // Utility methods
    //------------------

    private static void logd(String msg) {
        Log.d(TAG, "[ android.media.AudioTrack ] " + msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, "[ android.media.AudioTrack ] " + msg);
    }

}



