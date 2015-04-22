/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioTrack;
import android.media.PlaybackSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 * MediaSync class can be used to synchronously playback audio and video streams.
 * It can be used to play audio-only or video-only stream, too.
 *
 * <p>MediaSync is generally used like this:
 * <pre>
 * MediaSync sync = new MediaSync();
 * sync.configureSurface(surface);
 * Surface inputSurface = sync.createInputSurface();
 * ...
 * // MediaCodec videoDecoder = ...;
 * videoDecoder.configure(format, inputSurface, ...);
 * ...
 * sync.configureAudioTrack(audioTrack);
 * sync.setCallback(new MediaSync.Callback() {
 *     {@literal @Override}
 *     public void onReturnAudioBuffer(MediaSync sync, ByteBuffer audioBuffer, int bufferIndex) {
 *         ...
 *     }
 * }, null);
 * // This needs to be done since sync is paused on creation.
 * sync.setPlaybackRate(1.0f, MediaSync.PLAYBACK_RATE_AUDIO_MODE_RESAMPLE);
 *
 * for (;;) {
 *   ...
 *   // send video frames to surface for rendering, e.g., call
 *   // videoDecoder.releaseOutputBuffer(videoOutputBufferIx, videoPresentationTimeNs);
 *   // More details are available as below.
 *   ...
 *   sync.queueAudio(audioByteBuffer, bufferIndex, size, audioPresentationTimeUs); // non-blocking.
 *   // The audioByteBuffer and bufferIndex will be returned via callback.
 *   // More details are available as below.
 *   ...
 *     ...
 * }
 * sync.setPlaybackRate(0.0f, MediaSync.PLAYBACK_RATE_AUDIO_MODE_RESAMPLE);
 * sync.release();
 * sync = null;
 *
 * // The following code snippet illustrates how video/audio raw frames are created by
 * // MediaCodec's, how they are fed to MediaSync and how they are returned by MediaSync.
 * // This is the callback from MediaCodec.
 * onOutputBufferAvailable(MediaCodec codec, int bufferIndex, BufferInfo info) {
 *     // ...
 *     if (codec == videoDecoder) {
 *         // surface timestamp must contain media presentation time in nanoseconds.
 *         codec.releaseOutputBuffer(bufferIndex, 1000 * info.presentationTime);
 *     } else {
 *         ByteBuffer audioByteBuffer = codec.getOutputBuffer(bufferIndex);
 *         sync.queueByteBuffer(audioByteBuffer, bufferIndex, info.size, info.presentationTime);
 *     }
 *     // ...
 * }
 *
 * // This is the callback from MediaSync.
 * onReturnAudioBuffer(MediaSync sync, ByteBuffer buffer, int bufferIndex) {
 *     // ...
 *     audioDecoder.releaseBuffer(bufferIndex, false);
 *     // ...
 * }
 *
 * </pre>
 *
 * The client needs to configure corresponding sink (i.e., Surface and AudioTrack) based on
 * the stream type it will play.
 * <p>
 * For video, the client needs to call {@link #createInputSurface} to obtain a surface on
 * which it will render video frames.
 * <p>
 * For audio, the client needs to set up audio track correctly, e.g., using {@link
 * AudioTrack#MODE_STREAM}. The audio buffers are sent to MediaSync directly via {@link
 * #queueAudio}, and are returned to the client via {@link Callback#onReturnAudioBuffer}
 * asynchronously. The client should not modify an audio buffer till it's returned.
 * <p>
 * The client can optionally pre-fill audio/video buffers by setting playback rate to 0.0,
 * and then feed audio/video buffers to corresponding components. This can reduce possible
 * initial underrun.
 * <p>
 */
final public class MediaSync {
    /**
     * MediaSync callback interface. Used to notify the user asynchronously
     * of various MediaSync events.
     */
    public static abstract class Callback {
        /**
         * Called when returning an audio buffer which has been consumed.
         *
         * @param sync The MediaSync object.
         * @param audioBuffer The returned audio buffer.
         * @param bufferIndex The index associated with the audio buffer
         */
        public abstract void onReturnAudioBuffer(
                @NonNull MediaSync sync, @NonNull ByteBuffer audioBuffer, int bufferIndex);
    }

    private static final String TAG = "MediaSync";

    private static final int EVENT_CALLBACK = 1;
    private static final int EVENT_SET_CALLBACK = 2;

    private static final int CB_RETURN_AUDIO_BUFFER = 1;

    private static class AudioBuffer {
        public ByteBuffer mByteBuffer;
        public int mBufferIndex;
        public int mSizeInBytes;
        long mPresentationTimeUs;

        public AudioBuffer(@NonNull ByteBuffer byteBuffer, int bufferIndex,
                           int sizeInBytes, long presentationTimeUs) {
            mByteBuffer = byteBuffer;
            mBufferIndex = bufferIndex;
            mSizeInBytes = sizeInBytes;
            mPresentationTimeUs = presentationTimeUs;
        }
    }

    private final Object mCallbackLock = new Object();
    private Handler mCallbackHandler = null;
    private MediaSync.Callback mCallback = null;

    private Thread mAudioThread = null;
    // Created on mAudioThread when mAudioThread is started. When used on user thread, they should
    // be guarded by checking mAudioThread.
    private Handler mAudioHandler = null;
    private Looper mAudioLooper = null;

    private final Object mAudioLock = new Object();
    private AudioTrack mAudioTrack = null;
    private List<AudioBuffer> mAudioBuffers = new LinkedList<AudioBuffer>();
    private float mPlaybackRate = 0.0f;

    private long mNativeContext;

    /**
     * Class constructor. On creation, MediaSync is paused, i.e., playback rate is 0.0f.
     */
    public MediaSync() {
        native_setup();
    }

    private native final void native_setup();

    @Override
    protected void finalize() {
        native_finalize();
    }

    private native final void native_finalize();

    /**
     * Make sure you call this when you're done to free up any opened
     * component instance instead of relying on the garbage collector
     * to do this for you at some point in the future.
     */
    public final void release() {
        returnAudioBuffers();
        if (mAudioThread != null) {
            if (mAudioLooper != null) {
                mAudioLooper.quit();
            }
        }
        setCallback(null, null);
        native_release();
    }

    private native final void native_release();

    /**
     * Sets an asynchronous callback for actionable MediaSync events.
     * <p>
     * This method can be called multiple times to update a previously set callback. If the
     * handler is changed, undelivered notifications scheduled for the old handler may be dropped.
     * <p>
     * <b>Do not call this inside callback.</b>
     *
     * @param cb The callback that will run. Use {@code null} to stop receiving callbacks.
     * @param handler The Handler that will run the callback. Use {@code null} to use MediaSync's
     *     internal handler if it exists.
     */
    public void setCallback(@Nullable /* MediaSync. */ Callback cb, @Nullable Handler handler) {
        synchronized(mCallbackLock) {
            if (handler != null) {
                mCallbackHandler = handler;
            } else {
                Looper looper;
                if ((looper = Looper.myLooper()) == null) {
                    looper = Looper.getMainLooper();
                }
                if (looper == null) {
                    mCallbackHandler = null;
                } else {
                    mCallbackHandler = new Handler(looper);
                }
            }

            mCallback = cb;
        }
    }

    /**
     * Configures the output surface for MediaSync.
     *
     * @param surface Specify a surface on which to render the video data.
     * @throws IllegalArgumentException if the surface has been released, or is invalid.
     *     or can not be connected.
     * @throws IllegalStateException if not in the Initialized state, or another surface
     *     has already been configured.
     */
    public void configureSurface(@Nullable Surface surface) {
        native_configureSurface(surface);
    }

    private native final void native_configureSurface(@Nullable Surface surface);

    /**
     * Configures the audio track for MediaSync.
     *
     * @param audioTrack Specify an AudioTrack through which to render the audio data.
     * @throws IllegalArgumentException if the audioTrack has been released, or is invalid.
     * @throws IllegalStateException if not in the Initialized state, or another audio track
     *     has already been configured.
     */
    public void configureAudioTrack(@Nullable AudioTrack audioTrack) {
        // AudioTrack has sanity check for configured sample rate.
        int nativeSampleRateInHz = (audioTrack == null ? 0 : audioTrack.getSampleRate());

        native_configureAudioTrack(audioTrack, nativeSampleRateInHz);
        mAudioTrack = audioTrack;
        if (audioTrack != null && mAudioThread == null) {
            createAudioThread();
        }
    }

    private native final void native_configureAudioTrack(
            @Nullable AudioTrack audioTrack, int nativeSampleRateInHz);

    /**
     * Requests a Surface to use as the input. This may only be called after
     * {@link #configureSurface}.
     * <p>
     * The application is responsible for calling release() on the Surface when
     * done.
     * @throws IllegalStateException if not configured, or another input surface has
     *     already been created.
     */
    @NonNull
    public native final Surface createInputSurface();

    /**
     * Resample audio data when changing playback speed.
     * <p>
     * Resample the waveform based on the requested playback rate to get
     * a new waveform, and play back the new waveform at the original sampling
     * frequency.
     * <p><ul>
     * <li>When rate is larger than 1.0, pitch becomes higher.
     * <li>When rate is smaller than 1.0, pitch becomes lower.
     * </ul>
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_RESAMPLE = 2;

    /**
     * Time stretch audio when changing playback speed.
     * <p>
     * Time stretching changes the duration of the audio samples without
     * affecting their pitch. This is only supported for a limited range
     * of playback speeds, e.g. from 1/2x to 2x. If the rate is adjusted
     * beyond this limit, the rate change will fail.
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_STRETCH = 1;

    /**
     * Time stretch audio when changing playback speed, and may mute if
     * stretching is no longer supported.
     * <p>
     * Time stretching changes the duration of the audio samples without
     * affecting their pitch. This is only supported for a limited range
     * of playback speeds, e.g. from 1/2x to 2x. When it is no longer
     * supported, the audio may be muted.  Using this mode will not fail
     * for non-negative playback rates.
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_DEFAULT = 0;

    /** @hide */
    @IntDef(
        value = {
            PLAYBACK_RATE_AUDIO_MODE_DEFAULT,
            PLAYBACK_RATE_AUDIO_MODE_STRETCH,
            PLAYBACK_RATE_AUDIO_MODE_RESAMPLE,
        })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlaybackRateAudioMode {}

    /**
     * Sets playback rate and audio mode.
     *
     * @param rate the ratio between desired playback rate and normal one. 1.0 means normal
     *     playback speed. 0.0 means pause. Value larger than 1.0 means faster playback,
     *     while value between 0.0 and 1.0 for slower playback. <b>Note:</b> the normal rate
     *     does not change as a result of this call. To restore the original rate at any time,
     *     use 1.0.
     * @param audioMode audio playback mode. Must be one of the supported
     *     audio modes.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     * @throws IllegalArgumentException if audioMode is not supported.
     */
    public void setPlaybackRate(float rate, @PlaybackRateAudioMode int audioMode) {
        PlaybackSettings rateSettings = new PlaybackSettings();
        rateSettings.allowDefaults();
        switch (audioMode) {
            case PLAYBACK_RATE_AUDIO_MODE_DEFAULT:
                rateSettings.setSpeed(rate).setPitch(1.0f);
                break;
            case PLAYBACK_RATE_AUDIO_MODE_STRETCH:
                rateSettings.setSpeed(rate).setPitch(1.0f)
                        .setAudioFallbackMode(rateSettings.AUDIO_FALLBACK_MODE_FAIL);
                break;
            case PLAYBACK_RATE_AUDIO_MODE_RESAMPLE:
                rateSettings.setSpeed(rate).setPitch(rate);
                break;
            default:
            {
                final String msg = "Audio playback mode " + audioMode + " is not supported";
                throw new IllegalArgumentException(msg);
            }
        }
        setPlaybackSettings(rateSettings);
    }

    /**
     * Sets playback rate using {@link PlaybackSettings}.
     * <p>
     * When using MediaSync with {@link AudioTrack}, set playback settings using this
     * call instead of calling it directly on the track, so that the sync is aware of
     * the settings change.
     * <p>
     * This call also works if there is no audio track.
     *
     * @param settings the playback settings to use. {@link PlaybackSettings#getSpeed
     *     Speed} is the ratio between desired playback rate and normal one. 1.0 means
     *     normal playback speed. 0.0 means pause. Value larger than 1.0 means faster playback,
     *     while value between 0.0 and 1.0 for slower playback. <b>Note:</b> the normal rate
     *     does not change as a result of this call. To restore the original rate at any time,
     *     use speed of 1.0.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     * @throws IllegalArgumentException if the settings are not supported.
     */
    public void setPlaybackSettings(@NonNull PlaybackSettings settings) {
        float rate;
        try {
            rate = settings.getSpeed();

            // rate is specified
            if (mAudioTrack != null) {
                try {
                    if (rate == 0.0) {
                        mAudioTrack.pause();
                    } else {
                        mAudioTrack.setPlaybackSettings(settings);
                        mAudioTrack.play();
                    }
                } catch (IllegalStateException e) {
                    throw e;
                }
            }

            synchronized(mAudioLock) {
                mPlaybackRate = rate;
            }
            if (mPlaybackRate != 0.0 && mAudioThread != null) {
                postRenderAudio(0);
            }
            native_setPlaybackRate(mPlaybackRate);
        } catch (IllegalStateException e) {
            // rate is not specified; still, propagate settings to audio track
            if (mAudioTrack != null) {
                mAudioTrack.setPlaybackSettings(settings);
            }
        }
    }

    /**
     * Gets the playback rate using {@link PlaybackSettings}.
     *
     * @return the playback rate being used.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     */
    @NonNull
    public PlaybackSettings getPlaybackSettings() {
        if (mAudioTrack != null) {
            return mAudioTrack.getPlaybackSettings();
        } else {
            PlaybackSettings settings = new PlaybackSettings();
            settings.allowDefaults();
            settings.setSpeed(mPlaybackRate);
            return settings;
        }
    }

    private native final void native_setPlaybackRate(float rate);

   /**
    * Get current playback position.
    * <p>
    * The MediaTimestamp represents a clock ticking during media playback. It's represented
    * by an anchor frame ({@link MediaTimestamp#mediaTimeUs} and {@link MediaTimestamp#nanoTime})
    * and clock speed ({@link MediaTimestamp#clockRate}). For continous playback with
    * constant speed, its anchor frame doesn't change that often. Thereafter, it's recommended
    * to not call this method often.
    * <p>
    * To help users to get current playback position, this method always returns the timestamp of
    * just-rendered frame, i.e., {@link System#nanoTime} and its corresponding media time. They
    * can be used as current playback position.
    *
    * @param timestamp a reference to a non-null MediaTimestamp instance allocated
    *         and owned by caller.
    * @return true if a timestamp is available, or false if no timestamp is available.
    *         If a timestamp if available, the MediaTimestamp instance is filled in with
    *         playback rate, together with the current media timestamp and the system nanoTime
    *         corresponding to the measured media timestamp.
    *         In the case that no timestamp is available, any supplied instance is left unaltered.
    */
    public boolean getTimestamp(@NonNull MediaTimestamp timestamp)
    {
        if (timestamp == null) {
            throw new IllegalArgumentException();
        }
        return native_getTimestamp(timestamp);
    }

    private native final boolean native_getTimestamp(@NonNull MediaTimestamp timestamp);

    /**
     * Queues the audio data asynchronously for playback (AudioTrack must be in streaming mode).
     * @param audioData the buffer that holds the data to play. This buffer will be returned
     *     to the client via registered callback.
     * @param bufferIndex the buffer index used to identify audioData. It will be returned to
     *     the client along with audioData. This helps applications to keep track of audioData.
     * @param sizeInBytes number of bytes to queue.
     * @param presentationTimeUs the presentation timestamp in microseconds for the first frame
     *     in the buffer.
     * @throws IllegalStateException if audio track is not configured or internal configureation
     *     has not been done correctly.
     */
    public void queueAudio(
            @NonNull ByteBuffer audioData, int bufferIndex, int sizeInBytes,
            long presentationTimeUs) {
        if (mAudioTrack == null || mAudioThread == null) {
            throw new IllegalStateException(
                    "AudioTrack is NOT configured or audio thread is not created");
        }

        synchronized(mAudioLock) {
            mAudioBuffers.add(new AudioBuffer(
                    audioData, bufferIndex, sizeInBytes, presentationTimeUs));
        }

        if (mPlaybackRate != 0.0) {
            postRenderAudio(0);
        }
    }

    // When called on user thread, make sure to check mAudioThread != null.
    private void postRenderAudio(long delayMillis) {
        mAudioHandler.postDelayed(new Runnable() {
            public void run() {
                synchronized(mAudioLock) {
                    if (mPlaybackRate == 0.0) {
                        return;
                    }

                    if (mAudioBuffers.isEmpty()) {
                        return;
                    }

                    AudioBuffer audioBuffer = mAudioBuffers.get(0);
                    int sizeWritten = mAudioTrack.write(
                            audioBuffer.mByteBuffer,
                            audioBuffer.mSizeInBytes,
                            AudioTrack.WRITE_NON_BLOCKING);
                    if (sizeWritten > 0) {
                        if (audioBuffer.mPresentationTimeUs != -1) {
                            native_updateQueuedAudioData(
                                    audioBuffer.mSizeInBytes, audioBuffer.mPresentationTimeUs);
                            audioBuffer.mPresentationTimeUs = -1;
                        }

                        if (sizeWritten == audioBuffer.mSizeInBytes) {
                            postReturnByteBuffer(audioBuffer);
                            mAudioBuffers.remove(0);
                            if (!mAudioBuffers.isEmpty()) {
                                postRenderAudio(0);
                            }
                            return;
                        }

                        audioBuffer.mSizeInBytes -= sizeWritten;
                    }
                    // TODO: wait time depends on fullness of audio track.
                    postRenderAudio(10);
                }
            }
        }, delayMillis);
    }

    private native final void native_updateQueuedAudioData(
            int sizeInBytes, long presentationTimeUs);

    private final void postReturnByteBuffer(@NonNull final AudioBuffer audioBuffer) {
        synchronized(mCallbackLock) {
            if (mCallbackHandler != null) {
                final MediaSync sync = this;
                mCallbackHandler.post(new Runnable() {
                    public void run() {
                        synchronized(mCallbackLock) {
                            if (mCallbackHandler == null
                                    || mCallbackHandler.getLooper().getThread()
                                            != Thread.currentThread()) {
                                // callback handler has been changed.
                                return;
                            }
                            if (mCallback != null) {
                                mCallback.onReturnAudioBuffer(sync, audioBuffer.mByteBuffer,
                                        audioBuffer.mBufferIndex);
                            }
                        }
                    }
                });
            }
        }
    }

    private final void returnAudioBuffers() {
        synchronized(mAudioLock) {
            for (AudioBuffer audioBuffer: mAudioBuffers) {
                postReturnByteBuffer(audioBuffer);
            }
            mAudioBuffers.clear();
        }
    }

    private void createAudioThread() {
        mAudioThread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                synchronized(mAudioLock) {
                    mAudioLooper = Looper.myLooper();
                    mAudioHandler = new Handler();
                    mAudioLock.notify();
                }
                Looper.loop();
            }
        };
        mAudioThread.start();

        synchronized(mAudioLock) {
            try {
                mAudioLock.wait();
            } catch(InterruptedException e) {
            }
        }
    }

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private static native final void native_init();
}
