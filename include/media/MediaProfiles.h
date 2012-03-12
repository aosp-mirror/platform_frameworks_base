/*
 **
 ** Copyright 2010, The Android Open Source Project.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#ifndef ANDROID_MEDIAPROFILES_H
#define ANDROID_MEDIAPROFILES_H

#include <utils/threads.h>
#include <media/mediarecorder.h>

namespace android {

enum camcorder_quality {
    CAMCORDER_QUALITY_LIST_START = 0,
    CAMCORDER_QUALITY_LOW  = 0,
    CAMCORDER_QUALITY_HIGH = 1,
    CAMCORDER_QUALITY_QCIF = 2,
    CAMCORDER_QUALITY_CIF = 3,
    CAMCORDER_QUALITY_480P = 4,
    CAMCORDER_QUALITY_720P = 5,
    CAMCORDER_QUALITY_1080P = 6,
    CAMCORDER_QUALITY_QVGA = 7,
    CAMCORDER_QUALITY_LIST_END = 7,

    CAMCORDER_QUALITY_TIME_LAPSE_LIST_START = 1000,
    CAMCORDER_QUALITY_TIME_LAPSE_LOW  = 1000,
    CAMCORDER_QUALITY_TIME_LAPSE_HIGH = 1001,
    CAMCORDER_QUALITY_TIME_LAPSE_QCIF = 1002,
    CAMCORDER_QUALITY_TIME_LAPSE_CIF = 1003,
    CAMCORDER_QUALITY_TIME_LAPSE_480P = 1004,
    CAMCORDER_QUALITY_TIME_LAPSE_720P = 1005,
    CAMCORDER_QUALITY_TIME_LAPSE_1080P = 1006,
    CAMCORDER_QUALITY_TIME_LAPSE_QVGA = 1007,
    CAMCORDER_QUALITY_TIME_LAPSE_LIST_END = 1007,
};

/**
 * Set CIF as default maximum import and export resolution of video editor.
 * The maximum import and export resolutions are platform specific,
 * which should be defined in media_profiles.xml.
 * Set default maximum prefetch YUV frames to 6, which means video editor can
 * queue up to 6 YUV frames in the video encoder source.
 * This value is used to limit the amount of memory used by video editor
 * engine when the encoder consumes YUV frames at a lower speed
 * than video editor engine produces.
 */
enum videoeditor_capability {
    VIDEOEDITOR_DEFAULT_MAX_INPUT_FRAME_WIDTH = 352,
    VIDEOEDITOR_DEFUALT_MAX_INPUT_FRAME_HEIGHT = 288,
    VIDEOEDITOR_DEFAULT_MAX_OUTPUT_FRAME_WIDTH = 352,
    VIDEOEDITOR_DEFUALT_MAX_OUTPUT_FRAME_HEIGHT = 288,
    VIDEOEDITOR_DEFAULT_MAX_PREFETCH_YUV_FRAMES = 6
};

enum video_decoder {
    VIDEO_DECODER_WMV,
};

enum audio_decoder {
    AUDIO_DECODER_WMA,
};


class MediaProfiles
{
public:

    /**
     * Returns the singleton instance for subsequence queries.
     * or NULL if error.
     */
    static MediaProfiles* getInstance();

    /**
     * Returns the value for the given param name for the given camera at
     * the given quality level, or -1 if error.
     *
     * Supported param name are:
     * duration - the recording duration.
     * file.format - output file format. see mediarecorder.h for details
     * vid.codec - video encoder. see mediarecorder.h for details.
     * aud.codec - audio encoder. see mediarecorder.h for details.
     * vid.width - video frame width
     * vid.height - video frame height
     * vid.fps - video frame rate
     * vid.bps - video bit rate
     * aud.bps - audio bit rate
     * aud.hz - audio sample rate
     * aud.ch - number of audio channels
     */
    int getCamcorderProfileParamByName(const char *name, int cameraId,
                                       camcorder_quality quality) const;

    /**
     * Returns true if a profile for the given camera at the given quality exists,
     * or false if not.
     */
    bool hasCamcorderProfile(int cameraId, camcorder_quality quality) const;

