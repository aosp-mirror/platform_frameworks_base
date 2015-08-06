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
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.LinkedList;
import java.util.List;

/**
 * MediaSync class can be used to synchronously playback audio and video streams.
 * It can be used to play audio-only or video-only stream, too.
 *
 * <p>MediaSync is generally used like this:
 * <pre>
 * MediaSync sync = new MediaSync();
 * sync.setSurface(surface);
 * Surface inputSurface = sync.createInputSurface();
 * ...
 * // MediaCodec videoDecoder = ...;
 * videoDecoder.configure(format, inputSurface, ...);
 * ...
 * sync.setAudioTrack(audioTrack);
 * sync.setCallback(new MediaSync.Callback() {
 *     {@literal @Override}
 *     public void onAudioBufferConsumed(MediaSync sync, ByteBuffer audioBuffer, int bufferId) {
 *         ...
 *     }
 * }, null);
 * // This needs to be done since sync is paused on creation.
 * sync.setPlaybackParams(new PlaybackParams().setSpeed(1.f));
 *
 * for (;;) {
 *   ...
 *   // send video frames to surface for rendering, e.g., call
 *   // videoDecoder.releaseOutputBuffer(videoOutputBufferIx, videoPresentationTimeNs);
 *   // More details are available as below.
 *   ...
 *   sync.queueAudio(audioByteBuffer, bufferId, audioPresentationTimeUs); // non-blocking.
 *   // The audioByteBuffer and bufferId will be returned via callback.
 *   // More details are available as below.
 *   ...
 *     ...
 * }
 * sync.setPlaybackParams(new PlaybackParams().setSpeed(0.f));
 * sync.release();
 * sync = null;
 *
 * // The following code snippet illustrates how video/audio raw frames are created by
 * // MediaCodec's, how they are fed to MediaSync and how they are returned by MediaSync.
 * // This is the callback from MediaCodec.
 * onOutputBufferAvailable(MediaCodec codec, int bufferId, BufferInfo info) {
 *     // ...
 *     if (codec == videoDecoder) {
 *         // surface timestamp must contain media presentation time in nanoseconds.
 *         codec.releaseOutputBuffer(bufferId, 1000 * info.presentationTime);
 *     } else {
 *         ByteBuffer audioByteBuffer = codec.getOutputBuffer(bufferId);
 *         sync.queueAudio(audioByteBuffer, bufferId, info.presentationTime);
 *     }
 *     // ...
 * }
 *
 * // This is the callback from MediaSync.
 * onAudioBufferConsumed(MediaSync sync, ByteBuffer buffer, int bufferId) {
 *     // ...
 *     audioDecoder.releaseBuffer(bufferId, false);
 *     // ...
 * }
 *
 * </pre>
 *
 * The client needs to configure corresponding sink by setting the Surface and/or AudioTrack
 * based on the stream type it will play.
 * <p>
 * For video, the client needs to call {@link #createInputSurface} to obtain a surface on
 * which it will render video frames.
 * <p>
 * For audio, the client needs to set up audio track correctly, e.g., using {@link
 * AudioTrack#MODE_STREAM}. The audio buffers are sent to MediaSync directly via {@link
 * #queueAudio}, and are returned to the client via {@link Callback#onAudioBufferConsumed}
 * asynchronously. The client should not modify an audio buffer till it's returned.
 * <p>
 * The client can optionally pre-fill audio/video buffers by setting playback rate to 0.0,
 * and then feed audio/video buffers to corresponding components. This can reduce possible
 * initial underrun.
 * <p>
 */
public final class MediaSync {
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
         * @param bufferId The ID associated with audioBuffer as passed into
         *     {@link MediaSync#queueAudio}.
         */
        public abstract void onAudioBufferConsumed(
                @NonNull MediaSync sync, @NonNull ByteBuffer audioBuffer, int bufferId);
    }

    /** Audio track failed.
     * @see android.media.MediaSync.OnErrorListener
     */
    public static final int MEDIASYNC_ERROR_AUDIOTRACK_FAIL = 1;

