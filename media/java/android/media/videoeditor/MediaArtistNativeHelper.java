/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.videoeditor;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.videoeditor.VideoEditor.ExportProgressListener;
import android.media.videoeditor.VideoEditor.PreviewProgressListener;
import android.media.videoeditor.VideoEditor.MediaProcessingProgressListener;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

/**
 *This class provide Native methods to be used by MediaArtist {@hide}
 */
class MediaArtistNativeHelper {
    private static final String TAG = "MediaArtistNativeHelper";

    static {
        System.loadLibrary("videoeditor_jni");
    }

    private static final int MAX_THUMBNAIL_PERMITTED = 8;

    public static final int TASK_LOADING_SETTINGS = 1;
    public static final int TASK_ENCODING = 2;

    /**
     *  The resize paint
     */
    private static final Paint sResizePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final VideoEditor mVideoEditor;
    /*
     *  Semaphore to control preview calls
     */
    private final Semaphore mLock;

    private EditSettings mStoryBoardSettings;

    private String mOutputFilename;

    private PreviewClipProperties mClipProperties = null;

    private EditSettings mPreviewEditSettings;

    private AudioSettings mAudioSettings = null;

    private AudioTrack mAudioTrack = null;

    private boolean mInvalidatePreviewArray = true;

    private boolean mRegenerateAudio = true;

    private String mExportFilename = null;

    private int mProgressToApp;

    private String mRenderPreviewOverlayFile;
    private int mRenderPreviewRenderingMode;

    private boolean mIsFirstProgress;

    private static final String AUDIO_TRACK_PCM_FILE = "AudioPcm.pcm";

    // Processing indication
    public static final int PROCESSING_NONE          = 0;
    public static final int PROCESSING_AUDIO_PCM     = 1;
    public static final int PROCESSING_TRANSITION    = 2;
    public static final int PROCESSING_KENBURNS      = 3;
    public static final int PROCESSING_INTERMEDIATE1 = 11;
    public static final int PROCESSING_INTERMEDIATE2 = 12;
    public static final int PROCESSING_INTERMEDIATE3 = 13;
    public static final int PROCESSING_EXPORT        = 20;

    private int mProcessingState;
    private Object mProcessingObject;
    private PreviewProgressListener mPreviewProgressListener;
    private ExportProgressListener mExportProgressListener;
    private ExtractAudioWaveformProgressListener mExtractAudioWaveformProgressListener;
    private MediaProcessingProgressListener mMediaProcessingProgressListener;
    private final String mProjectPath;

    private long mPreviewProgress;

    private String mAudioTrackPCMFilePath;

    private int mTotalClips = 0;

    private boolean mErrorFlagSet = false;

    @SuppressWarnings("unused")
    private int mManualEditContext;

    /* Listeners */

    /**
     * Interface definition for a listener to be invoked when there is an update
     * in a running task.
     */
    public interface OnProgressUpdateListener {
        /**
         * Called when there is an update.
         *
         * @param taskId id of the task reporting an update.
         * @param progress progress of the task [0..100].
         * @see BasicEdit#TASK_ENCODING
         */
        public void OnProgressUpdate(int taskId, int progress);
    }

    /** Defines the version. */
    public final class Version {

        /** Major version number */
        public int major;

        /** Minor version number */
        public int minor;

        /** Revision number */
        public int revision;

        /** VIDEOEDITOR major version number */
        private static final int VIDEOEDITOR_MAJOR_VERSION = 0;

        /** VIDEOEDITOR minor version number */
        private static final int VIDEOEDITOR_MINOR_VERSION = 0;

        /** VIDEOEDITOR revision number */
        private static final int VIDEOEDITOR_REVISION_VERSION = 1;

        /** Method which returns the current VIDEOEDITOR version */
        public Version getVersion() {
            Version version = new Version();

            version.major = Version.VIDEOEDITOR_MAJOR_VERSION;
            version.minor = Version.VIDEOEDITOR_MINOR_VERSION;
            version.revision = Version.VIDEOEDITOR_REVISION_VERSION;

            return version;
        }
    }

    /**
     * Defines output audio formats.
     */
    public final class AudioFormat {
        /** No audio present in output clip. Used to generate video only clip */
        public static final int NO_AUDIO = 0;

        /** AMR Narrow Band. */
        public static final int AMR_NB = 1;

        /** Advanced Audio Coding (AAC). */
        public static final int AAC = 2;

        /** Advanced Audio Codec Plus (HE-AAC v1). */
        public static final int AAC_PLUS = 3;

        /** Advanced Audio Codec Plus (HE-AAC v2). */
        public static final int ENHANCED_AAC_PLUS = 4;

        /** MPEG layer 3 (MP3). */
        public static final int MP3 = 5;

        /** Enhanced Variable RateCodec (EVRC). */
        public static final int EVRC = 6;

        /** PCM (PCM). */
        public static final int PCM = 7;

        /** No transcoding. Output audio format is same as input audio format */
        public static final int NULL_AUDIO = 254;

        /** Unsupported audio format. */
        public static final int UNSUPPORTED_AUDIO = 255;
    }

    /**
     * Defines audio sampling frequencies.
     */
    public final class AudioSamplingFrequency {
        /**
         * Default sampling frequency. Uses the default frequency for a specific
         * audio format. For AAC the only supported (and thus default) sampling
         * frequency is 16 kHz. For this audio format the sampling frequency in
         * the OutputParams.
         **/
        public static final int FREQ_DEFAULT = 0;

        /** Audio sampling frequency of 8000 Hz. */
        public static final int FREQ_8000 = 8000;

        /** Audio sampling frequency of 11025 Hz. */
        public static final int FREQ_11025 = 11025;

        /** Audio sampling frequency of 12000 Hz. */
        public static final int FREQ_12000 = 12000;

        /** Audio sampling frequency of 16000 Hz. */
        public static final int FREQ_16000 = 16000;

        /** Audio sampling frequency of 22050 Hz. */
        public static final int FREQ_22050 = 22050;

        /** Audio sampling frequency of 24000 Hz. */
        public static final int FREQ_24000 = 24000;

        /** Audio sampling frequency of 32000 Hz. */
        public static final int FREQ_32000 = 32000;

        /** Audio sampling frequency of 44100 Hz. */
        public static final int FREQ_44100 = 44100;

        /** Audio sampling frequency of 48000 Hz. Not available for output file. */
        public static final int FREQ_48000 = 48000;
    }

    /**
     * Defines the supported fixed audio and video bitrates. These values are
     * for output audio video only.
     */
    public final class Bitrate {
        /** Variable bitrate. Means no bitrate regulation */
        public static final int VARIABLE = -1;

        /** An undefined bitrate. */
        public static final int UNDEFINED = 0;

        /** A bitrate of 9.2 kbits/s. */
        public static final int BR_9_2_KBPS = 9200;

        /** A bitrate of 12.2 kbits/s. */
        public static final int BR_12_2_KBPS = 12200;

        /** A bitrate of 16 kbits/s. */
        public static final int BR_16_KBPS = 16000;

        /** A bitrate of 24 kbits/s. */
        public static final int BR_24_KBPS = 24000;

        /** A bitrate of 32 kbits/s. */
        public static final int BR_32_KBPS = 32000;

        /** A bitrate of 48 kbits/s. */
        public static final int BR_48_KBPS = 48000;

        /** A bitrate of 64 kbits/s. */
        public static final int BR_64_KBPS = 64000;

        /** A bitrate of 96 kbits/s. */
        public static final int BR_96_KBPS = 96000;

        /** A bitrate of 128 kbits/s. */
        public static final int BR_128_KBPS = 128000;

        /** A bitrate of 192 kbits/s. */
        public static final int BR_192_KBPS = 192000;

        /** A bitrate of 256 kbits/s. */
        public static final int BR_256_KBPS = 256000;

        /** A bitrate of 288 kbits/s. */
        public static final int BR_288_KBPS = 288000;

        /** A bitrate of 384 kbits/s. */
        public static final int BR_384_KBPS = 384000;

        /** A bitrate of 512 kbits/s. */
        public static final int BR_512_KBPS = 512000;

        /** A bitrate of 800 kbits/s. */
        public static final int BR_800_KBPS = 800000;

        /** A bitrate of 2 Mbits/s. */
        public static final int BR_2_MBPS = 2000000;

        /** A bitrate of 5 Mbits/s. */
        public static final int BR_5_MBPS = 5000000;

        /** A bitrate of 8 Mbits/s. */
        public static final int BR_8_MBPS = 8000000;
    }

    /**
     * Defines all supported file types.
     */
    public final class FileType {
        /** 3GPP file type. */
        public static final int THREE_GPP = 0;

        /** MP4 file type. */
        public static final int MP4 = 1;

        /** AMR file type. */
        public static final int AMR = 2;

        /** MP3 audio file type. */
        public static final int MP3 = 3;

        /** PCM audio file type. */
        public static final int PCM = 4;

        /** JPEG image file type. */
        public static final int JPG = 5;

        /** GIF image file type. */
        public static final int GIF = 7;

        /** PNG image file type. */
        public static final int PNG = 8;

        /** M4V file type. */
        public static final int M4V = 10;

        /** Unsupported file type. */
        public static final int UNSUPPORTED = 255;
    }

    /**
     * Defines rendering types. Rendering can only be applied to files
     * containing video streams.
     **/
    public final class MediaRendering {
        /**
         * Resize to fit the output video with changing the aspect ratio if
         * needed.
         */
        public static final int RESIZING = 0;

        /**
         * Crop the input video to fit it with the output video resolution.
         **/
        public static final int CROPPING = 1;

        /**
         * Resize to fit the output video resolution but maintain the aspect
         * ratio. This framing type adds black borders if needed.
         */
        public static final int BLACK_BORDERS = 2;
    }

    /**
     * Defines the results.
     */
    public final class Result {
        /** No error. result OK */
        public static final int NO_ERROR = 0;

        /** File not found */
        public static final int ERR_FILE_NOT_FOUND = 1;

        /**
         * In case of UTF8 conversion, the size of the converted path will be
         * more than the corresponding allocated buffer.
         */
        public static final int ERR_BUFFER_OUT_TOO_SMALL = 2;

        /** Invalid file type. */
        public static final int ERR_INVALID_FILE_TYPE = 3;

        /** Invalid effect kind. */
        public static final int ERR_INVALID_EFFECT_KIND = 4;

        /** Invalid video effect. */
        public static final int ERR_INVALID_VIDEO_EFFECT_TYPE = 5;

        /** Invalid audio effect. */
        public static final int ERR_INVALID_AUDIO_EFFECT_TYPE = 6;

        /** Invalid video transition. */
        public static final int ERR_INVALID_VIDEO_TRANSITION_TYPE = 7;

        /** Invalid audio transition. */
        public static final int ERR_INVALID_AUDIO_TRANSITION_TYPE = 8;

        /** Invalid encoding frame rate. */
        public static final int ERR_INVALID_VIDEO_ENCODING_FRAME_RATE = 9;

        /** External effect is called but this function is not set. */
        public static final int ERR_EXTERNAL_EFFECT_NULL = 10;

        /** External transition is called but this function is not set. */
        public static final int ERR_EXTERNAL_TRANSITION_NULL = 11;

        /** Begin time cut is larger than the video clip duration. */
        public static final int ERR_BEGIN_CUT_LARGER_THAN_DURATION = 12;

        /** Begin cut time is larger or equal than end cut. */
        public static final int ERR_BEGIN_CUT_LARGER_THAN_END_CUT = 13;

        /** Two consecutive transitions are overlapping on one clip. */
        public static final int ERR_OVERLAPPING_TRANSITIONS = 14;

        /** Internal error, type size mismatch. */
        public static final int ERR_ANALYSIS_DATA_SIZE_TOO_SMALL = 15;

        /** An input 3GPP file is invalid/corrupted. */
        public static final int ERR_INVALID_3GPP_FILE = 16;

        /** A file contains an unsupported video format. */
        public static final int ERR_UNSUPPORTED_INPUT_VIDEO_FORMAT = 17;

        /** A file contains an unsupported audio format. */
        public static final int ERR_UNSUPPORTED_INPUT_AUDIO_FORMAT = 18;

        /** A file format is not supported. */
        public static final int ERR_AMR_EDITING_UNSUPPORTED = 19;

        /** An input clip has an unexpectedly large Video AU. */
        public static final int ERR_INPUT_VIDEO_AU_TOO_LARGE = 20;

        /** An input clip has an unexpectedly large Audio AU. */
        public static final int ERR_INPUT_AUDIO_AU_TOO_LARGE = 21;

        /** An input clip has a corrupted Audio AU. */
        public static final int ERR_INPUT_AUDIO_CORRUPTED_AU = 22;

        /** The video encoder encountered an Access Unit error. */
        public static final int ERR_ENCODER_ACCES_UNIT_ERROR = 23;

        /** Unsupported video format for Video Editing. */
        public static final int ERR_EDITING_UNSUPPORTED_VIDEO_FORMAT = 24;

        /** Unsupported H263 profile for Video Editing. */
        public static final int ERR_EDITING_UNSUPPORTED_H263_PROFILE = 25;

        /** Unsupported MPEG-4 profile for Video Editing. */
        public static final int ERR_EDITING_UNSUPPORTED_MPEG4_PROFILE = 26;

        /** Unsupported MPEG-4 RVLC tool for Video Editing. */
        public static final int ERR_EDITING_UNSUPPORTED_MPEG4_RVLC = 27;

        /** Unsupported audio format for Video Editing. */
        public static final int ERR_EDITING_UNSUPPORTED_AUDIO_FORMAT = 28;

        /** File contains no supported stream. */
        public static final int ERR_EDITING_NO_SUPPORTED_STREAM_IN_FILE = 29;

        /** File contains no video stream or an unsupported video stream. */
        public static final int ERR_EDITING_NO_SUPPORTED_VIDEO_STREAM_IN_FILE = 30;

        /** Internal error, clip analysis version mismatch. */
        public static final int ERR_INVALID_CLIP_ANALYSIS_VERSION = 31;

        /**
         * At least one of the clip analysis has been generated on another
         * platform (WIN32, ARM, etc.).
         */
        public static final int ERR_INVALID_CLIP_ANALYSIS_PLATFORM = 32;

        /** Clips don't have the same video format (H263 or MPEG4). */
        public static final int ERR_INCOMPATIBLE_VIDEO_FORMAT = 33;

        /** Clips don't have the same frame size. */
        public static final int ERR_INCOMPATIBLE_VIDEO_FRAME_SIZE = 34;

        /** Clips don't have the same MPEG-4 time scale. */
        public static final int ERR_INCOMPATIBLE_VIDEO_TIME_SCALE = 35;

        /** Clips don't have the same use of MPEG-4 data partitioning. */
        public static final int ERR_INCOMPATIBLE_VIDEO_DATA_PARTITIONING = 36;

        /** MP3 clips can't be assembled. */
        public static final int ERR_UNSUPPORTED_MP3_ASSEMBLY = 37;

        /**
         * The input 3GPP file does not contain any supported audio or video
         * track.
         */
        public static final int ERR_NO_SUPPORTED_STREAM_IN_FILE = 38;

        /**
         * The Volume of the added audio track (AddVolume) must be strictly
         * superior than zero.
         */
        public static final int ERR_ADDVOLUME_EQUALS_ZERO = 39;

        /**
         * The time at which an audio track is added can't be higher than the
         * input video track duration..
         */
        public static final int ERR_ADDCTS_HIGHER_THAN_VIDEO_DURATION = 40;

        /** The audio track file format setting is undefined. */
        public static final int ERR_UNDEFINED_AUDIO_TRACK_FILE_FORMAT = 41;

        /** The added audio track stream has an unsupported format. */
        public static final int ERR_UNSUPPORTED_ADDED_AUDIO_STREAM = 42;

        /** The audio mixing feature doesn't support the audio track type. */
        public static final int ERR_AUDIO_MIXING_UNSUPPORTED = 43;