    /**
     * Returns the output file formats supported.
     */
    Vector<output_format> getOutputFileFormats() const;

    /**
     * Returns the video encoders supported.
     */
    Vector<video_encoder> getVideoEncoders() const;

    /**
     * Returns the value for the given param name for the given video encoder
     * returned from getVideoEncoderByIndex or -1 if error.
     *
     * Supported param name are:
     * enc.vid.width.min - min video frame width
     * enc.vid.width.max - max video frame width
     * enc.vid.height.min - min video frame height
     * enc.vid.height.max - max video frame height
     * enc.vid.bps.min - min bit rate in bits per second
     * enc.vid.bps.max - max bit rate in bits per second
     * enc.vid.fps.min - min frame rate in frames per second
     * enc.vid.fps.max - max frame rate in frames per second
     */
    int getVideoEncoderParamByName(const char *name, video_encoder codec) const;

    /**
     * Returns the value for the given param name for the video editor cap
     * param or -1 if error.
     * Supported param name are:
     * videoeditor.input.width.max - max input video frame width
     * videoeditor.input.height.max - max input video frame height
     * videoeditor.output.width.max - max output video frame width
     * videoeditor.output.height.max - max output video frame height
     * maxPrefetchYUVFrames - max prefetch YUV frames in video editor engine. This value is used
     * to limit the memory consumption.
     */
    int getVideoEditorCapParamByName(const char *name) const;

    /**
     * Returns the value for the given param name for the video editor export codec format
     * param or -1 if error.
     * Supported param name are:
     * videoeditor.export.profile - export video profile
     * videoeditor.export.level - export video level
     * Supported param codec are:
     * 1 for h263
     * 2 for h264
     * 3 for mpeg4
     */
    int getVideoEditorExportParamByName(const char *name, int codec) const;

    /**
     * Returns the audio encoders supported.
     */
    Vector<audio_encoder> getAudioEncoders() const;

    /**
     * Returns the value for the given param name for the given audio encoder
     * returned from getAudioEncoderByIndex or -1 if error.
     *
     * Supported param name are:
     * enc.aud.ch.min - min number of channels
     * enc.aud.ch.max - max number of channels
     * enc.aud.bps.min - min bit rate in bits per second
     * enc.aud.bps.max - max bit rate in bits per second
     * enc.aud.hz.min - min sample rate in samples per second
     * enc.aud.hz.max - max sample rate in samples per second
     */
    int getAudioEncoderParamByName(const char *name, audio_encoder codec) const;

    /**
      * Returns the video decoders supported.
      */
    Vector<video_decoder> getVideoDecoders() const;

     /**
      * Returns the audio decoders supported.
      */
    Vector<audio_decoder> getAudioDecoders() const;

    /**
     * Returns the number of image encoding quality levels supported.
     */
    Vector<int> getImageEncodingQualityLevels(int cameraId) const;

    /**
     * Returns the start time offset (in ms) for the given camera Id.
     * If the given camera Id does not exist, -1 will be returned.
     */
    int getStartTimeOffsetMs(int cameraId) const;

private:
    enum {
        // Camcorder profiles (high/low) and timelapse profiles (high/low)
        kNumRequiredProfiles = 4,
    };

    MediaProfiles& operator=(const MediaProfiles&);  // Don't call me
    MediaProfiles(const MediaProfiles&);             // Don't call me
    MediaProfiles() { mVideoEditorCap = NULL; }        // Dummy default constructor
    ~MediaProfiles();                                // Don't delete me

    struct VideoCodec {
        VideoCodec(video_encoder codec, int bitRate, int frameWidth, int frameHeight, int frameRate)
            : mCodec(codec),
              mBitRate(bitRate),
              mFrameWidth(frameWidth),
              mFrameHeight(frameHeight),
              mFrameRate(frameRate) {}

