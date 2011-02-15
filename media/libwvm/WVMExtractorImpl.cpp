/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "WVMExtractorImpl"
#include <utils/Log.h>

#include "WVMExtractorImpl.h"
#include "WVMMediaSource.h"
#include "WVMFileSource.h"
#include "WVMLogging.h"
#include "WVStreamControlAPI.h"
#include "media/stagefright/MediaErrors.h"
#include "media/stagefright/MediaDefs.h"
#include "drm/DrmManagerClient.h"
#include "AndroidHooks.h"

#define AES_BLOCK_SIZE 16


using namespace android;

static DecryptHandle *sDecryptHandle;
static DrmManagerClient *sDrmManagerClient;


// Android integration callout hooks
static void HandleEcmCallout(char *ecm, unsigned long size)
{
    DrmBuffer buf(ecm, size);
    if (sDrmManagerClient != NULL) {
        sDrmManagerClient->initializeDecryptUnit(sDecryptHandle, 0, &buf);
    }
}

static int HandleDecryptCallout(char *in, char *out, int length, char *iv)
{
    int status = -1;

    if (sDrmManagerClient != NULL) {
        DrmBuffer encryptedDrmBuffer(in, length);
        DrmBuffer ivBuffer(iv, length);

        DrmBuffer decryptedDrmBuffer(out, length);
        DrmBuffer *decryptedDrmBufferPtr = &decryptedDrmBuffer;

        char ivout[AES_BLOCK_SIZE];
        if (in && length)
            memcpy(ivout, in + length - AES_BLOCK_SIZE, AES_BLOCK_SIZE);

        status = sDrmManagerClient->decrypt(sDecryptHandle, 0,
                                            &encryptedDrmBuffer, &decryptedDrmBufferPtr,
                                            &ivBuffer);

        if (iv)
            memcpy(iv, ivout, AES_BLOCK_SIZE);
    }

    return status;
}


namespace android {

// DLL entry - construct an extractor and return it
MediaExtractor *GetInstance(sp<DataSource> dataSource) {
    return new WVMExtractorImpl(dataSource);
}

WVMExtractorImpl::WVMExtractorImpl(sp<DataSource> dataSource)
    : mFileMetaData(new MetaData()),
      mDataSource(dataSource),
      mHaveMetaData(false),
      mSession(NULL),
      mSetupStatus(OK)
{
    dataSource->getDrmInfo(&sDecryptHandle, &sDrmManagerClient);

    // Set up callouts
    AndroidSetLogCallout(android_printbuf);
    AndroidSetEcmCallout(HandleEcmCallout);
    AndroidSetDecryptCallout(HandleDecryptCallout);

    if (sDecryptHandle != NULL) {
        if (sDecryptHandle->status != RightsStatus::RIGHTS_VALID) {
            mSetupStatus = ERROR_NO_LICENSE;
        }
    } else
        mSetupStatus = ERROR_NO_LICENSE;

    WVCredentials credentials;

    WVStatus result = WV_Initialize(NULL);
    if (result != WV_Status_OK) {
        LOGE("WV_Initialize returned status %d\n", result);
        mSetupStatus = ERROR_IO;
    } else {
        // Enable for debugging HTTP messages
        // WV_SetLogging(WV_Logging_HTTP);

        if (dataSource->getUri().size() == 0) {
            // No URI supplied, pull data from the data source
            mFileSource = new WVMFileSource(dataSource);
            result = WV_Setup(mSession, mFileSource.get(),
                              "RAW/RAW/RAW;destination=getdata", credentials,
                              WV_OutputFormat_ES, kStreamCacheSize);
        } else {
            // Use the URI
            result = WV_Setup(mSession, dataSource->getUri().string(),
                              "RAW/RAW/RAW;destination=getdata", credentials,
                              WV_OutputFormat_ES, kStreamCacheSize);
        }
        if (result != WV_Status_OK) {
            LOGE("WV_Setup returned status %d in WVMMediaSource::start\n", result);
            mSetupStatus = ERROR_IO;
        }
    }

    WV_SetWarningToErrorMS(5000);
}

WVMExtractorImpl::~WVMExtractorImpl() {
}

//
// Configure metadata for video and audio sources
//
status_t WVMExtractorImpl::readMetaData()
{
    if (mHaveMetaData)
        return OK;

    if (mSetupStatus != OK)
        return mSetupStatus;

    // Get Video Configuration
    WVVideoType videoType;
    unsigned short videoStreamID;
    unsigned short videoProfile;
    unsigned short level;
    unsigned short width, height;
    float aspect, frameRate;
    unsigned long bitRate;

    WVStatus result = WV_Info_GetVideoConfiguration(mSession, &videoType, &videoStreamID,
                                                    &videoProfile, &level, &width, &height,
                                                    &aspect,  &frameRate, &bitRate);
    if (result != WV_Status_OK)
        return ERROR_MALFORMED;

    // Get Audio Configuration
    WVAudioType audioType;
    unsigned short audioStreamID;
    unsigned short audioProfile;
    unsigned short numChannels;
    unsigned long sampleRate;

    result = WV_Info_GetAudioConfiguration(mSession, &audioType, &audioStreamID, &audioProfile,
                                           &numChannels, &sampleRate, &bitRate);
    if (result != WV_Status_OK)
        return ERROR_MALFORMED;

    std::string durationString = WV_Info_GetDuration(mSession, "sec");
    if (durationString == "") {
        return ERROR_MALFORMED;
    }

    int64_t duration = (int64_t)(strtod(durationString.c_str(), NULL) * 1000000);

    sp<MetaData> audioMetaData = new MetaData();
    sp<MetaData> videoMetaData = new MetaData();

    audioMetaData->setInt64(kKeyDuration, duration);
    videoMetaData->setInt64(kKeyDuration, duration);

    switch(videoType) {
    case WV_VideoType_H264:
        videoMetaData->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
        break;
    default:
        LOGE("Invalid WV video type %d, expected H264C\n", audioType);
        break;
    }

    switch(audioType) {
    case WV_AudioType_AAC:
        audioMetaData->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);
        break;
    default:
        LOGE("Invalid WV audio type %d, expected AAC\n", audioType);
        break;
    }