    /** The surface failed to handle video buffers.
     * @see android.media.MediaSync.OnErrorListener
     */
    public static final int MEDIASYNC_ERROR_SURFACE_FAIL = 2;

    /**
     * Interface definition of a callback to be invoked when there
     * has been an error during an asynchronous operation (other errors
     * will throw exceptions at method call time).
     */
    public interface OnErrorListener {
        /**
         * Called to indicate an error.
         *
         * @param sync The MediaSync the error pertains to
         * @param what The type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIASYNC_ERROR_AUDIOTRACK_FAIL}
         * <li>{@link #MEDIASYNC_ERROR_SURFACE_FAIL}
         * </ul>
         * @param extra an extra code, specific to the error. Typically
         * implementation dependent.
         */
        void onError(@NonNull MediaSync sync, int what, int extra);
    }

    private static final String TAG = "MediaSync";

    private static final int EVENT_CALLBACK = 1;
    private static final int EVENT_SET_CALLBACK = 2;

    private static final int CB_RETURN_AUDIO_BUFFER = 1;

    private static class AudioBuffer {
        public ByteBuffer mByteBuffer;
        public int mBufferIndex;
        long mPresentationTimeUs;

        public AudioBuffer(@NonNull ByteBuffer byteBuffer, int bufferId,
                           long presentationTimeUs) {
            mByteBuffer = byteBuffer;
            mBufferIndex = bufferId;
            mPresentationTimeUs = presentationTimeUs;
        }
    }

    private final Object mCallbackLock = new Object();
    private Handler mCallbackHandler = null;
    private MediaSync.Callback mCallback = null;

    private final Object mOnErrorListenerLock = new Object();
    private Handler mOnErrorListenerHandler = null;
    private MediaSync.OnErrorListener mOnErrorListener = null;

    private Thread mAudioThread = null;
    // Created on mAudioThread when mAudioThread is started. When used on user thread, they should
    // be guarded by checking mAudioThread.
    private Handler mAudioHandler = null;
    private Looper mAudioLooper = null;

    private final Object mAudioLock = new Object();
    private AudioTrack mAudioTrack = null;
    private List<AudioBuffer> mAudioBuffers = new LinkedList<AudioBuffer>();
    // this is only used for paused/running decisions, so it is not affected by clock drift
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
     * Sets an asynchronous callback for error events.
     * <p>
     * This method can be called multiple times to update a previously set listener. If the
     * handler is changed, undelivered notifications scheduled for the old handler may be dropped.
     * <p>
     * <b>Do not call this inside callback.</b>
     *
     * @param listener The callback that will run. Use {@code null} to stop receiving callbacks.
     * @param handler The Handler that will run the callback. Use {@code null} to use MediaSync's
     *     internal handler if it exists.
     */
    public void setOnErrorListener(@Nullable /* MediaSync. */ OnErrorListener listener,
            @Nullable Handler handler) {
        synchronized(mOnErrorListenerLock) {
            if (handler != null) {
                mOnErrorListenerHandler = handler;
            } else {
                Looper looper;
                if ((looper = Looper.myLooper()) == null) {
                    looper = Looper.getMainLooper();
                }
                if (looper == null) {
                    mOnErrorListenerHandler = null;
                } else {
                    mOnErrorListenerHandler = new Handler(looper);
                }
            }

            mOnErrorListener = listener;
        }
    }

    /**
     * Sets the output surface for MediaSync.
     * <p>
     * Currently, this is only supported in the Initialized state.
     *
     * @param surface Specify a surface on which to render the video data.
     * @throws IllegalArgumentException if the surface has been released, is invalid,
     *     or can not be connected.
     * @throws IllegalStateException if setting the surface is not supported, e.g.
     *     not in the Initialized state, or another surface has already been set.
     */
    public void setSurface(@Nullable Surface surface) {
        native_setSurface(surface);
    }

    private native final void native_setSurface(@Nullable Surface surface);

