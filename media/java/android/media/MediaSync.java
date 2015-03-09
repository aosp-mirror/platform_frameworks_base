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
import android.media.AudioTrack;
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
 * sync.configureAudioTrack(audioTrack, nativeSampleRateInHz);
 * sync.setCallback(new MediaSync.Callback() {
 *     \@Override
 *     public void onReturnAudioBuffer(MediaSync sync, ByteBuffer audioBuffer, int bufferIndex) {
 *         ...
 *     }
 * });
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
         */
        public abstract void onReturnAudioBuffer(
                MediaSync sync, ByteBuffer audioBuffer, int bufferIndex);
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

        public AudioBuffer(ByteBuffer byteBuffer, int bufferIndex,
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

    private int mNativeSampleRateInHz = 0;

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
     * It shouldn't be called inside callback.
     *
     * @param cb The callback that will run.
     * @param handler The Handler that will run the callback. Using null means to use MediaSync's
     *     internal handler if it exists.
     */
    public void setCallback(/* MediaSync. */ Callback cb, Handler handler) {
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
    public void configureSurface(Surface surface) {
        native_configureSurface(surface);
    }

    private native final void native_configureSurface(Surface surface);

    /**
     * Configures the audio track for MediaSync.
     *
     * @param audioTrack Specify an AudioTrack through which to render the audio data.
     * @throws IllegalArgumentException if the audioTrack has been released, or is invalid,
     *     or nativeSampleRateInHz is invalid.
     * @throws IllegalStateException if not in the Initialized state, or another audio track
     *     has already been configured.
     */
    public void configureAudioTrack(AudioTrack audioTrack, int nativeSampleRateInHz) {
        if (audioTrack != null && nativeSampleRateInHz <= 0) {
            final String msg = "Native sample rate " + nativeSampleRateInHz + " is invalid";
            throw new IllegalArgumentException(msg);
        }
        native_configureAudioTrack(audioTrack, nativeSampleRateInHz);
        mAudioTrack = audioTrack;
        mNativeSampleRateInHz = nativeSampleRateInHz;
        if (mAudioThread == null) {
            createAudioThread();
        }
    }

    private native final void native_configureAudioTrack(
            AudioTrack audioTrack, int nativeSampleRateInHz);

    /**
     * Requests a Surface to use as the input. This may only be called after
     * {@link #configureSurface}.
     * <p>
     * The application is responsible for calling release() on the Surface when
     * done.
     * @throws IllegalStateException if not configured, or another input surface has
     *     already been created.
     */
    public native final Surface createInputSurface();

    /**
     * Specifies resampling as audio mode for variable rate playback, i.e.,
     * resample the waveform based on the requested playback rate to get
     * a new waveform, and play back the new waveform at the original sampling
     * frequency.
     * When rate is larger than 1.0, pitch becomes higher.
     * When rate is smaller than 1.0, pitch becomes lower.
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_RESAMPLE = 0;

    /**
     * Specifies time stretching as audio mode for variable rate playback.
     * Time stretching changes the duration of the audio samples without
     * affecting its pitch.
     * FIXME: implement time strectching.
     * @hide
     */
    public static final int PLAYBACK_RATE_AUDIO_MODE_STRETCH = 1;

    /** @hide */
    @IntDef(
        value = {
            PLAYBACK_RATE_AUDIO_MODE_RESAMPLE,
            PLAYBACK_RATE_AUDIO_MODE_STRETCH })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlaybackRateAudioMode {}

    /**
     * Sets playback rate. It does same as {@link #setPlaybackRate(float, int)},
     * except that it always uses {@link #PLAYBACK_RATE_AUDIO_MODE_STRETCH} for audioMode.
     *
     * @param rate the ratio between desired playback rate and normal one. 1.0 means normal
     *     playback speed. 0.0 means stop or pause. Value larger than 1.0 means faster playback,
     *     while value between 0.0 and 1.0 for slower playback.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     * TODO: unhide when PLAYBACK_RATE_AUDIO_MODE_STRETCH is supported.
     * @hide
     */
    public void setPlaybackRate(float rate) {
        setPlaybackRate(rate, PLAYBACK_RATE_AUDIO_MODE_STRETCH);
    }

    /**
     * Sets playback rate and audio mode.
     *
     * <p> The supported audio modes are:
     * <ul>
     * <li> {@link #PLAYBACK_RATE_AUDIO_MODE_RESAMPLE}
     * </ul>
     *
     * @param rate the ratio between desired playback rate and normal one. 1.0 means normal
     *     playback speed. 0.0 means stop or pause. Value larger than 1.0 means faster playback,
     *     while value between 0.0 and 1.0 for slower playback.
     * @param audioMode audio playback mode. Must be one of the supported
     *     audio modes.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     * @throws IllegalArgumentException if audioMode is not supported.
     */
    public void setPlaybackRate(float rate, @PlaybackRateAudioMode int audioMode) {
        if (!isAudioPlaybackModeSupported(audioMode)) {
            final String msg = "Audio playback mode " + audioMode + " is not supported";
            throw new IllegalArgumentException(msg);
        }

        int status = AudioTrack.SUCCESS;
        if (mAudioTrack != null) {
            int playbackSampleRate = (int)(rate * mNativeSampleRateInHz + 0.5);
            rate = playbackSampleRate / (float)mNativeSampleRateInHz;

            try {
                if (rate == 0.0) {
                    mAudioTrack.pause();
                } else {
                    status = mAudioTrack.setPlaybackRate(playbackSampleRate);
                    mAudioTrack.play();
                }
            } catch (IllegalStateException e) {
                throw e;
            }
        }

        if (status != AudioTrack.SUCCESS) {
            throw new IllegalArgumentException("Fail to set playback rate in audio track");
        }

        synchronized(mAudioLock) {
            mPlaybackRate = rate;
        }
        if (mPlaybackRate != 0.0 && mAudioThread != null) {
            postRenderAudio(0);
        }
        native_setPlaybackRate(mPlaybackRate);
    }

    private native final void native_setPlaybackRate(float rate);

    /*
     * Test whether a given audio playback mode is supported.
     * TODO query supported AudioPlaybackMode from audio track.
     */
    private boolean isAudioPlaybackModeSupported(int mode) {
        return (mode == PLAYBACK_RATE_AUDIO_MODE_RESAMPLE);
    }

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
            ByteBuffer audioData, int bufferIndex, int sizeInBytes, long presentationTimeUs) {
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

    private final void postReturnByteBuffer(final AudioBuffer audioBuffer) {
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