        VideoCodec(const VideoCodec& copy) {
            mCodec = copy.mCodec;
            mBitRate = copy.mBitRate;
            mFrameWidth = copy.mFrameWidth;
            mFrameHeight = copy.mFrameHeight;
            mFrameRate = copy.mFrameRate;
        }

        ~VideoCodec() {}

        video_encoder mCodec;
        int mBitRate;
        int mFrameWidth;
        int mFrameHeight;
        int mFrameRate;
    };

    struct AudioCodec {
        AudioCodec(audio_encoder codec, int bitRate, int sampleRate, int channels)
            : mCodec(codec),
              mBitRate(bitRate),
              mSampleRate(sampleRate),
              mChannels(channels) {}

        AudioCodec(const AudioCodec& copy) {
            mCodec = copy.mCodec;
            mBitRate = copy.mBitRate;
            mSampleRate = copy.mSampleRate;
            mChannels = copy.mChannels;
        }

        ~AudioCodec() {}

        audio_encoder mCodec;
        int mBitRate;
        int mSampleRate;
        int mChannels;
    };

    struct CamcorderProfile {
        CamcorderProfile()
            : mCameraId(0),
              mFileFormat(OUTPUT_FORMAT_THREE_GPP),
              mQuality(CAMCORDER_QUALITY_HIGH),
              mDuration(0),
              mVideoCodec(0),
              mAudioCodec(0) {}

        CamcorderProfile(const CamcorderProfile& copy) {
            mCameraId = copy.mCameraId;
            mFileFormat = copy.mFileFormat;
            mQuality = copy.mQuality;
            mDuration = copy.mDuration;
            mVideoCodec = new VideoCodec(*copy.mVideoCodec);
            mAudioCodec = new AudioCodec(*copy.mAudioCodec);
        }

        ~CamcorderProfile() {
            delete mVideoCodec;
            delete mAudioCodec;
        }

        int mCameraId;
        output_format mFileFormat;
        camcorder_quality mQuality;
        int mDuration;
        VideoCodec *mVideoCodec;
        AudioCodec *mAudioCodec;
    };

    struct VideoEncoderCap {
        // Ugly constructor
        VideoEncoderCap(video_encoder codec,
                        int minBitRate, int maxBitRate,
                        int minFrameWidth, int maxFrameWidth,
                        int minFrameHeight, int maxFrameHeight,
                        int minFrameRate, int maxFrameRate)
            : mCodec(codec),
              mMinBitRate(minBitRate), mMaxBitRate(maxBitRate),
              mMinFrameWidth(minFrameWidth), mMaxFrameWidth(maxFrameWidth),
              mMinFrameHeight(minFrameHeight), mMaxFrameHeight(maxFrameHeight),
              mMinFrameRate(minFrameRate), mMaxFrameRate(maxFrameRate) {}

         ~VideoEncoderCap() {}

        video_encoder mCodec;
        int mMinBitRate, mMaxBitRate;
        int mMinFrameWidth, mMaxFrameWidth;
        int mMinFrameHeight, mMaxFrameHeight;
        int mMinFrameRate, mMaxFrameRate;
    };

    struct AudioEncoderCap {
        // Ugly constructor
        AudioEncoderCap(audio_encoder codec,
                        int minBitRate, int maxBitRate,
                        int minSampleRate, int maxSampleRate,
                        int minChannels, int maxChannels)
            : mCodec(codec),
              mMinBitRate(minBitRate), mMaxBitRate(maxBitRate),
              mMinSampleRate(minSampleRate), mMaxSampleRate(maxSampleRate),
              mMinChannels(minChannels), mMaxChannels(maxChannels) {}

        ~AudioEncoderCap() {}

        audio_encoder mCodec;
        int mMinBitRate, mMaxBitRate;
        int mMinSampleRate, mMaxSampleRate;
        int mMinChannels, mMaxChannels;
    };

    struct VideoDecoderCap {
        VideoDecoderCap(video_decoder codec): mCodec(codec) {}
        ~VideoDecoderCap() {}

        video_decoder mCodec;
    };

    struct AudioDecoderCap {
        AudioDecoderCap(audio_decoder codec): mCodec(codec) {}
        ~AudioDecoderCap() {}

