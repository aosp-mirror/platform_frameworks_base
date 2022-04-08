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

import android.annotation.CallbackExecutor;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

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
 * <p>Applications may want to register for informational and error
 * events in order to be informed of some internal update and possible
 * runtime errors during recording. Registration for such events is
 * done by setting the appropriate listeners (via calls
 * (to {@link #setOnInfoListener(OnInfoListener)}setOnInfoListener and/or
 * {@link #setOnErrorListener(OnErrorListener)}setOnErrorListener).
 * In order to receive the respective callback associated with these listeners,
 * applications are required to create MediaRecorder objects on threads with a
 * Looper running (the main UI thread by default already has a Looper running).
 *
 * <p><strong>Note:</strong> Currently, MediaRecorder does not work on the emulator.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use MediaRecorder for recording video, read the
 * <a href="{@docRoot}guide/topics/media/camera.html#capture-video">Camera</a> developer guide.
 * For more information about how to use MediaRecorder for recording sound, read the
 * <a href="{@docRoot}guide/topics/media/audio-capture.html">Audio Capture</a> developer guide.</p>
 * </div>
 */
public class MediaRecorder implements AudioRouting,
                                      AudioRecordingMonitor,
                                      AudioRecordingMonitorClient,
                                      MicrophoneDirection
{
    static {
        System.loadLibrary("media_jni");
        native_init();
    }
    private final static String TAG = "MediaRecorder";

    // The two fields below are accessed by native methods
    @SuppressWarnings("unused")
    private long mNativeContext;

    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private Surface mSurface;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private String mPath;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private FileDescriptor mFd;
    private File mFile;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private EventHandler mEventHandler;
    @UnsupportedAppUsage
    private OnErrorListener mOnErrorListener;
    @UnsupportedAppUsage
    private OnInfoListener mOnInfoListener;

    private int mChannelCount;

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

        mChannelCount = 1;
        String packageName = ActivityThread.currentPackageName();
        /* Native setup requires a weak reference to our object.
         * It's easier to create it here than in C++.
         */
        native_setup(new WeakReference<MediaRecorder>(this), packageName,
                ActivityThread.currentOpPackageName());
    }

    /**
     * Sets a {@link android.hardware.Camera} to use for recording.
     *
     * <p>Use this function to switch quickly between preview and capture mode without a teardown of
     * the camera object. {@link android.hardware.Camera#unlock()} should be called before
     * this. Must call before {@link #prepare}.</p>
     *
     * @param c the Camera to use for recording
     * @deprecated Use {@link #getSurface} and the {@link android.hardware.camera2} API instead.
     */
    @Deprecated
    public native void setCamera(Camera c);

    /**
     * Gets the surface to record from when using SURFACE video source.
     *
     * <p> May only be called after {@link #prepare}. Frames rendered to the Surface before
     * {@link #start} will be discarded.</p>
     *
     * @throws IllegalStateException if it is called before {@link #prepare}, after
     * {@link #stop}, or is called when VideoSource is not set to SURFACE.
     * @see android.media.MediaRecorder.VideoSource
     */
    public native Surface getSurface();

    /**
     * Configures the recorder to use a persistent surface when using SURFACE video source.
     * <p> May only be called before {@link #prepare}. If called, {@link #getSurface} should
     * not be used and will throw IllegalStateException. Frames rendered to the Surface
     * before {@link #start} will be discarded.</p>

     * @param surface a persistent input surface created by
     *           {@link MediaCodec#createPersistentInputSurface}
     * @throws IllegalStateException if it is called after {@link #prepare} and before
     * {@link #stop}.
     * @throws IllegalArgumentException if the surface was not created by
     *           {@link MediaCodec#createPersistentInputSurface}.
     * @see MediaCodec#createPersistentInputSurface
     * @see MediaRecorder.VideoSource
     */
    public void setInputSurface(@NonNull Surface surface) {
        if (!(surface instanceof MediaCodec.PersistentSurface)) {
            throw new IllegalArgumentException("not a PersistentSurface");
        }
        native_setInputSurface(surface);
    }

    private native final void native_setInputSurface(@NonNull Surface surface);

    /**
     * Sets a Surface to show a preview of recorded media (video). Calls this
     * before prepare() to make sure that the desirable preview display is
     * set. If {@link #setCamera(Camera)} is used and the surface has been
     * already set to the camera, application do not need to call this. If
     * this is called with non-null surface, the preview surface of the camera
     * will be replaced by the new surface. If this method is called with null
     * surface or not called at all, media recorder will not change the preview
     * surface of the camera.
     *
     * @param sv the Surface to use for the preview
     * @see android.hardware.Camera#setPreviewDisplay(android.view.SurfaceHolder)
     */
    public void setPreviewDisplay(Surface sv) {
        mSurface = sv;
    }

    /**
     * Defines the audio source.
     * An audio source defines both a default physical source of audio signal, and a recording
     * configuration. These constants are for instance used
     * in {@link MediaRecorder#setAudioSource(int)} or
     * {@link AudioRecord.Builder#setAudioSource(int)}.
     */
    public final class AudioSource {

        private AudioSource() {}

        /** @hide */
        public final static int AUDIO_SOURCE_INVALID = -1;

      /* Do not change these values without updating their counterparts
       * in system/media/audio/include/system/audio.h!
       */

        /** Default audio source **/
        public static final int DEFAULT = 0;

        /** Microphone audio source */
        public static final int MIC = 1;

        /** Voice call uplink (Tx) audio source.
         * <p>
         * Capturing from <code>VOICE_UPLINK</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_UPLINK = 2;

        /** Voice call downlink (Rx) audio source.
         * <p>
         * Capturing from <code>VOICE_DOWNLINK</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_DOWNLINK = 3;

        /** Voice call uplink + downlink audio source
         * <p>
         * Capturing from <code>VOICE_CALL</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_CALL = 4;

        /** Microphone audio source tuned for video recording, with the same orientation
         *  as the camera if available. */
        public static final int CAMCORDER = 5;

        /** Microphone audio source tuned for voice recognition. */
        public static final int VOICE_RECOGNITION = 6;

        /** Microphone audio source tuned for voice communications such as VoIP. It
         *  will for instance take advantage of echo cancellation or automatic gain control
         *  if available.
         */
        public static final int VOICE_COMMUNICATION = 7;

        /**
         * Audio source for a submix of audio streams to be presented remotely.
         * <p>
         * An application can use this audio source to capture a mix of audio streams
         * that should be transmitted to a remote receiver such as a Wifi display.
         * While recording is active, these audio streams are redirected to the remote
         * submix instead of being played on the device speaker or headset.
         * </p><p>
         * Certain streams are excluded from the remote submix, including
         * {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_ALARM},
         * and {@link AudioManager#STREAM_NOTIFICATION}.  These streams will continue
         * to be presented locally as usual.
         * </p><p>
         * Capturing the remote submix audio requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        @RequiresPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
        public static final int REMOTE_SUBMIX = 8;

        /** Microphone audio source tuned for unprocessed (raw) sound if available, behaves like
         *  {@link #DEFAULT} otherwise. */
        public static final int UNPROCESSED = 9;


        /**
         * Source for capturing audio meant to be processed in real time and played back for live
         * performance (e.g karaoke).
         * <p>
         * The capture path will minimize latency and coupling with
         * playback path.
         * </p>
         */
        public static final int VOICE_PERFORMANCE = 10;

        /**
         * Source for an echo canceller to capture the reference signal to be cancelled.
         * <p>
         * The echo reference signal will be captured as close as possible to the DAC in order
         * to include all post processing applied to the playback path.
         * </p><p>
         * Capturing the echo reference requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
        public static final int ECHO_REFERENCE = 1997;

        /**
         * Audio source for capturing broadcast radio tuner output.
         * Capturing the radio tuner output requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
        public static final int RADIO_TUNER = 1998;

        /**
         * Audio source for preemptible, low-priority software hotword detection
         * It presents the same gain and pre-processing tuning as {@link #VOICE_RECOGNITION}.
         * <p>
         * An application should use this audio source when it wishes to do
         * always-on software hotword detection, while gracefully giving in to any other application
         * that might want to read from the microphone.
         * </p>
         * This is a hidden audio source.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.CAPTURE_AUDIO_HOTWORD)
        public static final int HOTWORD = 1999;
    }

    /** @hide */
    @IntDef({
        AudioSource.DEFAULT,
        AudioSource.MIC,
        AudioSource.VOICE_UPLINK,
        AudioSource.VOICE_DOWNLINK,
        AudioSource.VOICE_CALL,
        AudioSource.CAMCORDER,
        AudioSource.VOICE_RECOGNITION,
        AudioSource.VOICE_COMMUNICATION,
        AudioSource.UNPROCESSED,
        AudioSource.VOICE_PERFORMANCE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    // TODO make AudioSource static (API change) and move this method inside the AudioSource class
    /**
     * @hide
     * @param source An audio source to test
     * @return true if the source is only visible to system components
     */
    public static boolean isSystemOnlyAudioSource(int source) {
        switch(source) {
        case AudioSource.DEFAULT:
        case AudioSource.MIC:
        case AudioSource.VOICE_UPLINK:
        case AudioSource.VOICE_DOWNLINK:
        case AudioSource.VOICE_CALL:
        case AudioSource.CAMCORDER:
        case AudioSource.VOICE_RECOGNITION:
        case AudioSource.VOICE_COMMUNICATION:
        //case REMOTE_SUBMIX:  considered "system" as it requires system permissions
        case AudioSource.UNPROCESSED:
        case AudioSource.VOICE_PERFORMANCE:
            return false;
        default:
            return true;
        }
    }

    /** @hide */
    public static final String toLogFriendlyAudioSource(int source) {
        switch(source) {
        case AudioSource.DEFAULT:
            return "DEFAULT";
        case AudioSource.MIC:
            return "MIC";
        case AudioSource.VOICE_UPLINK:
            return "VOICE_UPLINK";
        case AudioSource.VOICE_DOWNLINK:
            return "VOICE_DOWNLINK";
        case AudioSource.VOICE_CALL:
            return "VOICE_CALL";
        case AudioSource.CAMCORDER:
            return "CAMCORDER";
        case AudioSource.VOICE_RECOGNITION:
            return "VOICE_RECOGNITION";
        case AudioSource.VOICE_COMMUNICATION:
            return "VOICE_COMMUNICATION";
        case AudioSource.REMOTE_SUBMIX:
            return "REMOTE_SUBMIX";
        case AudioSource.UNPROCESSED:
            return "UNPROCESSED";
        case AudioSource.ECHO_REFERENCE:
            return "ECHO_REFERENCE";
        case AudioSource.VOICE_PERFORMANCE:
            return "VOICE_PERFORMANCE";
        case AudioSource.RADIO_TUNER:
            return "RADIO_TUNER";
        case AudioSource.HOTWORD:
            return "HOTWORD";
        case AudioSource.AUDIO_SOURCE_INVALID:
            return "AUDIO_SOURCE_INVALID";
        default:
            return "unknown source " + source;
        }
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
        /** Camera video source
         * <p>
         * Using the {@link android.hardware.Camera} API as video source.
         * </p>
         */
        public static final int CAMERA = 1;
        /** Surface video source
         * <p>
         * Using a Surface as video source.
         * </p><p>
         * This flag must be used when recording from an
         * {@link android.hardware.camera2} API source.
         * </p><p>
         * When using this video source type, use {@link MediaRecorder#getSurface()}
         * to retrieve the surface created by MediaRecorder.
         */
        public static final int SURFACE = 2;
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

        /** The following formats are audio only .aac or .amr formats */

        /**
         * AMR NB file format
         * @deprecated  Deprecated in favor of MediaRecorder.OutputFormat.AMR_NB
         */
        public static final int RAW_AMR = 3;

        /** AMR NB file format */
        public static final int AMR_NB = 3;

        /** AMR WB file format */
        public static final int AMR_WB = 4;

        /** @hide AAC ADIF file format */
        public static final int AAC_ADIF = 5;

        /** AAC ADTS file format */
        public static final int AAC_ADTS = 6;

        /** @hide Stream over a socket, limited to a single stream */
        public static final int OUTPUT_FORMAT_RTP_AVP = 7;

        /** H.264/AAC data encapsulated in MPEG2/TS */
        public static final int MPEG_2_TS = 8;

        /** VP8/VORBIS data in a WEBM container */
        public static final int WEBM = 9;

        /** @hide HEIC data in a HEIF container */
        public static final int HEIF = 10;

        /** Opus data in a Ogg container */
        public static final int OGG = 11;
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
        /** AMR (Wideband) audio codec */
        public static final int AMR_WB = 2;
        /** AAC Low Complexity (AAC-LC) audio codec */
        public static final int AAC = 3;
        /** High Efficiency AAC (HE-AAC) audio codec */
        public static final int HE_AAC = 4;
        /** Enhanced Low Delay AAC (AAC-ELD) audio codec */
        public static final int AAC_ELD = 5;
        /** Ogg Vorbis audio codec (Support is optional) */
        public static final int VORBIS = 6;
        /** Opus audio codec */
        public static final int OPUS = 7;
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
        public static final int VP8 = 4;
        public static final int HEVC = 5;
    }

    /**
     * Sets the audio source to be used for recording. If this method is not
     * called, the output file will not contain an audio track. The source needs
     * to be specified before setting recording-parameters or encoders. Call
     * this only before setOutputFormat().
     *
     * @param audioSource the audio source to use
     * @throws IllegalStateException if it is called after setOutputFormat()
     * @see android.media.MediaRecorder.AudioSource
     */
    public native void setAudioSource(@Source int audioSource)
            throws IllegalStateException;

    /**
     * Gets the maximum value for audio sources.
     * @see android.media.MediaRecorder.AudioSource
     */
    public static final int getAudioSourceMax() {
        return AudioSource.VOICE_PERFORMANCE;
    }

    /**
     * Indicates that this capture request is privacy sensitive and that
     * any concurrent capture is not permitted.
     * <p>
     * The default is not privacy sensitive except when the audio source set with
     * {@link #setAudioSource(int)} is {@link AudioSource#VOICE_COMMUNICATION} or
     * {@link AudioSource#CAMCORDER}.
     * <p>
     * Always takes precedence over default from audio source when set explicitly.
     * <p>
     * Using this API is only permitted when the audio source is one of:
     * <ul>
     * <li>{@link AudioSource#MIC}</li>
     * <li>{@link AudioSource#CAMCORDER}</li>
     * <li>{@link AudioSource#VOICE_RECOGNITION}</li>
     * <li>{@link AudioSource#VOICE_COMMUNICATION}</li>
     * <li>{@link AudioSource#UNPROCESSED}</li>
     * <li>{@link AudioSource#VOICE_PERFORMANCE}</li>
     * </ul>
     * Invoking {@link #prepare()} will throw an IOException if this
     * condition is not met.
     * <p>
     * Must be called after {@link #setAudioSource(int)} and before {@link #setOutputFormat(int)}.
     * @param privacySensitive True if capture from this MediaRecorder must be marked as privacy
     * sensitive, false otherwise.
     * @throws IllegalStateException if called before {@link #setAudioSource(int)}
     * or after {@link #setOutputFormat(int)}
     */
    public native void setPrivacySensitive(boolean privacySensitive);

    /**
     * Returns whether this MediaRecorder is marked as privacy sensitive or not with
     * regard to audio capture.
     * <p>
     * See {@link #setPrivacySensitive(boolean)}
     * <p>
     * @return true if privacy sensitive, false otherwise
     */
    public native boolean isPrivacySensitive();

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
     * If a time lapse CamcorderProfile is used, audio related source or recording
     * parameters are ignored.
     *
     * @param profile the CamcorderProfile to use
     * @see android.media.CamcorderProfile
     */
    public void setProfile(CamcorderProfile profile) {
        setOutputFormat(profile.fileFormat);
        setVideoFrameRate(profile.videoFrameRate);
        setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        setVideoEncodingBitRate(profile.videoBitRate);
        setVideoEncoder(profile.videoCodec);
        if (profile.quality >= CamcorderProfile.QUALITY_TIME_LAPSE_LOW &&
             profile.quality <= CamcorderProfile.QUALITY_TIME_LAPSE_QVGA) {
            // Nothing needs to be done. Call to setCaptureRate() enables
            // time lapse video recording.
        } else {
            setAudioEncodingBitRate(profile.audioBitRate);
            setAudioChannels(profile.audioChannels);
            setAudioSamplingRate(profile.audioSampleRate);
            setAudioEncoder(profile.audioCodec);
        }
    }

    /**
     * Set video frame capture rate. This can be used to set a different video frame capture
     * rate than the recorded video's playback rate. This method also sets the recording mode
     * to time lapse. In time lapse video recording, only video is recorded. Audio related
     * parameters are ignored when a time lapse recording session starts, if an application
     * sets them.
     *
     * @param fps Rate at which frames should be captured in frames per second.
     * The fps can go as low as desired. However the fastest fps will be limited by the hardware.
     * For resolutions that can be captured by the video camera, the fastest fps can be computed using
     * {@link android.hardware.Camera.Parameters#getPreviewFpsRange(int[])}. For higher
     * resolutions the fastest fps may be more restrictive.
     * Note that the recorder cannot guarantee that frames will be captured at the
     * given rate due to camera/encoder limitations. However it tries to be as close as
     * possible.
     */
    public void setCaptureRate(double fps) {
        // Make sure that time lapse is enabled when this method is called.
        setParameter("time-lapse-enable=1");
        setParameter("time-lapse-fps=" + fps);
    }

    /**
     * Sets the orientation hint for output video playback.
     * This method should be called before prepare(). This method will not
     * trigger the source video frame to rotate during video recording, but to
     * add a composition matrix containing the rotation angle in the output
     * video if the output format is OutputFormat.THREE_GPP or
     * OutputFormat.MPEG_4 so that a video player can choose the proper
     * orientation for playback. Note that some video players may choose
     * to ignore the compostion matrix in a video during playback.
     *
     * @param degrees the angle to be rotated clockwise in degrees.
     * The supported angles are 0, 90, 180, and 270 degrees.
     * @throws IllegalArgumentException if the angle is not supported.
     *
     */
    public void setOrientationHint(int degrees) {
        if (degrees != 0   &&
            degrees != 90  &&
            degrees != 180 &&
            degrees != 270) {
            throw new IllegalArgumentException("Unsupported angle: " + degrees);
        }
        setParameter("video-param-rotation-angle-degrees=" + degrees);
    }

    /**
     * Set and store the geodata (latitude and longitude) in the output file.
     * This method should be called before prepare(). The geodata is
     * stored in udta box if the output format is OutputFormat.THREE_GPP
     * or OutputFormat.MPEG_4, and is ignored for other output formats.
     * The geodata is stored according to ISO-6709 standard.
     *
     * @param latitude latitude in degrees. Its value must be in the
     * range [-90, 90].
     * @param longitude longitude in degrees. Its value must be in the
     * range [-180, 180].
     *
     * @throws IllegalArgumentException if the given latitude or
     * longitude is out of range.
     *
     */
    public void setLocation(float latitude, float longitude) {
        int latitudex10000  = (int) (latitude * 10000 + 0.5);
        int longitudex10000 = (int) (longitude * 10000 + 0.5);

        if (latitudex10000 > 900000 || latitudex10000 < -900000) {
            String msg = "Latitude: " + latitude + " out of range.";
            throw new IllegalArgumentException(msg);
        }
        if (longitudex10000 > 1800000 || longitudex10000 < -1800000) {
            String msg = "Longitude: " + longitude + " out of range";
            throw new IllegalArgumentException(msg);
        }

        setParameter("param-geotag-latitude=" + latitudex10000);
        setParameter("param-geotag-longitude=" + longitudex10000);
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
     * after setVideoSource(). Call this after setOutputFormat() but before
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
     * after setVideoSource(). Call this after setOutputFormat() but before
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
     * Call this after setOutputFormat() but before prepare().
     * After recording reaches the specified duration, a notification
     * will be sent to the {@link android.media.MediaRecorder.OnInfoListener}
     * with a "what" code of {@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
     * and recording will be stopped. Stopping happens asynchronously, there
     * is no guarantee that the recorder will have stopped by the time the
     * listener is notified.
     *
     * <p>When using MPEG-4 container ({@link #setOutputFormat(int)} with
     * {@link OutputFormat#MPEG_4}), it is recommended to set maximum duration that fits the use
     * case. Setting a larger than required duration may result in a larger than needed output file
     * because of space reserved for MOOV box expecting large movie data in this recording session.
     *  Unused space of MOOV box is turned into FREE box in the output file.</p>
     *
     * @param max_duration_ms the maximum duration in ms (if zero or negative, disables the duration limit)
     *
     */
    public native void setMaxDuration(int max_duration_ms) throws IllegalArgumentException;

    /**
     * Sets the maximum filesize (in bytes) of the recording session.
     * Call this after setOutputFormat() but before prepare().
     * After recording reaches the specified filesize, a notification
     * will be sent to the {@link android.media.MediaRecorder.OnInfoListener}
     * with a "what" code of {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}
     * and recording will be stopped. Stopping happens asynchronously, there
     * is no guarantee that the recorder will have stopped by the time the
     * listener is notified.
     *
     * <p>When using MPEG-4 container ({@link #setOutputFormat(int)} with
     * {@link OutputFormat#MPEG_4}), it is recommended to set maximum filesize that fits the use
     * case. Setting a larger than required filesize may result in a larger than needed output file
     * because of space reserved for MOOV box expecting large movie data in this recording session.
     * Unused space of MOOV box is turned into FREE box in the output file.</p>
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
     * from 8 to 96 kHz, the sampling rate supported by AMRNB is 8kHz, and the sampling
     * rate supported by AMRWB is 16kHz. Please consult with the related audio coding
     * standard for the supported audio sampling rate.
     *
     * @param samplingRate the sampling rate for audio in samples per second.
     */
    public void setAudioSamplingRate(int samplingRate) {
        if (samplingRate <= 0) {
            throw new IllegalArgumentException("Audio sampling rate is not positive");
        }
        setParameter("audio-param-sampling-rate=" + samplingRate);
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
        mChannelCount = numChannels;
        setParameter("audio-param-number-of-channels=" + numChannels);
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
        setParameter("audio-param-encoding-bitrate=" + bitRate);
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
        setParameter("video-param-encoding-bitrate=" + bitRate);
    }

    /**
     * Sets the desired video encoding profile and level for recording. The profile and level
     * must be valid for the video encoder set by {@link #setVideoEncoder}. This method can
     * called before or after {@link #setVideoEncoder} but it must be called before {@link #prepare}.
     * {@code prepare()} may perform additional checks on the parameter to make sure that the specified
     * profile and level are applicable, and sometimes the passed profile or level will be
     * discarded due to codec capablity or to ensure the video recording can proceed smoothly
     * based on the capabilities of the platform. <br>Application can also use the
     * {@link MediaCodecInfo.CodecCapabilities#profileLevels} to query applicable combination of profile
     * and level for the corresponding format. Note that the requested profile/level may not be supported by
     * the codec that is actually being used by this MediaRecorder instance.
     * @param profile declared in {@link MediaCodecInfo.CodecProfileLevel}.
     * @param level declared in {@link MediaCodecInfo.CodecProfileLevel}.
     * @throws IllegalArgumentException when an invalid profile or level value is used.
     */
    public void setVideoEncodingProfileLevel(int profile, int level) {
        if (profile <= 0)  {
            throw new IllegalArgumentException("Video encoding profile is not positive");
        }
        if (level <= 0)  {
            throw new IllegalArgumentException("Video encoding level is not positive");
        }
        setParameter("video-param-encoder-profile=" + profile);
        setParameter("video-param-encoder-level=" + level);
    }

    /**
     * Currently not implemented. It does nothing.
     * @deprecated Time lapse mode video recording using camera still image capture
     * is not desirable, and will not be supported.
     * @hide
     */
    public void setAuxiliaryOutputFile(FileDescriptor fd)
    {
        Log.w(TAG, "setAuxiliaryOutputFile(FileDescriptor) is no longer supported.");
    }

    /**
     * Currently not implemented. It does nothing.
     * @deprecated Time lapse mode video recording using camera still image capture
     * is not desirable, and will not be supported.
     * @hide
     */
    public void setAuxiliaryOutputFile(String path)
    {
        Log.w(TAG, "setAuxiliaryOutputFile(String) is no longer supported.");
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
        mFile = null;
        mFd = fd;
    }

    /**
     * Pass in the file object to be written. Call this after setOutputFormat() but before prepare().
     * File should be seekable. After setting the next output file, application should not use the
     * file until {@link #stop}. Application is responsible for cleaning up unused files after
     * {@link #stop} is called.
     *
     * @param file the file object to be written into.
     */
    public void setOutputFile(File file)
    {
        mPath = null;
        mFd = null;
        mFile = file;
    }

    /**
     * Sets the next output file descriptor to be used when the maximum filesize is reached
     * on the prior output {@link #setOutputFile} or {@link #setNextOutputFile}). File descriptor
     * must be seekable and writable. After setting the next output file, application should not
     * use the file referenced by this file descriptor until {@link #stop}. It is the application's
     * responsibility to close the file descriptor. It is safe to do so as soon as this call returns.
     * Application must call this after receiving on the
     * {@link android.media.MediaRecorder.OnInfoListener} a "what" code of
     * {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING} and before receiving a "what" code of
     * {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}. The file is not used until switching to
     * that output. Application will receive{@link #MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED}
     * when the next output file is used. Application will not be able to set a new output file if
     * the previous one has not been used. Application is responsible for cleaning up unused files
     * after {@link #stop} is called.
     *
     * @param fd an open file descriptor to be written into.
     * @throws IllegalStateException if it is called before prepare().
     * @throws IOException if setNextOutputFile fails otherwise.
     */
    public void setNextOutputFile(FileDescriptor fd) throws IOException
    {
        _setNextOutputFile(fd);
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
        mFile = null;
        mPath = path;
    }

    /**
     * Sets the next output file to be used when the maximum filesize is reached on the prior
     * output {@link #setOutputFile} or {@link #setNextOutputFile}). File should be seekable.
     * After setting the next output file, application should not use the file until {@link #stop}.
     * Application must call this after receiving on the
     * {@link android.media.MediaRecorder.OnInfoListener} a "what" code of
     * {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING} and before receiving a "what" code of
     * {@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}. The file is not used until switching to
     * that output. Application will receive {@link #MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED}
     * when the next output file is used. Application will not be able to set a new output file if
     * the previous one has not been used. Application is responsible for cleaning up unused files
     * after {@link #stop} is called.
     *
     * @param  file The file to use.
     * @throws IllegalStateException if it is called before prepare().
     * @throws IOException if setNextOutputFile fails otherwise.
     */
    public void setNextOutputFile(File file) throws IOException
    {
        RandomAccessFile f = new RandomAccessFile(file, "rw");
        try {
            _setNextOutputFile(f.getFD());
        } finally {
            f.close();
        }
    }

    // native implementation
    private native void _setOutputFile(FileDescriptor fd) throws IllegalStateException, IOException;
    private native void _setNextOutputFile(FileDescriptor fd) throws IllegalStateException, IOException;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
            RandomAccessFile file = new RandomAccessFile(mPath, "rw");
            try {
                _setOutputFile(file.getFD());
            } finally {
                file.close();
            }
        } else if (mFd != null) {
            _setOutputFile(mFd);
        } else if (mFile != null) {
            RandomAccessFile file = new RandomAccessFile(mFile, "rw");
            try {
                _setOutputFile(file.getFD());
            } finally {
                file.close();
            }
        } else {
            throw new IOException("No valid output file");
        }

        _prepare();
    }

    /**
     * Begins capturing and encoding data to the file specified with
     * setOutputFile(). Call this after prepare().
     *
     * <p>Since API level 13, if applications set a camera via
     * {@link #setCamera(Camera)}, the apps can use the camera after this method
     * call. The apps do not need to lock the camera again. However, if this
     * method fails, the apps should still lock the camera back. The apps should
     * not start another recording session during recording.
     *
     * @throws IllegalStateException if it is called before
     * prepare() or when the camera is already in use by another app.
     */
    public native void start() throws IllegalStateException;

    /**
     * Stops recording. Call this after start(). Once recording is stopped,
     * you will have to configure it again as if it has just been constructed.
     * Note that a RuntimeException is intentionally thrown to the
     * application, if no valid audio/video data has been received when stop()
     * is called. This happens if stop() is called immediately after
     * start(). The failure lets the application take action accordingly to
     * clean up the output file (delete the output file, for instance), since
     * the output file is not properly constructed when this happens.
     *
     * @throws IllegalStateException if it is called before start()
     */
    public native void stop() throws IllegalStateException;

    /**
     * Pauses recording. Call this after start(). You may resume recording
     * with resume() without reconfiguration, as opposed to stop(). It does
     * nothing if the recording is already paused.
     *
     * When the recording is paused and resumed, the resulting output would
     * be as if nothing happend during paused period, immediately switching
     * to the resumed scene.
     *
     * @throws IllegalStateException if it is called before start() or after
     * stop()
     */
    public native void pause() throws IllegalStateException;

    /**
     * Resumes recording. Call this after start(). It does nothing if the
     * recording is not paused.
     *
     * @throws IllegalStateException if it is called before start() or after
     * stop()
     * @see android.media.MediaRecorder#pause
     */
    public native void resume() throws IllegalStateException;

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

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * in include/media/mediarecorder.h or mediaplayer.h!
     */
    /** Unspecified media recorder error.
     * @see android.media.MediaRecorder.OnErrorListener
     */
    public static final int MEDIA_RECORDER_ERROR_UNKNOWN = 1;
    /** Media server died. In this case, the application must release the
     * MediaRecorder object and instantiate a new one.
     * @see android.media.MediaRecorder.OnErrorListener
     */
    public static final int MEDIA_ERROR_SERVER_DIED = 100;

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
         * <li>{@link #MEDIA_ERROR_SERVER_DIED}
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
    /** Unspecified media recorder info.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_UNKNOWN              = 1;
    /** A maximum duration had been setup and has now been reached.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 800;
    /** A maximum filesize had been setup and has now been reached.
     * Note: This event will not be sent if application already set
     * next output file through {@link #setNextOutputFile}.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED = 801;
    /** A maximum filesize had been setup and current recorded file size
     * has reached 90% of the limit. This is sent once per file upon
     * reaching/passing the 90% limit. To continue the recording, applicaiton
     * should use {@link #setNextOutputFile} to set the next output file.
     * Otherwise, recording will stop when reaching maximum file size.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING = 802;
    /** A maximum filesize had been reached and MediaRecorder has switched
     * output to a new file set by application {@link #setNextOutputFile}.
     * For best practice, application should use this event to keep track
     * of whether the file previously set has been used or not.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED = 803;

    /** informational events for individual tracks, for testing purpose.
     * The track informational event usually contains two parts in the ext1
     * arg of the onInfo() callback: bit 31-28 contains the track id; and
     * the rest of the 28 bits contains the informational event defined here.
     * For example, ext1 = (1 << 28 | MEDIA_RECORDER_TRACK_INFO_TYPE) if the
     * track id is 1 for informational event MEDIA_RECORDER_TRACK_INFO_TYPE;
     * while ext1 = (0 << 28 | MEDIA_RECORDER_TRACK_INFO_TYPE) if the track
     * id is 0 for informational event MEDIA_RECORDER_TRACK_INFO_TYPE. The
     * application should extract the track id and the type of informational
     * event from ext1, accordingly.
     *
     * FIXME:
     * Please update the comment for onInfo also when these
     * events are unhidden so that application knows how to extract the track
     * id and the informational event type from onInfo callback.
     *
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_LIST_START        = 1000;
    /** Signal the completion of the track for the recording session.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_COMPLETION_STATUS = 1000;
    /** Indicate the recording progress in time (ms) during recording.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_PROGRESS_IN_TIME  = 1001;
    /** Indicate the track type: 0 for Audio and 1 for Video.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_TYPE              = 1002;
    /** Provide the track duration information.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_DURATION_MS       = 1003;
    /** Provide the max chunk duration in time (ms) for the given track.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_MAX_CHUNK_DUR_MS  = 1004;
    /** Provide the total number of recordd frames.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_ENCODED_FRAMES    = 1005;
    /** Provide the max spacing between neighboring chunks for the given track.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INTER_CHUNK_TIME_MS    = 1006;
    /** Provide the elapsed time measuring from the start of the recording
     * till the first output frame of the given track is received, excluding
     * any intentional start time offset of a recording session for the
     * purpose of eliminating the recording sound in the recorded file.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_INITIAL_DELAY_MS  = 1007;
    /** Provide the start time difference (delay) betweeen this track and
     * the start of the movie.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_START_OFFSET_MS   = 1008;
    /** Provide the total number of data (in kilo-bytes) encoded.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_DATA_KBYTES       = 1009;
    /**
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_LIST_END          = 2000;


    /**
     * Interface definition of a callback to be invoked to communicate some
     * info and/or warning about the recording.
     */
    public interface OnInfoListener
    {
        /**
         * Called to indicate an info or a warning during recording.
         *
         * @param mr   the MediaRecorder the info pertains to
         * @param what the type of info or warning that has occurred
         * <ul>
         * <li>{@link #MEDIA_RECORDER_INFO_UNKNOWN}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}
         * </ul>
         * @param extra   an extra code, specific to the info type
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
        private static final int MEDIA_RECORDER_EVENT_LIST_START = 1;
        private static final int MEDIA_RECORDER_EVENT_ERROR      = 1;
        private static final int MEDIA_RECORDER_EVENT_INFO       = 2;
        private static final int MEDIA_RECORDER_EVENT_LIST_END   = 99;

        /* Events related to individual tracks */
        private static final int MEDIA_RECORDER_TRACK_EVENT_LIST_START = 100;
        private static final int MEDIA_RECORDER_TRACK_EVENT_ERROR      = 100;
        private static final int MEDIA_RECORDER_TRACK_EVENT_INFO       = 101;
        private static final int MEDIA_RECORDER_TRACK_EVENT_LIST_END   = 1000;

        private static final int MEDIA_RECORDER_AUDIO_ROUTING_CHANGED  = 10000;

        @Override
        public void handleMessage(Message msg) {
            if (mMediaRecorder.mNativeContext == 0) {
                Log.w(TAG, "mediarecorder went away with unhandled events");
                return;
            }
            switch(msg.what) {
            case MEDIA_RECORDER_EVENT_ERROR:
            case MEDIA_RECORDER_TRACK_EVENT_ERROR:
                if (mOnErrorListener != null)
                    mOnErrorListener.onError(mMediaRecorder, msg.arg1, msg.arg2);

                return;

            case MEDIA_RECORDER_EVENT_INFO:
            case MEDIA_RECORDER_TRACK_EVENT_INFO:
                if (mOnInfoListener != null)
                    mOnInfoListener.onInfo(mMediaRecorder, msg.arg1, msg.arg2);

                return;

            case MEDIA_RECORDER_AUDIO_ROUTING_CHANGED:
                AudioManager.resetAudioPortGeneration();
                synchronized (mRoutingChangeListeners) {
                    for (NativeRoutingEventHandlerDelegate delegate
                            : mRoutingChangeListeners.values()) {
                        delegate.notifyClient();
                    }
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    //--------------------------------------------------------------------------
    // Explicit Routing
    //--------------------
    private AudioDeviceInfo mPreferredDevice = null;

    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the input from this MediaRecorder.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio source.
     *  If deviceInfo is null, default routing is restored.
     * @return true if succesful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio input device.
     */
    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
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
     * Returns the selected input device specified by {@link #setPreferredDevice}. Note that this
     * is not guaranteed to correspond to the actual device being used for recording.
     */
    @Override
    public AudioDeviceInfo getPreferredDevice() {
        synchronized (this) {
            return mPreferredDevice;
        }
    }

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this MediaRecorder
     * Note: The query is only valid if the MediaRecorder is currently recording.
     * If the recorder is not recording, the returned device can be null or correspond to previously
     * selected device when the recorder was last active.
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
     * Call BEFORE adding a routing callback handler or AFTER removing a routing callback handler.
     */
    @GuardedBy("mRoutingChangeListeners")
    private void enableNativeRoutingCallbacksLocked(boolean enabled) {
        if (mRoutingChangeListeners.size() == 0) {
            native_enableDeviceCallback(enabled);
        }
    }

    /**
     * The list of AudioRouting.OnRoutingChangedListener interfaces added (with
     * {@link #addOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener, Handler)}
     * by an app to receive (re)routing notifications.
     */
    @GuardedBy("mRoutingChangeListeners")
    private ArrayMap<AudioRouting.OnRoutingChangedListener,
            NativeRoutingEventHandlerDelegate> mRoutingChangeListeners = new ArrayMap<>();

    /**
     * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of routing
     * changes on this MediaRecorder.
     * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
     * notifications of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the handler on the main looper will be used.
     */
    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
                                            Handler handler) {
        synchronized (mRoutingChangeListeners) {
            if (listener != null && !mRoutingChangeListeners.containsKey(listener)) {
                enableNativeRoutingCallbacksLocked(true);
                mRoutingChangeListeners.put(
                        listener, new NativeRoutingEventHandlerDelegate(this, listener,
                                handler != null ? handler : mEventHandler));
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
                enableNativeRoutingCallbacksLocked(false);
            }
        }
    }

    private native final boolean native_setInputDevice(int deviceId);
    private native final int native_getRoutedDeviceId();
    private native final void native_enableDeviceCallback(boolean enabled);

    //--------------------------------------------------------------------------
    // Microphone information
    //--------------------
    /**
     * Return A lists of {@link MicrophoneInfo} representing the active microphones.
     * By querying channel mapping for each active microphone, developer can know how
     * the microphone is used by each channels or a capture stream.
     *
     * @return a lists of {@link MicrophoneInfo} representing the active microphones
     * @throws IOException if an error occurs
     */
    public List<MicrophoneInfo> getActiveMicrophones() throws IOException {
        ArrayList<MicrophoneInfo> activeMicrophones = new ArrayList<>();
        int status = native_getActiveMicrophones(activeMicrophones);
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

    private native final int native_getActiveMicrophones(
            ArrayList<MicrophoneInfo> activeMicrophones);

    //--------------------------------------------------------------------------
    // MicrophoneDirection
    //--------------------
    /**
     * Specifies the logical microphone (for processing).
     *
     * @param direction Direction constant.
     * @return true if sucessful.
     */
    public boolean setPreferredMicrophoneDirection(@DirectionMode int direction) {
        return native_setPreferredMicrophoneDirection(direction) == 0;
    }

    /**
     * Specifies the zoom factor (i.e. the field dimension) for the selected microphone
     * (for processing). The selected microphone is determined by the use-case for the stream.
     *
     * @param zoom the desired field dimension of microphone capture. Range is from -1 (wide angle),
     * though 0 (no zoom) to 1 (maximum zoom).
     * @return true if sucessful.
     */
    public boolean setPreferredMicrophoneFieldDimension(
                            @FloatRange(from = -1.0, to = 1.0) float zoom) {
        Preconditions.checkArgument(
                zoom >= -1 && zoom <= 1, "Argument must fall between -1 & 1 (inclusive)");
        return native_setPreferredMicrophoneFieldDimension(zoom) == 0;
    }

    private native int native_setPreferredMicrophoneDirection(int direction);
    private native int native_setPreferredMicrophoneFieldDimension(float zoom);

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
        if (mNativeContext == 0) {
            return 0;
        }
        return native_getPortId();
    }

    private native int native_getPortId();

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
     * using the MediaRecorder. In particular, whenever an Activity
     * of an application is paused (its onPause() method is called),
     * or stopped (its onStop() method is called), this method should be
     * invoked to release the MediaRecorder object, unless the application
     * has a special need to keep the object around. In addition to
     * unnecessary resources (such as memory and instances of codecs)
     * being held, failure to call this method immediately if a
     * MediaRecorder object is no longer needed may also lead to
     * continuous battery consumption for mobile devices, and recording
     * failure for other applications if no multiple instances of the
     * same codec are supported on a device. Even if multiple instances
     * of the same codec are supported, some performance degradation
     * may be expected when unnecessary multiple instances are used
     * at the same time.
     */
    public native void release();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static native final void native_init();

    @UnsupportedAppUsage
    private native final void native_setup(Object mediarecorder_this,
            String clientName, String opPackageName) throws IllegalStateException;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private native final void native_finalize();

    @UnsupportedAppUsage
    private native void setParameter(String nameValuePair);

    /**
     *  Return Metrics data about the current Mediarecorder instance.
     *
     * @return a {@link PersistableBundle} containing the set of attributes and values
     * available for the media being generated by this instance of
     * MediaRecorder.
     * The attributes are descibed in {@link MetricsConstants}.
     *
     *  Additional vendor-specific fields may also be present in
     *  the return value.
     */
    public PersistableBundle getMetrics() {
        PersistableBundle bundle = native_getMetrics();
        return bundle;
    }

    private native PersistableBundle native_getMetrics();

    @Override
    protected void finalize() { native_finalize(); }

    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        /**
         * Key to extract the audio bitrate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_BITRATE = "android.media.mediarecorder.audio-bitrate";

        /**
         * Key to extract the number of audio channels
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_CHANNELS = "android.media.mediarecorder.audio-channels";

        /**
         * Key to extract the audio samplerate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_SAMPLERATE = "android.media.mediarecorder.audio-samplerate";

        /**
         * Key to extract the audio timescale
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_TIMESCALE = "android.media.mediarecorder.audio-timescale";

        /**
         * Key to extract the video capture frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is a double.
         */
        public static final String CAPTURE_FPS = "android.media.mediarecorder.capture-fps";

        /**
         * Key to extract the video capture framerate enable value
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String CAPTURE_FPS_ENABLE = "android.media.mediarecorder.capture-fpsenable";

        /**
         * Key to extract the intended playback frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String FRAMERATE = "android.media.mediarecorder.frame-rate";

        /**
         * Key to extract the height (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String HEIGHT = "android.media.mediarecorder.height";

        /**
         * Key to extract the recorded movies time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the movie's timing is in milliseconds.
         */
        public static final String MOVIE_TIMESCALE = "android.media.mediarecorder.movie-timescale";

        /**
         * Key to extract the rotation (in degrees) to properly orient the video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String ROTATION = "android.media.mediarecorder.rotation";

        /**
         * Key to extract the video bitrate from being used
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_BITRATE = "android.media.mediarecorder.video-bitrate";

        /**
         * Key to extract the value for how often video iframes are generated
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_IFRAME_INTERVAL = "android.media.mediarecorder.video-iframe-interval";

        /**
         * Key to extract the video encoding level
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_LEVEL = "android.media.mediarecorder.video-encoder-level";

        /**
         * Key to extract the video encoding profile
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_PROFILE = "android.media.mediarecorder.video-encoder-profile";

        /**
         * Key to extract the recorded video time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the video's timing is in milliseconds.
         */
        public static final String VIDEO_TIMESCALE = "android.media.mediarecorder.video-timescale";

        /**
         * Key to extract the width (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String WIDTH = "android.media.mediarecorder.width";

    }
}