    audioMetaData->setInt32(kKeyTrackID, audioStreamID);
    videoMetaData->setInt32(kKeyTrackID, videoStreamID);

    audioMetaData->setInt32(kKeyChannelCount, numChannels);
    audioMetaData->setInt32(kKeySampleRate, sampleRate);

    videoMetaData->setInt32(kKeyWidth, width);
    videoMetaData->setInt32(kKeyHeight, height);

    status_t status;

    status = readAVCCMetaData(videoMetaData);
    if (status != OK)
        return status;

    status = readESDSMetaData(audioMetaData);
    if (status != OK)
        return status;

    mAudioSource = new WVMMediaSource(mSession, WV_EsSelector_Audio, audioMetaData);
    mVideoSource = new WVMMediaSource(mSession, WV_EsSelector_Video, videoMetaData);

    //  Since the WVExtractor goes away soon after this, we delegate ownership of some resources
    //  to the constructed media source
    if (mFileSource.get())
        mVideoSource->delegateFileSource(mFileSource);

    mVideoSource->delegateDataSource(mDataSource);

    mFileMetaData->setCString(kKeyMIMEType, "video/mp4");

    mHaveMetaData = true;

    return OK;
}

status_t WVMExtractorImpl::readAVCCMetaData(sp<MetaData> videoMetaData)
{
    WVStatus result;

    const unsigned char *config;
    unsigned long size;
    int limit = 50;
    do {
        size_t bytesRead;
        bool auStart;
        unsigned long long dts, pts;
        unsigned char buf[1];
        size_t bufSize = 0;

        //
        // In order to get the codec config data, we need to have the WVMK
        // pull some video data.  But we can't use it yet, so just request 0 bytes.
        //
        (void)WV_GetEsData(mSession, WV_EsSelector_Video,  buf, bufSize,
                           bytesRead, auStart, dts, pts);

        result = WV_Info_GetCodecConfig(mSession, WV_CodecConfigType_AVCC, config, size);
        if (result != WV_Status_OK)
            usleep(100);
    } while (result == WV_Status_Warning_Not_Available && limit-- > 0);

    if (result != WV_Status_OK) {
        LOGE("WV_Info_GetCodecConfig AVCC returned error %d\n", result);
        return ERROR_IO;
    }

#if 0
    char *filename = "/data/wvm/avcc";
    FILE *f = fopen(filename, "w");
    if (!f)
        LOGD("Failed to open %s", filename);
    else {
        fwrite(config, size, 1, f);
        fclose(f);
    }
#endif

#if 0
    unsigned char mp4_force[] =
        { 0x01, 0x42, 0x00, 0x1e, 0xff, 0xe1, 0x00, 0x1b, 0x67, 0x42, 0x80, 0x1e, 0x96, 0x52, 0x01, 0x40,
          0x5f, 0xf3, 0x60, 0x2a, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x09, 0xda,
          0x14, 0x2a, 0x48, 0x01, 0x00, 0x04, 0x68, 0xcb, 0x8d, 0x48 } ;
    unsigned char wvm_force[] =
        { 0x01, 0x42, 0x80, 0x1e, 0xff, 0xe1, 0x00, 0x1c, 0x67, 0x42, 0x80, 0x1e, 0x96, 0x52, 0x01, 0x40,
          0x5f, 0xf3, 0x60, 0x2a, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x09, 0xda,
          0x14, 0x2a, 0x48, 0x00, 0x01, 0x00, 0x05, 0x68, 0xcb, 0x8d, 0x48, 0x00 } ;
    unsigned char wvm_force_no_zero[] =
        { 0x01, 0x42, 0x80, 0x1e, 0xff, 0xe1, 0x00, 0x1b, 0x67, 0x42, 0x80, 0x1e, 0x96, 0x52, 0x01, 0x40,
          0x5f, 0xf3, 0x60, 0x2a, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x09, 0xda,
          0x14, 0x2a, 0x48, 0x01, 0x00, 0x04, 0x68, 0xcb, 0x8d, 0x48 } ;
    unsigned char wvm_force_modprof[] =
        { 0x01, 0x42, 0x00, 0x1e, 0xff, 0xe1, 0x00, 0x1c, 0x67, 0x42, 0x80, 0x1e, 0x96, 0x52, 0x01, 0x40,
          0x5f, 0xf3, 0x60, 0x2a, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x09, 0xda,
          0x14, 0x2a, 0x48, 0x00, 0x01, 0x00, 0x05, 0x68, 0xcb, 0x8d, 0x48, 0x00 } ;

    videoMetaData->setData(kKeyAVCC, kTypeAVCC, wvm_force_no_zero, sizeof(wvm_force_no_zero));
#else
    videoMetaData->setData(kKeyAVCC, kTypeAVCC, config, size);
#endif
    return OK;
}

