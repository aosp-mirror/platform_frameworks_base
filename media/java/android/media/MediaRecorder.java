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

import android.view.Surface;
import android.hardware.Camera;
import java.io.IOException;

/**
 * Used to record audio and video. The recording control is based on a
 * simple state machine (see below). 
 * 
 * <p><img src="../../../images/mediarecorder_state_diagram.gif" border="0" />
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
 * <p>See the <a href="../../../toolbox/apis/media.html">Android Media APIs</a> 
 * page for additional help with using MediaRecorder.
 */
public class MediaRecorder
{    
    static {
        System.loadLibrary("media_jni");
    }
    
    // The two fields below are accessed by native methods
    @SuppressWarnings("unused")
    private int mNativeContext;
    
    @SuppressWarnings("unused")
    private Surface mSurface;
    
    /**
     * Default constructor.
     */
    public MediaRecorder() {
        native_setup();
    }
  
    /**
     * Sets a Camera to use for recording. Use this function to switch
     * quickly between preview and capture mode without a teardown of
     * the camera object. Must call before prepare().
     * 
     * @param c the Camera to use for recording
     * FIXME: Temporarily hidden until API approval
     * @hide
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
    }

    /**
     * Defines the video source. These constants are used with 
     * {@link MediaRecorder#setVideoSource(int)}.
     * @hide
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
        /** Raw AMR file format */
        public static final int RAW_AMR = 3;
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
        //public static final AAC = 2;  currently unsupported
    }

    /**
     * Defines the video encoding. These constants are used with 
     * {@link MediaRecorder#setVideoEncoder(int)}.
     * @hide
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
     * Sets the video source to be used for recording. If this method is not
     * called, the output file will not contain an video track. The source needs
     * to be specified before setting recording-parameters or encoders. Call 
     * this only before setOutputFormat().
     * 
     * @param video_source the video source to use
     * @throws IllegalStateException if it is called after setOutputFormat()
     * @see android.media.MediaRecorder.VideoSource
     * @hide
     */ 
    public native void setVideoSource(int video_source)
            throws IllegalStateException;

    /**
     * Sets the format of the output file produced during recording. Call this
     * after setAudioSource()/setVideoSource() but before prepare().
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
     * @hide
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
     * @hide
     */
    public native void setVideoFrameRate(int rate) throws IllegalStateException;

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
     * @hide
     */ 
    public native void setVideoEncoder(int video_encoder)
            throws IllegalStateException;

    /**
     * Sets the path of the output file to be produced. Call this after
     * setOutputFormat() but before prepare().
     * 
     * @param path The pathname to use()
     * @throws IllegalStateException if it is called before
     * setOutputFormat() or after prepare()
     */ 
    public native void setOutputFile(String path) throws IllegalStateException;
  
    /**
     * Prepares the recorder to begin capturing and encoding data. This method
     * must be called after setting up the desired audio and video sources,
     * encoders, file format, etc., but before start().
     * 
     * @throws IllegalStateException if it is called after
     * start() or before setOutputFormat().
     * @throws IOException if prepare fails otherwise.
     */
    public native void prepare() throws IllegalStateException, IOException;

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
    public native void reset();
    
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
     

    /**
     * Releases resources associated with this MediaRecorder object.
     * It is good practice to call this method when you're done
     * using the MediaRecorder.
     */
    public native void release();

    private native final void native_setup() throws IllegalStateException;
    
    private native final void native_finalize();
    
    @Override
    protected void finalize() { native_finalize(); }
}