        audio_decoder mCodec;
    };

    struct NameToTagMap {
        const char* name;
        int tag;
    };

    struct ImageEncodingQualityLevels {
        int mCameraId;
        Vector<int> mLevels;
    };
    struct ExportVideoProfile {
        ExportVideoProfile(int codec, int profile, int level)
            :mCodec(codec),mProfile(profile),mLevel(level) {}
        ~ExportVideoProfile() {}
        int mCodec;
        int mProfile;
        int mLevel;
    };
    struct VideoEditorCap {
        VideoEditorCap(int inFrameWidth, int inFrameHeight,
            int outFrameWidth, int outFrameHeight, int frames)
            : mMaxInputFrameWidth(inFrameWidth),
              mMaxInputFrameHeight(inFrameHeight),
              mMaxOutputFrameWidth(outFrameWidth),
              mMaxOutputFrameHeight(outFrameHeight),
              mMaxPrefetchYUVFrames(frames) {}

        ~VideoEditorCap() {}

        int mMaxInputFrameWidth;
        int mMaxInputFrameHeight;
        int mMaxOutputFrameWidth;
        int mMaxOutputFrameHeight;
        int mMaxPrefetchYUVFrames;
    };

    int getCamcorderProfileIndex(int cameraId, camcorder_quality quality) const;
    void initRequiredProfileRefs(const Vector<int>& cameraIds);
    int getRequiredProfileRefIndex(int cameraId);

    // Debug
    static void logVideoCodec(const VideoCodec& codec);
    static void logAudioCodec(const AudioCodec& codec);
    static void logVideoEncoderCap(const VideoEncoderCap& cap);
    static void logAudioEncoderCap(const AudioEncoderCap& cap);
    static void logVideoDecoderCap(const VideoDecoderCap& cap);
    static void logAudioDecoderCap(const AudioDecoderCap& cap);
    static void logVideoEditorCap(const VideoEditorCap& cap);

    // If the xml configuration file does exist, use the settings
    // from the xml
    static MediaProfiles* createInstanceFromXmlFile(const char *xml);
    static output_format createEncoderOutputFileFormat(const char **atts);
    static VideoCodec* createVideoCodec(const char **atts, MediaProfiles *profiles);
    static AudioCodec* createAudioCodec(const char **atts, MediaProfiles *profiles);
    static AudioDecoderCap* createAudioDecoderCap(const char **atts);
    static VideoDecoderCap* createVideoDecoderCap(const char **atts);
    static VideoEncoderCap* createVideoEncoderCap(const char **atts);
    static AudioEncoderCap* createAudioEncoderCap(const char **atts);
    static VideoEditorCap* createVideoEditorCap(
                const char **atts, MediaProfiles *profiles);
    static ExportVideoProfile* createExportVideoProfile(const char **atts);

    static CamcorderProfile* createCamcorderProfile(
                int cameraId, const char **atts, Vector<int>& cameraIds);

    static int getCameraId(const char **atts);

    void addStartTimeOffset(int cameraId, const char **atts);

    ImageEncodingQualityLevels* findImageEncodingQualityLevels(int cameraId) const;
    void addImageEncodingQualityLevel(int cameraId, const char** atts);

    // Customized element tag handler for parsing the xml configuration file.
    static void startElementHandler(void *userData, const char *name, const char **atts);

    // If the xml configuration file does not exist, use hard-coded values
    static MediaProfiles* createDefaultInstance();

    static CamcorderProfile *createDefaultCamcorderQcifProfile(camcorder_quality quality);
    static CamcorderProfile *createDefaultCamcorderCifProfile(camcorder_quality quality);
    static void createDefaultCamcorderLowProfiles(
            MediaProfiles::CamcorderProfile **lowProfile,
            MediaProfiles::CamcorderProfile **lowSpecificProfile);
    static void createDefaultCamcorderHighProfiles(
            MediaProfiles::CamcorderProfile **highProfile,
            MediaProfiles::CamcorderProfile **highSpecificProfile);

