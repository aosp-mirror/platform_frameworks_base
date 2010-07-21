/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.media.CamcorderProfile;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.lang.ref.WeakReference;

/**
 * Used to record audio and video. The recording control is based on a
 * simple state machine (see below).
 *
 * <p><img src="{@docRoot}images/mediarecorder_state_diagram.gif" border="0" />
 * </p>
 *
 * <p>A common case of using MediaRecorder to record audio works as follows:
 *
 * <pre>MediaRecorder recorder = new MediaRecorder();
 * recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
 * recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
 * recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
 * recorder.setOutputFile(PATH_NAME);
 * recorder.prepare();
 * recorder.start();   // Recording is now started
 * ...
 * recorder.stop();
 * recorder.reset();   // You can reuse the object by going back to setAudioSource() step
 * recorder.release(); // Now the object cannot be reused
 * </pre>
 *
 * <p>See the <a href="{@docRoot}guide/topics/media/index.html">Audio and Video</a>
 * documentation for additional help with using MediaRecorder.
 * <p>Note: Currently, MediaRecorder does not work on the emulator.
 */
public class MediaRecorder
{
    static {
        System.loadLibrary("media_jni");
        native_init();
    }
    private final static String TAG = "MediaRecorder";

    // The two fields below are accessed by native methods
    @SuppressWarnings("unused")
    private int mNativeContext;

    @SuppressWarnings("unused")
    private Surface mSurface;

    private String mPath;
    private FileDescriptor mFd;
    private EventHandler mEventHandler;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener mOnInfoListener;

