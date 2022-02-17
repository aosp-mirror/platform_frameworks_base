/*
 * Copyright (C) 2012 The Android Open Source Project
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

package androidx.media.filterfw.decoder;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.RenderTarget;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

@TargetApi(16)
public class MediaDecoder implements
        Runnable,
        TrackDecoder.Listener {

    public interface Listener {
        /**
         * Notifies a listener when a decoded video frame is available. The listener should use
         * {@link MediaDecoder#grabVideoFrame(FrameImage2D, int)} to grab the video data for this
         * frame.
         */
        void onVideoFrameAvailable();

        /**
         * Notifies a listener when one or more audio samples are available. The listener should use
         * {@link MediaDecoder#grabAudioSamples(FrameValue)} to grab the audio samples.
         */
        void onAudioSamplesAvailable();

        /**
         * Notifies a listener that decoding has started. This method is called on the decoder
         * thread.
         */
        void onDecodingStarted();

        /**
         * Notifies a listener that decoding has stopped. This method is called on the decoder
         * thread.
         */
        void onDecodingStopped();

        /**
         * Notifies a listener that an error occurred. If an error occurs, {@link MediaDecoder} is
         * stopped and no more events are reported to this {@link Listener}'s callbacks.
         * This method is called on the decoder thread.
         */
        void onError(Exception e);
    }

    public static final int ROTATE_NONE = 0;
    public static final int ROTATE_90_RIGHT = 90;
    public static final int ROTATE_180 = 180;
    public static final int ROTATE_90_LEFT = 270;

    private static final String LOG_TAG = "MediaDecoder";
    private static final boolean DEBUG = false;

    private static final int MAX_EVENTS = 32;
    private static final int EVENT_START = 0;
    private static final int EVENT_STOP = 1;
    private static final int EVENT_EOF = 2;

    private final Listener mListener;
    private final Uri mUri;
    private final Context mContext;

    private final LinkedBlockingQueue<Integer> mEventQueue;

    private final Thread mDecoderThread;

    private MediaExtractor mMediaExtractor;

    private RenderTarget mRenderTarget;

    private int mDefaultRotation;
    private int mVideoTrackIndex;
    private int mAudioTrackIndex;

    private VideoTrackDecoder mVideoTrackDecoder;
    private AudioTrackDecoder mAudioTrackDecoder;

    private boolean mStarted;

    private long mStartMicros;

    private boolean mOpenGLEnabled = true;

    private boolean mSignaledEndOfInput;
    private boolean mSeenEndOfAudioOutput;
    private boolean mSeenEndOfVideoOutput;

    public MediaDecoder(Context context, Uri uri, Listener listener) {
        this(context, uri, 0, listener);
    }

    public MediaDecoder(Context context, Uri uri, long startMicros, Listener listener) {
        if (context == null) {
            throw new NullPointerException("context cannot be null");
        }
        mContext = context;

        if (uri == null) {
            throw new NullPointerException("uri cannot be null");
        }
        mUri = uri;

        if (startMicros < 0) {
            throw new IllegalArgumentException("startMicros cannot be negative");
        }
        mStartMicros = startMicros;

        if (listener == null) {
            throw new NullPointerException("listener cannot be null");
        }
        mListener = listener;

        mEventQueue = new LinkedBlockingQueue<Integer>(MAX_EVENTS);
        mDecoderThread = new Thread(this);
    }

    /**
     * Set whether decoder may use OpenGL for decoding.
     *
     * This must be called before {@link #start()}.
     *
     * @param enabled flag whether to enable OpenGL decoding (default is true).
     */
    public void setOpenGLEnabled(boolean enabled) {
        // If event-queue already has events, we have started already.
        if (mEventQueue.isEmpty()) {
            mOpenGLEnabled = enabled;
        } else {
            throw new IllegalStateException(
                    "Must call setOpenGLEnabled() before calling start()!");
        }
    }

    /**
     * Returns whether OpenGL is enabled for decoding.
     *
     * @return whether OpenGL is enabled for decoding.
     */
    public boolean isOpenGLEnabled() {
        return mOpenGLEnabled;
    }

    public void start() {
        mEventQueue.offer(EVENT_START);
        mDecoderThread.start();
    }

    public void stop() {
        stop(true);
    }

    private void stop(boolean manual) {
        if (manual) {
            mEventQueue.offer(EVENT_STOP);
            mDecoderThread.interrupt();
        } else {
            mEventQueue.offer(EVENT_EOF);
        }
    }

    @Override
    public void run() {
        Integer event;
        try {
            while (true) {
                event = mEventQueue.poll();
                boolean shouldStop = false;
                if (event != null) {
                    switch (event) {
                        case EVENT_START:
                            onStart();
                            break;
                        case EVENT_EOF:
                            if (mVideoTrackDecoder != null) {
                                mVideoTrackDecoder.waitForFrameGrab();
                            }
                            // once the last frame has been grabbed, fall through and stop
                        case EVENT_STOP:
                            onStop(true);
                            shouldStop = true;
                            break;
                    }
                } else if (mStarted) {
                    decode();
                }
                if (shouldStop) {
                    break;
                }

            }
        } catch (Exception e) {
            mListener.onError(e);
            onStop(false);
        }
    }

    private void onStart() throws Exception {
        if (mOpenGLEnabled) {
            getRenderTarget().focus();
        }

        mMediaExtractor = new MediaExtractor();
        mMediaExtractor.setDataSource(mContext, mUri, null);

        mVideoTrackIndex = -1;
        mAudioTrackIndex = -1;

        for (int i = 0; i < mMediaExtractor.getTrackCount(); i++) {
            MediaFormat format = mMediaExtractor.getTrackFormat(i);
            if (DEBUG) {
                Log.i(LOG_TAG, "Uri " + mUri + ", track " + i + ": " + format);
            }
            if (DecoderUtil.isVideoFormat(format) && mVideoTrackIndex == -1) {
                mVideoTrackIndex = i;
            } else if (DecoderUtil.isAudioFormat(format) && mAudioTrackIndex == -1) {
                mAudioTrackIndex = i;
            }
        }

        if (mVideoTrackIndex == -1 && mAudioTrackIndex == -1) {
            throw new IllegalArgumentException(
                    "Couldn't find a video or audio track in the provided file");
        }

        if (mVideoTrackIndex != -1) {
            MediaFormat videoFormat = mMediaExtractor.getTrackFormat(mVideoTrackIndex);
            mVideoTrackDecoder = mOpenGLEnabled
                    ? new GpuVideoTrackDecoder(mVideoTrackIndex, videoFormat, this)
                    : new CpuVideoTrackDecoder(mVideoTrackIndex, videoFormat, this);
            mVideoTrackDecoder.init();
            mMediaExtractor.selectTrack(mVideoTrackIndex);
            if (Build.VERSION.SDK_INT >= 17) {
                retrieveDefaultRotation();
            }
        }

        if (mAudioTrackIndex != -1) {
            MediaFormat audioFormat = mMediaExtractor.getTrackFormat(mAudioTrackIndex);
            mAudioTrackDecoder = new AudioTrackDecoder(mAudioTrackIndex, audioFormat, this);
            mAudioTrackDecoder.init();
            mMediaExtractor.selectTrack(mAudioTrackIndex);
        }

        if (mStartMicros > 0) {
            mMediaExtractor.seekTo(mStartMicros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        mStarted = true;
        mListener.onDecodingStarted();
    }

    @TargetApi(17)
    private void retrieveDefaultRotation() throws IOException {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        metadataRetriever.setDataSource(mContext, mUri);
        String rotationString = metadataRetriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        mDefaultRotation = rotationString == null ? 0 : Integer.parseInt(rotationString);
        metadataRetriever.release();
    }

    private void onStop(boolean notifyListener) {
        mMediaExtractor.release();
        mMediaExtractor = null;

        if (mVideoTrackDecoder != null) {
            mVideoTrackDecoder.release();
            mVideoTrackDecoder = null;
        }

        if (mAudioTrackDecoder != null) {
            mAudioTrackDecoder.release();
            mAudioTrackDecoder = null;
        }

        if (mOpenGLEnabled) {
            if (mRenderTarget != null) {
                getRenderTarget().release();
            }
            RenderTarget.focusNone();
        }

        mVideoTrackIndex = -1;
        mAudioTrackIndex = -1;

        mEventQueue.clear();
        mStarted = false;
        if (notifyListener) {
            mListener.onDecodingStopped();
        }
    }

    private void decode() {
        int sampleTrackIndex = mMediaExtractor.getSampleTrackIndex();
        if (sampleTrackIndex >= 0) {
            if (sampleTrackIndex == mVideoTrackIndex) {
                mVideoTrackDecoder.feedInput(mMediaExtractor);
            } else if (sampleTrackIndex == mAudioTrackIndex) {
                mAudioTrackDecoder.feedInput(mMediaExtractor);
            }
        } else if (!mSignaledEndOfInput) {
            if (mVideoTrackDecoder != null) {
                mVideoTrackDecoder.signalEndOfInput();
            }
            if (mAudioTrackDecoder != null) {
                mAudioTrackDecoder.signalEndOfInput();
            }
            mSignaledEndOfInput = true;
        }

        if (mVideoTrackDecoder != null) {
            mVideoTrackDecoder.drainOutputBuffer();
        }
        if (mAudioTrackDecoder != null) {
            mAudioTrackDecoder.drainOutputBuffer();
        }
    }

    /**
     * Fills the argument frame with the video data, using the rotation hint obtained from the
     * file's metadata, if any.
     *
     * @see #grabVideoFrame(FrameImage2D, int)
     */
    public void grabVideoFrame(FrameImage2D outputVideoFrame) {
        grabVideoFrame(outputVideoFrame, mDefaultRotation);
    }

    /**
     * Fills the argument frame with the video data, the frame will be returned with the given
     * rotation applied.
     *
     * @param outputVideoFrame the output video frame.
     * @param videoRotation the rotation angle that is applied to the raw decoded frame.
     *   Value is one of {ROTATE_NONE, ROTATE_90_RIGHT, ROTATE_180, ROTATE_90_LEFT}.
     */
    public void grabVideoFrame(FrameImage2D outputVideoFrame, int videoRotation) {
        if (mVideoTrackDecoder != null && outputVideoFrame != null) {
            mVideoTrackDecoder.grabFrame(outputVideoFrame, videoRotation);
        }
    }

    /**
     * Fills the argument frame with the audio data.
     *
     * @param outputAudioFrame the output audio frame.
     */
    public void grabAudioSamples(FrameValue outputAudioFrame) {
        if (mAudioTrackDecoder != null) {
            if (outputAudioFrame != null) {
                mAudioTrackDecoder.grabSample(outputAudioFrame);
            } else {
                mAudioTrackDecoder.clearBuffer();
            }
        }
    }

    /**
     * Gets the duration, in nanoseconds.
     */
    public long getDuration() {
        if (!mStarted) {
            throw new IllegalStateException("MediaDecoder has not been started");
        }

        MediaFormat mediaFormat = mMediaExtractor.getTrackFormat(
                mVideoTrackIndex != -1 ? mVideoTrackIndex : mAudioTrackIndex);
        return mediaFormat.getLong(MediaFormat.KEY_DURATION) * 1000;
    }

    private RenderTarget getRenderTarget() {
        if (mRenderTarget == null) {
            mRenderTarget = RenderTarget.newTarget(1, 1);
        }
        return mRenderTarget;
    }

    @Override
    public void onDecodedOutputAvailable(TrackDecoder decoder) {
        if (decoder == mVideoTrackDecoder) {
            mListener.onVideoFrameAvailable();
        } else if (decoder == mAudioTrackDecoder) {
            mListener.onAudioSamplesAvailable();
        }
    }

    @Override
    public void onEndOfStream(TrackDecoder decoder) {
        if (decoder == mAudioTrackDecoder) {
            mSeenEndOfAudioOutput = true;
        } else if (decoder == mVideoTrackDecoder) {
            mSeenEndOfVideoOutput = true;
        }

        if ((mAudioTrackDecoder == null || mSeenEndOfAudioOutput)
                && (mVideoTrackDecoder == null || mSeenEndOfVideoOutput)) {
            stop(false);
        }
    }

}