    /**
     * Sets the audio track for MediaSync.
     * <p>
     * Currently, this is only supported in the Initialized state.
     *
     * @param audioTrack Specify an AudioTrack through which to render the audio data.
     * @throws IllegalArgumentException if the audioTrack has been released, or is invalid.
     * @throws IllegalStateException if setting the audio track is not supported, e.g.
     *     not in the Initialized state, or another audio track has already been set.
     */
    public void setAudioTrack(@Nullable AudioTrack audioTrack) {
        native_setAudioTrack(audioTrack);
        mAudioTrack = audioTrack;
        if (audioTrack != null && mAudioThread == null) {
            createAudioThread();
        }
    }

    private native final void native_setAudioTrack(@Nullable AudioTrack audioTrack);

    /**
     * Requests a Surface to use as the input. This may only be called after
     * {@link #setSurface}.
     * <p>
     * The application is responsible for calling release() on the Surface when
     * done.
     * @throws IllegalStateException if not set, or another input surface has
     *     already been created.
     */
    @NonNull
    public native final Surface createInputSurface();

    /**
     * Sets playback rate using {@link PlaybackParams}.
     * <p>
     * When using MediaSync with {@link AudioTrack}, set playback params using this
     * call instead of calling it directly on the track, so that the sync is aware of
     * the params change.
     * <p>
     * This call also works if there is no audio track.
     *
     * @param params the playback params to use. {@link PlaybackParams#getSpeed
     *     Speed} is the ratio between desired playback rate and normal one. 1.0 means
     *     normal playback speed. 0.0 means pause. Value larger than 1.0 means faster playback,
     *     while value between 0.0 and 1.0 for slower playback. <b>Note:</b> the normal rate
     *     does not change as a result of this call. To restore the original rate at any time,
     *     use speed of 1.0.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     * @throws IllegalArgumentException if the params are not supported.
     */
    public void setPlaybackParams(@NonNull PlaybackParams params) {
        synchronized(mAudioLock) {
            mPlaybackRate = native_setPlaybackParams(params);;
        }
        if (mPlaybackRate != 0.0 && mAudioThread != null) {
            postRenderAudio(0);
        }
    }

    /**
     * Gets the playback rate using {@link PlaybackParams}.
     *
     * @return the playback rate being used.
     *
     * @throws IllegalStateException if the internal sync engine or the audio track has not
     *     been initialized.
     */
    @NonNull
    public native PlaybackParams getPlaybackParams();

    private native float native_setPlaybackParams(@NonNull PlaybackParams params);

    /**
     * Sets A/V sync mode.
     *
     * @param params the A/V sync params to apply
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     * @throws IllegalArgumentException if params are not supported.
     */
    public void setSyncParams(@NonNull SyncParams params) {
        synchronized(mAudioLock) {
            mPlaybackRate = native_setSyncParams(params);;
        }
        if (mPlaybackRate != 0.0 && mAudioThread != null) {
            postRenderAudio(0);
        }
    }

    private native float native_setSyncParams(@NonNull SyncParams params);

    /**
     * Gets the A/V sync mode.
     *
     * @return the A/V sync params
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    @NonNull
    public native SyncParams getSyncParams();

    /**
     * Flushes all buffers from the sync object.
     * <p>
     * All pending unprocessed audio and video buffers are discarded. If an audio track was
     * configured, it is flushed and stopped. If a video output surface was configured, the
     * last frame queued to it is left on the frame. Queue a blank video frame to clear the
     * surface,
     * <p>
     * No callbacks are received for the flushed buffers.
     *
     * @throws IllegalStateException if the internal player engine has not been
     * initialized.
     */
    public void flush() {
        synchronized(mAudioLock) {
            mAudioBuffers.clear();
            mCallbackHandler.removeCallbacksAndMessages(null);
        }
        if (mAudioTrack != null) {
            mAudioTrack.pause();
            mAudioTrack.flush();
            // Call stop() to signal to the AudioSink to completely fill the
            // internal buffer before resuming playback.
            mAudioTrack.stop();
        }
        native_flush();
    }

    private native final void native_flush();