        /** The audio mixing feature doesn't support MP3 audio tracks. */
        public static final int ERR_AUDIO_MIXING_MP3_UNSUPPORTED = 44;

        /**
         * An added audio track limits the available features: uiAddCts must be
         * 0 and bRemoveOriginal must be true.
         */
        public static final int ERR_FEATURE_UNSUPPORTED_WITH_AUDIO_TRACK = 45;

        /**
         * An added audio track limits the available features: uiAddCts must be
         * 0 and bRemoveOriginal must be true.
         */
        public static final int ERR_FEATURE_UNSUPPORTED_WITH_AAC = 46;

        /** Input audio track is not of a type that can be mixed with output. */
        public static final int ERR_AUDIO_CANNOT_BE_MIXED = 47;

        /** Input audio track is not AMR-NB, so it can't be mixed with output. */
        public static final int ERR_ONLY_AMRNB_INPUT_CAN_BE_MIXED = 48;

        /**
         * An added EVRC audio track limit the available features: uiAddCts must
         * be 0 and bRemoveOriginal must be true.
         */
        public static final int ERR_FEATURE_UNSUPPORTED_WITH_EVRC = 49;

        /** H263 profiles other than 0 are not supported. */
        public static final int ERR_H263_PROFILE_NOT_SUPPORTED = 51;

        /** File contains no video stream or an unsupported video stream. */
        public static final int ERR_NO_SUPPORTED_VIDEO_STREAM_IN_FILE = 52;

        /** Transcoding of the input file(s) is necessary. */
        public static final int WAR_TRANSCODING_NECESSARY = 53;

        /**
         * The size of the output file will exceed the maximum configured value.
         */
        public static final int WAR_MAX_OUTPUT_SIZE_EXCEEDED = 54;

        /** The time scale is too big. */
        public static final int WAR_TIMESCALE_TOO_BIG = 55;

        /** The year is out of range */
        public static final int ERR_CLOCK_BAD_REF_YEAR = 56;

        /** The directory could not be opened */
        public static final int ERR_DIR_OPEN_FAILED = 57;

        /** The directory could not be read */
        public static final int ERR_DIR_READ_FAILED = 58;

        /** There are no more entries in the current directory */
        public static final int ERR_DIR_NO_MORE_ENTRY = 59;

        /** The input parameter/s has error */
        public static final int ERR_PARAMETER = 60;

        /** There is a state machine error */
        public static final int ERR_STATE = 61;

        /** Memory allocation failed */
        public static final int ERR_ALLOC = 62;

        /** Context is invalid */
        public static final int ERR_BAD_CONTEXT = 63;

        /** Context creation failed */
        public static final int ERR_CONTEXT_FAILED = 64;

        /** Invalid stream ID */
        public static final int ERR_BAD_STREAM_ID = 65;

        /** Invalid option ID */
        public static final int ERR_BAD_OPTION_ID = 66;

        /** The option is write only */
        public static final int ERR_WRITE_ONLY = 67;

        /** The option is read only */
        public static final int ERR_READ_ONLY = 68;

        /** The feature is not implemented in this version */
        public static final int ERR_NOT_IMPLEMENTED = 69;

        /** The media type is not supported */
        public static final int ERR_UNSUPPORTED_MEDIA_TYPE = 70;

        /** No data to be encoded */
        public static final int WAR_NO_DATA_YET = 71;

        /** No data to be decoded */
        public static final int WAR_NO_MORE_STREAM = 72;

        /** Time stamp is invalid */
        public static final int WAR_INVALID_TIME = 73;

        /** No more data to be decoded */
        public static final int WAR_NO_MORE_AU = 74;

        /** Semaphore timed out */
        public static final int WAR_TIME_OUT = 75;

        /** Memory buffer is full */
        public static final int WAR_BUFFER_FULL = 76;

        /** Server has asked for redirection */
        public static final int WAR_REDIRECT = 77;

        /** Too many streams in input */
        public static final int WAR_TOO_MUCH_STREAMS = 78;

        /** The file cannot be opened/ written into as it is locked */
        public static final int ERR_FILE_LOCKED = 79;

        /** The file access mode is invalid */
        public static final int ERR_FILE_BAD_MODE_ACCESS = 80;

        /** The file pointer points to an invalid location */
        public static final int ERR_FILE_INVALID_POSITION = 81;

        /** Invalid string */
        public static final int ERR_STR_BAD_STRING = 94;

        /** The input string cannot be converted */
        public static final int ERR_STR_CONV_FAILED = 95;

        /** The string size is too large */
        public static final int ERR_STR_OVERFLOW = 96;

        /** Bad string arguments */
        public static final int ERR_STR_BAD_ARGS = 97;

        /** The string value is larger than maximum size allowed */
        public static final int WAR_STR_OVERFLOW = 98;

        /** The string value is not present in this comparison operation */
        public static final int WAR_STR_NOT_FOUND = 99;

        /** The thread is not started */
        public static final int ERR_THREAD_NOT_STARTED = 100;

        /** Trancoding done warning */
        public static final int WAR_TRANSCODING_DONE = 101;

        /** Unsupported mediatype */
        public static final int WAR_MEDIATYPE_NOT_SUPPORTED = 102;

        /** Input file contains invalid/unsupported streams */
        public static final int ERR_INPUT_FILE_CONTAINS_NO_SUPPORTED_STREAM = 103;

        /** Invalid input file */
        public static final int ERR_INVALID_INPUT_FILE = 104;

        /** Invalid output video format */
        public static final int ERR_UNDEFINED_OUTPUT_VIDEO_FORMAT = 105;

        /** Invalid output video frame size */
        public static final int ERR_UNDEFINED_OUTPUT_VIDEO_FRAME_SIZE = 106;

        /** Invalid output video frame rate */
        public static final int ERR_UNDEFINED_OUTPUT_VIDEO_FRAME_RATE = 107;

        /** Invalid output audio format */
        public static final int ERR_UNDEFINED_OUTPUT_AUDIO_FORMAT = 108;

        /** Invalid video frame size for H.263 */
        public static final int ERR_INVALID_VIDEO_FRAME_SIZE_FOR_H263 = 109;

        /** Invalid video frame rate for H.263 */
        public static final int ERR_INVALID_VIDEO_FRAME_RATE_FOR_H263 = 110;

        /** invalid playback duration */
        public static final int ERR_DURATION_IS_NULL = 111;

        /** Invalid H.263 profile in file */
        public static final int ERR_H263_FORBIDDEN_IN_MP4_FILE = 112;

        /** Invalid AAC sampling frequency */
        public static final int ERR_INVALID_AAC_SAMPLING_FREQUENCY = 113;

        /** Audio conversion failure */
        public static final int ERR_AUDIO_CONVERSION_FAILED = 114;

        /** Invalid trim start and end times */
        public static final int ERR_BEGIN_CUT_EQUALS_END_CUT = 115;

        /** End time smaller than start time for trim */
        public static final int ERR_END_CUT_SMALLER_THAN_BEGIN_CUT = 116;

        /** Output file size is small */
        public static final int ERR_MAXFILESIZE_TOO_SMALL = 117;

        /** Output video bitrate is too low */
        public static final int ERR_VIDEOBITRATE_TOO_LOW = 118;

        /** Output audio bitrate is too low */
        public static final int ERR_AUDIOBITRATE_TOO_LOW = 119;

        /** Output video bitrate is too high */
        public static final int ERR_VIDEOBITRATE_TOO_HIGH = 120;

        /** Output audio bitrate is too high */
        public static final int ERR_AUDIOBITRATE_TOO_HIGH = 121;

        /** Output file size is too small */
        public static final int ERR_OUTPUT_FILE_SIZE_TOO_SMALL = 122;

        /** Unknown stream type */
        public static final int ERR_READER_UNKNOWN_STREAM_TYPE = 123;

        /** Invalid metadata in input stream */
        public static final int WAR_READER_NO_METADATA = 124;

        /** Invalid file reader info warning */
        public static final int WAR_READER_INFORMATION_NOT_PRESENT = 125;

        /** Warning to indicate the the writer is being stopped */
        public static final int WAR_WRITER_STOP_REQ = 131;

        /** Video decoder failed to provide frame for transcoding */
        public static final int WAR_VIDEORENDERER_NO_NEW_FRAME = 132;

        /** Video deblocking filter is not implemented */
        public static final int WAR_DEBLOCKING_FILTER_NOT_IMPLEMENTED = 133;

        /** H.263 decoder profile not supported */
        public static final int ERR_DECODER_H263_PROFILE_NOT_SUPPORTED = 134;

        /** The input file contains unsupported H.263 profile */
        public static final int ERR_DECODER_H263_NOT_BASELINE = 135;

        /** There is no more space to store the output file */
        public static final int ERR_NOMORE_SPACE_FOR_FILE = 136;

        /** Internal error. */
        public static final int ERR_INTERNAL = 255;
    }

    /**
     * Defines output video formats.
     */
    public final class VideoFormat {
        /** No video present in output clip. Used to generate audio only clip */
        public static final int NO_VIDEO = 0;

        /** H263 baseline format. */
        public static final int H263 = 1;

        /** MPEG4 video Simple Profile format. */
        public static final int MPEG4 = 2;

        /** MPEG4 video Simple Profile format with support for EMP. */
        public static final int MPEG4_EMP = 3;

        /** H264 video */
        public static final int H264 = 4;

        /** No transcoding. Output video format is same as input video format */
        public static final int NULL_VIDEO = 254;

        /** Unsupported video format. */
        public static final int UNSUPPORTED = 255;
    }

    /** Defines video profiles and levels. */
    public final class VideoProfile {
        /** H263, Profile 0, Level 10. */
        public static final int H263_PROFILE_0_LEVEL_10 = MediaProperties.H263_PROFILE_0_LEVEL_10;

        /** H263, Profile 0, Level 20. */
        public static final int H263_PROFILE_0_LEVEL_20 = MediaProperties.H263_PROFILE_0_LEVEL_20;

        /** H263, Profile 0, Level 30. */
        public static final int H263_PROFILE_0_LEVEL_30 = MediaProperties.H263_PROFILE_0_LEVEL_30;

        /** H263, Profile 0, Level 40. */
        public static final int H263_PROFILE_0_LEVEL_40 = MediaProperties.H263_PROFILE_0_LEVEL_40;

        /** H263, Profile 0, Level 45. */
        public static final int H263_PROFILE_0_LEVEL_45 = MediaProperties.H263_PROFILE_0_LEVEL_45;

        /** MPEG4, Simple Profile, Level 0. */
        public static final int MPEG4_SP_LEVEL_0 = MediaProperties.MPEG4_SP_LEVEL_0;

        /** MPEG4, Simple Profile, Level 0B. */
        public static final int MPEG4_SP_LEVEL_0B = MediaProperties.MPEG4_SP_LEVEL_0B;

        /** MPEG4, Simple Profile, Level 1. */
        public static final int MPEG4_SP_LEVEL_1 = MediaProperties.MPEG4_SP_LEVEL_1;

        /** MPEG4, Simple Profile, Level 2. */
        public static final int MPEG4_SP_LEVEL_2 = MediaProperties.MPEG4_SP_LEVEL_2;

        /** MPEG4, Simple Profile, Level 3. */
        public static final int MPEG4_SP_LEVEL_3 = MediaProperties.MPEG4_SP_LEVEL_3;

        /** MPEG4, Simple Profile, Level 4A. */
        public static final int MPEG4_SP_LEVEL_4A = MediaProperties.MPEG4_SP_LEVEL_4A;

        /** MPEG4, Simple Profile, Level 0. */
        public static final int MPEG4_SP_LEVEL_5 = MediaProperties.MPEG4_SP_LEVEL_5;

        /** H264, Profile 0, Level 1. */
        public static final int H264_PROFILE_0_LEVEL_1 = MediaProperties.H264_PROFILE_0_LEVEL_1;

        /** H264, Profile 0, Level 1b. */
        public static final int H264_PROFILE_0_LEVEL_1b = MediaProperties.H264_PROFILE_0_LEVEL_1B;

        /** H264, Profile 0, Level 1.1 */
        public static final int H264_PROFILE_0_LEVEL_1_1 = MediaProperties.H264_PROFILE_0_LEVEL_1_1;

        /** H264, Profile 0, Level 1.2 */
        public static final int H264_PROFILE_0_LEVEL_1_2 = MediaProperties.H264_PROFILE_0_LEVEL_1_2;

        /** H264, Profile 0, Level 1.3 */
        public static final int H264_PROFILE_0_LEVEL_1_3 = MediaProperties.H264_PROFILE_0_LEVEL_1_3;

        /** H264, Profile 0, Level 2. */
        public static final int H264_PROFILE_0_LEVEL_2 = MediaProperties.H264_PROFILE_0_LEVEL_2;

        /** H264, Profile 0, Level 2.1 */
        public static final int H264_PROFILE_0_LEVEL_2_1 = MediaProperties.H264_PROFILE_0_LEVEL_2_1;

        /** H264, Profile 0, Level 2.2 */
        public static final int H264_PROFILE_0_LEVEL_2_2 = MediaProperties.H264_PROFILE_0_LEVEL_2_2;

        /** H264, Profile 0, Level 3. */
        public static final int H264_PROFILE_0_LEVEL_3 = MediaProperties.H264_PROFILE_0_LEVEL_3;

        /** H264, Profile 0, Level 3.1 */
        public static final int H264_PROFILE_0_LEVEL_3_1 = MediaProperties.H264_PROFILE_0_LEVEL_3_1;

        /** H264, Profile 0, Level 3.2 */
        public static final int H264_PROFILE_0_LEVEL_3_2 = MediaProperties.H264_PROFILE_0_LEVEL_3_2;

        /** H264, Profile 0, Level 4. */
        public static final int H264_PROFILE_0_LEVEL_4 = MediaProperties.H264_PROFILE_0_LEVEL_4;

        /** H264, Profile 0, Level 4.1 */
        public static final int H264_PROFILE_0_LEVEL_4_1 = MediaProperties.H264_PROFILE_0_LEVEL_4_1;

        /** H264, Profile 0, Level 4.2 */
        public static final int H264_PROFILE_0_LEVEL_4_2 = MediaProperties.H264_PROFILE_0_LEVEL_4_2;

        /** H264, Profile 0, Level 5. */
        public static final int H264_PROFILE_0_LEVEL_5 = MediaProperties.H264_PROFILE_0_LEVEL_5;

        /** H264, Profile 0, Level 5.1 */
        public static final int H264_PROFILE_0_LEVEL_5_1 = MediaProperties.H264_PROFILE_0_LEVEL_5_1;

        /** Profile out of range. */
        public static final int OUT_OF_RANGE = MediaProperties.UNSUPPORTED_PROFILE_LEVEL;
    }

    /** Defines video frame sizes. */
    public final class VideoFrameSize {

        public static final int SIZE_UNDEFINED = -1;

        /** SQCIF 128 x 96 pixels. */
        public static final int SQCIF = 0;

        /** QQVGA 160 x 120 pixels. */
        public static final int QQVGA = 1;

        /** QCIF 176 x 144 pixels. */
        public static final int QCIF = 2;

        /** QVGA 320 x 240 pixels. */
        public static final int QVGA = 3;

        /** CIF 352 x 288 pixels. */
        public static final int CIF = 4;

        /** VGA 640 x 480 pixels. */
        public static final int VGA = 5;

        /** WVGA 800 X 480 pixels */
        public static final int WVGA = 6;

        /** NTSC 720 X 480 pixels */
        public static final int NTSC = 7;

        /** 640 x 360 */
        public static final int nHD = 8;

        /** 854 x 480 */
        public static final int WVGA16x9 = 9;

        /** 720p 1280 X 720 */
        public static final int V720p = 10;

        /** W720p 1080 x 720 */
        public static final int W720p = 11;

        /** S720p 960 x 720 */
        public static final int S720p = 12;

        /** 1080p 1920 x 1080 */
        public static final int V1080p = 13;
    }

    /**
     * Defines output video frame rates.
     */
    public final class VideoFrameRate {
        /** Frame rate of 5 frames per second. */
        public static final int FR_5_FPS = 0;

