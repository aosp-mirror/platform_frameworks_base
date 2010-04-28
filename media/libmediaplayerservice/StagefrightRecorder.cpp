/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "StagefrightRecorder"
#include <utils/Log.h>

#include "StagefrightRecorder.h"

#include <binder/IPCThreadState.h>
#include <media/stagefright/AudioSource.h>
#include <media/stagefright/AMRWriter.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <camera/ICamera.h>
#include <camera/Camera.h>
#include <surfaceflinger/ISurface.h>
#include <utils/Errors.h>
#include <sys/types.h>
#include <unistd.h>

namespace android {

StagefrightRecorder::StagefrightRecorder() {
    reset();
}

StagefrightRecorder::~StagefrightRecorder() {
    stop();

    if (mOutputFd >= 0) {
        ::close(mOutputFd);
        mOutputFd = -1;
    }
}

status_t StagefrightRecorder::init() {
    return OK;
}

status_t StagefrightRecorder::setAudioSource(audio_source as) {
    mAudioSource = as;

    return OK;
}

status_t StagefrightRecorder::setVideoSource(video_source vs) {
    mVideoSource = vs;

    return OK;
}

status_t StagefrightRecorder::setOutputFormat(output_format of) {
    mOutputFormat = of;

    return OK;
}

status_t StagefrightRecorder::setAudioEncoder(audio_encoder ae) {
    mAudioEncoder = ae;

    return OK;
}

status_t StagefrightRecorder::setVideoEncoder(video_encoder ve) {
    mVideoEncoder = ve;

    return OK;
}

status_t StagefrightRecorder::setVideoSize(int width, int height) {
    mVideoWidth = width;
    mVideoHeight = height;

    return OK;
}

status_t StagefrightRecorder::setVideoFrameRate(int frames_per_second) {
    mFrameRate = frames_per_second;

    return OK;
}

status_t StagefrightRecorder::setCamera(const sp<ICamera> &camera) {
    LOGV("setCamera: pid %d pid %d", IPCThreadState::self()->getCallingPid(), getpid());
    if (camera == 0) {
        LOGE("camera is NULL");
        return UNKNOWN_ERROR;
    }

    mFlags &= ~ FLAGS_SET_CAMERA | FLAGS_HOT_CAMERA;
    mCamera = Camera::create(camera);
    if (mCamera == 0) {
        LOGE("Unable to connect to camera");
        return UNKNOWN_ERROR;
    }

    LOGV("Connected to camera");
    mFlags |= FLAGS_SET_CAMERA;
    if (mCamera->previewEnabled()) {
        LOGV("camera is hot");
        mFlags |= FLAGS_HOT_CAMERA;
    }

    return OK;
}

status_t StagefrightRecorder::setPreviewSurface(const sp<ISurface> &surface) {
    mPreviewSurface = surface;

    return OK;
}

status_t StagefrightRecorder::setOutputFile(const char *path) {
    // We don't actually support this at all, as the media_server process
    // no longer has permissions to create files.

    return UNKNOWN_ERROR;
}

status_t StagefrightRecorder::setOutputFile(int fd, int64_t offset, int64_t length) {
    // These don't make any sense, do they?
    CHECK_EQ(offset, 0);
    CHECK_EQ(length, 0);

    if (mOutputFd >= 0) {
        ::close(mOutputFd);
    }
    mOutputFd = dup(fd);

    return OK;
}

status_t StagefrightRecorder::setParameters(const String8 &params) {
    mParams = params;

    return OK;
}

status_t StagefrightRecorder::setListener(const sp<IMediaPlayerClient> &listener) {
    mListener = listener;

    return OK;
}

status_t StagefrightRecorder::prepare() {
    return OK;
}

status_t StagefrightRecorder::start() {
    if (mWriter != NULL) {
        return UNKNOWN_ERROR;
    }

    switch (mOutputFormat) {
        case OUTPUT_FORMAT_DEFAULT:
        case OUTPUT_FORMAT_THREE_GPP:
        case OUTPUT_FORMAT_MPEG_4:
            return startMPEG4Recording();

        case OUTPUT_FORMAT_AMR_NB:
        case OUTPUT_FORMAT_AMR_WB:
            return startAMRRecording();

        default:
            return UNKNOWN_ERROR;
    }
}

sp<MediaSource> StagefrightRecorder::createAMRAudioSource() {
    uint32_t sampleRate =
        mAudioEncoder == AUDIO_ENCODER_AMR_NB ? 8000 : 16000;

    sp<AudioSource> audioSource =
        new AudioSource(
                mAudioSource,
                sampleRate,
                AudioSystem::CHANNEL_IN_MONO);

    status_t err = audioSource->initCheck();

    if (err != OK) {
        return NULL;
    }

    sp<MetaData> encMeta = new MetaData;
    encMeta->setCString(
            kKeyMIMEType,
            mAudioEncoder == AUDIO_ENCODER_AMR_NB
                ? MEDIA_MIMETYPE_AUDIO_AMR_NB : MEDIA_MIMETYPE_AUDIO_AMR_WB);

    int32_t maxInputSize;
    CHECK(audioSource->getFormat()->findInt32(
                kKeyMaxInputSize, &maxInputSize));

    encMeta->setInt32(kKeyMaxInputSize, maxInputSize);
    encMeta->setInt32(kKeyChannelCount, 1);
    encMeta->setInt32(kKeySampleRate, sampleRate);

    OMXClient client;
    CHECK_EQ(client.connect(), OK);

    sp<MediaSource> audioEncoder =
        OMXCodec::Create(client.interface(), encMeta,
                         true /* createEncoder */, audioSource);

    return audioEncoder;
}

status_t StagefrightRecorder::startAMRRecording() {
    if (mAudioSource == AUDIO_SOURCE_LIST_END
        || mVideoSource != VIDEO_SOURCE_LIST_END) {
        return UNKNOWN_ERROR;
    }

    if (mOutputFormat == OUTPUT_FORMAT_AMR_NB
            && mAudioEncoder != AUDIO_ENCODER_DEFAULT
            && mAudioEncoder != AUDIO_ENCODER_AMR_NB) {
        return UNKNOWN_ERROR;
    } else if (mOutputFormat == OUTPUT_FORMAT_AMR_WB
            && mAudioEncoder != AUDIO_ENCODER_AMR_WB) {
        return UNKNOWN_ERROR;
    }

    sp<MediaSource> audioEncoder = createAMRAudioSource();

    if (audioEncoder == NULL) {
        return UNKNOWN_ERROR;
    }

    CHECK(mOutputFd >= 0);
    mWriter = new AMRWriter(dup(mOutputFd));
    mWriter->addSource(audioEncoder);
    mWriter->start();

    return OK;
}

status_t StagefrightRecorder::startMPEG4Recording() {
    mWriter = new MPEG4Writer(dup(mOutputFd));

    if (mVideoSource == VIDEO_SOURCE_DEFAULT
            || mVideoSource == VIDEO_SOURCE_CAMERA) {
        CHECK(mCamera != NULL);

        sp<CameraSource> cameraSource =
            CameraSource::CreateFromCamera(mCamera);

        CHECK(cameraSource != NULL);

        cameraSource->setPreviewSurface(mPreviewSurface);

        sp<MetaData> enc_meta = new MetaData;
        switch (mVideoEncoder) {
            case VIDEO_ENCODER_H263:
                enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);
                break;

            case VIDEO_ENCODER_MPEG_4_SP:
                enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);
                break;

            case VIDEO_ENCODER_H264:
                enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
                break;

            default:
                CHECK(!"Should not be here, unsupported video encoding.");
                break;
        }