    /**
     * Default constructor.
     */
    public MediaRecorder() {

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaRecorder>(this));
    }

    /**
     * Sets a Camera to use for recording. Use this function to switch
     * quickly between preview and capture mode without a teardown of
     * the camera object. Must call before prepare().
     *
     * @param c the Camera to use for recording
     */
    public native void setCamera(Camera c);

    /**
     * Sets a Surface to show a preview of recorded media (video). Calls this
     * before prepare() to make sure that the desirable preview display is
     * set.
     *
     * @param sv the Surface to use for the preview
     */
    public void setPreviewDisplay(Surface sv) {
        mSurface = sv;
    }

    /**
     * Defines the audio source. These constants are used with
     * {@link MediaRecorder#setAudioSource(int)}.
     */
    public final class AudioSource {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private AudioSource() {}
        public static final int DEFAULT = 0;
        /** Microphone audio source */
        public static final int MIC = 1;

        /** Voice call uplink (Tx) audio source */
        public static final int VOICE_UPLINK = 2;

        /** Voice call downlink (Rx) audio source */
        public static final int VOICE_DOWNLINK = 3;

        /** Voice call uplink + downlink audio source */
        public static final int VOICE_CALL = 4;

        /** Microphone audio source with same orientation as camera if available, the main
         *  device microphone otherwise */
        public static final int CAMCORDER = 5;

        /** Microphone audio source tuned for voice recognition if available, behaves like
         *  {@link #DEFAULT} otherwise. */
        public static final int VOICE_RECOGNITION = 6;
    }

    /**
     * Defines the video source. These constants are used with
     * {@link MediaRecorder#setVideoSource(int)}.
     */
    public final class VideoSource {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private VideoSource() {}
        public static final int DEFAULT = 0;
        /** Camera video source */
        public static final int CAMERA = 1;
    }

    /**
     * Defines the output format. These constants are used with
     * {@link MediaRecorder#setOutputFormat(int)}.
     */
    public final class OutputFormat {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private OutputFormat() {}
        public static final int DEFAULT = 0;
        /** 3GPP media file format*/
        public static final int THREE_GPP = 1;
        /** MPEG4 media file format*/
        public static final int MPEG_4 = 2;

        /** The following formats are audio only .aac or .amr formats **/
        /** @deprecated  Deprecated in favor of AMR_NB */
        /** TODO: change link when AMR_NB is exposed. Deprecated in favor of MediaRecorder.OutputFormat.AMR_NB */
        public static final int RAW_AMR = 3;
        /** @hide AMR NB file format */
        public static final int AMR_NB = 3;
        /** @hide AMR WB file format */
        public static final int AMR_WB = 4;
        /** @hide AAC ADIF file format */
        public static final int AAC_ADIF = 5;
        /** @hide AAC ADTS file format */
        public static final int AAC_ADTS = 6;
    };

    /**
     * Defines the audio encoding. These constants are used with
     * {@link MediaRecorder#setAudioEncoder(int)}.
     */
    public final class AudioEncoder {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private AudioEncoder() {}
        public static final int DEFAULT = 0;
        /** AMR (Narrowband) audio codec */
        public static final int AMR_NB = 1;
        /** @hide AMR (Wideband) audio codec */
        public static final int AMR_WB = 2;
        /** @hide AAC audio codec */
        public static final int AAC = 3;
        /** @hide enhanced AAC audio codec */
        public static final int AAC_PLUS = 4;
        /** @hide enhanced AAC plus audio codec */
        public static final int EAAC_PLUS = 5;
    }

    /**
     * Defines the video encoding. These constants are used with
     * {@link MediaRecorder#setVideoEncoder(int)}.
     */
    public final class VideoEncoder {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private VideoEncoder() {}
        public static final int DEFAULT = 0;
        public static final int H263 = 1;
        public static final int H264 = 2;
        public static final int MPEG_4_SP = 3;
    }

    /**
     * Sets the audio source to be used for recording. If this method is not
     * called, the output file will not contain an audio track. The source needs
     * to be specified before setting recording-parameters or encoders. Call
     * this only before setOutputFormat().
     *
     * @param audio_source the audio source to use
     * @throws IllegalStateException if it is called after setOutputFormat()
     * @see android.media.MediaRecorder.AudioSource
     */
    public native void setAudioSource(int audio_source)
            throws IllegalStateException;

    /**
     * Gets the maximum value for audio sources.
     * @see android.media.MediaRecorder.AudioSource
     */
    public static final int getAudioSourceMax() { return AudioSource.VOICE_RECOGNITION; }

    /**
     * Sets the video source to be used for recording. If this method is not
     * called, the output file will not contain an video track. The source needs
     * to be specified before setting recording-parameters or encoders. Call
     * this only before setOutputFormat().
     *
     * @param video_source the video source to use
     * @throws IllegalStateException if it is called after setOutputFormat()
     * @see android.media.MediaRecorder.VideoSource
     */
    public native void setVideoSource(int video_source)
            throws IllegalStateException;

    /**
     * Uses the settings from a CamcorderProfile object for recording. This method should
     * be called after the video AND audio sources are set, and before setOutputFile().
     *
     * @param profile the CamcorderProfile to use
     * @see android.media.CamcorderProfile
     */
    public void setProfile(CamcorderProfile profile) {
        setOutputFormat(profile.fileFormat);
        setVideoFrameRate(profile.videoFrameRate);
        setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        setVideoEncodingBitRate(profile.videoBitRate);
        setAudioEncodingBitRate(profile.audioBitRate);
        setAudioChannels(profile.audioChannels);
        setAudioSamplingRate(profile.audioSampleRate);
        setVideoEncoder(profile.videoCodec);
        setAudioEncoder(profile.audioCodec);
    }

    /**
     * Enables/Disables time lapse capture and sets its parameters. This method should
     * be called after setProfile().
     *
     * @param enableTimeLapse Pass true to enable time lapse capture, false to disable it.
     * @param timeBetweenTimeLapseFrameCaptureMs time between two captures of time lapse frames.
     * @param encoderLevel the video encoder level.
     */
    public void setTimeLapseParameters(boolean enableTimeLapse,
            int timeBetweenTimeLapseFrameCaptureMs, int encoderLevel) {
        setParameter(String.format("time-lapse-enable=%d",
                    (enableTimeLapse) ? 1 : 0));
        setParameter(String.format("time-between-time-lapse-frame-capture=%d",
                    timeBetweenTimeLapseFrameCaptureMs));
        setVideoEncoderLevel(encoderLevel);
    }

    /**
     * Sets the format of the output file produced during recording. Call this
     * after setAudioSource()/setVideoSource() but before prepare().
     *
     * <p>It is recommended to always use 3GP format when using the H.263
     * video encoder and AMR audio encoder. Using an MPEG-4 container format
     * may confuse some desktop players.</p>
     *
     * @param output_format the output format to use. The output format
     * needs to be specified before setting recording-parameters or encoders.
     * @throws IllegalStateException if it is called after prepare() or before
     * setAudioSource()/setVideoSource().
     * @see android.media.MediaRecorder.OutputFormat
     */
    public native void setOutputFormat(int output_format)
            throws IllegalStateException;

    /**
     * Sets the width and height of the video to be captured.  Must be called
     * after setVideoSource(). Call this after setOutFormat() but before
     * prepare().
     *
     * @param width the width of the video to be captured
     * @param height the height of the video to be captured
     * @throws IllegalStateException if it is called after
     * prepare() or before setOutputFormat()
     */
    public native void setVideoSize(int width, int height)
            throws IllegalStateException;

    /**
     * Sets the frame rate of the video to be captured.  Must be called
     * after setVideoSource(). Call this after setOutFormat() but before
     * prepare().
     *
     * @param rate the number of frames per second of video to capture
     * @throws IllegalStateException if it is called after
     * prepare() or before setOutputFormat().
     *
     * NOTE: On some devices that have auto-frame rate, this sets the
     * maximum frame rate, not a constant frame rate. Actual frame rate
     * will vary according to lighting conditions.
     */
    public native void setVideoFrameRate(int rate) throws IllegalStateException;

    /**
     * Sets the maximum duration (in ms) of the recording session.
     * Call this after setOutFormat() but before prepare().
     * After recording reaches the specified duration, a notification
     * will be sent to the {@link android.media.MediaRecorder.OnInfoListener}
     * with a "what" code of {@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
     * and recording will be stopped. Stopping happens asynchronously, there
     * is no guarantee that the recorder will have stopped by the time the
     * listener is notified.
     *
     * @param max_duration_ms the maximum duration in ms (if zero or negative, disables the duration limit)
     *
     */
    public native void setMaxDuration(int max_duration_ms) throws IllegalArgumentException;

    /**
     * Sets the maximum filesize (in bytes) of the recording session.
     * Call this after setOutFormat() but before prepare().
     * After recording reaches the specified filesize, a notification
     * will be sent to the {@link android.media.MediaRecorder.OnInfoListener}
     * with a "what" code of {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}
     * and recording will be stopped. Stopping happens asynchronously, there
     * is no guarantee that the recorder will have stopped by the time the
     * listener is notified.
     *
     * @param max_filesize_bytes the maximum filesize in bytes (if zero or negative, disables the limit)
     *
     */
    public native void setMaxFileSize(long max_filesize_bytes) throws IllegalArgumentException;

    /**
     * Sets the audio encoder to be used for recording. If this method is not
     * called, the output file will not contain an audio track. Call this after
     * setOutputFormat() but before prepare().
     *
     * @param audio_encoder the audio encoder to use.
     * @throws IllegalStateException if it is called before
     * setOutputFormat() or after prepare().
     * @see android.media.MediaRecorder.AudioEncoder
     */
    public native void setAudioEncoder(int audio_encoder)
            throws IllegalStateException;

    /**
     * Sets the video encoder to be used for recording. If this method is not
     * called, the output file will not contain an video track. Call this after
     * setOutputFormat() and before prepare().
     *
     * @param video_encoder the video encoder to use.
     * @throws IllegalStateException if it is called before
     * setOutputFormat() or after prepare()
     * @see android.media.MediaRecorder.VideoEncoder
     */
    public native void setVideoEncoder(int video_encoder)
            throws IllegalStateException;

    /**
     * Sets the audio sampling rate for recording. Call this method before prepare().
     * Prepare() may perform additional checks on the parameter to make sure whether
     * the specified audio sampling rate is applicable. The sampling rate really depends
     * on the format for the audio recording, as well as the capabilities of the platform.
     * For instance, the sampling rate supported by AAC audio coding standard ranges
     * from 8 to 96 kHz. Please consult with the related audio coding standard for the
     * supported audio sampling rate.
     *
     * @param samplingRate the sampling rate for audio in samples per second.
     */
    public void setAudioSamplingRate(int samplingRate) {
        if (samplingRate <= 0) {
            throw new IllegalArgumentException("Audio sampling rate is not positive");
        }
        setParameter(String.format("audio-param-sampling-rate=%d", samplingRate));
    }

    /**
     * Sets the number of audio channels for recording. Call this method before prepare().
     * Prepare() may perform additional checks on the parameter to make sure whether the
     * specified number of audio channels are applicable.
     *
     * @param numChannels the number of audio channels. Usually it is either 1 (mono) or 2
     * (stereo).
     */
    public void setAudioChannels(int numChannels) {
        if (numChannels <= 0) {
            throw new IllegalArgumentException("Number of channels is not positive");
        }
        setParameter(String.format("audio-param-number-of-channels=%d", numChannels));
    }

    /**
     * Sets the audio encoding bit rate for recording. Call this method before prepare().
     * Prepare() may perform additional checks on the parameter to make sure whether the
     * specified bit rate is applicable, and sometimes the passed bitRate will be clipped
     * internally to ensure the audio recording can proceed smoothly based on the
     * capabilities of the platform.
     *
     * @param bitRate the audio encoding bit rate in bits per second.
     */
    public void setAudioEncodingBitRate(int bitRate) {
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Audio encoding bit rate is not positive");
        }
        setParameter(String.format("audio-param-encoding-bitrate=%d", bitRate));
    }

    /**
     * Sets the video encoding bit rate for recording. Call this method before prepare().
     * Prepare() may perform additional checks on the parameter to make sure whether the
     * specified bit rate is applicable, and sometimes the passed bitRate will be
     * clipped internally to ensure the video recording can proceed smoothly based on
     * the capabilities of the platform.
     *
     * @param bitRate the video encoding bit rate in bits per second.
     */
    public void setVideoEncodingBitRate(int bitRate) {
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Video encoding bit rate is not positive");
        }
        setParameter(String.format("video-param-encoding-bitrate=%d", bitRate));
    }

    /**
     * Sets the level of the encoder. Call this before prepare().
     *
     * @param encoderLevel the video encoder level.
     */
    public void setVideoEncoderLevel(int encoderLevel) {
        setParameter(String.format("video-param-encoder-level=%d", encoderLevel));
    }

    /**
     * Pass in the file descriptor of the file to be written. Call this after
     * setOutputFormat() but before prepare().
     *
     * @param fd an open file descriptor to be written into.
     * @throws IllegalStateException if it is called before
     * setOutputFormat() or after prepare()
     */
    public void setOutputFile(FileDescriptor fd) throws IllegalStateException
    {
        mPath = null;
        mFd = fd;
    }

    /**
     * Sets the path of the output file to be produced. Call this after
     * setOutputFormat() but before prepare().
     *
     * @param path The pathname to use.
     * @throws IllegalStateException if it is called before
     * setOutputFormat() or after prepare()
     */
    public void setOutputFile(String path) throws IllegalStateException
    {
        mFd = null;
        mPath = path;
    }

    // native implementation
    private native void _setOutputFile(FileDescriptor fd, long offset, long length)
        throws IllegalStateException, IOException;
    private native void _prepare() throws IllegalStateException, IOException;

    /**
     * Prepares the recorder to begin capturing and encoding data. This method
     * must be called after setting up the desired audio and video sources,
     * encoders, file format, etc., but before start().
     *
     * @throws IllegalStateException if it is called after
     * start() or before setOutputFormat().
     * @throws IOException if prepare fails otherwise.
     */
    public void prepare() throws IllegalStateException, IOException
    {
        if (mPath != null) {
            FileOutputStream fos = new FileOutputStream(mPath);
            try {
                _setOutputFile(fos.getFD(), 0, 0);
            } finally {
                fos.close();
            }
        } else if (mFd != null) {
            _setOutputFile(mFd, 0, 0);
        } else {
            throw new IOException("No valid output file");
        }
        _prepare();
    }

    /**
     * Begins capturing and encoding data to the file specified with
     * setOutputFile(). Call this after prepare().
     *
     * @throws IllegalStateException if it is called before
     * prepare().
     */
    public native void start() throws IllegalStateException;

    /**
     * Stops recording. Call this after start(). Once recording is stopped,
     * you will have to configure it again as if it has just been constructed.
     *
     * @throws IllegalStateException if it is called before start()
     */
    public native void stop() throws IllegalStateException;

    /**
     * Restarts the MediaRecorder to its idle state. After calling
     * this method, you will have to configure it again as if it had just been
     * constructed.
     */
    public void reset() {
        native_reset();

        // make sure none of the listeners get called anymore
        mEventHandler.removeCallbacksAndMessages(null);
    }

    private native void native_reset();

    /**
     * Returns the maximum absolute amplitude that was sampled since the last
     * call to this method. Call this only after the setAudioSource().
     *
     * @return the maximum absolute amplitude measured since the last call, or
     * 0 when called for the first time
     * @throws IllegalStateException if it is called before
     * the audio source has been set.
     */
    public native int getMaxAmplitude() throws IllegalStateException;

    /* Do not change this value without updating its counterpart
     * in include/media/mediarecorder.h!
     */
    /** Unspecified media recorder error.
     * @see android.media.MediaRecorder.OnErrorListener
     */
    public static final int MEDIA_RECORDER_ERROR_UNKNOWN = 1;

    /**
     * Interface definition for a callback to be invoked when an error
     * occurs while recording.
     */
    public interface OnErrorListener
    {
        /**
         * Called when an error occurs while recording.
         *
         * @param mr the MediaRecorder that encountered the error
         * @param what    the type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIA_RECORDER_ERROR_UNKNOWN}
         * </ul>
         * @param extra   an extra code, specific to the error type
         */
        void onError(MediaRecorder mr, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an error occurs while
     * recording.
     *
     * @param l the callback that will be run
     */
    public void setOnErrorListener(OnErrorListener l)
    {
        mOnErrorListener = l;
    }

    /* Do not change these values without updating their counterparts
     * in include/media/mediarecorder.h!
     */
    /** Unspecified media recorder error.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_UNKNOWN              = 1;
    /** A maximum duration had been setup and has now been reached.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 800;
    /** A maximum filesize had been setup and has now been reached.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED = 801;

    /**
     * Interface definition for a callback to be invoked when an error
     * occurs while recording.
     */
    public interface OnInfoListener
    {
        /**
         * Called when an error occurs while recording.
         *
         * @param mr the MediaRecorder that encountered the error
         * @param what    the type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIA_RECORDER_INFO_UNKNOWN}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}
         * </ul>
         * @param extra   an extra code, specific to the error type
         */
        void onInfo(MediaRecorder mr, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an informational event occurs while
     * recording.
     *
     * @param listener the callback that will be run
     */
    public void setOnInfoListener(OnInfoListener listener)
    {
        mOnInfoListener = listener;
    }

    private class EventHandler extends Handler
    {
        private MediaRecorder mMediaRecorder;

        public EventHandler(MediaRecorder mr, Looper looper) {
            super(looper);
            mMediaRecorder = mr;
        }

        /* Do not change these values without updating their counterparts
         * in include/media/mediarecorder.h!
         */
        private static final int MEDIA_RECORDER_EVENT_ERROR = 1;
        private static final int MEDIA_RECORDER_EVENT_INFO  = 2;

        @Override
        public void handleMessage(Message msg) {
            if (mMediaRecorder.mNativeContext == 0) {
                Log.w(TAG, "mediarecorder went away with unhandled events");
                return;
            }
            switch(msg.what) {
            case MEDIA_RECORDER_EVENT_ERROR:
                if (mOnErrorListener != null)
                    mOnErrorListener.onError(mMediaRecorder, msg.arg1, msg.arg2);

                return;

            case MEDIA_RECORDER_EVENT_INFO:
                if (mOnInfoListener != null)
                    mOnInfoListener.onInfo(mMediaRecorder, msg.arg1, msg.arg2);

                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /**
     * Called from native code when an interesting event happens.  This method
     * just uses the EventHandler system to post the event back to the main app thread.
     * We use a weak reference to the original MediaRecorder object so that the native
     * code is safe from the object disappearing from underneath it.  (This is
     * the cookie passed to native_setup().)
     */
    private static void postEventFromNative(Object mediarecorder_ref,
                                            int what, int arg1, int arg2, Object obj)
    {
        MediaRecorder mr = (MediaRecorder)((WeakReference)mediarecorder_ref).get();
        if (mr == null) {
            return;
        }

        if (mr.mEventHandler != null) {
            Message m = mr.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            mr.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Releases resources associated with this MediaRecorder object.
     * It is good practice to call this method when you're done
     * using the MediaRecorder.
     */
    public native void release();

    private static native final void native_init();

    private native final void native_setup(Object mediarecorder_this) throws IllegalStateException;

    private native final void native_finalize();

    private native void setParameter(String nameValuePair);

    @Override
    protected void finalize() { native_finalize(); }
}
