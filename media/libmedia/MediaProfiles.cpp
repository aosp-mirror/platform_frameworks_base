/*
**
** Copyright 2010, The Android Open Source Project
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


//#define LOG_NDEBUG 0
#define LOG_TAG "MediaProfiles"

#include <stdlib.h>
#include <utils/Log.h>
#include <utils/Vector.h>
#include <cutils/properties.h>
#include <expat.h>
#include <media/MediaProfiles.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/openmax/OMX_Video.h>

namespace android {

Mutex MediaProfiles::sLock;
bool MediaProfiles::sIsInitialized = false;
MediaProfiles *MediaProfiles::sInstance = NULL;

const MediaProfiles::NameToTagMap MediaProfiles::sVideoEncoderNameMap[] = {
    {"h263", VIDEO_ENCODER_H263},
    {"h264", VIDEO_ENCODER_H264},
    {"m4v",  VIDEO_ENCODER_MPEG_4_SP}
};

const MediaProfiles::NameToTagMap MediaProfiles::sAudioEncoderNameMap[] = {
    {"amrnb", AUDIO_ENCODER_AMR_NB},
    {"amrwb", AUDIO_ENCODER_AMR_WB},
    {"aac",   AUDIO_ENCODER_AAC},
};

const MediaProfiles::NameToTagMap MediaProfiles::sFileFormatMap[] = {
    {"3gp", OUTPUT_FORMAT_THREE_GPP},
    {"mp4", OUTPUT_FORMAT_MPEG_4}
};

const MediaProfiles::NameToTagMap MediaProfiles::sVideoDecoderNameMap[] = {
    {"wmv", VIDEO_DECODER_WMV}
};

const MediaProfiles::NameToTagMap MediaProfiles::sAudioDecoderNameMap[] = {
    {"wma", AUDIO_DECODER_WMA}
};

const MediaProfiles::NameToTagMap MediaProfiles::sCamcorderQualityNameMap[] = {
    {"low", CAMCORDER_QUALITY_LOW},
    {"high", CAMCORDER_QUALITY_HIGH},
    {"qcif", CAMCORDER_QUALITY_QCIF},
    {"cif", CAMCORDER_QUALITY_CIF},
    {"480p", CAMCORDER_QUALITY_480P},
    {"720p", CAMCORDER_QUALITY_720P},
    {"1080p", CAMCORDER_QUALITY_1080P},
    {"qvga", CAMCORDER_QUALITY_QVGA},

    {"timelapselow",  CAMCORDER_QUALITY_TIME_LAPSE_LOW},
    {"timelapsehigh", CAMCORDER_QUALITY_TIME_LAPSE_HIGH},
    {"timelapseqcif", CAMCORDER_QUALITY_TIME_LAPSE_QCIF},
    {"timelapsecif", CAMCORDER_QUALITY_TIME_LAPSE_CIF},
    {"timelapse480p", CAMCORDER_QUALITY_TIME_LAPSE_480P},
    {"timelapse720p", CAMCORDER_QUALITY_TIME_LAPSE_720P},
    {"timelapse1080p", CAMCORDER_QUALITY_TIME_LAPSE_1080P},
    {"timelapseqvga", CAMCORDER_QUALITY_TIME_LAPSE_QVGA},
};

/*static*/ void
MediaProfiles::logVideoCodec(const MediaProfiles::VideoCodec& codec)
{
    LOGV("video codec:");
    LOGV("codec = %d", codec.mCodec);
    LOGV("bit rate: %d", codec.mBitRate);
    LOGV("frame width: %d", codec.mFrameWidth);
    LOGV("frame height: %d", codec.mFrameHeight);
    LOGV("frame rate: %d", codec.mFrameRate);
}

/*static*/ void
MediaProfiles::logAudioCodec(const MediaProfiles::AudioCodec& codec)
{
    LOGV("audio codec:");
    LOGV("codec = %d", codec.mCodec);
    LOGV("bit rate: %d", codec.mBitRate);
    LOGV("sample rate: %d", codec.mSampleRate);
    LOGV("number of channels: %d", codec.mChannels);
}

/*static*/ void
MediaProfiles::logVideoEncoderCap(const MediaProfiles::VideoEncoderCap& cap)
{
    LOGV("video encoder cap:");
    LOGV("codec = %d", cap.mCodec);
    LOGV("bit rate: min = %d and max = %d", cap.mMinBitRate, cap.mMaxBitRate);
    LOGV("frame width: min = %d and max = %d", cap.mMinFrameWidth, cap.mMaxFrameWidth);
    LOGV("frame height: min = %d and max = %d", cap.mMinFrameHeight, cap.mMaxFrameHeight);
    LOGV("frame rate: min = %d and max = %d", cap.mMinFrameRate, cap.mMaxFrameRate);
}

/*static*/ void
MediaProfiles::logAudioEncoderCap(const MediaProfiles::AudioEncoderCap& cap)
{
    LOGV("audio encoder cap:");
    LOGV("codec = %d", cap.mCodec);
    LOGV("bit rate: min = %d and max = %d", cap.mMinBitRate, cap.mMaxBitRate);
    LOGV("sample rate: min = %d and max = %d", cap.mMinSampleRate, cap.mMaxSampleRate);
    LOGV("number of channels: min = %d and max = %d", cap.mMinChannels, cap.mMaxChannels);
}

/*static*/ void
MediaProfiles::logVideoDecoderCap(const MediaProfiles::VideoDecoderCap& cap)
{
    LOGV("video decoder cap:");
    LOGV("codec = %d", cap.mCodec);
}

/*static*/ void
MediaProfiles::logAudioDecoderCap(const MediaProfiles::AudioDecoderCap& cap)
{
    LOGV("audio codec cap:");
    LOGV("codec = %d", cap.mCodec);
}

/*static*/ void
MediaProfiles::logVideoEditorCap(const MediaProfiles::VideoEditorCap& cap)
{
    LOGV("videoeditor cap:");
    LOGV("mMaxInputFrameWidth = %d", cap.mMaxInputFrameWidth);
    LOGV("mMaxInputFrameHeight = %d", cap.mMaxInputFrameHeight);
    LOGV("mMaxOutputFrameWidth = %d", cap.mMaxOutputFrameWidth);
    LOGV("mMaxOutputFrameHeight = %d", cap.mMaxOutputFrameHeight);
}