        /** Frame rate of 7.5 frames per second. */
        public static final int FR_7_5_FPS = 1;

        /** Frame rate of 10 frames per second. */
        public static final int FR_10_FPS = 2;

        /** Frame rate of 12.5 frames per second. */
        public static final int FR_12_5_FPS = 3;

        /** Frame rate of 15 frames per second. */
        public static final int FR_15_FPS = 4;

        /** Frame rate of 20 frames per second. */
        public static final int FR_20_FPS = 5;

        /** Frame rate of 25 frames per second. */
        public static final int FR_25_FPS = 6;

        /** Frame rate of 30 frames per second. */
        public static final int FR_30_FPS = 7;
    }

    /**
     * Defines Video Effect Types.
     */
    public static class VideoEffect {

        public static final int NONE = 0;

        public static final int FADE_FROM_BLACK = 8;

        public static final int FADE_TO_BLACK = 16;

        public static final int EXTERNAL = 256;

        public static final int BLACK_AND_WHITE = 257;

        public static final int PINK = 258;

        public static final int GREEN = 259;

        public static final int SEPIA = 260;

        public static final int NEGATIVE = 261;

        public static final int FRAMING = 262;

        public static final int TEXT = 263;

        public static final int ZOOM_IN = 264;

        public static final int ZOOM_OUT = 265;

        public static final int FIFTIES = 266;

        public static final int COLORRGB16 = 267;

        public static final int GRADIENT = 268;
    }

    /**
     * Defines the video transitions.
     */
    public static class VideoTransition {
        /** No transition */
        public static final int NONE = 0;

        /** Cross fade transition */
        public static final int CROSS_FADE = 1;

        /** External transition. Currently not available. */
        public static final int EXTERNAL = 256;

        /** AlphaMagic transition. */
        public static final int ALPHA_MAGIC = 257;

        /** Slide transition. */
        public static final int SLIDE_TRANSITION = 258;

        /** Fade to black transition. */
        public static final int FADE_BLACK = 259;
    }

    /**
     * Defines settings for the AlphaMagic transition
     */
    public static class AlphaMagicSettings {
        /** Name of the alpha file (JPEG file). */
        public String file;

        /** Blending percentage [0..100] 0 = no blending. */
        public int blendingPercent;

        /** Invert the default rotation direction of the AlphaMagic effect. */
        public boolean invertRotation;

        public int rgbWidth;
        public int rgbHeight;
    }

    /** Defines the direction of the Slide transition. */
    public static final class SlideDirection {

        /** Right out left in. */
        public static final int RIGHT_OUT_LEFT_IN = 0;

        /** Left out right in. */
        public static final int LEFT_OUT_RIGTH_IN = 1;

        /** Top out bottom in. */
        public static final int TOP_OUT_BOTTOM_IN = 2;

        /** Bottom out top in */
        public static final int BOTTOM_OUT_TOP_IN = 3;
    }

    /** Defines the Slide transition settings. */
    public static class SlideTransitionSettings {
        /**
         * Direction of the slide transition. See {@link SlideDirection
         * SlideDirection} for valid values.
         */
        public int direction;
    }

    /**
     * Defines the settings of a single clip.
     */
    public static class ClipSettings {

        /**
         * The path to the clip file.
         * <p>
         * File format of the clip, it can be:
         * <ul>
         * <li>3GP file containing MPEG4/H263/H264 video and AAC/AMR audio
         * <li>JPG file
         * </ul>
         */

        public String clipPath;

        /**
         * The path of the decoded file. This is used only for image files.
         */
        public String clipDecodedPath;

        /**
         * The path of the Original file. This is used only for image files.
         */
        public String clipOriginalPath;

        /**
         * File type of the clip. See {@link FileType FileType} for valid
         * values.
         */
        public int fileType;

        /** Begin of the cut in the clip in milliseconds. */
        public int beginCutTime;

        /**
         * End of the cut in the clip in milliseconds. Set both
         * <code>beginCutTime</code> and <code>endCutTime</code> to
         * <code>0</code> to get the full length of the clip without a cut. In
         * case of JPG clip, this is the duration of the JPEG file.
         */
        public int endCutTime;

        /**
         * Begin of the cut in the clip in percentage of the file duration.
         */
        public int beginCutPercent;

        /**
         * End of the cut in the clip in percentage of the file duration. Set
         * both <code>beginCutPercent</code> and <code>endCutPercent</code> to
         * <code>0</code> to get the full length of the clip without a cut.
         */
        public int endCutPercent;

        /** Enable panning and zooming. */
        public boolean panZoomEnabled;

        /** Zoom percentage at start of clip. 0 = no zoom, 100 = full zoom */
        public int panZoomPercentStart;

        /** Top left X coordinate at start of clip. */
        public int panZoomTopLeftXStart;

        /** Top left Y coordinate at start of clip. */
        public int panZoomTopLeftYStart;

        /** Zoom percentage at start of clip. 0 = no zoom, 100 = full zoom */
        public int panZoomPercentEnd;

        /** Top left X coordinate at end of clip. */
        public int panZoomTopLeftXEnd;

        /** Top left Y coordinate at end of clip. */
        public int panZoomTopLeftYEnd;

        /**
         * Set The media rendering. See {@link MediaRendering MediaRendering}
         * for valid values.
         */
        public int mediaRendering;

        /**
         * RGB width and Height
         */
         public int rgbWidth;
         public int rgbHeight;
    }

    /**
     * Defines settings for a transition.
     */
    public static class TransitionSettings {

        /** Duration of the transition in msec. */
        public int duration;

        /**
         * Transition type for video. See {@link VideoTransition
         * VideoTransition} for valid values.
         */
        public int videoTransitionType;

        /**
         * Transition type for audio. See {@link AudioTransition
         * AudioTransition} for valid values.
         */
        public int audioTransitionType;

        /**
         * Transition behaviour. See {@link TransitionBehaviour
         * TransitionBehaviour} for valid values.
         */
        public int transitionBehaviour;

        /**
         * Settings for AlphaMagic transition. Only needs to be set if
         * <code>videoTransitionType</code> is set to
         * <code>VideoTransition.ALPHA_MAGIC</code>. See
         * {@link AlphaMagicSettings AlphaMagicSettings}.
         */
        public AlphaMagicSettings alphaSettings;

        /**
         * Settings for the Slide transition. See
         * {@link SlideTransitionSettings SlideTransitionSettings}.
         */
        public SlideTransitionSettings slideSettings;
    }

    public static final class AudioTransition {
        /** No audio transition. */
        public static final int NONE = 0;

        /** Cross-fade audio transition. */
        public static final int CROSS_FADE = 1;
    }

    /**
     * Defines transition behaviors.
     */
    public static final class TransitionBehaviour {

        /** The transition uses an increasing speed. */
        public static final int SPEED_UP = 0;

        /** The transition uses a linear (constant) speed. */
        public static final int LINEAR = 1;

        /** The transition uses a decreasing speed. */
        public static final int SPEED_DOWN = 2;

        /**
         * The transition uses a constant speed, but slows down in the middle
         * section.
         */
        public static final int SLOW_MIDDLE = 3;

        /**
         * The transition uses a constant speed, but increases speed in the
         * middle section.
         */
        public static final int FAST_MIDDLE = 4;
    }

    /**
     * Defines settings for the background music.
     */
    public static class BackgroundMusicSettings {

        /** Background music file. */
        public String file;

        /** File type. See {@link FileType FileType} for valid values. */
        public int fileType;

        /**
         * Insertion time in milliseconds, in the output video where the
         * background music must be inserted.
         */
        public long insertionTime;

        /**
         * Volume, as a percentage of the background music track, to use. If
         * this field is set to 100, the background music will replace the audio
         * from the video input file(s).
         */
        public int volumePercent;

        /**
         * Start time in milliseconds in the background muisc file from where
         * the background music should loop. Set both <code>beginLoop</code> and
         * <code>endLoop</code> to <code>0</code> to disable looping.
         */
        public long beginLoop;

        /**
         * End time in milliseconds in the background music file to where the
         * background music should loop. Set both <code>beginLoop</code> and
         * <code>endLoop</code> to <code>0</code> to disable looping.
         */
        public long endLoop;

        public boolean enableDucking;

        public int duckingThreshold;

        public int lowVolume;

        public boolean isLooping;
    }

    /** Defines settings for an effect. */
    public static class AudioEffect {
        /** No audio effect. */
        public static final int NONE = 0;

        /** Fade-in effect. */
        public static final int FADE_IN = 8;

        /** Fade-out effect. */
        public static final int FADE_OUT = 16;
    }

    /** Defines the effect settings. */
    public static class EffectSettings {

        /** Start time of the effect in milliseconds. */
        public int startTime;

        /** Duration of the effect in milliseconds. */
        public int duration;

        /**
         * Video effect type. See {@link VideoEffect VideoEffect} for valid
         * values.
         */
        public int videoEffectType;

        /**
         * Audio effect type. See {@link AudioEffect AudioEffect} for valid
         * values.
         */
        public int audioEffectType;

        /**
         * Start time of the effect in percents of the duration of the clip. A
         * value of 0 percent means start time is from the beginning of the
         * clip.
         */
        public int startPercent;

        /**
         * Duration of the effect in percents of the duration of the clip.
         */
        public int durationPercent;

        /**
         * Framing file.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise
         * this field is ignored.
         */
        public String framingFile;

        /**
         * Framing buffer.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise
         * this field is ignored.
         */
        public int[] framingBuffer;

        /**
         * Bitmap type Can be from RGB_565 (4), ARGB_4444 (5), ARGB_8888 (6);
         **/

        public int bitmapType;

        public int width;

        public int height;

        /**
         * Top left x coordinate. This coordinate is used to set the x
         * coordinate of the picture in the framing file when the framing file
         * is selected. The x coordinate is also used to set the location of the
         * text in the text effect.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FRAMING VideoEffect.FRAMING} or
         * {@link VideoEffect#TEXT VideoEffect.TEXT}. Otherwise this field is
         * ignored.
         */
        public int topLeftX;

        /**
         * Top left y coordinate. This coordinate is used to set the y
         * coordinate of the picture in the framing file when the framing file
         * is selected. The y coordinate is also used to set the location of the
         * text in the text effect.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FRAMING VideoEffect.FRAMING} or
         * {@link VideoEffect#TEXT VideoEffect.TEXT}. Otherwise this field is
         * ignored.
         */
        public int topLeftY;

        /**
         * Should the frame be resized or not. If this field is set to
         * <link>true</code> then the frame size is matched with the output
         * video size.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise
         * this field is ignored.
         */
        public boolean framingResize;

        /**
         * Size to which the framing buffer needs to be resized to
         * This is valid only if framingResize is true
         */
        public int framingScaledSize;
        /**
         * Text to insert in the video.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT}. Otherwise this
         * field is ignored.
         */
        public String text;

        /**
         * Text attributes for the text to insert in the video.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT}. Otherwise this
         * field is ignored. For more details about this field see the
         * integration guide.
         */
        public String textRenderingData;

        /** Width of the text buffer in pixels. */
        public int textBufferWidth;

        /** Height of the text buffer in pixels. */
        public int textBufferHeight;

        /**
         * Processing rate for the fifties effect. A high value (e.g. 30)
         * results in high effect strength.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#FIFTIES VideoEffect.FIFTIES}. Otherwise
         * this field is ignored.
         */
        public int fiftiesFrameRate;

        /**
         * RGB 16 color of the RGB16 and gradient color effect.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#COLORRGB16 VideoEffect.COLORRGB16} or
         * {@link VideoEffect#GRADIENT VideoEffect.GRADIENT}. Otherwise this
         * field is ignored.
         */
        public int rgb16InputColor;

        /**
         * Start alpha blending percentage.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT} or
         * {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise this field
         * is ignored.
         */
        public int alphaBlendingStartPercent;

        /**
         * Middle alpha blending percentage.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT} or
         * {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise this field
         * is ignored.
         */
        public int alphaBlendingMiddlePercent;

        /**
         * End alpha blending percentage.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT} or
         * {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise this field
         * is ignored.
         */
        public int alphaBlendingEndPercent;

        /**
         * Duration, in percentage of effect duration of the fade-in phase.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT} or
         * {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise this field
         * is ignored.
         */
        public int alphaBlendingFadeInTimePercent;

        /**
         * Duration, in percentage of effect duration of the fade-out phase.
         * <p>
         * This field is only used when the field <code>videoEffectType</code>
         * is set to {@link VideoEffect#TEXT VideoEffect.TEXT} or
         * {@link VideoEffect#FRAMING VideoEffect.FRAMING}. Otherwise this field
         * is ignored.
         */
        public int alphaBlendingFadeOutTimePercent;
    }

    /** Defines the clip properties for preview */
    public static class PreviewClips {

        /**
         * The path to the clip file.
         * <p>
         * File format of the clip, it can be:
         * <ul>
         * <li>3GP file containing MPEG4/H263 video and AAC/AMR audio
         * <li>JPG file
         * </ul>
         */

        public String clipPath;

        /**
         * File type of the clip. See {@link FileType FileType} for valid
         * values.
         */
        public int fileType;

        /** Begin of the cut in the clip in milliseconds. */
        public long beginPlayTime;

        public long endPlayTime;

        /**
         * Set The media rendering. See {@link MediaRendering MediaRendering}
         * for valid values.
         */
        public int mediaRendering;

    }

    /** Defines the audio settings. */
    public static class AudioSettings {

        String pFile;

        /** < PCM file path */
        String Id;

        boolean bRemoveOriginal;

        /** < If true, the original audio track is not taken into account */
        int channels;

        /** < Number of channels (1=mono, 2=stereo) of BGM clip */
        int Fs;

        /**
         * < Sampling audio frequency (8000 for amr, 16000 or more for aac) of
         * BGM clip
         */
        int ExtendedFs;

        /** < Extended frequency for AAC+, eAAC+ streams of BGM clip */
        long startMs;

        /** < Time, in milliseconds, at which the added audio track is inserted */
        long beginCutTime;

        long endCutTime;

        int fileType;

        int volume;

        /** < Volume, in percentage, of the added audio track */
        boolean loop;

        /** < Looping on/off > **/

        /** Audio mix and Duck **/
        int ducking_threshold;

        int ducking_lowVolume;

        boolean bInDucking_enable;

        String pcmFilePath;
    }

    /** Encapsulates preview clips and effect settings */
    public static class PreviewSettings {

        public PreviewClips[] previewClipsArray;

        /** The effect settings. */
        public EffectSettings[] effectSettingsArray;

    }

    /** Encapsulates clip properties */
    public static class PreviewClipProperties {

        public Properties[] clipProperties;

    }

    /** Defines the editing settings. */
    public static class EditSettings {

        /**
         * Array of clip settings. There is one <code>clipSetting</code> for
         * each clip.
         */
        public ClipSettings[] clipSettingsArray;

        /**
         * Array of transition settings. If there are n clips (and thus n
         * <code>clipSettings</code>) then there are (n-1) transitions and (n-1)
         * <code>transistionSettings</code> in
         * <code>transistionSettingsArray</code>.
         */
        public TransitionSettings[] transitionSettingsArray;

        /** The effect settings. */
        public EffectSettings[] effectSettingsArray;

        /**
         * Video frame rate of the output clip. See {@link VideoFrameRate
         * VideoFrameRate} for valid values.
         */
        public int videoFrameRate;

        /** Output file name. Must be an absolute path. */
        public String outputFile;

        /**
         * Size of the video frames in the output clip. See
         * {@link VideoFrameSize VideoFrameSize} for valid values.
         */
        public int videoFrameSize;

        /**
         * Format of the video stream in the output clip. See
         * {@link VideoFormat VideoFormat} for valid values.
         */
        public int videoFormat;

        /**
         * Format of the audio stream in the output clip. See
         * {@link AudioFormat AudioFormat} for valid values.
         */
        public int audioFormat;

        /**
         * Sampling frequency of the audio stream in the output clip. See
         * {@link AudioSamplingFrequency AudioSamplingFrequency} for valid
         * values.
         */
        public int audioSamplingFreq;