    static CamcorderProfile *createDefaultCamcorderTimeLapseQcifProfile(camcorder_quality quality);
    static CamcorderProfile *createDefaultCamcorderTimeLapse480pProfile(camcorder_quality quality);
    static void createDefaultCamcorderTimeLapseLowProfiles(
            MediaProfiles::CamcorderProfile **lowTimeLapseProfile,
            MediaProfiles::CamcorderProfile **lowSpecificTimeLapseProfile);
    static void createDefaultCamcorderTimeLapseHighProfiles(
            MediaProfiles::CamcorderProfile **highTimeLapseProfile,
            MediaProfiles::CamcorderProfile **highSpecificTimeLapseProfile);

    static void createDefaultCamcorderProfiles(MediaProfiles *profiles);
    static void createDefaultVideoEncoders(MediaProfiles *profiles);
    static void createDefaultAudioEncoders(MediaProfiles *profiles);
    static void createDefaultVideoDecoders(MediaProfiles *profiles);
    static void createDefaultAudioDecoders(MediaProfiles *profiles);
    static void createDefaultEncoderOutputFileFormats(MediaProfiles *profiles);
    static void createDefaultImageEncodingQualityLevels(MediaProfiles *profiles);
    static void createDefaultImageDecodingMaxMemory(MediaProfiles *profiles);
    static void createDefaultVideoEditorCap(MediaProfiles *profiles);
    static void createDefaultExportVideoProfiles(MediaProfiles *profiles);

    static VideoEncoderCap* createDefaultH263VideoEncoderCap();
    static VideoEncoderCap* createDefaultM4vVideoEncoderCap();
    static AudioEncoderCap* createDefaultAmrNBEncoderCap();

    static int findTagForName(const NameToTagMap *map, size_t nMappings, const char *name);

    /**
     * Check on existing profiles with the following criteria:
     * 1. Low quality profile must have the lowest video
     *    resolution product (width x height)
     * 2. High quality profile must have the highest video
     *    resolution product (width x height)
     *
     * and add required low/high quality camcorder/timelapse
     * profiles if they are not found. This allows to remove
     * duplicate profile definitions in the media_profiles.xml
     * file.
     */
    void checkAndAddRequiredProfilesIfNecessary();


    // Mappings from name (for instance, codec name) to enum value
    static const NameToTagMap sVideoEncoderNameMap[];
    static const NameToTagMap sAudioEncoderNameMap[];
    static const NameToTagMap sFileFormatMap[];
    static const NameToTagMap sVideoDecoderNameMap[];
    static const NameToTagMap sAudioDecoderNameMap[];
    static const NameToTagMap sCamcorderQualityNameMap[];

    static bool sIsInitialized;
    static MediaProfiles *sInstance;
    static Mutex sLock;
    int mCurrentCameraId;

    Vector<CamcorderProfile*> mCamcorderProfiles;
    Vector<AudioEncoderCap*>  mAudioEncoders;
    Vector<VideoEncoderCap*>  mVideoEncoders;
    Vector<AudioDecoderCap*>  mAudioDecoders;
    Vector<VideoDecoderCap*>  mVideoDecoders;
    Vector<output_format>     mEncoderOutputFileFormats;
    Vector<ImageEncodingQualityLevels *>  mImageEncodingQualityLevels;
    KeyedVector<int, int> mStartTimeOffsets;

    typedef struct {
        bool mHasRefProfile;      // Refers to an existing profile
        int  mRefProfileIndex;    // Reference profile index
        int  mResolutionProduct;  // width x height
    } RequiredProfileRefInfo;     // Required low and high profiles

    typedef struct {
        RequiredProfileRefInfo mRefs[kNumRequiredProfiles];
        int mCameraId;
    } RequiredProfiles;

    RequiredProfiles *mRequiredProfileRefs;
    Vector<int>              mCameraIds;
    VideoEditorCap* mVideoEditorCap;
    Vector<ExportVideoProfile*> mVideoEditorExportProfiles;
};

}; // namespace android

#endif // ANDROID_MEDIAPROFILES_H