/*static*/ int
MediaProfiles::findTagForName(const MediaProfiles::NameToTagMap *map, size_t nMappings, const char *name)
{
    int tag = -1;
    for (size_t i = 0; i < nMappings; ++i) {
        if (!strcmp(map[i].name, name)) {
            tag = map[i].tag;
            break;
        }
    }
    return tag;
}

/*static*/ MediaProfiles::VideoCodec*
MediaProfiles::createVideoCodec(const char **atts, MediaProfiles *profiles)
{
    CHECK(!strcmp("codec",     atts[0]) &&
          !strcmp("bitRate",   atts[2]) &&
          !strcmp("width",     atts[4]) &&
          !strcmp("height",    atts[6]) &&
          !strcmp("frameRate", atts[8]));

    const size_t nMappings = sizeof(sVideoEncoderNameMap)/sizeof(sVideoEncoderNameMap[0]);
    const int codec = findTagForName(sVideoEncoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::VideoCodec *videoCodec =
        new MediaProfiles::VideoCodec(static_cast<video_encoder>(codec),
            atoi(atts[3]), atoi(atts[5]), atoi(atts[7]), atoi(atts[9]));
    logVideoCodec(*videoCodec);

    size_t nCamcorderProfiles;
    CHECK((nCamcorderProfiles = profiles->mCamcorderProfiles.size()) >= 1);
    profiles->mCamcorderProfiles[nCamcorderProfiles - 1]->mVideoCodec = videoCodec;
    return videoCodec;
}

/*static*/ MediaProfiles::AudioCodec*
MediaProfiles::createAudioCodec(const char **atts, MediaProfiles *profiles)
{
    CHECK(!strcmp("codec",      atts[0]) &&
          !strcmp("bitRate",    atts[2]) &&
          !strcmp("sampleRate", atts[4]) &&
          !strcmp("channels",   atts[6]));
    const size_t nMappings = sizeof(sAudioEncoderNameMap)/sizeof(sAudioEncoderNameMap[0]);
    const int codec = findTagForName(sAudioEncoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::AudioCodec *audioCodec =
        new MediaProfiles::AudioCodec(static_cast<audio_encoder>(codec),
            atoi(atts[3]), atoi(atts[5]), atoi(atts[7]));
    logAudioCodec(*audioCodec);

    size_t nCamcorderProfiles;
    CHECK((nCamcorderProfiles = profiles->mCamcorderProfiles.size()) >= 1);
    profiles->mCamcorderProfiles[nCamcorderProfiles - 1]->mAudioCodec = audioCodec;
    return audioCodec;
}
/*static*/ MediaProfiles::AudioDecoderCap*
MediaProfiles::createAudioDecoderCap(const char **atts)
{
    CHECK(!strcmp("name",    atts[0]) &&
          !strcmp("enabled", atts[2]));

    const size_t nMappings = sizeof(sAudioDecoderNameMap)/sizeof(sAudioDecoderNameMap[0]);
    const int codec = findTagForName(sAudioDecoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::AudioDecoderCap *cap =
        new MediaProfiles::AudioDecoderCap(static_cast<audio_decoder>(codec));
    logAudioDecoderCap(*cap);
    return cap;
}

/*static*/ MediaProfiles::VideoDecoderCap*
MediaProfiles::createVideoDecoderCap(const char **atts)
{
    CHECK(!strcmp("name",    atts[0]) &&
          !strcmp("enabled", atts[2]));

    const size_t nMappings = sizeof(sVideoDecoderNameMap)/sizeof(sVideoDecoderNameMap[0]);
    const int codec = findTagForName(sVideoDecoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::VideoDecoderCap *cap =
        new MediaProfiles::VideoDecoderCap(static_cast<video_decoder>(codec));
    logVideoDecoderCap(*cap);
    return cap;
}

/*static*/ MediaProfiles::VideoEncoderCap*
MediaProfiles::createVideoEncoderCap(const char **atts)
{
    CHECK(!strcmp("name",           atts[0])  &&
          !strcmp("enabled",        atts[2])  &&
          !strcmp("minBitRate",     atts[4])  &&
          !strcmp("maxBitRate",     atts[6])  &&
          !strcmp("minFrameWidth",  atts[8])  &&
          !strcmp("maxFrameWidth",  atts[10]) &&
          !strcmp("minFrameHeight", atts[12]) &&
          !strcmp("maxFrameHeight", atts[14]) &&
          !strcmp("minFrameRate",   atts[16]) &&
          !strcmp("maxFrameRate",   atts[18]));

    const size_t nMappings = sizeof(sVideoEncoderNameMap)/sizeof(sVideoEncoderNameMap[0]);
    const int codec = findTagForName(sVideoEncoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::VideoEncoderCap *cap =
        new MediaProfiles::VideoEncoderCap(static_cast<video_encoder>(codec),
            atoi(atts[5]), atoi(atts[7]), atoi(atts[9]), atoi(atts[11]), atoi(atts[13]),
            atoi(atts[15]), atoi(atts[17]), atoi(atts[19]));
    logVideoEncoderCap(*cap);
    return cap;
}

/*static*/ MediaProfiles::AudioEncoderCap*
MediaProfiles::createAudioEncoderCap(const char **atts)
{
    CHECK(!strcmp("name",          atts[0])  &&
          !strcmp("enabled",       atts[2])  &&
          !strcmp("minBitRate",    atts[4])  &&
          !strcmp("maxBitRate",    atts[6])  &&
          !strcmp("minSampleRate", atts[8])  &&
          !strcmp("maxSampleRate", atts[10]) &&
          !strcmp("minChannels",   atts[12]) &&
          !strcmp("maxChannels",   atts[14]));

    const size_t nMappings = sizeof(sAudioEncoderNameMap)/sizeof(sAudioEncoderNameMap[0]);
    const int codec = findTagForName(sAudioEncoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::AudioEncoderCap *cap =
        new MediaProfiles::AudioEncoderCap(static_cast<audio_encoder>(codec), atoi(atts[5]), atoi(atts[7]),
            atoi(atts[9]), atoi(atts[11]), atoi(atts[13]),
            atoi(atts[15]));
    logAudioEncoderCap(*cap);
    return cap;
}

/*static*/ output_format
MediaProfiles::createEncoderOutputFileFormat(const char **atts)
{
    CHECK(!strcmp("name", atts[0]));

    const size_t nMappings =sizeof(sFileFormatMap)/sizeof(sFileFormatMap[0]);
    const int format = findTagForName(sFileFormatMap, nMappings, atts[1]);
    CHECK(format != -1);

    return static_cast<output_format>(format);
}

static bool isCameraIdFound(int cameraId, const Vector<int>& cameraIds) {
    for (int i = 0, n = cameraIds.size(); i < n; ++i) {
        if (cameraId == cameraIds[i]) {
            return true;
        }
    }
    return false;
}

/*static*/ MediaProfiles::CamcorderProfile*
MediaProfiles::createCamcorderProfile(int cameraId, const char **atts, Vector<int>& cameraIds)
{
    CHECK(!strcmp("quality",    atts[0]) &&
          !strcmp("fileFormat", atts[2]) &&
          !strcmp("duration",   atts[4]));

    const size_t nProfileMappings = sizeof(sCamcorderQualityNameMap)/sizeof(sCamcorderQualityNameMap[0]);
    const int quality = findTagForName(sCamcorderQualityNameMap, nProfileMappings, atts[1]);
    CHECK(quality != -1);

    const size_t nFormatMappings = sizeof(sFileFormatMap)/sizeof(sFileFormatMap[0]);
    const int fileFormat = findTagForName(sFileFormatMap, nFormatMappings, atts[3]);
    CHECK(fileFormat != -1);

    MediaProfiles::CamcorderProfile *profile = new MediaProfiles::CamcorderProfile;
    profile->mCameraId = cameraId;
    if (!isCameraIdFound(cameraId, cameraIds)) {
        cameraIds.add(cameraId);
    }
    profile->mFileFormat = static_cast<output_format>(fileFormat);
    profile->mQuality = static_cast<camcorder_quality>(quality);
    profile->mDuration = atoi(atts[5]);
    return profile;
}

MediaProfiles::ImageEncodingQualityLevels*
MediaProfiles::findImageEncodingQualityLevels(int cameraId) const
{
    int n = mImageEncodingQualityLevels.size();
    for (int i = 0; i < n; i++) {
        ImageEncodingQualityLevels *levels = mImageEncodingQualityLevels[i];
        if (levels->mCameraId == cameraId) {
            return levels;
        }
    }
    return NULL;
}

void MediaProfiles::addImageEncodingQualityLevel(int cameraId, const char** atts)
{
    CHECK(!strcmp("quality", atts[0]));
    int quality = atoi(atts[1]);
    LOGV("%s: cameraId=%d, quality=%d\n", __func__, cameraId, quality);
    ImageEncodingQualityLevels *levels = findImageEncodingQualityLevels(cameraId);

    if (levels == NULL) {
        levels = new ImageEncodingQualityLevels();
        levels->mCameraId = cameraId;
        mImageEncodingQualityLevels.add(levels);
    }

    levels->mLevels.add(quality);
}

/*static*/ int
MediaProfiles::getCameraId(const char** atts)
{
    if (!atts[0]) return 0;  // default cameraId = 0
    CHECK(!strcmp("cameraId", atts[0]));
    return atoi(atts[1]);
}

void MediaProfiles::addStartTimeOffset(int cameraId, const char** atts)
{
    int offsetTimeMs = 700;
    if (atts[2]) {
        CHECK(!strcmp("startOffsetMs", atts[2]));
        offsetTimeMs = atoi(atts[3]);
    }

    LOGV("%s: cameraId=%d, offset=%d ms", __func__, cameraId, offsetTimeMs);
    mStartTimeOffsets.replaceValueFor(cameraId, offsetTimeMs);
}
/*static*/ MediaProfiles::ExportVideoProfile*
MediaProfiles::createExportVideoProfile(const char **atts)
{
    CHECK(!strcmp("name", atts[0]) &&
          !strcmp("profile", atts[2]) &&
          !strcmp("level", atts[4]));

    const size_t nMappings =
        sizeof(sVideoEncoderNameMap)/sizeof(sVideoEncoderNameMap[0]);
    const int codec = findTagForName(sVideoEncoderNameMap, nMappings, atts[1]);
    CHECK(codec != -1);

    MediaProfiles::ExportVideoProfile *profile =
        new MediaProfiles::ExportVideoProfile(
            codec, atoi(atts[3]), atoi(atts[5]));

    return profile;
}
/*static*/ MediaProfiles::VideoEditorCap*
MediaProfiles::createVideoEditorCap(const char **atts, MediaProfiles *profiles)
{
    CHECK(!strcmp("maxInputFrameWidth", atts[0]) &&
          !strcmp("maxInputFrameHeight", atts[2])  &&
          !strcmp("maxOutputFrameWidth", atts[4]) &&
          !strcmp("maxOutputFrameHeight", atts[6]) &&
          !strcmp("maxPrefetchYUVFrames", atts[8]));

    MediaProfiles::VideoEditorCap *pVideoEditorCap =
        new MediaProfiles::VideoEditorCap(atoi(atts[1]), atoi(atts[3]),
                atoi(atts[5]), atoi(atts[7]), atoi(atts[9]));

    logVideoEditorCap(*pVideoEditorCap);
    profiles->mVideoEditorCap = pVideoEditorCap;

    return pVideoEditorCap;
}

/*static*/ void
MediaProfiles::startElementHandler(void *userData, const char *name, const char **atts)
{
    MediaProfiles *profiles = (MediaProfiles *) userData;
    if (strcmp("Video", name) == 0) {
        createVideoCodec(atts, profiles);
    } else if (strcmp("Audio", name) == 0) {
        createAudioCodec(atts, profiles);
    } else if (strcmp("VideoEncoderCap", name) == 0 &&
               strcmp("true", atts[3]) == 0) {
        profiles->mVideoEncoders.add(createVideoEncoderCap(atts));
    } else if (strcmp("AudioEncoderCap", name) == 0 &&
               strcmp("true", atts[3]) == 0) {
        profiles->mAudioEncoders.add(createAudioEncoderCap(atts));
    } else if (strcmp("VideoDecoderCap", name) == 0 &&
               strcmp("true", atts[3]) == 0) {
        profiles->mVideoDecoders.add(createVideoDecoderCap(atts));
    } else if (strcmp("AudioDecoderCap", name) == 0 &&
               strcmp("true", atts[3]) == 0) {
        profiles->mAudioDecoders.add(createAudioDecoderCap(atts));
    } else if (strcmp("EncoderOutputFileFormat", name) == 0) {
        profiles->mEncoderOutputFileFormats.add(createEncoderOutputFileFormat(atts));
    } else if (strcmp("CamcorderProfiles", name) == 0) {
        profiles->mCurrentCameraId = getCameraId(atts);
        profiles->addStartTimeOffset(profiles->mCurrentCameraId, atts);
    } else if (strcmp("EncoderProfile", name) == 0) {
        profiles->mCamcorderProfiles.add(
            createCamcorderProfile(profiles->mCurrentCameraId, atts, profiles->mCameraIds));
    } else if (strcmp("ImageEncoding", name) == 0) {
        profiles->addImageEncodingQualityLevel(profiles->mCurrentCameraId, atts);
    } else if (strcmp("VideoEditorCap", name) == 0) {
        createVideoEditorCap(atts, profiles);
    } else if (strcmp("ExportVideoProfile", name) == 0) {
        profiles->mVideoEditorExportProfiles.add(createExportVideoProfile(atts));
    }
}

static bool isCamcorderProfile(camcorder_quality quality) {
    return quality >= CAMCORDER_QUALITY_LIST_START &&
           quality <= CAMCORDER_QUALITY_LIST_END;
}

static bool isTimelapseProfile(camcorder_quality quality) {
    return quality >= CAMCORDER_QUALITY_TIME_LAPSE_LIST_START &&
           quality <= CAMCORDER_QUALITY_TIME_LAPSE_LIST_END;
}

void MediaProfiles::initRequiredProfileRefs(const Vector<int>& cameraIds) {
    LOGV("Number of camera ids: %d", cameraIds.size());
    CHECK(cameraIds.size() > 0);
    mRequiredProfileRefs = new RequiredProfiles[cameraIds.size()];
    for (size_t i = 0, n = cameraIds.size(); i < n; ++i) {
        mRequiredProfileRefs[i].mCameraId = cameraIds[i];
        for (size_t j = 0; j < kNumRequiredProfiles; ++j) {
            mRequiredProfileRefs[i].mRefs[j].mHasRefProfile = false;
            mRequiredProfileRefs[i].mRefs[j].mRefProfileIndex = -1;
            if ((j & 1) == 0) {  // low resolution
                mRequiredProfileRefs[i].mRefs[j].mResolutionProduct = 0x7FFFFFFF;
            } else {             // high resolution
                mRequiredProfileRefs[i].mRefs[j].mResolutionProduct = 0;
            }
        }
    }
}

int MediaProfiles::getRequiredProfileRefIndex(int cameraId) {
    for (size_t i = 0, n = mCameraIds.size(); i < n; ++i) {
        if (mCameraIds[i] == cameraId) {
            return i;
        }
    }
    return -1;
}

void MediaProfiles::checkAndAddRequiredProfilesIfNecessary() {
    if (sIsInitialized) {
        return;
    }

    initRequiredProfileRefs(mCameraIds);

    for (size_t i = 0, n = mCamcorderProfiles.size(); i < n; ++i) {
        int product = mCamcorderProfiles[i]->mVideoCodec->mFrameWidth *
                      mCamcorderProfiles[i]->mVideoCodec->mFrameHeight;

        camcorder_quality quality = mCamcorderProfiles[i]->mQuality;
        int cameraId = mCamcorderProfiles[i]->mCameraId;
        int index = -1;
        int refIndex = getRequiredProfileRefIndex(cameraId);
        CHECK(refIndex != -1);
        RequiredProfileRefInfo *info;
        camcorder_quality refQuality;
        VideoCodec *codec = NULL;

        // Check high and low from either camcorder profile or timelapse profile
        // but not both. Default, check camcorder profile
        size_t j = 0;
        size_t n = 2;
        if (isTimelapseProfile(quality)) {
            // Check timelapse profile instead.
            j = 2;
            n = kNumRequiredProfiles;
        } else {
            // Must be camcorder profile.
            CHECK(isCamcorderProfile(quality));
        }
        for (; j < n; ++j) {
            info = &(mRequiredProfileRefs[refIndex].mRefs[j]);
            if ((j % 2 == 0 && product > info->mResolutionProduct) ||  // low
                (j % 2 != 0 && product < info->mResolutionProduct)) {  // high
                continue;
            }
            switch (j) {
                case 0:
                   refQuality = CAMCORDER_QUALITY_LOW;
                   break;
                case 1:
                   refQuality = CAMCORDER_QUALITY_HIGH;
                   break;
                case 2:
                   refQuality = CAMCORDER_QUALITY_TIME_LAPSE_LOW;
                   break;
                case 3:
                   refQuality = CAMCORDER_QUALITY_TIME_LAPSE_HIGH;
                   break;
                default:
                    CHECK(!"Should never reach here");
            }

            if (!info->mHasRefProfile) {
                index = getCamcorderProfileIndex(cameraId, refQuality);
            }
            if (index == -1) {
                // New high or low quality profile is found.
                // Update its reference.
                info->mHasRefProfile = true;
                info->mRefProfileIndex = i;
                info->mResolutionProduct = product;
            }
        }
    }

    for (size_t cameraId = 0; cameraId < mCameraIds.size(); ++cameraId) {
        for (size_t j = 0; j < kNumRequiredProfiles; ++j) {
            int refIndex = getRequiredProfileRefIndex(cameraId);
            CHECK(refIndex != -1);
            RequiredProfileRefInfo *info =
                    &mRequiredProfileRefs[refIndex].mRefs[j];

            if (info->mHasRefProfile) {

                CamcorderProfile *profile =
                    new CamcorderProfile(
                            *mCamcorderProfiles[info->mRefProfileIndex]);

                // Overwrite the quality
                switch (j % kNumRequiredProfiles) {
                    case 0:
                        profile->mQuality = CAMCORDER_QUALITY_LOW;
                        break;
                    case 1:
                        profile->mQuality = CAMCORDER_QUALITY_HIGH;
                        break;
                    case 2:
                        profile->mQuality = CAMCORDER_QUALITY_TIME_LAPSE_LOW;
                        break;
                    case 3:
                        profile->mQuality = CAMCORDER_QUALITY_TIME_LAPSE_HIGH;
                        break;
                    default:
                        CHECK(!"Should never come here");
                }

                int index = getCamcorderProfileIndex(cameraId, profile->mQuality);
                if (index != -1) {
                    LOGV("Profile quality %d for camera %d already exists",
                        profile->mQuality, cameraId);
                    CHECK(index == refIndex);
                    continue;
                }

                // Insert the new profile
                LOGV("Add a profile: quality %d=>%d for camera %d",
                        mCamcorderProfiles[info->mRefProfileIndex]->mQuality,
                        profile->mQuality, cameraId);

                mCamcorderProfiles.add(profile);
            }
        }
    }
}

/*static*/ MediaProfiles*
MediaProfiles::getInstance()
{
    LOGV("getInstance");
    Mutex::Autolock lock(sLock);
    if (!sIsInitialized) {
        char value[PROPERTY_VALUE_MAX];
        if (property_get("media.settings.xml", value, NULL) <= 0) {
            const char *defaultXmlFile = "/etc/media_profiles.xml";
            FILE *fp = fopen(defaultXmlFile, "r");
            if (fp == NULL) {
                LOGW("could not find media config xml file");
                sInstance = createDefaultInstance();
            } else {
                fclose(fp);  // close the file first.
                sInstance = createInstanceFromXmlFile(defaultXmlFile);
            }
        } else {
            sInstance = createInstanceFromXmlFile(value);
        }
        CHECK(sInstance != NULL);
        sInstance->checkAndAddRequiredProfilesIfNecessary();
        sIsInitialized = true;
    }

    return sInstance;
}

/*static*/ MediaProfiles::VideoEncoderCap*
MediaProfiles::createDefaultH263VideoEncoderCap()
{
    return new MediaProfiles::VideoEncoderCap(
        VIDEO_ENCODER_H263, 192000, 420000, 176, 352, 144, 288, 1, 20);
}

/*static*/ MediaProfiles::VideoEncoderCap*
MediaProfiles::createDefaultM4vVideoEncoderCap()
{
    return new MediaProfiles::VideoEncoderCap(
        VIDEO_ENCODER_MPEG_4_SP, 192000, 420000, 176, 352, 144, 288, 1, 20);
}


/*static*/ void
MediaProfiles::createDefaultVideoEncoders(MediaProfiles *profiles)
{
    profiles->mVideoEncoders.add(createDefaultH263VideoEncoderCap());
    profiles->mVideoEncoders.add(createDefaultM4vVideoEncoderCap());
}

/*static*/ MediaProfiles::CamcorderProfile*
MediaProfiles::createDefaultCamcorderTimeLapseQcifProfile(camcorder_quality quality)
{
    MediaProfiles::VideoCodec *videoCodec =
        new MediaProfiles::VideoCodec(VIDEO_ENCODER_H263, 1000000, 176, 144, 20);

    AudioCodec *audioCodec = new AudioCodec(AUDIO_ENCODER_AMR_NB, 12200, 8000, 1);
    CamcorderProfile *profile = new MediaProfiles::CamcorderProfile;
    profile->mCameraId = 0;
    profile->mFileFormat = OUTPUT_FORMAT_THREE_GPP;
    profile->mQuality = quality;
    profile->mDuration = 60;
    profile->mVideoCodec = videoCodec;
    profile->mAudioCodec = audioCodec;
    return profile;
}

/*static*/ MediaProfiles::CamcorderProfile*
MediaProfiles::createDefaultCamcorderTimeLapse480pProfile(camcorder_quality quality)
{
    MediaProfiles::VideoCodec *videoCodec =
        new MediaProfiles::VideoCodec(VIDEO_ENCODER_H263, 20000000, 720, 480, 20);

    AudioCodec *audioCodec = new AudioCodec(AUDIO_ENCODER_AMR_NB, 12200, 8000, 1);
    CamcorderProfile *profile = new MediaProfiles::CamcorderProfile;
    profile->mCameraId = 0;
    profile->mFileFormat = OUTPUT_FORMAT_THREE_GPP;
    profile->mQuality = quality;
    profile->mDuration = 60;
    profile->mVideoCodec = videoCodec;
    profile->mAudioCodec = audioCodec;
    return profile;
}

/*static*/ void
MediaProfiles::createDefaultCamcorderTimeLapseLowProfiles(
        MediaProfiles::CamcorderProfile **lowTimeLapseProfile,
        MediaProfiles::CamcorderProfile **lowSpecificTimeLapseProfile) {
    *lowTimeLapseProfile = createDefaultCamcorderTimeLapseQcifProfile(CAMCORDER_QUALITY_TIME_LAPSE_LOW);
    *lowSpecificTimeLapseProfile = createDefaultCamcorderTimeLapseQcifProfile(CAMCORDER_QUALITY_TIME_LAPSE_QCIF);
}

/*static*/ void
MediaProfiles::createDefaultCamcorderTimeLapseHighProfiles(
        MediaProfiles::CamcorderProfile **highTimeLapseProfile,
        MediaProfiles::CamcorderProfile **highSpecificTimeLapseProfile) {
    *highTimeLapseProfile = createDefaultCamcorderTimeLapse480pProfile(CAMCORDER_QUALITY_TIME_LAPSE_HIGH);
    *highSpecificTimeLapseProfile = createDefaultCamcorderTimeLapse480pProfile(CAMCORDER_QUALITY_TIME_LAPSE_480P);
}

/*static*/ MediaProfiles::CamcorderProfile*
MediaProfiles::createDefaultCamcorderQcifProfile(camcorder_quality quality)
{
    MediaProfiles::VideoCodec *videoCodec =
        new MediaProfiles::VideoCodec(VIDEO_ENCODER_H263, 192000, 176, 144, 20);

    MediaProfiles::AudioCodec *audioCodec =
        new MediaProfiles::AudioCodec(AUDIO_ENCODER_AMR_NB, 12200, 8000, 1);

    MediaProfiles::CamcorderProfile *profile = new MediaProfiles::CamcorderProfile;
    profile->mCameraId = 0;
    profile->mFileFormat = OUTPUT_FORMAT_THREE_GPP;
    profile->mQuality = quality;
    profile->mDuration = 30;
    profile->mVideoCodec = videoCodec;
    profile->mAudioCodec = audioCodec;
    return profile;
}

/*static*/ MediaProfiles::CamcorderProfile*
MediaProfiles::createDefaultCamcorderCifProfile(camcorder_quality quality)
{
    MediaProfiles::VideoCodec *videoCodec =
        new MediaProfiles::VideoCodec(VIDEO_ENCODER_H263, 360000, 352, 288, 20);

    AudioCodec *audioCodec = new AudioCodec(AUDIO_ENCODER_AMR_NB, 12200, 8000, 1);
    CamcorderProfile *profile = new MediaProfiles::CamcorderProfile;
    profile->mCameraId = 0;
    profile->mFileFormat = OUTPUT_FORMAT_THREE_GPP;
    profile->mQuality = quality;
    profile->mDuration = 60;
    profile->mVideoCodec = videoCodec;
    profile->mAudioCodec = audioCodec;
    return profile;
}

/*static*/ void
MediaProfiles::createDefaultCamcorderLowProfiles(
        MediaProfiles::CamcorderProfile **lowProfile,
        MediaProfiles::CamcorderProfile **lowSpecificProfile) {
    *lowProfile = createDefaultCamcorderQcifProfile(CAMCORDER_QUALITY_LOW);
    *lowSpecificProfile = createDefaultCamcorderQcifProfile(CAMCORDER_QUALITY_QCIF);
}

/*static*/ void
MediaProfiles::createDefaultCamcorderHighProfiles(
        MediaProfiles::CamcorderProfile **highProfile,
        MediaProfiles::CamcorderProfile **highSpecificProfile) {
    *highProfile = createDefaultCamcorderCifProfile(CAMCORDER_QUALITY_HIGH);
    *highSpecificProfile = createDefaultCamcorderCifProfile(CAMCORDER_QUALITY_CIF);
}

/*static*/ void
MediaProfiles::createDefaultCamcorderProfiles(MediaProfiles *profiles)
{
    // low camcorder profiles.
    MediaProfiles::CamcorderProfile *lowProfile, *lowSpecificProfile;
    createDefaultCamcorderLowProfiles(&lowProfile, &lowSpecificProfile);
    profiles->mCamcorderProfiles.add(lowProfile);
    profiles->mCamcorderProfiles.add(lowSpecificProfile);

    // high camcorder profiles.
    MediaProfiles::CamcorderProfile* highProfile, *highSpecificProfile;
    createDefaultCamcorderHighProfiles(&highProfile, &highSpecificProfile);
    profiles->mCamcorderProfiles.add(highProfile);
    profiles->mCamcorderProfiles.add(highSpecificProfile);

    // low camcorder time lapse profiles.
    MediaProfiles::CamcorderProfile *lowTimeLapseProfile, *lowSpecificTimeLapseProfile;
    createDefaultCamcorderTimeLapseLowProfiles(&lowTimeLapseProfile, &lowSpecificTimeLapseProfile);
    profiles->mCamcorderProfiles.add(lowTimeLapseProfile);
    profiles->mCamcorderProfiles.add(lowSpecificTimeLapseProfile);

    // high camcorder time lapse profiles.
    MediaProfiles::CamcorderProfile *highTimeLapseProfile, *highSpecificTimeLapseProfile;
    createDefaultCamcorderTimeLapseHighProfiles(&highTimeLapseProfile, &highSpecificTimeLapseProfile);
    profiles->mCamcorderProfiles.add(highTimeLapseProfile);
    profiles->mCamcorderProfiles.add(highSpecificTimeLapseProfile);

    // For emulator and other legacy devices which does not have a
    // media_profiles.xml file, We assume that the default camera id
    // is 0 and that is the only camera available.
    profiles->mCameraIds.push(0);
}

/*static*/ void
MediaProfiles::createDefaultAudioEncoders(MediaProfiles *profiles)
{
    profiles->mAudioEncoders.add(createDefaultAmrNBEncoderCap());
}

/*static*/ void
MediaProfiles::createDefaultVideoDecoders(MediaProfiles *profiles)
{
    MediaProfiles::VideoDecoderCap *cap =
        new MediaProfiles::VideoDecoderCap(VIDEO_DECODER_WMV);

    profiles->mVideoDecoders.add(cap);
}

/*static*/ void
MediaProfiles::createDefaultAudioDecoders(MediaProfiles *profiles)
{
    MediaProfiles::AudioDecoderCap *cap =
        new MediaProfiles::AudioDecoderCap(AUDIO_DECODER_WMA);

    profiles->mAudioDecoders.add(cap);
}

/*static*/ void
MediaProfiles::createDefaultEncoderOutputFileFormats(MediaProfiles *profiles)
{
    profiles->mEncoderOutputFileFormats.add(OUTPUT_FORMAT_THREE_GPP);
    profiles->mEncoderOutputFileFormats.add(OUTPUT_FORMAT_MPEG_4);
}

/*static*/ MediaProfiles::AudioEncoderCap*
MediaProfiles::createDefaultAmrNBEncoderCap()
{
    return new MediaProfiles::AudioEncoderCap(
        AUDIO_ENCODER_AMR_NB, 5525, 12200, 8000, 8000, 1, 1);
}

/*static*/ void
MediaProfiles::createDefaultImageEncodingQualityLevels(MediaProfiles *profiles)
{
    ImageEncodingQualityLevels *levels = new ImageEncodingQualityLevels();
    levels->mCameraId = 0;
    levels->mLevels.add(70);
    levels->mLevels.add(80);
    levels->mLevels.add(90);
    profiles->mImageEncodingQualityLevels.add(levels);
}

/*static*/ void
MediaProfiles::createDefaultVideoEditorCap(MediaProfiles *profiles)
{
    profiles->mVideoEditorCap =
        new MediaProfiles::VideoEditorCap(
                VIDEOEDITOR_DEFAULT_MAX_INPUT_FRAME_WIDTH,
                VIDEOEDITOR_DEFUALT_MAX_INPUT_FRAME_HEIGHT,
                VIDEOEDITOR_DEFAULT_MAX_OUTPUT_FRAME_WIDTH,
                VIDEOEDITOR_DEFUALT_MAX_OUTPUT_FRAME_HEIGHT,
                VIDEOEDITOR_DEFAULT_MAX_PREFETCH_YUV_FRAMES);
}
/*static*/ void
MediaProfiles::createDefaultExportVideoProfiles(MediaProfiles *profiles)
{
    // Create default video export profiles
    profiles->mVideoEditorExportProfiles.add(
        new ExportVideoProfile(VIDEO_ENCODER_H263,
            OMX_VIDEO_H263ProfileBaseline, OMX_VIDEO_H263Level10));
    profiles->mVideoEditorExportProfiles.add(
        new ExportVideoProfile(VIDEO_ENCODER_MPEG_4_SP,
            OMX_VIDEO_MPEG4ProfileSimple, OMX_VIDEO_MPEG4Level1));
    profiles->mVideoEditorExportProfiles.add(
        new ExportVideoProfile(VIDEO_ENCODER_H264,
            OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel13));
}

/*static*/ MediaProfiles*
MediaProfiles::createDefaultInstance()
{
    MediaProfiles *profiles = new MediaProfiles;
    createDefaultCamcorderProfiles(profiles);
    createDefaultVideoEncoders(profiles);
    createDefaultAudioEncoders(profiles);
    createDefaultVideoDecoders(profiles);
    createDefaultAudioDecoders(profiles);
    createDefaultEncoderOutputFileFormats(profiles);
    createDefaultImageEncodingQualityLevels(profiles);
    createDefaultVideoEditorCap(profiles);
    createDefaultExportVideoProfiles(profiles);
    return profiles;
}

/*static*/ MediaProfiles*
MediaProfiles::createInstanceFromXmlFile(const char *xml)
{
    FILE *fp = NULL;
    CHECK((fp = fopen(xml, "r")));

    XML_Parser parser = ::XML_ParserCreate(NULL);
    CHECK(parser != NULL);

    MediaProfiles *profiles = new MediaProfiles();
    ::XML_SetUserData(parser, profiles);
    ::XML_SetElementHandler(parser, startElementHandler, NULL);

    /*
      FIXME:
      expat is not compiled with -DXML_DTD. We don't have DTD parsing support.

      if (!::XML_SetParamEntityParsing(parser, XML_PARAM_ENTITY_PARSING_ALWAYS)) {
          LOGE("failed to enable DTD support in the xml file");
          return UNKNOWN_ERROR;
      }

    */

    const int BUFF_SIZE = 512;
    for (;;) {
        void *buff = ::XML_GetBuffer(parser, BUFF_SIZE);
        if (buff == NULL) {
            LOGE("failed to in call to XML_GetBuffer()");
            delete profiles;
            profiles = NULL;
            goto exit;
        }

        int bytes_read = ::fread(buff, 1, BUFF_SIZE, fp);
        if (bytes_read < 0) {
            LOGE("failed in call to read");
            delete profiles;
            profiles = NULL;
            goto exit;
        }

        CHECK(::XML_ParseBuffer(parser, bytes_read, bytes_read == 0));

        if (bytes_read == 0) break;  // done parsing the xml file
    }

exit:
    ::XML_ParserFree(parser);
    ::fclose(fp);
    return profiles;
}

Vector<output_format> MediaProfiles::getOutputFileFormats() const
{
    return mEncoderOutputFileFormats;  // copy out
}

Vector<video_encoder> MediaProfiles::getVideoEncoders() const
{
    Vector<video_encoder> encoders;
    for (size_t i = 0; i < mVideoEncoders.size(); ++i) {
        encoders.add(mVideoEncoders[i]->mCodec);
    }
    return encoders;  // copy out
}

int MediaProfiles::getVideoEncoderParamByName(const char *name, video_encoder codec) const
{
    LOGV("getVideoEncoderParamByName: %s for codec %d", name, codec);
    int index = -1;
    for (size_t i = 0, n = mVideoEncoders.size(); i < n; ++i) {
        if (mVideoEncoders[i]->mCodec == codec) {
            index = i;
            break;
        }
    }
    if (index == -1) {
        LOGE("The given video encoder %d is not found", codec);
        return -1;
    }

    if (!strcmp("enc.vid.width.min", name)) return mVideoEncoders[index]->mMinFrameWidth;
    if (!strcmp("enc.vid.width.max", name)) return mVideoEncoders[index]->mMaxFrameWidth;
    if (!strcmp("enc.vid.height.min", name)) return mVideoEncoders[index]->mMinFrameHeight;
    if (!strcmp("enc.vid.height.max", name)) return mVideoEncoders[index]->mMaxFrameHeight;
    if (!strcmp("enc.vid.bps.min", name)) return mVideoEncoders[index]->mMinBitRate;
    if (!strcmp("enc.vid.bps.max", name)) return mVideoEncoders[index]->mMaxBitRate;
    if (!strcmp("enc.vid.fps.min", name)) return mVideoEncoders[index]->mMinFrameRate;
    if (!strcmp("enc.vid.fps.max", name)) return mVideoEncoders[index]->mMaxFrameRate;

    LOGE("The given video encoder param name %s is not found", name);
    return -1;
}
int MediaProfiles::getVideoEditorExportParamByName(
    const char *name, int codec) const
{
    LOGV("getVideoEditorExportParamByName: name %s codec %d", name, codec);
    ExportVideoProfile *exportProfile = NULL;
    int index = -1;
    for (size_t i =0; i < mVideoEditorExportProfiles.size(); i++) {
        exportProfile = mVideoEditorExportProfiles[i];
        if (exportProfile->mCodec == codec) {
            index = i;
            break;
        }
    }
    if (index == -1) {
        LOGE("The given video decoder %d is not found", codec);
        return -1;
    }
    if (!strcmp("videoeditor.export.profile", name))
        return exportProfile->mProfile;
    if (!strcmp("videoeditor.export.level", name))
        return exportProfile->mLevel;

    LOGE("The given video editor export param name %s is not found", name);
    return -1;
}
int MediaProfiles::getVideoEditorCapParamByName(const char *name) const
{
    LOGV("getVideoEditorCapParamByName: %s", name);

    if (mVideoEditorCap == NULL) {
        LOGE("The mVideoEditorCap is not created, then create default cap.");
        createDefaultVideoEditorCap(sInstance);
    }

    if (!strcmp("videoeditor.input.width.max", name))
        return mVideoEditorCap->mMaxInputFrameWidth;
    if (!strcmp("videoeditor.input.height.max", name))
        return mVideoEditorCap->mMaxInputFrameHeight;
    if (!strcmp("videoeditor.output.width.max", name))
        return mVideoEditorCap->mMaxOutputFrameWidth;
    if (!strcmp("videoeditor.output.height.max", name))
        return mVideoEditorCap->mMaxOutputFrameHeight;
    if (!strcmp("maxPrefetchYUVFrames", name))
        return mVideoEditorCap->mMaxPrefetchYUVFrames;

    LOGE("The given video editor param name %s is not found", name);
    return -1;
}

Vector<audio_encoder> MediaProfiles::getAudioEncoders() const
{
    Vector<audio_encoder> encoders;
    for (size_t i = 0; i < mAudioEncoders.size(); ++i) {
        encoders.add(mAudioEncoders[i]->mCodec);
    }
    return encoders;  // copy out
}

int MediaProfiles::getAudioEncoderParamByName(const char *name, audio_encoder codec) const
{
    LOGV("getAudioEncoderParamByName: %s for codec %d", name, codec);
    int index = -1;
    for (size_t i = 0, n = mAudioEncoders.size(); i < n; ++i) {
        if (mAudioEncoders[i]->mCodec == codec) {
            index = i;
            break;
        }
    }
    if (index == -1) {
        LOGE("The given audio encoder %d is not found", codec);
        return -1;
    }

    if (!strcmp("enc.aud.ch.min", name)) return mAudioEncoders[index]->mMinChannels;
    if (!strcmp("enc.aud.ch.max", name)) return mAudioEncoders[index]->mMaxChannels;
    if (!strcmp("enc.aud.bps.min", name)) return mAudioEncoders[index]->mMinBitRate;
    if (!strcmp("enc.aud.bps.max", name)) return mAudioEncoders[index]->mMaxBitRate;
    if (!strcmp("enc.aud.hz.min", name)) return mAudioEncoders[index]->mMinSampleRate;
    if (!strcmp("enc.aud.hz.max", name)) return mAudioEncoders[index]->mMaxSampleRate;

    LOGE("The given audio encoder param name %s is not found", name);
    return -1;
}

Vector<video_decoder> MediaProfiles::getVideoDecoders() const
{
    Vector<video_decoder> decoders;
    for (size_t i = 0; i < mVideoDecoders.size(); ++i) {
        decoders.add(mVideoDecoders[i]->mCodec);
    }
    return decoders;  // copy out
}

Vector<audio_decoder> MediaProfiles::getAudioDecoders() const
{
    Vector<audio_decoder> decoders;
    for (size_t i = 0; i < mAudioDecoders.size(); ++i) {
        decoders.add(mAudioDecoders[i]->mCodec);
    }
    return decoders;  // copy out
}

int MediaProfiles::getCamcorderProfileIndex(int cameraId, camcorder_quality quality) const
{
    int index = -1;
    for (size_t i = 0, n = mCamcorderProfiles.size(); i < n; ++i) {
        if (mCamcorderProfiles[i]->mCameraId == cameraId &&
            mCamcorderProfiles[i]->mQuality == quality) {
            index = i;
            break;
        }
    }
    return index;
}

int MediaProfiles::getCamcorderProfileParamByName(const char *name,
                                                  int cameraId,
                                                  camcorder_quality quality) const
{
    LOGV("getCamcorderProfileParamByName: %s for camera %d, quality %d",
         name, cameraId, quality);

    int index = getCamcorderProfileIndex(cameraId, quality);
    if (index == -1) {
        LOGE("The given camcorder profile camera %d quality %d is not found",
             cameraId, quality);
        return -1;
    }

    if (!strcmp("duration", name)) return mCamcorderProfiles[index]->mDuration;
    if (!strcmp("file.format", name)) return mCamcorderProfiles[index]->mFileFormat;
    if (!strcmp("vid.codec", name)) return mCamcorderProfiles[index]->mVideoCodec->mCodec;
    if (!strcmp("vid.width", name)) return mCamcorderProfiles[index]->mVideoCodec->mFrameWidth;
    if (!strcmp("vid.height", name)) return mCamcorderProfiles[index]->mVideoCodec->mFrameHeight;
    if (!strcmp("vid.bps", name)) return mCamcorderProfiles[index]->mVideoCodec->mBitRate;
    if (!strcmp("vid.fps", name)) return mCamcorderProfiles[index]->mVideoCodec->mFrameRate;
    if (!strcmp("aud.codec", name)) return mCamcorderProfiles[index]->mAudioCodec->mCodec;
    if (!strcmp("aud.bps", name)) return mCamcorderProfiles[index]->mAudioCodec->mBitRate;
    if (!strcmp("aud.ch", name)) return mCamcorderProfiles[index]->mAudioCodec->mChannels;
    if (!strcmp("aud.hz", name)) return mCamcorderProfiles[index]->mAudioCodec->mSampleRate;

    LOGE("The given camcorder profile param id %d name %s is not found", cameraId, name);
    return -1;
}

bool MediaProfiles::hasCamcorderProfile(int cameraId, camcorder_quality quality) const
{
    return (getCamcorderProfileIndex(cameraId, quality) != -1);
}

Vector<int> MediaProfiles::getImageEncodingQualityLevels(int cameraId) const
{
    Vector<int> result;
    ImageEncodingQualityLevels *levels = findImageEncodingQualityLevels(cameraId);
    if (levels != NULL) {
        result = levels->mLevels;  // copy out
    }
    return result;
}

int MediaProfiles::getStartTimeOffsetMs(int cameraId) const {
    int offsetTimeMs = -1;
    ssize_t index = mStartTimeOffsets.indexOfKey(cameraId);
    if (index >= 0) {
        offsetTimeMs = mStartTimeOffsets.valueFor(cameraId);
    }
    LOGV("offsetTime=%d ms and cameraId=%d", offsetTimeMs, cameraId);
    return offsetTimeMs;
}

MediaProfiles::~MediaProfiles()
{
    CHECK("destructor should never be called" == 0);
#if 0
    for (size_t i = 0; i < mAudioEncoders.size(); ++i) {
        delete mAudioEncoders[i];
    }
    mAudioEncoders.clear();

    for (size_t i = 0; i < mVideoEncoders.size(); ++i) {
        delete mVideoEncoders[i];
    }
    mVideoEncoders.clear();

    for (size_t i = 0; i < mVideoDecoders.size(); ++i) {
        delete mVideoDecoders[i];
    }
    mVideoDecoders.clear();

    for (size_t i = 0; i < mAudioDecoders.size(); ++i) {
        delete mAudioDecoders[i];
    }
    mAudioDecoders.clear();

    for (size_t i = 0; i < mCamcorderProfiles.size(); ++i) {
        delete mCamcorderProfiles[i];
    }
    mCamcorderProfiles.clear();
#endif
}
} // namespace android