        /**
         * Maximum file size. By setting this you can set the maximum size of
         * the output clip. Set it to <code>0</code> to let the class ignore
         * this filed.
         */
        public int maxFileSize;

        /**
         * Number of audio channels in output clip. Use <code>0</code> for none,
         * <code>1</code> for mono or <code>2</code> for stereo. None is only
         * allowed when the <code>audioFormat</code> field is set to
         * {@link AudioFormat#NO_AUDIO AudioFormat.NO_AUDIO} or
         * {@link AudioFormat#NULL_AUDIO AudioFormat.NULL_AUDIO} Mono is only
         * allowed when the <code>audioFormat</code> field is set to
         * {@link AudioFormat#AAC AudioFormat.AAC}
         */
        public int audioChannels;

        /** Video bitrate. See {@link Bitrate Bitrate} for valid values. */
        public int videoBitrate;

        /** Audio bitrate. See {@link Bitrate Bitrate} for valid values. */
        public int audioBitrate;

        /**
         * Background music settings. See {@link BackgroundMusicSettings
         * BackgroundMusicSettings} for valid values.
         */
        public BackgroundMusicSettings backgroundMusicSettings;

        public int primaryTrackVolume;

    }

    /**
     * Defines the media properties.
     **/

    public static class Properties {

        /**
         * Duration of the media in milliseconds.
         */

        public int duration;

        /**
         * File type.
         */

        public int fileType;

        /**
         * Video format.
         */

        public int videoFormat;

        /**
         * Duration of the video stream of the media in milliseconds.
         */

        public int videoDuration;

        /**
         * Bitrate of the video stream of the media.
         */

        public int videoBitrate;

        /**
         * Width of the video frames or the width of the still picture in
         * pixels.
         */

        public int width;

        /**
         * Height of the video frames or the height of the still picture in
         * pixels.
         */

        public int height;

        /**
         * Average frame rate of video in the media in frames per second.
         */

        public float averageFrameRate;

        /**
         * Profile and level of the video in the media.
         */

        public int profileAndLevel;

        /**
         * Audio format.
         */

        public int audioFormat;

        /**
         * Duration of the audio stream of the media in milliseconds.
         */

        public int audioDuration;

        /**
         * Bitrate of the audio stream of the media.
         */

        public int audioBitrate;

        /**
         * Number of audio channels in the media.
         */

        public int audioChannels;

        /**
         * Sampling frequency of the audio stream in the media in samples per
         * second.
         */

        public int audioSamplingFrequency;

        /**
         * Volume value of the audio track as percentage.
         */
        public int audioVolumeValue;

        public String Id;
    }

    /**
     * Constructor
     *
     * @param projectPath The path where the VideoEditor stores all files
     *        related to the project
     * @param lock The semaphore
     * @param veObj The video editor reference
     */
    public MediaArtistNativeHelper(String projectPath, Semaphore lock, VideoEditor veObj) {
        mProjectPath = projectPath;
        if (veObj != null) {
            mVideoEditor = veObj;
        } else {
            mVideoEditor = null;
            throw new IllegalArgumentException("video editor object is null");
        }
        if (mStoryBoardSettings == null) {
            mStoryBoardSettings = new EditSettings();
        }

        mLock = lock;

        _init(mProjectPath, "null");
        mAudioTrackPCMFilePath = null;
    }

    /**
     * @return The project path
     */
    String getProjectPath() {
        return mProjectPath;
    }

    /**
     * @return The Audio Track PCM file path
     */
    String getProjectAudioTrackPCMFilePath() {
        return mAudioTrackPCMFilePath;
    }

    /**
     * Invalidates the PCM file
     */
    void invalidatePcmFile() {
        if (mAudioTrackPCMFilePath != null) {
            new File(mAudioTrackPCMFilePath).delete();
            mAudioTrackPCMFilePath = null;
        }
    }

    @SuppressWarnings("unused")
    private void onProgressUpdate(int taskId, int progress) {
        if (mProcessingState == PROCESSING_EXPORT) {
            if (mExportProgressListener != null) {
                if (mProgressToApp < progress) {
                    mExportProgressListener.onProgress(mVideoEditor, mOutputFilename, progress);
                    /* record previous progress */
                    mProgressToApp = progress;
                }
            }
        }
        else {
            // Adapt progress depending on current state
            int actualProgress = 0;
            int action = 0;

            if (mProcessingState == PROCESSING_AUDIO_PCM) {
                action = MediaProcessingProgressListener.ACTION_DECODE;
            } else {
                action = MediaProcessingProgressListener.ACTION_ENCODE;
            }

            switch (mProcessingState) {
                case PROCESSING_AUDIO_PCM:
                    actualProgress = progress;
                    break;
                case PROCESSING_TRANSITION:
                    actualProgress = progress;
                    break;
                case PROCESSING_KENBURNS:
                    actualProgress = progress;
                    break;
                case PROCESSING_INTERMEDIATE1:
                    if ((progress == 0) && (mProgressToApp != 0)) {
                        mProgressToApp = 0;
                    }
                    if ((progress != 0) || (mProgressToApp != 0)) {
                        actualProgress = progress/4;
                    }
                    break;
                case PROCESSING_INTERMEDIATE2:
                    if ((progress != 0) || (mProgressToApp != 0)) {
                        actualProgress = 25 + progress/4;
                    }
                    break;
                case PROCESSING_INTERMEDIATE3:
                    if ((progress != 0) || (mProgressToApp != 0)) {
                        actualProgress = 50 + progress/2;
                    }
                    break;
                case PROCESSING_NONE:

                default:
                    Log.e(TAG, "ERROR unexpected State=" + mProcessingState);
                    return;
            }
            if ((mProgressToApp != actualProgress) && (actualProgress != 0)) {

                mProgressToApp = actualProgress;

                if (mMediaProcessingProgressListener != null) {
                    // Send the progress indication
                    mMediaProcessingProgressListener.onProgress(mProcessingObject, action,
                                                                actualProgress);
                }
            }
            /* avoid 0 in next intermediate call */
            if (mProgressToApp == 0) {
                if (mMediaProcessingProgressListener != null) {
                    /*
                     *  Send the progress indication
                     */
                    mMediaProcessingProgressListener.onProgress(mProcessingObject, action,
                                                                actualProgress);
                }
                mProgressToApp = 1;
            }
        }
    }

    @SuppressWarnings("unused")
    private void onPreviewProgressUpdate(int progress, boolean isFinished,
                  boolean updateOverlay, String filename, int renderingMode) {
        if (mPreviewProgressListener != null) {
            if (mIsFirstProgress) {
                mPreviewProgressListener.onStart(mVideoEditor);
                mIsFirstProgress = false;
            }

            final VideoEditor.OverlayData overlayData;
            if (updateOverlay) {
                overlayData = new VideoEditor.OverlayData();
                if (filename != null) {
                    overlayData.set(BitmapFactory.decodeFile(filename), renderingMode);
                } else {
                    overlayData.setClear();
                }
            } else {
                overlayData = null;
            }

            if (progress != 0) {
                mPreviewProgress = progress;
            }

            if (isFinished) {
                mPreviewProgressListener.onStop(mVideoEditor);
            } else {
                mPreviewProgressListener.onProgress(mVideoEditor, progress, overlayData);
            }
        }
    }

    /**
     * Release the native helper object
     */
    void releaseNativeHelper() throws InterruptedException {
        release();
    }

    /**
     * Release the native helper to end the Audio Graph process
     */
    @SuppressWarnings("unused")
    private void onAudioGraphExtractProgressUpdate(int progress, boolean isVideo) {
        if ((mExtractAudioWaveformProgressListener != null) && (progress > 0)) {
            mExtractAudioWaveformProgressListener.onProgress(progress);
        }
    }

    /**
     * Populates the Effect Settings in EffectSettings
     *
     * @param effects The reference of EffectColor
     *
     * @return The populated effect settings in EffectSettings reference
     */
    EffectSettings getEffectSettings(EffectColor effects) {
        EffectSettings effectSettings = new EffectSettings();
        effectSettings.startTime = (int)effects.getStartTime();
        effectSettings.duration = (int)effects.getDuration();
        effectSettings.videoEffectType = getEffectColorType(effects);
        effectSettings.audioEffectType = 0;
        effectSettings.startPercent = 0;
        effectSettings.durationPercent = 0;
        effectSettings.framingFile = null;
        effectSettings.topLeftX = 0;
        effectSettings.topLeftY = 0;
        effectSettings.framingResize = false;
        effectSettings.text = null;
        effectSettings.textRenderingData = null;
        effectSettings.textBufferWidth = 0;
        effectSettings.textBufferHeight = 0;
        if (effects.getType() == EffectColor.TYPE_FIFTIES) {
            effectSettings.fiftiesFrameRate = 15;
        } else {
            effectSettings.fiftiesFrameRate = 0;
        }

        if ((effectSettings.videoEffectType == VideoEffect.COLORRGB16)
                || (effectSettings.videoEffectType == VideoEffect.GRADIENT)) {
            effectSettings.rgb16InputColor = effects.getColor();
        }

        effectSettings.alphaBlendingStartPercent = 0;
        effectSettings.alphaBlendingMiddlePercent = 0;
        effectSettings.alphaBlendingEndPercent = 0;
        effectSettings.alphaBlendingFadeInTimePercent = 0;
        effectSettings.alphaBlendingFadeOutTimePercent = 0;
        return effectSettings;
    }

    /**
     * Populates the Overlay Settings in EffectSettings
     *
     * @param overlay The reference of OverlayFrame
     *
     * @return The populated overlay settings in EffectSettings reference
     */
    EffectSettings getOverlaySettings(OverlayFrame overlay) {
        EffectSettings effectSettings = new EffectSettings();
        Bitmap bitmap = null;

        effectSettings.startTime = (int)overlay.getStartTime();
        effectSettings.duration = (int)overlay.getDuration();
        effectSettings.videoEffectType = VideoEffect.FRAMING;
        effectSettings.audioEffectType = 0;
        effectSettings.startPercent = 0;
        effectSettings.durationPercent = 0;
        effectSettings.framingFile = null;

        if ((bitmap = overlay.getBitmap()) != null) {
            effectSettings.framingFile = overlay.getFilename();

            if (effectSettings.framingFile == null) {
                try {
                    (overlay).save(mProjectPath);
                } catch (IOException e) {
                    Log.e(TAG, "getOverlaySettings : File not found");
                }
                effectSettings.framingFile = overlay.getFilename();
            }
            if (bitmap.getConfig() == Bitmap.Config.ARGB_8888)
                effectSettings.bitmapType = 6;
            else if (bitmap.getConfig() == Bitmap.Config.ARGB_4444)
                effectSettings.bitmapType = 5;
            else if (bitmap.getConfig() == Bitmap.Config.RGB_565)
                effectSettings.bitmapType = 4;
            else if (bitmap.getConfig() == Bitmap.Config.ALPHA_8)
                throw new RuntimeException("Bitmap config not supported");

            effectSettings.width = bitmap.getWidth();
            effectSettings.height = bitmap.getHeight();
            effectSettings.framingBuffer = new int[effectSettings.width];
            int tmp = 0;
            short maxAlpha = 0;
            short minAlpha = (short)0xFF;
            short alpha = 0;
            while (tmp < effectSettings.height) {
                bitmap.getPixels(effectSettings.framingBuffer, 0,
                                 effectSettings.width, 0, tmp,
                                 effectSettings.width, 1);
                for (int i = 0; i < effectSettings.width; i++) {
                    alpha = (short)((effectSettings.framingBuffer[i] >> 24) & 0xFF);
                    if (alpha > maxAlpha) {
                        maxAlpha = alpha;
                    }
                    if (alpha < minAlpha) {
                        minAlpha = alpha;
                    }
                }
                tmp += 1;
            }
            alpha = (short)((maxAlpha + minAlpha) / 2);
            alpha = (short)((alpha * 100) / 256);
            effectSettings.alphaBlendingEndPercent = alpha;
            effectSettings.alphaBlendingMiddlePercent = alpha;
            effectSettings.alphaBlendingStartPercent = alpha;
            effectSettings.alphaBlendingFadeInTimePercent = 100;
            effectSettings.alphaBlendingFadeOutTimePercent = 100;
            effectSettings.framingBuffer = null;

            /*
             * Set the resized RGB file dimensions
             */
            effectSettings.width = overlay.getResizedRGBSizeWidth();
            if(effectSettings.width == 0) {
                effectSettings.width = bitmap.getWidth();
            }

            effectSettings.height = overlay.getResizedRGBSizeHeight();
            if(effectSettings.height == 0) {
                effectSettings.height = bitmap.getHeight();
            }

        }

        effectSettings.topLeftX = 0;
        effectSettings.topLeftY = 0;

        effectSettings.framingResize = true;
        effectSettings.text = null;
        effectSettings.textRenderingData = null;
        effectSettings.textBufferWidth = 0;
        effectSettings.textBufferHeight = 0;
        effectSettings.fiftiesFrameRate = 0;
        effectSettings.rgb16InputColor = 0;
        int mediaItemHeight;
        int aspectRatio;
        if (overlay.getMediaItem() instanceof MediaImageItem) {
            if (((MediaImageItem)overlay.getMediaItem()).getGeneratedImageClip() != null) {
                // Ken Burns was applied
                mediaItemHeight = ((MediaImageItem)overlay.getMediaItem()).getGeneratedClipHeight();
                aspectRatio = getAspectRatio(
                    ((MediaImageItem)overlay.getMediaItem()).getGeneratedClipWidth()
                    , mediaItemHeight);
            } else {
                //For image get the scaled height. Aspect ratio would remain the same
                mediaItemHeight = ((MediaImageItem)overlay.getMediaItem()).getScaledHeight();
                aspectRatio = overlay.getMediaItem().getAspectRatio();
            }
        } else {
            aspectRatio = overlay.getMediaItem().getAspectRatio();
            mediaItemHeight = overlay.getMediaItem().getHeight();
        }
        effectSettings.framingScaledSize = findVideoResolution(aspectRatio, mediaItemHeight);
        return effectSettings;
    }

     /* get Video Editor aspect ratio */
    int nativeHelperGetAspectRatio() {
        return mVideoEditor.getAspectRatio();
    }

    /**
     * Sets the audio regenerate flag
     *
     * @param flag The boolean to set the audio regenerate flag
     *
     */
    void setAudioflag(boolean flag) {
        //check if the file exists.
        if (!(new File(String.format(mProjectPath + "/" + AUDIO_TRACK_PCM_FILE)).exists())) {
            flag = true;
        }
        mRegenerateAudio = flag;
    }

    /**
     * Gets the audio regenerate flag
     *
     * @param return The boolean to get the audio regenerate flag
     *
     */
    boolean getAudioflag() {
        return mRegenerateAudio;
    }

    /**
     * Maps the average frame rate to one of the defined enum values
     *
     * @param averageFrameRate The average frame rate of video item
     *
     * @return The frame rate from one of the defined enum values
     */
    int GetClosestVideoFrameRate(int averageFrameRate) {
        if (averageFrameRate >= 25) {
            return VideoFrameRate.FR_30_FPS;
        } else if (averageFrameRate >= 20) {
            return VideoFrameRate.FR_25_FPS;
        } else if (averageFrameRate >= 15) {
            return VideoFrameRate.FR_20_FPS;
        } else if (averageFrameRate >= 12) {
            return VideoFrameRate.FR_15_FPS;
        } else if (averageFrameRate >= 10) {
            return VideoFrameRate.FR_12_5_FPS;
        } else if (averageFrameRate >= 7) {
            return VideoFrameRate.FR_10_FPS;
        } else if (averageFrameRate >= 5) {
            return VideoFrameRate.FR_7_5_FPS;
        } else {
            return -1;
        }
    }