    /**
     * Get current playback position.
     * <p>
     * The MediaTimestamp represents how the media time correlates to the system time in
     * a linear fashion using an anchor and a clock rate. During regular playback, the media
     * time moves fairly constantly (though the anchor frame may be rebased to a current
     * system time, the linear correlation stays steady). Therefore, this method does not
     * need to be called often.
     * <p>
     * To help users get current playback position, this method always anchors the timestamp
     * to the current {@link System#nanoTime system time}, so
     * {@link MediaTimestamp#getAnchorMediaTimeUs} can be used as current playback position.
     *
     * @return a MediaTimestamp object if a timestamp is available, or {@code null} if no timestamp
     *         is available, e.g. because the media player has not been initialized.
     *
     * @see MediaTimestamp
     */
    @Nullable
    public MediaTimestamp getTimestamp()
    {
        try {
            // TODO: create the timestamp in native
            MediaTimestamp timestamp = new MediaTimestamp();
            if (native_getTimestamp(timestamp)) {
                return timestamp;
            } else {
                return null;
            }
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private native final boolean native_getTimestamp(@NonNull MediaTimestamp timestamp);

    /**
     * Queues the audio data asynchronously for playback (AudioTrack must be in streaming mode).
     * If the audio track was flushed as a result of {@link #flush}, it will be restarted.
     * @param audioData the buffer that holds the data to play. This buffer will be returned
     *     to the client via registered callback.
     * @param bufferId an integer used to identify audioData. It will be returned to
     *     the client along with audioData. This helps applications to keep track of audioData,
     *     e.g., it can be used to store the output buffer index used by the audio codec.
     * @param presentationTimeUs the presentation timestamp in microseconds for the first frame
     *     in the buffer.
     * @throws IllegalStateException if audio track is not set or internal configureation
     *     has not been done correctly.
     */
    public void queueAudio(
            @NonNull ByteBuffer audioData, int bufferId, long presentationTimeUs) {
        if (mAudioTrack == null || mAudioThread == null) {
            throw new IllegalStateException(
                    "AudioTrack is NOT set or audio thread is not created");
        }

        synchronized(mAudioLock) {
            mAudioBuffers.add(new AudioBuffer(audioData, bufferId, presentationTimeUs));
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
                    int size = audioBuffer.mByteBuffer.remaining();
                    // restart audio track after flush
                    if (size > 0 && mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            mAudioTrack.play();
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "could not start audio track");
                        }
                    }
                    int sizeWritten = mAudioTrack.write(
                            audioBuffer.mByteBuffer,
                            size,
                            AudioTrack.WRITE_NON_BLOCKING);
                    if (sizeWritten > 0) {
                        if (audioBuffer.mPresentationTimeUs != -1) {
                            native_updateQueuedAudioData(
                                    size, audioBuffer.mPresentationTimeUs);
                            audioBuffer.mPresentationTimeUs = -1;
                        }

                        if (sizeWritten == size) {
                            postReturnByteBuffer(audioBuffer);
                            mAudioBuffers.remove(0);
                            if (!mAudioBuffers.isEmpty()) {
                                postRenderAudio(0);
                            }
                            return;
                        }
                    }
                    long pendingTimeMs = TimeUnit.MICROSECONDS.toMillis(
                            native_getPlayTimeForPendingAudioFrames());
                    postRenderAudio(pendingTimeMs / 2);
                }
            }
        }, delayMillis);
    }

    private native final void native_updateQueuedAudioData(
            int sizeInBytes, long presentationTimeUs);

    private native final long native_getPlayTimeForPendingAudioFrames();

    private final void postReturnByteBuffer(@NonNull final AudioBuffer audioBuffer) {
        synchronized(mCallbackLock) {
            if (mCallbackHandler != null) {
                final MediaSync sync = this;
                mCallbackHandler.post(new Runnable() {
                    public void run() {
                        Callback callback;
                        synchronized(mCallbackLock) {
                            callback = mCallback;
                            if (mCallbackHandler == null
                                    || mCallbackHandler.getLooper().getThread()
                                            != Thread.currentThread()) {
                                // callback handler has been changed.
                                return;
                            }
                        }
                        if (callback != null) {
                            callback.onAudioBufferConsumed(sync, audioBuffer.mByteBuffer,
                                    audioBuffer.mBufferIndex);
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