status_t WVMExtractorImpl::readESDSMetaData(sp<MetaData> audioMetaData)
{
    WVStatus result;

    const unsigned char *config;
    unsigned long size;
    int limit = 50;
    do {
        size_t bytesRead;
        bool auStart;
        unsigned long long dts, pts;
        unsigned char buf[1];
        size_t bufSize = 0;

        //
        // In order to get the codec config data, we need to have the WVMK
        // pull some audio data.  But we can't use it yet, so just request 0 bytes.
        //
        (void)WV_GetEsData(mSession, WV_EsSelector_Audio,  buf, bufSize,
                           bytesRead, auStart, dts, pts);

        result = WV_Info_GetCodecConfig(mSession, WV_CodecConfigType_ESDS, config, size);
        if (result != WV_Status_OK)
            usleep(100);
    } while (result == WV_Status_Warning_Not_Available && limit-- > 0);

    if (result != WV_Status_OK) {
        LOGE("WV_Info_GetCodecConfig ESDS returned error %d\n", result);
        return ERROR_IO;
    }

#if 0
    char *filename = "/data/wvm/esds";
    FILE *f = fopen(filename, "w");
    if (!f)
        LOGD("Failed to open %s", filename);
    else {
        fwrite(config, size, 1, f);
        fclose(f);
    }
#endif
    audioMetaData->setData(kKeyESDS, kTypeESDS, config, size);
    return OK;
}

size_t WVMExtractorImpl::countTracks() {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return 0;
    }

    return 2;  // 1 audio + 1 video
}

sp<MediaSource> WVMExtractorImpl::getTrack(size_t index)
{
    status_t err;
    if ((err = readMetaData()) != OK) {
        return NULL;
    }

    sp<MediaSource> result;

    switch(index) {
    case 0:
        result = mVideoSource;
        break;
    case 1:
        result = mAudioSource;
        break;
    default:
        break;
    }
    return result;
}


sp<MetaData> WVMExtractorImpl::getTrackMetaData(size_t index, uint32_t flags)
{
    status_t err;
    if ((err = readMetaData()) != OK) {
        return NULL;
    }

    sp<MetaData> result;
    switch(index) {
    case 0:
        if (mVideoSource != NULL)
            result = mVideoSource->getFormat();
        break;
    case 1:
        if (mAudioSource != NULL)
            result = mAudioSource->getFormat();
        break;
    default:
        break;
    }
    return result;
}

sp<MetaData> WVMExtractorImpl::getMetaData() {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return new MetaData;
    }

    return mFileMetaData;
}

} // namespace android