    /**
     * Helper function to adjust the effect or overlay start time
     * depending on the begin and end boundary time of meddia item
     */
    public void adjustEffectsStartTimeAndDuration(EffectSettings lEffect, int beginCutTime,
                                                  int endCutTime) {

        int effectStartTime = 0;
        int effectDuration = 0;

        /**
         * cbct -> clip begin cut time
         * cect -> clip end cut time
         ****************************************
         *  |                                 |
         *  |         cbct        cect        |
         *  | <-1-->   |           |          |
         *  |       <--|-2->       |          |
         *  |          | <---3---> |          |
         *  |          |        <--|-4--->    |
         *  |          |           | <--5-->  |
         *  |      <---|------6----|---->     |
         *  |                                 |
         *  < : effectStart
         *  > : effectStart + effectDuration
         ****************************************
         **/

        /** 1 & 5 */
        /**
         * Effect falls out side the trim duration. In such a case effects shall
         * not be applied.
         */
        if ((lEffect.startTime > endCutTime)
                || ((lEffect.startTime + lEffect.duration) <= beginCutTime)) {

            effectStartTime = 0;
            effectDuration = 0;

            lEffect.startTime = effectStartTime;
            lEffect.duration = effectDuration;
            return;
        }

        /** 2 */
        if ((lEffect.startTime < beginCutTime)
                && ((lEffect.startTime + lEffect.duration) > beginCutTime)
                && ((lEffect.startTime + lEffect.duration) <= endCutTime)) {
            effectStartTime = 0;
            effectDuration = lEffect.duration;

            effectDuration -= (beginCutTime - lEffect.startTime);
            lEffect.startTime = effectStartTime;
            lEffect.duration = effectDuration;
            return;
        }

        /** 3 */
        if ((lEffect.startTime >= beginCutTime)
                && ((lEffect.startTime + lEffect.duration) <= endCutTime)) {
            effectStartTime = lEffect.startTime - beginCutTime;
            lEffect.startTime = effectStartTime;
            lEffect.duration = lEffect.duration;
            return;
        }

        /** 4 */
        if ((lEffect.startTime >= beginCutTime)
                && ((lEffect.startTime + lEffect.duration) > endCutTime)) {
            effectStartTime = lEffect.startTime - beginCutTime;
            effectDuration = endCutTime - lEffect.startTime;
            lEffect.startTime = effectStartTime;
            lEffect.duration = effectDuration;
            return;
        }

        /** 6 */
        if ((lEffect.startTime < beginCutTime)
                && ((lEffect.startTime + lEffect.duration) > endCutTime)) {
            effectStartTime = 0;
            effectDuration = endCutTime - beginCutTime;
            lEffect.startTime = effectStartTime;
            lEffect.duration = effectDuration;
            return;
        }

    }

    /**
     * Generates the clip for preview or export
     *
     * @param editSettings The EditSettings reference for generating
     * a clip for preview or export
     *
     * @return error value
     */
    public int generateClip(EditSettings editSettings) {
        int err = 0;

        try {
            err = nativeGenerateClip(editSettings);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Illegal Argument exception in load settings");
            return -1;
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Illegal state exception in load settings");
            return -1;
        } catch (RuntimeException ex) {
            Log.e(TAG, "Runtime exception in load settings");
            return -1;
        }
        return err;
    }

    /**
     * Init function to initialiZe the  ClipSettings reference to
     * default values
     *
     * @param lclipSettings The ClipSettings reference
     */
    void initClipSettings(ClipSettings lclipSettings) {
        lclipSettings.clipPath = null;
        lclipSettings.clipDecodedPath = null;
        lclipSettings.clipOriginalPath = null;
        lclipSettings.fileType = 0;
        lclipSettings.endCutTime = 0;
        lclipSettings.beginCutTime = 0;
        lclipSettings.beginCutPercent = 0;
        lclipSettings.endCutPercent = 0;
        lclipSettings.panZoomEnabled = false;
        lclipSettings.panZoomPercentStart = 0;
        lclipSettings.panZoomTopLeftXStart = 0;
        lclipSettings.panZoomTopLeftYStart = 0;
        lclipSettings.panZoomPercentEnd = 0;
        lclipSettings.panZoomTopLeftXEnd = 0;
        lclipSettings.panZoomTopLeftYEnd = 0;
        lclipSettings.mediaRendering = 0;
    }


    /**
     * Populates the settings for generating an effect clip
     *
     * @param lMediaItem The media item for which the effect clip
     * needs to be generated
     * @param lclipSettings The ClipSettings reference containing
     * clips data
     * @param e The EditSettings reference containing effect specific data
     * @param uniqueId The unique id used in the name of the output clip
     * @param clipNo Used for internal purpose
     *
     * @return The name and path of generated clip
     */
    String generateEffectClip(MediaItem lMediaItem, ClipSettings lclipSettings,
            EditSettings e,String uniqueId,int clipNo) {
        int err = 0;
        EditSettings editSettings = null;
        String EffectClipPath = null;

        editSettings = new EditSettings();

        editSettings.clipSettingsArray = new ClipSettings[1];
        editSettings.clipSettingsArray[0] = lclipSettings;

        editSettings.backgroundMusicSettings = null;
        editSettings.transitionSettingsArray = null;
        editSettings.effectSettingsArray = e.effectSettingsArray;

        EffectClipPath = String.format(mProjectPath + "/" + "ClipEffectIntermediate" + "_"
                + lMediaItem.getId() + uniqueId + ".3gp");

        File tmpFile = new File(EffectClipPath);
        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        if (lMediaItem instanceof MediaVideoItem) {
            MediaVideoItem m = (MediaVideoItem)lMediaItem;

            editSettings.audioFormat = AudioFormat.AAC;
            editSettings.audioChannels = 2;
            editSettings.audioBitrate = Bitrate.BR_64_KBPS;
            editSettings.audioSamplingFreq = AudioSamplingFrequency.FREQ_32000;

            editSettings.videoBitrate = Bitrate.BR_5_MBPS;
            //editSettings.videoFormat = VideoFormat.MPEG4;
            editSettings.videoFormat = VideoFormat.H264;
            editSettings.videoFrameRate = VideoFrameRate.FR_30_FPS;
            editSettings.videoFrameSize = findVideoResolution(mVideoEditor.getAspectRatio(),
                    m.getHeight());
        } else {
            MediaImageItem m = (MediaImageItem)lMediaItem;
            editSettings.audioBitrate = Bitrate.BR_64_KBPS;
            editSettings.audioChannels = 2;
            editSettings.audioFormat = AudioFormat.AAC;
            editSettings.audioSamplingFreq = AudioSamplingFrequency.FREQ_32000;

            editSettings.videoBitrate = Bitrate.BR_5_MBPS;
            editSettings.videoFormat = VideoFormat.H264;
            editSettings.videoFrameRate = VideoFrameRate.FR_30_FPS;
            editSettings.videoFrameSize = findVideoResolution(mVideoEditor.getAspectRatio(),
                    m.getScaledHeight());
        }

        editSettings.outputFile = EffectClipPath;

        if (clipNo == 1) {
            mProcessingState  = PROCESSING_INTERMEDIATE1;
        } else if (clipNo == 2) {
            mProcessingState  = PROCESSING_INTERMEDIATE2;
        }
        mProcessingObject = lMediaItem;
        err = generateClip(editSettings);
        mProcessingState  = PROCESSING_NONE;

        if (err == 0) {
            lclipSettings.clipPath = EffectClipPath;
            lclipSettings.fileType = FileType.THREE_GPP;
            return EffectClipPath;
        } else {
            throw new RuntimeException("preview generation cannot be completed");
        }
    }


    /**
     * Populates the settings for generating a Ken Burn effect clip
     *
     * @param m The media image item for which the Ken Burn effect clip
     * needs to be generated
     * @param e The EditSettings reference clip specific data
     *
     * @return The name and path of generated clip
     */
    String generateKenBurnsClip(EditSettings e, MediaImageItem m) {
        String output = null;
        int err = 0;

        e.backgroundMusicSettings = null;
        e.transitionSettingsArray = null;
        e.effectSettingsArray = null;
        output = String.format(mProjectPath + "/" + "ImageClip-" + m.getId() + ".3gp");

        File tmpFile = new File(output);
        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        e.outputFile = output;
        e.audioBitrate = Bitrate.BR_64_KBPS;
        e.audioChannels = 2;
        e.audioFormat = AudioFormat.AAC;
        e.audioSamplingFreq = AudioSamplingFrequency.FREQ_32000;

        e.videoBitrate = Bitrate.BR_5_MBPS;
        e.videoFormat = VideoFormat.H264;
        e.videoFrameRate = VideoFrameRate.FR_30_FPS;
        e.videoFrameSize = findVideoResolution(mVideoEditor.getAspectRatio(),
                                                           m.getScaledHeight());
        mProcessingState  = PROCESSING_KENBURNS;
        mProcessingObject = m;
        err = generateClip(e);
        // Reset the processing state and check for errors
        mProcessingState  = PROCESSING_NONE;
        if (err != 0) {
            throw new RuntimeException("preview generation cannot be completed");
        }
        return output;
    }


    /**
     * Calculates the output resolution for transition clip
     *
     * @param m1 First media item associated with transition
     * @param m2 Second media item associated with transition
     *
     * @return The transition resolution
     */
    private int getTransitionResolution(MediaItem m1, MediaItem m2) {
        int clip1Height = 0;
        int clip2Height = 0;
        int videoSize = 0;

        if (m1 != null && m2 != null) {
            if (m1 instanceof MediaVideoItem) {
                clip1Height = m1.getHeight();
            } else if (m1 instanceof MediaImageItem) {
                clip1Height = ((MediaImageItem)m1).getScaledHeight();
            }
            if (m2 instanceof MediaVideoItem) {
                clip2Height = m2.getHeight();
            } else if (m2 instanceof MediaImageItem) {
                clip2Height = ((MediaImageItem)m2).getScaledHeight();
            }
            if (clip1Height > clip2Height) {
                videoSize = findVideoResolution(mVideoEditor.getAspectRatio(), clip1Height);
            } else {
                videoSize = findVideoResolution(mVideoEditor.getAspectRatio(), clip2Height);
            }
        } else if (m1 == null && m2 != null) {
            if (m2 instanceof MediaVideoItem) {
                clip2Height = m2.getHeight();
            } else if (m2 instanceof MediaImageItem) {
                clip2Height = ((MediaImageItem)m2).getScaledHeight();
            }
            videoSize = findVideoResolution(mVideoEditor.getAspectRatio(), clip2Height);
        } else if (m1 != null && m2 == null) {
            if (m1 instanceof MediaVideoItem) {
                clip1Height = m1.getHeight();
            } else if (m1 instanceof MediaImageItem) {
                clip1Height = ((MediaImageItem)m1).getScaledHeight();
            }
            videoSize = findVideoResolution(mVideoEditor.getAspectRatio(), clip1Height);
        }
        return videoSize;
    }

    /**
     * Populates the settings for generating an transition clip
     *
     * @param m1 First media item associated with transition
     * @param m2 Second media item associated with transition
     * @param e The EditSettings reference containing
     * clip specific data
     * @param uniqueId The unique id used in the name of the output clip
     * @param t The Transition specific data
     *
     * @return The name and path of generated clip
     */
    String generateTransitionClip(EditSettings e, String uniqueId,
            MediaItem m1, MediaItem m2,Transition t) {
        String outputFilename = null;
        int err = 0;

        outputFilename = String.format(mProjectPath + "/" + uniqueId + ".3gp");
        e.outputFile = outputFilename;
        e.audioBitrate = Bitrate.BR_64_KBPS;
        e.audioChannels = 2;
        e.audioFormat = AudioFormat.AAC;
        e.audioSamplingFreq = AudioSamplingFrequency.FREQ_32000;

        e.videoBitrate = Bitrate.BR_5_MBPS;
        e.videoFormat = VideoFormat.H264;
        e.videoFrameRate = VideoFrameRate.FR_30_FPS;
        e.videoFrameSize = getTransitionResolution(m1, m2);

        if (new File(outputFilename).exists()) {
            new File(outputFilename).delete();
        }
        mProcessingState  = PROCESSING_INTERMEDIATE3;
        mProcessingObject = t;
        err = generateClip(e);
        // Reset the processing state and check for errors
        mProcessingState  = PROCESSING_NONE;
        if (err != 0) {
            throw new RuntimeException("preview generation cannot be completed");
        }
        return outputFilename;
    }

    /**
     * Populates effects and overlays in EffectSettings structure
     * and also adjust the start time and duration of effects and overlays
     * w.r.t to total story board time
     *
     * @param m1 Media item associated with effect
     * @param effectSettings The EffectSettings reference containing
     *      effect specific data
     * @param beginCutTime The begin cut time of the clip associated with effect
     * @param endCutTime The end cut time of the clip associated with effect
     * @param storyBoardTime The current story board time
     *
     * @return The updated index
     */
    private int populateEffects(MediaItem m, EffectSettings[] effectSettings, int i,
            int beginCutTime, int endCutTime, int storyBoardTime) {

        if (m.getBeginTransition() != null && m.getBeginTransition().getDuration() > 0
                && m.getEndTransition() != null && m.getEndTransition().getDuration() > 0) {
            beginCutTime += m.getBeginTransition().getDuration();
            endCutTime -= m.getEndTransition().getDuration();
        } else if (m.getBeginTransition() == null && m.getEndTransition() != null
                && m.getEndTransition().getDuration() > 0) {
            endCutTime -= m.getEndTransition().getDuration();
        } else if (m.getEndTransition() == null && m.getBeginTransition() != null
                && m.getBeginTransition().getDuration() > 0) {
            beginCutTime += m.getBeginTransition().getDuration();
        }

        final List<Effect> effects = m.getAllEffects();
        final List<Overlay> overlays = m.getAllOverlays();

        for (Overlay overlay : overlays) {
            effectSettings[i] = getOverlaySettings((OverlayFrame)overlay);
            adjustEffectsStartTimeAndDuration(effectSettings[i], beginCutTime, endCutTime);
            effectSettings[i].startTime += storyBoardTime;
            i++;
        }

        for (Effect effect : effects) {
            if (effect instanceof EffectColor) {
                effectSettings[i] = getEffectSettings((EffectColor)effect);
                adjustEffectsStartTimeAndDuration(effectSettings[i], beginCutTime, endCutTime);
                effectSettings[i].startTime += storyBoardTime;
                i++;
            }
        }

        return i;
    }

    /**
     * Adjusts the media item boundaries for use in export or preview
     *
     * @param clipSettings The ClipSettings reference
     * @param clipProperties The Properties reference
     * @param m The media item
     */
    private void adjustMediaItemBoundary(ClipSettings clipSettings,
                                         Properties clipProperties, MediaItem m) {
        if (m.getBeginTransition() != null && m.getBeginTransition().getDuration() > 0
                && m.getEndTransition() != null && m.getEndTransition().getDuration() > 0) {
            clipSettings.beginCutTime += m.getBeginTransition().getDuration();
            clipSettings.endCutTime -= m.getEndTransition().getDuration();
        } else if (m.getBeginTransition() == null && m.getEndTransition() != null
                && m.getEndTransition().getDuration() > 0) {
            clipSettings.endCutTime -= m.getEndTransition().getDuration();
        } else if (m.getEndTransition() == null && m.getBeginTransition() != null
                && m.getBeginTransition().getDuration() > 0) {
            clipSettings.beginCutTime += m.getBeginTransition().getDuration();
        }

        clipProperties.duration = clipSettings.endCutTime - clipSettings.beginCutTime;

        if (clipProperties.videoDuration != 0) {
            clipProperties.videoDuration = clipSettings.endCutTime - clipSettings.beginCutTime;
        }

        if (clipProperties.audioDuration != 0) {
            clipProperties.audioDuration = clipSettings.endCutTime - clipSettings.beginCutTime;
        }
    }

