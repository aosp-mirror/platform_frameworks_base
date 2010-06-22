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
    CAMCORDER_QUALITY_LOW  = 0,
    CAMCORDER_QUALITY_HIGH = 1
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

private:
    MediaProfiles& operator=(const MediaProfiles&);  // Don't call me
    MediaProfiles(const MediaProfiles&);             // Don't call me
    MediaProfiles() {}                               // Dummy default constructor
    ~MediaProfiles();                                // Don't delete me

    struct VideoCodec {
        VideoCodec(video_encoder codec, int bitRate, int frameWidth, int frameHeight, int frameRate)
            : mCodec(codec),
              mBitRate(bitRate),
              mFrameWidth(frameWidth),
              mFrameHeight(frameHeight),
              mFrameRate(frameRate) {}

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

    // Debug
    static void logVideoCodec(const VideoCodec& codec);
    static void logAudioCodec(const AudioCodec& codec);
    static void logVideoEncoderCap(const VideoEncoderCap& cap);
    static void logAudioEncoderCap(const AudioEncoderCap& cap);
    static void logVideoDecoderCap(const VideoDecoderCap& cap);
    static void logAudioDecoderCap(const AudioDecoderCap& cap);

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
    static CamcorderProfile* createCamcorderProfile(int cameraId, const char **atts);
    static int getCameraId(const char **atts);

    ImageEncodingQualityLevels* findImageEncodingQualityLevels(int cameraId) const;
    void addImageEncodingQualityLevel(int cameraId, const char** atts);

    // Customized element tag handler for parsing the xml configuration file.
    static void startElementHandler(void *userData, const char *name, const char **atts);

    // If the xml configuration file does not exist, use hard-coded values
    static MediaProfiles* createDefaultInstance();
    static CamcorderProfile *createDefaultCamcorderLowProfile();
    static CamcorderProfile *createDefaultCamcorderHighProfile();
    static void createDefaultCamcorderProfiles(MediaProfiles *profiles);
    static void createDefaultVideoEncoders(MediaProfiles *profiles);
    static void createDefaultAudioEncoders(MediaProfiles *profiles);
    static void createDefaultVideoDecoders(MediaProfiles *profiles);
    static void createDefaultAudioDecoders(MediaProfiles *profiles);
    static void createDefaultEncoderOutputFileFormats(MediaProfiles *profiles);
    static void createDefaultImageEncodingQualityLevels(MediaProfiles *profiles);
    static void createDefaultImageDecodingMaxMemory(MediaProfiles *profiles);
    static VideoEncoderCap* createDefaultH263VideoEncoderCap();
    static VideoEncoderCap* createDefaultM4vVideoEncoderCap();
    static AudioEncoderCap* createDefaultAmrNBEncoderCap();

    static int findTagForName(const NameToTagMap *map, size_t nMappings, const char *name);

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
};

}; // namespace android

#endif // ANDROID_MEDIAPROFILES_H