        sp<MetaData> meta = cameraSource->getFormat();

        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

        enc_meta->setInt32(kKeyWidth, width);
        enc_meta->setInt32(kKeyHeight, height);

        OMXClient client;
        CHECK_EQ(client.connect(), OK);

        sp<MediaSource> encoder =
            OMXCodec::Create(
                    client.interface(), enc_meta,
                    true /* createEncoder */, cameraSource);

        CHECK(mOutputFd >= 0);
        mWriter->addSource(encoder);
    }

    if (mAudioSource != AUDIO_SOURCE_LIST_END) {
        sp<MediaSource> audioEncoder = createAMRAudioSource();

        if (audioEncoder == NULL) {
            return UNKNOWN_ERROR;
        }

        mWriter->addSource(audioEncoder);
    }

    mWriter->start();
    return OK;
}

status_t StagefrightRecorder::stop() {
    if (mWriter == NULL) {
        return UNKNOWN_ERROR;
    }

    mWriter->stop();
    mWriter = NULL;

    return OK;
}

status_t StagefrightRecorder::close() {
    stop();

    if (mCamera != 0) {
        if ((mFlags & FLAGS_HOT_CAMERA) == 0) {
            LOGV("Camera was cold when we started, stopping preview");
            mCamera->stopPreview();
        }
        if (mFlags & FLAGS_SET_CAMERA) {
            LOGV("Unlocking camera");
            mCamera->unlock();
        }
        mFlags = 0;
    }
    return OK;
}

status_t StagefrightRecorder::reset() {
    stop();

    mAudioSource = AUDIO_SOURCE_LIST_END;
    mVideoSource = VIDEO_SOURCE_LIST_END;
    mOutputFormat = OUTPUT_FORMAT_LIST_END;
    mAudioEncoder = AUDIO_ENCODER_LIST_END;
    mVideoEncoder = VIDEO_ENCODER_LIST_END;
    mVideoWidth = -1;
    mVideoHeight = -1;
    mFrameRate = -1;
    mOutputFd = -1;
    mFlags = 0;

    return OK;
}

status_t StagefrightRecorder::getMaxAmplitude(int *max) {
    *max = 0;

    return OK;
}

}  // namespace android