    /**
     * Generates the transition if transition is present
     * and is in invalidated state
     *
     * @param transition The Transition reference
     * @param editSettings The EditSettings reference
     * @param clipPropertiesArray The clip Properties array
     * @param i The index in clip Properties array for current clip
     */
    private void generateTransition(Transition transition, EditSettings editSettings,
            PreviewClipProperties clipPropertiesArray, int index) {
        if (!(transition.isGenerated())) {
            transition.generate();
        }
        editSettings.clipSettingsArray[index] = new ClipSettings();
        editSettings.clipSettingsArray[index].clipPath = transition.getFilename();
        editSettings.clipSettingsArray[index].fileType = FileType.THREE_GPP;
        editSettings.clipSettingsArray[index].beginCutTime = 0;
        editSettings.clipSettingsArray[index].endCutTime = (int)transition.getDuration();
        editSettings.clipSettingsArray[index].mediaRendering = MediaRendering.BLACK_BORDERS;

        try {
            clipPropertiesArray.clipProperties[index] =
                getMediaProperties(transition.getFilename());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported file or file not found");
        }

        clipPropertiesArray.clipProperties[index].Id = null;
        clipPropertiesArray.clipProperties[index].audioVolumeValue = 100;
        clipPropertiesArray.clipProperties[index].duration = (int)transition.getDuration();
        if (clipPropertiesArray.clipProperties[index].videoDuration != 0) {
            clipPropertiesArray.clipProperties[index].videoDuration = (int)transition.getDuration();
        }

        if (clipPropertiesArray.clipProperties[index].audioDuration != 0) {
            clipPropertiesArray.clipProperties[index].audioDuration = (int)transition.getDuration();
        }
    }

    /**
     * Sets the volume for current media item in clip properties array
     *
     * @param m The media item
     * @param clipProperties The clip properties array reference
     * @param i The index in clip Properties array for current clip
     */
    private void adjustVolume(MediaItem m, PreviewClipProperties clipProperties,
                              int index) {
        if (m instanceof MediaVideoItem) {
            final boolean videoMuted = ((MediaVideoItem)m).isMuted();
            if (videoMuted == false) {
                mClipProperties.clipProperties[index].audioVolumeValue =
                    ((MediaVideoItem)m).getVolume();
            } else {
                mClipProperties.clipProperties[index].audioVolumeValue = 0;
            }
        } else if (m instanceof MediaImageItem) {
            mClipProperties.clipProperties[index].audioVolumeValue = 0;
        }
    }

    /**
     * Checks for odd size image width and height
     *
     * @param m The media item
     * @param clipProperties The clip properties array reference
     * @param i The index in clip Properties array for current clip
     */
    private void checkOddSizeImage(MediaItem m, PreviewClipProperties clipProperties, int index) {
        if (m instanceof MediaImageItem) {
            int width = mClipProperties.clipProperties[index].width;
            int height = mClipProperties.clipProperties[index].height;

            if ((width % 2) != 0) {
                width -= 1;
            }
            if ((height % 2) != 0) {
                height -= 1;
            }
            mClipProperties.clipProperties[index].width = width;
            mClipProperties.clipProperties[index].height = height;
        }
    }

    /**
     * Populates the media item properties and calculates the maximum
     * height among all the clips
     *
     * @param m The media item
     * @param i The index in clip Properties array for current clip
     * @param maxHeight The max height from the clip properties
     *
     * @return Updates the max height if current clip's height is greater
     * than all previous clips height
     */
    private int populateMediaItemProperties(MediaItem m, int index, int maxHeight) {
        mPreviewEditSettings.clipSettingsArray[index] = new ClipSettings();
        if (m instanceof MediaVideoItem) {
            mPreviewEditSettings.clipSettingsArray[index] =
                ((MediaVideoItem)m).getVideoClipProperties();
            if (((MediaVideoItem)m).getHeight() > maxHeight) {
                maxHeight = ((MediaVideoItem)m).getHeight();
            }
        } else if (m instanceof MediaImageItem) {
            mPreviewEditSettings.clipSettingsArray[index] =
                ((MediaImageItem)m).getImageClipProperties();
            if (((MediaImageItem)m).getScaledHeight() > maxHeight) {
                maxHeight = ((MediaImageItem)m).getScaledHeight();
            }
        }
        /** + Handle the image files here */
        if (mPreviewEditSettings.clipSettingsArray[index].fileType == FileType.JPG) {
            mPreviewEditSettings.clipSettingsArray[index].clipDecodedPath =
                ((MediaImageItem)m).getDecodedImageFileName();

            mPreviewEditSettings.clipSettingsArray[index].clipOriginalPath =
                         mPreviewEditSettings.clipSettingsArray[index].clipPath;
        }
        return maxHeight;
    }

    /**
     * Populates the background music track properties
     *
     * @param mediaBGMList The background music list
     *
     */
    private void populateBackgroundMusicProperties(List<AudioTrack> mediaBGMList) {

        if (mediaBGMList.size() == 1) {
            mAudioTrack = mediaBGMList.get(0);
        } else {
            mAudioTrack = null;
        }

        if (mAudioTrack != null) {
            mAudioSettings = new AudioSettings();
            Properties mAudioProperties = new Properties();
            mAudioSettings.pFile = null;
            mAudioSettings.Id = mAudioTrack.getId();
            try {
                mAudioProperties = getMediaProperties(mAudioTrack.getFilename());
            } catch (Exception e) {
               throw new IllegalArgumentException("Unsupported file or file not found");
            }
            mAudioSettings.bRemoveOriginal = false;
            mAudioSettings.channels = mAudioProperties.audioChannels;
            mAudioSettings.Fs = mAudioProperties.audioSamplingFrequency;
            mAudioSettings.loop = mAudioTrack.isLooping();
            mAudioSettings.ExtendedFs = 0;
            mAudioSettings.pFile = mAudioTrack.getFilename();
            mAudioSettings.startMs = mAudioTrack.getStartTime();
            mAudioSettings.beginCutTime = mAudioTrack.getBoundaryBeginTime();
            mAudioSettings.endCutTime = mAudioTrack.getBoundaryEndTime();
            if (mAudioTrack.isMuted()) {
                mAudioSettings.volume = 0;
            } else {
                mAudioSettings.volume = mAudioTrack.getVolume();
            }
            mAudioSettings.fileType = mAudioProperties.fileType;
            mAudioSettings.ducking_lowVolume = mAudioTrack.getDuckedTrackVolume();
            mAudioSettings.ducking_threshold = mAudioTrack.getDuckingThreshhold();
            mAudioSettings.bInDucking_enable = mAudioTrack.isDuckingEnabled();
            mAudioTrackPCMFilePath = String.format(mProjectPath + "/" + AUDIO_TRACK_PCM_FILE);
            mAudioSettings.pcmFilePath = mAudioTrackPCMFilePath;

            mPreviewEditSettings.backgroundMusicSettings = new BackgroundMusicSettings();
            mPreviewEditSettings.backgroundMusicSettings.file = mAudioTrackPCMFilePath;
            mPreviewEditSettings.backgroundMusicSettings.fileType = mAudioProperties.fileType;
            mPreviewEditSettings.backgroundMusicSettings.insertionTime =
                mAudioTrack.getStartTime();
            mPreviewEditSettings.backgroundMusicSettings.volumePercent = mAudioTrack.getVolume();
            mPreviewEditSettings.backgroundMusicSettings.beginLoop =
                mAudioTrack.getBoundaryBeginTime();
            mPreviewEditSettings.backgroundMusicSettings.endLoop =
                                               mAudioTrack.getBoundaryEndTime();
            mPreviewEditSettings.backgroundMusicSettings.enableDucking =
                mAudioTrack.isDuckingEnabled();
            mPreviewEditSettings.backgroundMusicSettings.duckingThreshold =
                mAudioTrack.getDuckingThreshhold();
            mPreviewEditSettings.backgroundMusicSettings.lowVolume =
                mAudioTrack.getDuckedTrackVolume();
            mPreviewEditSettings.backgroundMusicSettings.isLooping = mAudioTrack.isLooping();
            mPreviewEditSettings.primaryTrackVolume = 100;
            mProcessingState  = PROCESSING_AUDIO_PCM;
            mProcessingObject = mAudioTrack;
        } else {
            mAudioSettings = null;
            mPreviewEditSettings.backgroundMusicSettings = null;
            mAudioTrackPCMFilePath = null;
        }
    }

    /**
     * Calculates all the effects in all the media items
     * in media items list
     *
     * @param mediaItemsList The media item list
     *
     * @return The total number of effects
     *
     */
    private int getTotalEffects(List<MediaItem> mediaItemsList) {
        int totalEffects = 0;
        final Iterator<MediaItem> it = mediaItemsList.iterator();
        while (it.hasNext()) {
            final MediaItem t = it.next();
            totalEffects += t.getAllEffects().size();
            totalEffects += t.getAllOverlays().size();
            final Iterator<Effect> ef = t.getAllEffects().iterator();
            while (ef.hasNext()) {
                final Effect e = ef.next();
                if (e instanceof EffectKenBurns) {
                    totalEffects--;
                }
            }
        }
        return totalEffects;
    }

    /**
     * This function is responsible for forming clip settings
     * array and clip properties array including transition clips
     * and effect settings for preview purpose or export.
     *
     *
     * @param mediaItemsList The media item list
     * @param mediaTransitionList The transitions list
     * @param mediaBGMList The background music list
     * @param listener The MediaProcessingProgressListener
     *
     */
    void previewStoryBoard(List<MediaItem> mediaItemsList,
            List<Transition> mediaTransitionList, List<AudioTrack> mediaBGMList,
            MediaProcessingProgressListener listener) {
        if (mInvalidatePreviewArray) {
            int previewIndex = 0;
            int totalEffects = 0;
            int storyBoardTime = 0;
            int maxHeight = 0;
            int beginCutTime = 0;
            int endCutTime = 0;
            int effectIndex = 0;
            Transition lTransition = null;
            MediaItem lMediaItem = null;
            mPreviewEditSettings = new EditSettings();
            mClipProperties = new PreviewClipProperties();
            mTotalClips = 0;

            mTotalClips = mediaItemsList.size();
            for (Transition transition : mediaTransitionList) {
                if (transition.getDuration() > 0) {
                    mTotalClips++;
                }
            }

            totalEffects = getTotalEffects(mediaItemsList);

            mPreviewEditSettings.clipSettingsArray = new ClipSettings[mTotalClips];
            mPreviewEditSettings.effectSettingsArray = new EffectSettings[totalEffects];
            mClipProperties.clipProperties = new Properties[mTotalClips];

            /** record the call back progress listener */
            mMediaProcessingProgressListener = listener;
            mProgressToApp = 0;

            if (mediaItemsList.size() > 0) {
                for (int i = 0; i < mediaItemsList.size(); i++) {
                    /* Get the Media Item from the list */
                    lMediaItem = mediaItemsList.get(i);
                    if (lMediaItem instanceof MediaVideoItem) {
                        beginCutTime = (int)((MediaVideoItem)lMediaItem).getBoundaryBeginTime();
                        endCutTime = (int)((MediaVideoItem)lMediaItem).getBoundaryEndTime();
                    } else if (lMediaItem instanceof MediaImageItem) {
                        beginCutTime = 0;
                        endCutTime = (int)((MediaImageItem)lMediaItem).getTimelineDuration();
                    }
                    /* Get the transition associated with Media Item */
                    lTransition = lMediaItem.getBeginTransition();
                    if (lTransition != null && (lTransition.getDuration() > 0)) {
                        /* generate transition clip */
                        generateTransition(lTransition, mPreviewEditSettings,
                                           mClipProperties, previewIndex);
                        storyBoardTime += mClipProperties.clipProperties[previewIndex].duration;
                        previewIndex++;
                    }
                    /* Populate media item properties */
                    maxHeight = populateMediaItemProperties(lMediaItem, previewIndex, maxHeight);
                    /* Get the clip properties of the media item. */
                    if (lMediaItem instanceof MediaImageItem) {
                        int tmpCnt = 0;
                        boolean bEffectKbPresent = false;
                        final List<Effect> effectList = lMediaItem.getAllEffects();
                        /**
                         * Check if Ken Burns effect is present
                         */
                        while (tmpCnt < effectList.size()) {
                            if (effectList.get(tmpCnt) instanceof EffectKenBurns) {
                                bEffectKbPresent = true;
                                break;
                            }
                            tmpCnt++;
                        }

                        if (bEffectKbPresent) {
                            try {
                                  if(((MediaImageItem)lMediaItem).getGeneratedImageClip() != null) {
                                     mClipProperties.clipProperties[previewIndex]
                                        = getMediaProperties(((MediaImageItem)lMediaItem).
                                                             getGeneratedImageClip());
                                  }
                                  else {
                                   mClipProperties.clipProperties[previewIndex]
                                      = getMediaProperties(((MediaImageItem)lMediaItem).
                                                             getScaledImageFileName());
                                   mClipProperties.clipProperties[previewIndex].width =
                                             ((MediaImageItem)lMediaItem).getScaledWidth();
                                   mClipProperties.clipProperties[previewIndex].height =
                                             ((MediaImageItem)lMediaItem).getScaledHeight();
                                  }
                                } catch (Exception e) {
                                   throw new IllegalArgumentException("Unsupported file or file not found");
                                }
                         } else {
                              try {
                                  mClipProperties.clipProperties[previewIndex]
                                      = getMediaProperties(((MediaImageItem)lMediaItem).
                                                               getScaledImageFileName());
                              } catch (Exception e) {
                                throw new IllegalArgumentException("Unsupported file or file not found");
                              }
                            mClipProperties.clipProperties[previewIndex].width =
                                        ((MediaImageItem)lMediaItem).getScaledWidth();
                            mClipProperties.clipProperties[previewIndex].height =
                                        ((MediaImageItem)lMediaItem).getScaledHeight();
                        }
                    } else {
                        try {
                            mClipProperties.clipProperties[previewIndex]
                                 = getMediaProperties(lMediaItem.getFilename());
                            } catch (Exception e) {
                              throw new IllegalArgumentException("Unsupported file or file not found");
                          }
                    }
                    mClipProperties.clipProperties[previewIndex].Id = lMediaItem.getId();
                    checkOddSizeImage(lMediaItem, mClipProperties, previewIndex);
                    adjustVolume(lMediaItem, mClipProperties, previewIndex);

                    /*
                     * Adjust media item start time and end time w.r.t to begin
                     * and end transitions associated with media item
                     */

                    adjustMediaItemBoundary(mPreviewEditSettings.clipSettingsArray[previewIndex],
                            mClipProperties.clipProperties[previewIndex], lMediaItem);

                    /*
                     * Get all the effects and overlays for that media item and
                     * adjust start time and duration of effects
                     */

                    effectIndex = populateEffects(lMediaItem,
                            mPreviewEditSettings.effectSettingsArray, effectIndex, beginCutTime,
                            endCutTime, storyBoardTime);
                    storyBoardTime += mClipProperties.clipProperties[previewIndex].duration;
                    previewIndex++;

                    /* Check if there is any end transition at last media item */

                    if (i == (mediaItemsList.size() - 1)) {
                        lTransition = lMediaItem.getEndTransition();
                        if (lTransition != null && (lTransition.getDuration() > 0)) {
                            generateTransition(lTransition, mPreviewEditSettings, mClipProperties,
                                    previewIndex);
                            break;
                        }
                    }
                }

                if (!mErrorFlagSet) {
                    mPreviewEditSettings.videoFrameSize = findVideoResolution(mVideoEditor
                            .getAspectRatio(), maxHeight);
                    populateBackgroundMusicProperties(mediaBGMList);

                    /** call to native populate settings */
                    try {
                        nativePopulateSettings(mPreviewEditSettings, mClipProperties, mAudioSettings);
                    } catch (IllegalArgumentException ex) {
                        Log.e(TAG, "Illegal argument exception in nativePopulateSettings");
                        throw ex;
                    } catch (IllegalStateException ex) {
                        Log.e(TAG, "Illegal state exception in nativePopulateSettings");
                        throw ex;
                    } catch (RuntimeException ex) {
                        Log.e(TAG, "Runtime exception in nativePopulateSettings");
                        throw ex;
                    }
                    mInvalidatePreviewArray = false;
                    mProcessingState  = PROCESSING_NONE;
                }
            }
            if (mErrorFlagSet) {
                mErrorFlagSet = false;
                throw new RuntimeException("preview generation cannot be completed");
            }
        }
    } /* END of previewStoryBoard */

    /**
     * This function is responsible for starting the preview
     *
     *
     * @param surface The surface on which preview has to be displayed
     * @param fromMs The time in ms from which preview has to be started
     * @param toMs The time in ms till preview has to be played
     * @param loop To loop the preview or not
     * @param callbackAfterFrameCount INdicated after how many frames
     * the callback is needed
     * @param listener The PreviewProgressListener
     */
    void doPreview(Surface surface, long fromMs, long toMs, boolean loop,
            int callbackAfterFrameCount, PreviewProgressListener listener) {
        mPreviewProgress = fromMs;
        mIsFirstProgress = true;
        mPreviewProgressListener = listener;

        if (!mInvalidatePreviewArray) {
            try {
                /** Modify the image files names to rgb image files. */
                for (int clipCnt = 0; clipCnt < mPreviewEditSettings.clipSettingsArray.length;
                    clipCnt++) {
                    if (mPreviewEditSettings.clipSettingsArray[clipCnt].fileType == FileType.JPG) {
                        mPreviewEditSettings.clipSettingsArray[clipCnt].clipPath =
                            mPreviewEditSettings.clipSettingsArray[clipCnt].clipDecodedPath;
                    }
                }
                nativePopulateSettings(mPreviewEditSettings, mClipProperties, mAudioSettings);
                nativeStartPreview(surface, fromMs, toMs, callbackAfterFrameCount, loop);
            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "Illegal argument exception in nativeStartPreview");
                throw ex;
            } catch (IllegalStateException ex) {
                Log.e(TAG, "Illegal state exception in nativeStartPreview");
                throw ex;
            } catch (RuntimeException ex) {
                Log.e(TAG, "Runtime exception in nativeStartPreview");
                throw ex;
            }
        } else {
            throw new IllegalStateException("generatePreview is in progress");
        }
    }

    /**
     * This function is responsible for stopping the preview
     */
    long stopPreview() {
        return nativeStopPreview();
    }

    /**
     * This function is responsible for rendering a single frame
     * from the complete story board on the surface
     *
     * @param surface The surface on which frame has to be rendered
     * @param time The time in ms at which the frame has to be rendered
     * @param surfaceWidth The surface width
     * @param surfaceHeight The surface height
     * @param overlayData The overlay data
     *
     * @return The actual time from the story board at which the  frame was extracted
     * and rendered
     */
    long renderPreviewFrame(Surface surface, long time, int surfaceWidth,
            int surfaceHeight, VideoEditor.OverlayData overlayData) {
        if (mInvalidatePreviewArray) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Call generate preview first");
            }
            throw new IllegalStateException("Call generate preview first");
        }

        long timeMs = 0;
        try {
            for (int clipCnt = 0; clipCnt < mPreviewEditSettings.clipSettingsArray.length;
                  clipCnt++) {
                if (mPreviewEditSettings.clipSettingsArray[clipCnt].fileType == FileType.JPG) {
                    mPreviewEditSettings.clipSettingsArray[clipCnt].clipPath =
                        mPreviewEditSettings.clipSettingsArray[clipCnt].clipDecodedPath;
                }
            }

            // Reset the render preview frame params that shall be set by native.
            mRenderPreviewOverlayFile = null;
            mRenderPreviewRenderingMode = MediaRendering.RESIZING;

            nativePopulateSettings(mPreviewEditSettings, mClipProperties, mAudioSettings);

            timeMs = (long)nativeRenderPreviewFrame(surface, time, surfaceWidth, surfaceHeight);

            if (mRenderPreviewOverlayFile != null) {
                overlayData.set(BitmapFactory.decodeFile(mRenderPreviewOverlayFile),
                        mRenderPreviewRenderingMode);
            } else {
                overlayData.setClear();
            }
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Illegal Argument exception in nativeRenderPreviewFrame");
            throw ex;
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Illegal state exception in nativeRenderPreviewFrame");
            throw ex;
        } catch (RuntimeException ex) {
            Log.e(TAG, "Runtime exception in nativeRenderPreviewFrame");
            throw ex;
        }

        return timeMs;
    }

    private void previewFrameEditInfo(String filename, int renderingMode) {
        mRenderPreviewOverlayFile = filename;
        mRenderPreviewRenderingMode = renderingMode;
    }


    /**
     * This function is responsible for rendering a single frame
     * from a single media item on the surface
     *
     * @param surface The surface on which frame has to be rendered
     * @param filepath The file path for which the frame needs to be displayed
     * @param time The time in ms at which the frame has to be rendered
     * @param framewidth The frame width
     * @param framewidth The frame height
     *
     * @return The actual time from media item at which the  frame was extracted
     * and rendered
     */
    long renderMediaItemPreviewFrame(Surface surface, String filepath,
                                            long time, int framewidth, int frameheight) {
        long timeMs = 0;
        try {
            timeMs = (long)nativeRenderMediaItemPreviewFrame(surface, filepath, framewidth,
                    frameheight, 0, 0, time);
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Illegal Argument exception in renderMediaItemPreviewFrame");
            throw ex;
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Illegal state exception in renderMediaItemPreviewFrame");
            throw ex;
        } catch (RuntimeException ex) {
            Log.e(TAG, "Runtime exception in renderMediaItemPreviewFrame");
            throw ex;
        }

        return timeMs;
    }

    /**
     * This function sets the flag to invalidate the preview array
     * and for generating the preview again
     */
    void setGeneratePreview(boolean isRequired) {
        boolean semAcquiredDone = false;
        try {
            lock();
            semAcquiredDone = true;
            mInvalidatePreviewArray = isRequired;
        } catch (InterruptedException ex) {
            Log.e(TAG, "Runtime exception in renderMediaItemPreviewFrame");
        } finally {
            if (semAcquiredDone) {
                unlock();
            }
        }
    }

    /**
     * @return Returns the current status of preview invalidation
     * flag
     */
    boolean getGeneratePreview() {
        return mInvalidatePreviewArray;
    }

    /**
     * Calculates the aspect ratio from widht and height
     *
     * @param w The width of media item
     * @param h The height of media item
     *
     * @return The calculated aspect ratio
     */
    int getAspectRatio(int w, int h) {
        double apRatio = (double)(w) / (double)(h);
        BigDecimal bd = new BigDecimal(apRatio);
        bd = bd.setScale(3, BigDecimal.ROUND_HALF_UP);
        apRatio = bd.doubleValue();
        int var = MediaProperties.ASPECT_RATIO_16_9;
        if (apRatio >= 1.7) {
            var = MediaProperties.ASPECT_RATIO_16_9;
        } else if (apRatio >= 1.6) {
            var = MediaProperties.ASPECT_RATIO_5_3;
        } else if (apRatio >= 1.5) {
            var = MediaProperties.ASPECT_RATIO_3_2;
        } else if (apRatio > 1.3) {
            var = MediaProperties.ASPECT_RATIO_4_3;
        } else if (apRatio >= 1.2) {
            var = MediaProperties.ASPECT_RATIO_11_9;
        }
        return var;
    }

    /**
     * Maps the file type used in native layer
     * to file type used in JAVA layer
     *
     * @param fileType The file type in native layer
     *
     * @return The File type in JAVA layer
     */
    int getFileType(int fileType) {
        int retValue = -1;
        switch (fileType) {
            case FileType.UNSUPPORTED:
                retValue = MediaProperties.FILE_UNSUPPORTED;
                break;
            case FileType.THREE_GPP:
                retValue = MediaProperties.FILE_3GP;
                break;
            case FileType.MP4:
                retValue = MediaProperties.FILE_MP4;
                break;
            case FileType.JPG:
                retValue = MediaProperties.FILE_JPEG;
                break;
            case FileType.PNG:
                retValue = MediaProperties.FILE_PNG;
                break;
            case FileType.MP3:
                retValue = MediaProperties.FILE_MP3;
                break;
            case FileType.M4V:
                retValue = MediaProperties.FILE_M4V;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the video codec type used in native layer
     * to video codec type used in JAVA layer
     *
     * @param codecType The video codec type in native layer
     *
     * @return The video codec type in JAVA layer
     */
    int getVideoCodecType(int codecType) {
        int retValue = -1;
        switch (codecType) {
            case VideoFormat.H263:
                retValue = MediaProperties.VCODEC_H263;
                break;
            case VideoFormat.H264:
                retValue = MediaProperties.VCODEC_H264BP;
                break;
            case VideoFormat.MPEG4:
                retValue = MediaProperties.VCODEC_MPEG4;
                break;
            case VideoFormat.UNSUPPORTED:

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the audio codec type used in native layer
     * to audio codec type used in JAVA layer
     *
     * @param audioType The audio codec type in native layer
     *
     * @return The audio codec type in JAVA layer
     */
    int getAudioCodecType(int codecType) {
        int retValue = -1;
        switch (codecType) {
            case AudioFormat.AMR_NB:
                retValue = MediaProperties.ACODEC_AMRNB;
                break;
            case AudioFormat.AAC:
                retValue = MediaProperties.ACODEC_AAC_LC;
                break;
            case AudioFormat.MP3:
                retValue = MediaProperties.ACODEC_MP3;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Returns the frame rate as integer
     *
     * @param fps The fps as enum
     *
     * @return The frame rate as integer
     */
    int getFrameRate(int fps) {
        int retValue = -1;
        switch (fps) {
            case VideoFrameRate.FR_5_FPS:
                retValue = 5;
                break;
            case VideoFrameRate.FR_7_5_FPS:
                retValue = 8;
                break;
            case VideoFrameRate.FR_10_FPS:
                retValue = 10;
                break;
            case VideoFrameRate.FR_12_5_FPS:
                retValue = 13;
                break;
            case VideoFrameRate.FR_15_FPS:
                retValue = 15;
                break;
            case VideoFrameRate.FR_20_FPS:
                retValue = 20;
                break;
            case VideoFrameRate.FR_25_FPS:
                retValue = 25;
                break;
            case VideoFrameRate.FR_30_FPS:
                retValue = 30;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the file type used in JAVA layer
     * to file type used in native layer
     *
     * @param fileType The file type in JAVA layer
     *
     * @return The File type in native layer
     */
    int getMediaItemFileType(int fileType) {
        int retValue = -1;

        switch (fileType) {
            case MediaProperties.FILE_UNSUPPORTED:
                retValue = FileType.UNSUPPORTED;
                break;
            case MediaProperties.FILE_3GP:
                retValue = FileType.THREE_GPP;
                break;
            case MediaProperties.FILE_MP4:
                retValue = FileType.MP4;
                break;
            case MediaProperties.FILE_JPEG:
                retValue = FileType.JPG;
                break;
            case MediaProperties.FILE_PNG:
                retValue = FileType.PNG;
                break;
            case MediaProperties.FILE_M4V:
                retValue = FileType.M4V;
                break;

            default:
                retValue = -1;
        }
        return retValue;

    }

    /**
     * Maps the rendering mode used in native layer
     * to rendering mode used in JAVA layer
     *
     * @param renderingMode The rendering mode in JAVA layer
     *
     * @return The rendering mode in native layer
     */
    int getMediaItemRenderingMode(int renderingMode) {
        int retValue = -1;
        switch (renderingMode) {
            case MediaItem.RENDERING_MODE_BLACK_BORDER:
                retValue = MediaRendering.BLACK_BORDERS;
                break;
            case MediaItem.RENDERING_MODE_STRETCH:
                retValue = MediaRendering.RESIZING;
                break;
            case MediaItem.RENDERING_MODE_CROPPING:
                retValue = MediaRendering.CROPPING;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the transition behavior used in JAVA layer
     * to transition behavior used in native layer
     *
     * @param transitionType The transition behavior in JAVA layer
     *
     * @return The transition behavior in native layer
     */
    int getVideoTransitionBehaviour(int transitionType) {
        int retValue = -1;
        switch (transitionType) {
            case Transition.BEHAVIOR_SPEED_UP:
                retValue = TransitionBehaviour.SPEED_UP;
                break;
            case Transition.BEHAVIOR_SPEED_DOWN:
                retValue = TransitionBehaviour.SPEED_DOWN;
                break;
            case Transition.BEHAVIOR_LINEAR:
                retValue = TransitionBehaviour.LINEAR;
                break;
            case Transition.BEHAVIOR_MIDDLE_SLOW:
                retValue = TransitionBehaviour.SLOW_MIDDLE;
                break;
            case Transition.BEHAVIOR_MIDDLE_FAST:
                retValue = TransitionBehaviour.FAST_MIDDLE;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the transition slide direction used in JAVA layer
     * to transition slide direction used in native layer
     *
     * @param slideDirection The transition slide direction
     * in JAVA layer
     *
     * @return The transition slide direction in native layer
     */
    int getSlideSettingsDirection(int slideDirection) {
        int retValue = -1;
        switch (slideDirection) {
            case TransitionSliding.DIRECTION_RIGHT_OUT_LEFT_IN:
                retValue = SlideDirection.RIGHT_OUT_LEFT_IN;
                break;
            case TransitionSliding.DIRECTION_LEFT_OUT_RIGHT_IN:
                retValue = SlideDirection.LEFT_OUT_RIGTH_IN;
                break;
            case TransitionSliding.DIRECTION_TOP_OUT_BOTTOM_IN:
                retValue = SlideDirection.TOP_OUT_BOTTOM_IN;
                break;
            case TransitionSliding.DIRECTION_BOTTOM_OUT_TOP_IN:
                retValue = SlideDirection.BOTTOM_OUT_TOP_IN;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Maps the effect color type used in JAVA layer
     * to effect color type used in native layer
     *
     * @param effect The EffectColor reference
     *
     * @return The color effect value from native layer
     */
    private int getEffectColorType(EffectColor effect) {
        int retValue = -1;
        switch (effect.getType()) {
            case EffectColor.TYPE_COLOR:
                if (effect.getColor() == EffectColor.GREEN) {
                    retValue = VideoEffect.GREEN;
                } else if (effect.getColor() == EffectColor.PINK) {
                    retValue = VideoEffect.PINK;
                } else if (effect.getColor() == EffectColor.GRAY) {
                    retValue = VideoEffect.BLACK_AND_WHITE;
                } else {
                    retValue = VideoEffect.COLORRGB16;
                }
                break;
            case EffectColor.TYPE_GRADIENT:
                retValue = VideoEffect.GRADIENT;
                break;
            case EffectColor.TYPE_SEPIA:
                retValue = VideoEffect.SEPIA;
                break;
            case EffectColor.TYPE_NEGATIVE:
                retValue = VideoEffect.NEGATIVE;
                break;
            case EffectColor.TYPE_FIFTIES:
                retValue = VideoEffect.FIFTIES;
                break;

            default:
                retValue = -1;
        }
        return retValue;
    }

    /**
     * Calculates video resolution for output clip
     * based on clip's height and aspect ratio of storyboard
     *
     * @param aspectRatio The aspect ratio of story board
     * @param height The height of clip
     *
     * @return The video resolution
     */
    private int findVideoResolution(int aspectRatio, int height) {
        final Pair<Integer, Integer>[] resolutions;
        final Pair<Integer, Integer> maxResolution;
        int retValue = VideoFrameSize.SIZE_UNDEFINED;
        switch (aspectRatio) {
            case MediaProperties.ASPECT_RATIO_3_2:
                if (height == MediaProperties.HEIGHT_480)
                    retValue = VideoFrameSize.NTSC;
                else if (height == MediaProperties.HEIGHT_720)
                    retValue = VideoFrameSize.W720p;
                break;
            case MediaProperties.ASPECT_RATIO_16_9:
                if (height == MediaProperties.HEIGHT_480)
                    retValue = VideoFrameSize.WVGA16x9;
                else if (height == MediaProperties.HEIGHT_720)
                    retValue = VideoFrameSize.V720p;
                else if (height == MediaProperties.HEIGHT_1080)
                    retValue = VideoFrameSize.V1080p;
                break;
            case MediaProperties.ASPECT_RATIO_4_3:
                if (height == MediaProperties.HEIGHT_480)
                    retValue = VideoFrameSize.VGA;
                else if (height == MediaProperties.HEIGHT_720)
                    retValue = VideoFrameSize.S720p;
                break;
            case MediaProperties.ASPECT_RATIO_5_3:
                if (height == MediaProperties.HEIGHT_480)
                    retValue = VideoFrameSize.WVGA;
                break;
            case MediaProperties.ASPECT_RATIO_11_9:
                if (height == MediaProperties.HEIGHT_144)
                    retValue = VideoFrameSize.QCIF;
                else if (height == MediaProperties.HEIGHT_288)
                    retValue = VideoFrameSize.CIF;
                break;
        }
        if (retValue == VideoFrameSize.SIZE_UNDEFINED) {
            resolutions = MediaProperties.getSupportedResolutions(mVideoEditor.getAspectRatio());
            // Get the highest resolution
            maxResolution = resolutions[resolutions.length - 1];
            retValue = findVideoResolution(mVideoEditor.getAspectRatio(), maxResolution.second);
        }

        return retValue;
    }

    /**
     * This method is responsible for exporting a movie
     *
     * @param filePath The output file path
     * @param projectDir The output project directory
     * @param height The height of clip
     * @param bitrate The bitrate at which the movie should be exported
     * @param mediaItemsList The media items list
     * @param mediaTransitionList The transitions list
     * @param mediaBGMList The background track list
     * @param listener The ExportProgressListener
     *
     */
    void export(String filePath, String projectDir, int height, int bitrate,
            List<MediaItem> mediaItemsList, List<Transition> mediaTransitionList,
            List<AudioTrack> mediaBGMList, ExportProgressListener listener) {

        int outBitrate = 0;
        mExportFilename = filePath;
        previewStoryBoard(mediaItemsList, mediaTransitionList, mediaBGMList,null);
        mExportProgressListener = listener;

        mProgressToApp = 0;

        switch (bitrate) {
            case MediaProperties.BITRATE_28K:
                outBitrate = Bitrate.BR_32_KBPS;
                break;
            case MediaProperties.BITRATE_40K:
                outBitrate = Bitrate.BR_48_KBPS;
                break;
            case MediaProperties.BITRATE_64K:
                outBitrate = Bitrate.BR_64_KBPS;
                break;
            case MediaProperties.BITRATE_96K:
                outBitrate = Bitrate.BR_96_KBPS;
                break;
            case MediaProperties.BITRATE_128K:
                outBitrate = Bitrate.BR_128_KBPS;
                break;
            case MediaProperties.BITRATE_192K:
                outBitrate = Bitrate.BR_192_KBPS;
                break;
            case MediaProperties.BITRATE_256K:
                outBitrate = Bitrate.BR_256_KBPS;
                break;
            case MediaProperties.BITRATE_384K:
                outBitrate = Bitrate.BR_384_KBPS;
                break;
            case MediaProperties.BITRATE_512K:
                outBitrate = Bitrate.BR_512_KBPS;
                break;
            case MediaProperties.BITRATE_800K:
                outBitrate = Bitrate.BR_800_KBPS;
                break;
            case MediaProperties.BITRATE_2M:
                outBitrate = Bitrate.BR_2_MBPS;
                break;

            case MediaProperties.BITRATE_5M:
                outBitrate = Bitrate.BR_5_MBPS;
                break;
            case MediaProperties.BITRATE_8M:
                outBitrate = Bitrate.BR_8_MBPS;
                break;

            default:
                throw new IllegalArgumentException("Argument Bitrate incorrect");
        }
        mPreviewEditSettings.videoFrameRate = VideoFrameRate.FR_30_FPS;
        mPreviewEditSettings.outputFile = mOutputFilename = filePath;

        int aspectRatio = mVideoEditor.getAspectRatio();
        mPreviewEditSettings.videoFrameSize = findVideoResolution(aspectRatio, height);
        mPreviewEditSettings.videoFormat = VideoFormat.H264;
        mPreviewEditSettings.audioFormat = AudioFormat.AAC;
        mPreviewEditSettings.audioSamplingFreq = AudioSamplingFrequency.FREQ_32000;
        mPreviewEditSettings.maxFileSize = 0;
        mPreviewEditSettings.audioChannels = 2;
        mPreviewEditSettings.videoBitrate = outBitrate;
        mPreviewEditSettings.audioBitrate = Bitrate.BR_96_KBPS;

        mPreviewEditSettings.transitionSettingsArray = new TransitionSettings[mTotalClips - 1];
        for (int index = 0; index < mTotalClips - 1; index++) {
            mPreviewEditSettings.transitionSettingsArray[index] = new TransitionSettings();
            mPreviewEditSettings.transitionSettingsArray[index].videoTransitionType =
                VideoTransition.NONE;
            mPreviewEditSettings.transitionSettingsArray[index].audioTransitionType =
                AudioTransition.NONE;
        }

        for (int clipCnt = 0; clipCnt < mPreviewEditSettings.clipSettingsArray.length; clipCnt++) {
            if (mPreviewEditSettings.clipSettingsArray[clipCnt].fileType == FileType.JPG) {
                mPreviewEditSettings.clipSettingsArray[clipCnt].clipPath =
                mPreviewEditSettings.clipSettingsArray[clipCnt].clipOriginalPath;
            }
        }
        nativePopulateSettings(mPreviewEditSettings, mClipProperties, mAudioSettings);

        int err = 0;
        try {
            mProcessingState  = PROCESSING_EXPORT;
            mProcessingObject = null;
            err = generateClip(mPreviewEditSettings);
            mProcessingState  = PROCESSING_NONE;
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "IllegalArgument for generateClip");
            throw ex;
        } catch (IllegalStateException ex) {
            Log.e(TAG, "IllegalStateExceptiont for generateClip");
            throw ex;
        } catch (RuntimeException ex) {
            Log.e(TAG, "RuntimeException for generateClip");
            throw ex;
        }

        if (err != 0) {
            Log.e(TAG, "RuntimeException for generateClip");
            throw new RuntimeException("generateClip failed with error=" + err);
        }

        mExportProgressListener = null;
    }

    /**
     * This methods takes care of stopping the Export process
     *
     * @param The input file name for which export has to be stopped
     */
    void stop(String filename) {
        try {
            stopEncoding();
            new File(mExportFilename).delete();
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Illegal state exception in unload settings");
            throw ex;
        } catch (RuntimeException ex) {
            Log.e(TAG, "Runtime exception in unload settings");
            throw ex;
        }
    }

    /**
     * This method extracts a frame from the input file
     * and returns the frame as a bitmap
     *
     * @param inputFile The inputFile
     * @param width The width of the output frame
     * @param height The height of the output frame
     * @param timeMS The time in ms at which the frame has to be extracted
     */
    Bitmap getPixels(String inputFile, int width, int height, long timeMS) {
        if (inputFile == null) {
            throw new IllegalArgumentException("Invalid input file");
        }

        /* Make width and height as even */
        final int newWidth = (width + 1) & 0xFFFFFFFE;
        final int newHeight = (height + 1) & 0xFFFFFFFE;

        /* Create a temp bitmap for resized thumbnails */
        Bitmap tempBitmap = null;
        if ((newWidth != width) || (newHeight != height)) {
             tempBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
        }

        IntBuffer rgb888 = IntBuffer.allocate(newWidth * newHeight * 4);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        nativeGetPixels(inputFile, rgb888.array(), newWidth, newHeight, timeMS);

        if ((newWidth == width) && (newHeight == height)) {
            bitmap.copyPixelsFromBuffer(rgb888);
        } else {
            /* Create a temp bitmap to be used for resize */
            tempBitmap.copyPixelsFromBuffer(rgb888);

            /* Create a canvas to resize */
            final Canvas canvas = new Canvas(bitmap);
            canvas.drawBitmap(tempBitmap, new Rect(0, 0, newWidth, newHeight),
                                          new Rect(0, 0, width, height), sResizePaint);
        }

        if (tempBitmap != null) {
            tempBitmap.recycle();
        }

        return bitmap;
    }

    /**
     * This method extracts a list of frame from the
     * input file and returns the frame in bitmap array
     *
     * @param filename The inputFile
     * @param width The width of the output frame
     * @param height The height of the output frame
     * @param startMs The starting time in ms
     * @param endMs The end time in ms
     * @param thumbnailCount The number of frames to be extracted
     * from startMs to endMs
     *
     * @return The frames as bitmaps in bitmap array
     **/
    Bitmap[] getPixelsList(String filename, int width, int height, long startMs, long endMs,
            int thumbnailCount) {
        int[] rgb888 = null;
        int thumbnailSize = 0;
        Bitmap tempBitmap = null;

        /* Make width and height as even */
        final int newWidth = (width + 1) & 0xFFFFFFFE;
        final int newHeight = (height + 1) & 0xFFFFFFFE;
        thumbnailSize = newWidth * newHeight * 4;

        /* Create a temp bitmap for resized thumbnails */
        if ((newWidth != width) || (newHeight != height)) {
            tempBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
        }
        int i = 0;
        int deltaTime = (int)(endMs - startMs) / thumbnailCount;
        Bitmap[] bitmaps = null;

        try {
            // This may result in out of Memory Error
            rgb888 = new int[thumbnailSize * thumbnailCount];
            bitmaps = new Bitmap[thumbnailCount];
        } catch (Throwable e) {
            // Allocating to new size with Fixed count
            try {
                rgb888 = new int[thumbnailSize * MAX_THUMBNAIL_PERMITTED];
                bitmaps = new Bitmap[MAX_THUMBNAIL_PERMITTED];
                thumbnailCount = MAX_THUMBNAIL_PERMITTED;
            } catch (Throwable ex) {
                throw new RuntimeException("Memory allocation fails, thumbnail count too large: "
                        + thumbnailCount);
            }
        }
        IntBuffer tmpBuffer = IntBuffer.allocate(thumbnailSize);
        nativeGetPixelsList(filename, rgb888, newWidth, newHeight, deltaTime, thumbnailCount,
                startMs, endMs);

        for (; i < thumbnailCount; i++) {
            bitmaps[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            tmpBuffer.put(rgb888, (i * thumbnailSize), thumbnailSize);
            tmpBuffer.rewind();

            if ((newWidth == width) && (newHeight == height)) {
                bitmaps[i].copyPixelsFromBuffer(tmpBuffer);
            } else {
                /* Copy the out rgb buffer to temp bitmap */
                tempBitmap.copyPixelsFromBuffer(tmpBuffer);

                /* Create a canvas to resize */
                final Canvas canvas = new Canvas(bitmaps[i]);
                canvas.drawBitmap(tempBitmap, new Rect(0, 0, newWidth, newHeight),
                                              new Rect(0, 0, width, height), sResizePaint);
            }
        }

        if (tempBitmap != null) {
            tempBitmap.recycle();
        }

        return bitmaps;
    }

    /**
     * This method generates the audio graph
     *
     * @param uniqueId The unique id
     * @param inFileName The inputFile
     * @param OutAudiGraphFileName output filename
     * @param frameDuration The each frame duration
     * @param audioChannels The number of audio channels
     * @param samplesCount Total number of samples count
     * @param listener ExtractAudioWaveformProgressListener reference
     * @param isVideo The flag to indicate if the file is video file or not
     *
     **/
    void generateAudioGraph(String uniqueId, String inFileName, String OutAudiGraphFileName,
            int frameDuration, int audioChannels, int samplesCount,
            ExtractAudioWaveformProgressListener listener, boolean isVideo) {
        String tempPCMFileName;

        mExtractAudioWaveformProgressListener = listener;

        /**
         * In case of Video, first call will generate the PCM file to make the
         * audio graph
         */
        if (isVideo) {
            tempPCMFileName = String.format(mProjectPath + "/" + uniqueId + ".pcm");
        } else {
            tempPCMFileName = mAudioTrackPCMFilePath;
        }

        /**
         * For Video item, generate the PCM
         */
        if (isVideo) {
            nativeGenerateRawAudio(inFileName, tempPCMFileName);
        }

        nativeGenerateAudioGraph(tempPCMFileName, OutAudiGraphFileName, frameDuration,
                audioChannels, samplesCount);

        /**
         * Once the audio graph file is generated, delete the pcm file
         */
        if (isVideo) {
            new File(tempPCMFileName).delete();
        }
    }

    void clearPreviewSurface(Surface surface) {
        nativeClearSurface(surface);
    }

    /**
     * Grab the semaphore which arbitrates access to the editor
     *
     * @throws InterruptedException
     */
    private void lock() throws InterruptedException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbing semaphore", new Throwable());
        }
        mLock.acquire();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "lock: grabbed semaphore");
        }
    }

    /**
     * Release the semaphore which arbitrates access to the editor
     */
    private void unlock() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "unlock: releasing semaphore");
        }
        mLock.release();
    }

    /**     Native Methods        */
    native Properties getMediaProperties(String file) throws IllegalArgumentException,
            IllegalStateException, RuntimeException, Exception;

    /**
     * Get the version of ManualEdit.
     *
     * @return version of ManualEdit
     * @throws RuntimeException if an error occurred
     * @see Version
     */
    private static native Version getVersion() throws RuntimeException;

    /**
     * Returns the video thumbnail in an array of integers. Output format is
     * ARGB8888.
     *
     * @param pixelArray the array that receives the pixel values
     * @param width width of the video thumbnail
     * @param height height of the video thumbnail
     * @param timeMS desired time of the thumbnail in ms
     * @return actual time in ms of the thumbnail generated
     * @throws IllegalStateException if the class has not been initialized
     * @throws IllegalArgumentException if the pixelArray is not available or
     *             one of the dimensions is negative or zero or the time is
     *             negative
     * @throws RuntimeException on runtime errors in native code
     */
    private native int nativeGetPixels(String fileName, int[] pixelArray, int width, int height,
            long timeMS);

    private native int nativeGetPixelsList(String fileName, int[] pixelArray, int width, int height,
            int timeMS, int nosofTN, long startTimeMs, long endTimeMs);

    /**
     * Releases the JNI and cleans up the core native module.. Should be called
     * only after init( )
     *
     * @throws IllegalStateException if the method could not be called
     */
    private native void release() throws IllegalStateException, RuntimeException;

    /*
     * Clear the preview surface
     */
    private native void nativeClearSurface(Surface surface);

    /**
     * Stops the encoding. This method should only be called after encoding has
     * started using method <code> startEncoding</code>
     *
     * @throws IllegalStateException if the method could not be called
     */
    private native void stopEncoding() throws IllegalStateException, RuntimeException;


    private native void _init(String tempPath, String libraryPath)
            throws IllegalArgumentException, IllegalStateException, RuntimeException;

    private native void nativeStartPreview(Surface mSurface, long fromMs, long toMs,
            int callbackAfterFrameCount, boolean loop) throws IllegalArgumentException,
            IllegalStateException, RuntimeException;

    private native void nativePopulateSettings(EditSettings editSettings,
            PreviewClipProperties mProperties, AudioSettings mAudioSettings)
    throws IllegalArgumentException, IllegalStateException, RuntimeException;

    private native int nativeRenderPreviewFrame(Surface mSurface, long timeMs,
                                                 int surfaceWidth, int surfaceHeight)
                                                 throws IllegalArgumentException,
                                                 IllegalStateException, RuntimeException;

    private native int nativeRenderMediaItemPreviewFrame(Surface mSurface, String filepath,
            int framewidth, int frameheight, int surfacewidth, int surfaceheight, long timeMs)
    throws IllegalArgumentException, IllegalStateException, RuntimeException;

    private native int nativeStopPreview();

    private native int nativeGenerateAudioGraph(String pcmFilePath, String outGraphPath,
            int frameDuration, int channels, int sampleCount);

    private native int nativeGenerateRawAudio(String InFileName, String PCMFileName);

    private native int nativeGenerateClip(EditSettings editSettings)
    throws IllegalArgumentException, IllegalStateException, RuntimeException;

}
