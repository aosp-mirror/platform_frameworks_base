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
#include <camera/CameraParameters.h>
#include <surfaceflinger/ISurface.h>
#include <utils/Errors.h>
#include <sys/types.h>
#include <unistd.h>
#include <ctype.h>

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
    if (width <= 0 || height <= 0) {
        LOGE("Invalid video size: %dx%d", width, height);
        return BAD_VALUE;
    }

    // Additional check on the dimension will be performed later
    mVideoWidth = width;
    mVideoHeight = height;

    return OK;
}

status_t StagefrightRecorder::setVideoFrameRate(int frames_per_second) {
    if (frames_per_second <= 0 || frames_per_second > 30) {
        LOGE("Invalid video frame rate: %d", frames_per_second);
        return BAD_VALUE;
    }

    // Additional check on the frame rate will be performed later
    mFrameRate = frames_per_second;

    return OK;
}

status_t StagefrightRecorder::setCamera(const sp<ICamera> &camera) {
    LOGV("setCamera");
    if (camera == 0) {
        LOGE("camera is NULL");
        return UNKNOWN_ERROR;
    }

    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    mFlags &= ~FLAGS_HOT_CAMERA;
    mCamera = Camera::create(camera);
    if (mCamera == 0) {
        LOGE("Unable to connect to camera");
        IPCThreadState::self()->restoreCallingIdentity(token);
        return UNKNOWN_ERROR;
    }

    LOGV("Connected to camera");
    if (mCamera->previewEnabled()) {
        LOGV("camera is hot");
        mFlags |= FLAGS_HOT_CAMERA;
    }
    IPCThreadState::self()->restoreCallingIdentity(token);

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

// Attempt to parse an int64 literal optionally surrounded by whitespace,
// returns true on success, false otherwise.
static bool safe_strtoi64(const char *s, int64_t *val) {
    char *end;
    *val = strtoll(s, &end, 10);

    if (end == s || errno == ERANGE) {
        return false;
    }

    // Skip trailing whitespace
    while (isspace(*end)) {
        ++end;
    }

    // For a successful return, the string must contain nothing but a valid
    // int64 literal optionally surrounded by whitespace.

    return *end == '\0';
}

// Return true if the value is in [0, 0x007FFFFFFF]
static bool safe_strtoi32(const char *s, int32_t *val) {
    int64_t temp;
    if (safe_strtoi64(s, &temp)) {
        if (temp >= 0 && temp <= 0x007FFFFFFF) {
            *val = static_cast<int32_t>(temp);
            return true;
        }
    }
    return false;
}

// Trim both leading and trailing whitespace from the given string.
static void TrimString(String8 *s) {
    size_t num_bytes = s->bytes();
    const char *data = s->string();

    size_t leading_space = 0;
    while (leading_space < num_bytes && isspace(data[leading_space])) {
        ++leading_space;
    }

    size_t i = num_bytes;
    while (i > leading_space && isspace(data[i - 1])) {
        --i;
    }

    s->setTo(String8(&data[leading_space], i - leading_space));
}

status_t StagefrightRecorder::setParamAudioSamplingRate(int32_t sampleRate) {
    LOGV("setParamAudioSamplingRate: %d", sampleRate);
    if (sampleRate <= 0) {
        LOGE("Invalid audio sampling rate: %d", sampleRate);
        return BAD_VALUE;
    }

    // Additional check on the sample rate will be performed later.
    mSampleRate = sampleRate;
    return OK;
}

status_t StagefrightRecorder::setParamAudioNumberOfChannels(int32_t channels) {
    LOGV("setParamAudioNumberOfChannels: %d", channels);
    if (channels <= 0 || channels >= 3) {
        LOGE("Invalid number of audio channels: %d", channels);
    }

    // Additional check on the number of channels will be performed later.
    mAudioChannels = channels;
    return OK;
}

status_t StagefrightRecorder::setParamAudioEncodingBitRate(int32_t bitRate) {
    LOGV("setParamAudioEncodingBitRate: %d", bitRate);
    if (bitRate <= 0) {
        LOGE("Invalid audio encoding bit rate: %d", bitRate);
        return BAD_VALUE;
    }

    // The target bit rate may not be exactly the same as the requested.
    // It depends on many factors, such as rate control, and the bit rate
    // range that a specific encoder supports. The mismatch between the
    // the target and requested bit rate will NOT be treated as an error.
    mAudioBitRate = bitRate;
    return OK;
}

status_t StagefrightRecorder::setParamVideoEncodingBitRate(int32_t bitRate) {
    LOGV("setParamVideoEncodingBitRate: %d", bitRate);
    if (bitRate <= 0) {
        LOGE("Invalid video encoding bit rate: %d", bitRate);
        return BAD_VALUE;
    }

    // The target bit rate may not be exactly the same as the requested.
    // It depends on many factors, such as rate control, and the bit rate
    // range that a specific encoder supports. The mismatch between the
    // the target and requested bit rate will NOT be treated as an error.
    mVideoBitRate = bitRate;
    return OK;
}

status_t StagefrightRecorder::setParamMaxDurationOrFileSize(int64_t limit,
        bool limit_is_duration) {
    LOGV("setParamMaxDurationOrFileSize: limit (%lld) for %s",
            limit, limit_is_duration?"duration":"size");
    if (limit_is_duration) {  // limit is in ms
        if (limit <= 1000) {  // XXX: 1 second
            LOGE("Max file duration is too short: %lld us", limit);
        }
        mMaxFileDurationUs = limit * 1000LL;
    } else {
        if (limit <= 1024) {  // XXX: 1 kB
            LOGE("Max file size is too small: %lld bytes", limit);
        }
        mMaxFileSizeBytes = limit;
    }
    return OK;
}

status_t StagefrightRecorder::setParamInterleaveDuration(int32_t durationUs) {
    LOGV("setParamInterleaveDuration: %d", durationUs);
    if (durationUs <= 500000) {           //  500 ms
        // If interleave duration is too small, it is very inefficient to do
        // interleaving since the metadata overhead will count for a significant
        // portion of the saved contents
        LOGE("Audio/video interleave duration is too small: %d us", durationUs);
        return BAD_VALUE;
    } else if (durationUs >= 10000000) {  // 10 seconds
        // If interleaving duration is too large, it can cause the recording
        // session to use too much memory since we have to save the output
        // data before we write them out
        LOGE("Audio/video interleave duration is too large: %d us", durationUs);
        return BAD_VALUE;
    }
    mInterleaveDurationUs = durationUs;
    return OK;
}

// If interval <  0, only the first frame is I frame, and rest are all P frames
// If interval == 0, all frames are encoded as I frames. No P frames
// If interval >  0, it is the time spacing between 2 neighboring I frames
status_t StagefrightRecorder::setParamIFramesInterval(int32_t interval) {
    LOGV("setParamIFramesInterval: %d seconds", interval);
    mIFramesInterval = interval;
    return OK;
}

status_t StagefrightRecorder::setParameter(
        const String8 &key, const String8 &value) {
    LOGV("setParameter: key (%s) => value (%s)", key.string(), value.string());
    if (key == "max-duration") {
        int64_t max_duration_ms;
        if (safe_strtoi64(value.string(), &max_duration_ms)) {
            return setParamMaxDurationOrFileSize(
                    max_duration_ms, true /* limit_is_duration */);
        }
    } else if (key == "max-filesize") {
        int64_t max_filesize_bytes;
        if (safe_strtoi64(value.string(), &max_filesize_bytes)) {
            return setParamMaxDurationOrFileSize(
                    max_filesize_bytes, false /* limit is filesize */);
        }
    } else if (key == "audio-param-sampling-rate") {
        int32_t sampling_rate;
        if (safe_strtoi32(value.string(), &sampling_rate)) {
            return setParamAudioSamplingRate(sampling_rate);
        }
    } else if (key == "audio-param-number-of-channels") {
        int32_t number_of_channels;
        if (safe_strtoi32(value.string(), &number_of_channels)) {
            return setParamAudioNumberOfChannels(number_of_channels);
        }
    } else if (key == "audio-param-encoding-bitrate") {
        int32_t audio_bitrate;
        if (safe_strtoi32(value.string(), &audio_bitrate)) {
            return setParamAudioEncodingBitRate(audio_bitrate);
        }
    } else if (key == "video-param-encoding-bitrate") {
        int32_t video_bitrate;
        if (safe_strtoi32(value.string(), &video_bitrate)) {
            return setParamVideoEncodingBitRate(video_bitrate);
        }
    } else if (key == "param-interleave-duration-us") {
        int32_t durationUs;
        if (safe_strtoi32(value.string(), &durationUs)) {
            return setParamInterleaveDuration(durationUs);
        }
    } else if (key == "param-i-frames-interval") {
        int32_t interval;
        if (safe_strtoi32(value.string(), &interval)) {
            return setParamIFramesInterval(interval);
        }
    } else {
        LOGE("setParameter: failed to find key %s", key.string());
    }
    return BAD_VALUE;
}

status_t StagefrightRecorder::setParameters(const String8 &params) {
    LOGV("setParameters: %s", params.string());
    const char *cparams = params.string();
    const char *key_start = cparams;
    for (;;) {
        const char *equal_pos = strchr(key_start, '=');
        if (equal_pos == NULL) {
            LOGE("Parameters %s miss a value", cparams);
            return BAD_VALUE;
        }
        String8 key(key_start, equal_pos - key_start);
        TrimString(&key);
        if (key.length() == 0) {
            LOGE("Parameters %s contains an empty key", cparams);
            return BAD_VALUE;
        }
        const char *value_start = equal_pos + 1;
        const char *semicolon_pos = strchr(value_start, ';');
        String8 value;
        if (semicolon_pos == NULL) {
            value.setTo(value_start);
        } else {
            value.setTo(value_start, semicolon_pos - value_start);
        }
        if (setParameter(key, value) != OK) {
            return BAD_VALUE;
        }
        if (semicolon_pos == NULL) {
            break;  // Reaches the end
        }
        key_start = semicolon_pos + 1;
    }
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

        case OUTPUT_FORMAT_AAC_ADIF:
        case OUTPUT_FORMAT_AAC_ADTS:
            return startAACRecording();

        default:
            return UNKNOWN_ERROR;
    }
}

sp<MediaSource> StagefrightRecorder::createAudioSource() {
    sp<AudioSource> audioSource =
        new AudioSource(
                mAudioSource,
                mSampleRate,
                AudioSystem::CHANNEL_IN_MONO);

    status_t err = audioSource->initCheck();

    if (err != OK) {
        LOGE("audio source is not initialized");
        return NULL;
    }

    sp<MetaData> encMeta = new MetaData;
    const char *mime;
    switch (mAudioEncoder) {
        case AUDIO_ENCODER_AMR_NB:
        case AUDIO_ENCODER_DEFAULT:
            mime = MEDIA_MIMETYPE_AUDIO_AMR_NB;
            break;
        case AUDIO_ENCODER_AMR_WB:
            mime = MEDIA_MIMETYPE_AUDIO_AMR_WB;
            break;
        case AUDIO_ENCODER_AAC:
            mime = MEDIA_MIMETYPE_AUDIO_AAC;
            break;
        default:
            LOGE("Unknown audio encoder: %d", mAudioEncoder);
            return NULL;
    }
    encMeta->setCString(kKeyMIMEType, mime);

    int32_t maxInputSize;
    CHECK(audioSource->getFormat()->findInt32(
                kKeyMaxInputSize, &maxInputSize));

    encMeta->setInt32(kKeyMaxInputSize, maxInputSize);
    encMeta->setInt32(kKeyChannelCount, mAudioChannels);
    encMeta->setInt32(kKeySampleRate, mSampleRate);
    encMeta->setInt32(kKeyBitRate, mAudioBitRate);

    OMXClient client;
    CHECK_EQ(client.connect(), OK);

    sp<MediaSource> audioEncoder =
        OMXCodec::Create(client.interface(), encMeta,
                         true /* createEncoder */, audioSource);

    return audioEncoder;
}

status_t StagefrightRecorder::startAACRecording() {
    CHECK(mOutputFormat == OUTPUT_FORMAT_AAC_ADIF ||
          mOutputFormat == OUTPUT_FORMAT_AAC_ADTS);

    CHECK(mAudioEncoder == AUDIO_ENCODER_AAC);
    CHECK(mAudioSource != AUDIO_SOURCE_LIST_END);
    CHECK(mOutputFd >= 0);

    CHECK(0 == "AACWriter is not implemented yet");

    return OK;
}

status_t StagefrightRecorder::startAMRRecording() {
    CHECK(mOutputFormat == OUTPUT_FORMAT_AMR_NB ||
          mOutputFormat == OUTPUT_FORMAT_AMR_WB);

    if (mOutputFormat == OUTPUT_FORMAT_AMR_NB) {
        if (mAudioEncoder != AUDIO_ENCODER_DEFAULT &&
            mAudioEncoder != AUDIO_ENCODER_AMR_NB) {
            LOGE("Invalid encoder %d used for AMRNB recording",
                    mAudioEncoder);
            return UNKNOWN_ERROR;
        }
        if (mSampleRate != 8000) {
            LOGE("Invalid sampling rate %d used for AMRNB recording",
                    mSampleRate);
            return UNKNOWN_ERROR;
        }
    } else {  // mOutputFormat must be OUTPUT_FORMAT_AMR_WB
        if (mAudioEncoder != AUDIO_ENCODER_AMR_WB) {
            LOGE("Invlaid encoder %d used for AMRWB recording",
                    mAudioEncoder);
            return UNKNOWN_ERROR;
        }
        if (mSampleRate != 16000) {
            LOGE("Invalid sample rate %d used for AMRWB recording",
                    mSampleRate);
            return UNKNOWN_ERROR;
        }
    }
    if (mAudioChannels != 1) {
        LOGE("Invalid number of audio channels %d used for amr recording",
                mAudioChannels);
        return UNKNOWN_ERROR;
    }

    if (mAudioSource >= AUDIO_SOURCE_LIST_END) {
        LOGE("Invalid audio source: %d", mAudioSource);
        return UNKNOWN_ERROR;
    }

    sp<MediaSource> audioEncoder = createAudioSource();

    if (audioEncoder == NULL) {
        return UNKNOWN_ERROR;
    }

    CHECK(mOutputFd >= 0);
    mWriter = new AMRWriter(dup(mOutputFd));
    mWriter->addSource(audioEncoder);

    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);
    mWriter->start();

    return OK;
}

status_t StagefrightRecorder::startMPEG4Recording() {
    mWriter = new MPEG4Writer(dup(mOutputFd));

    // Add audio source first if it exists
    if (mAudioSource != AUDIO_SOURCE_LIST_END) {
        sp<MediaSource> audioEncoder;
        switch(mAudioEncoder) {
            case AUDIO_ENCODER_AMR_NB:
            case AUDIO_ENCODER_AMR_WB:
            case AUDIO_ENCODER_AAC:
                audioEncoder = createAudioSource();
                break;
            default:
                LOGE("Unsupported audio encoder: %d", mAudioEncoder);
                return UNKNOWN_ERROR;
        }

        if (audioEncoder == NULL) {
            return UNKNOWN_ERROR;
        }

        mWriter->addSource(audioEncoder);
    }
    if (mVideoSource == VIDEO_SOURCE_DEFAULT
            || mVideoSource == VIDEO_SOURCE_CAMERA) {

        int64_t token = IPCThreadState::self()->clearCallingIdentity();
        if (mCamera == 0) {
            mCamera = Camera::connect(0);
            mCamera->lock();
        }

        // Set the actual video recording frame size
        CameraParameters params(mCamera->getParameters());
        params.setPreviewSize(mVideoWidth, mVideoHeight);
        params.setPreviewFrameRate(mFrameRate);
        String8 s = params.flatten();
        CHECK_EQ(OK, mCamera->setParameters(s));
        CameraParameters newCameraParams(mCamera->getParameters());

        // Check on video frame size
        int frameWidth = 0, frameHeight = 0;
        newCameraParams.getPreviewSize(&frameWidth, &frameHeight);
        if (frameWidth  < 0 || frameWidth  != mVideoWidth ||
            frameHeight < 0 || frameHeight != mVideoHeight) {
            LOGE("Failed to set the video frame size to %dx%d",
                    mVideoWidth, mVideoHeight);
            IPCThreadState::self()->restoreCallingIdentity(token);
            return UNKNOWN_ERROR;
        }

        // Check on video frame rate
        int frameRate = newCameraParams.getPreviewFrameRate();
        if (frameRate < 0 || (frameRate - mFrameRate) != 0) {
            LOGE("Failed to set frame rate to %d fps. The actual "
                 "frame rate is %d", mFrameRate, frameRate);
        }

        CHECK_EQ(OK, mCamera->setPreviewDisplay(mPreviewSurface));
        IPCThreadState::self()->restoreCallingIdentity(token);

        sp<CameraSource> cameraSource =
            CameraSource::CreateFromCamera(mCamera);

        CHECK(cameraSource != NULL);

        sp<MetaData> enc_meta = new MetaData;
        enc_meta->setInt32(kKeyBitRate, mVideoBitRate);
        enc_meta->setInt32(kKeySampleRate, mFrameRate);  // XXX: kKeySampleRate?

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

        int32_t width, height, stride, sliceHeight;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));
        CHECK(meta->findInt32(kKeyStride, &stride));
        CHECK(meta->findInt32(kKeySliceHeight, &sliceHeight));

        enc_meta->setInt32(kKeyWidth, width);
        enc_meta->setInt32(kKeyHeight, height);
        enc_meta->setInt32(kKeyIFramesInterval, mIFramesInterval);
        enc_meta->setInt32(kKeyStride, stride);
        enc_meta->setInt32(kKeySliceHeight, sliceHeight);

        OMXClient client;
        CHECK_EQ(client.connect(), OK);

        sp<MediaSource> encoder =
            OMXCodec::Create(
                    client.interface(), enc_meta,
                    true /* createEncoder */, cameraSource);

        CHECK(mOutputFd >= 0);
        mWriter->addSource(encoder);
    }

    {
        // MPEGWriter specific handling
        MPEG4Writer *writer = ((MPEG4Writer *) mWriter.get());  // mWriter is an MPEGWriter
        writer->setInterleaveDuration(mInterleaveDurationUs);
    }

    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);
    mWriter->start();
    return OK;
}

status_t StagefrightRecorder::pause() {
    if (mWriter == NULL) {
        return UNKNOWN_ERROR;
    }
    mWriter->pause();
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
        int64_t token = IPCThreadState::self()->clearCallingIdentity();
        if ((mFlags & FLAGS_HOT_CAMERA) == 0) {
            LOGV("Camera was cold when we started, stopping preview");
            mCamera->stopPreview();
        }
        mCamera->unlock();
        mCamera = NULL;
        IPCThreadState::self()->restoreCallingIdentity(token);
        mFlags = 0;
    }
    return OK;
}

status_t StagefrightRecorder::reset() {
    stop();

    // No audio or video source by default
    mAudioSource = AUDIO_SOURCE_LIST_END;
    mVideoSource = VIDEO_SOURCE_LIST_END;

    // Default parameters
    mOutputFormat  = OUTPUT_FORMAT_THREE_GPP;
    mAudioEncoder  = AUDIO_ENCODER_AMR_NB;
    mVideoEncoder  = VIDEO_ENCODER_H263;
    mVideoWidth    = 176;
    mVideoHeight   = 144;
    mFrameRate     = 20;
    mVideoBitRate  = 192000;
    mSampleRate    = 8000;
    mAudioChannels = 1;
    mAudioBitRate  = 12200;
    mInterleaveDurationUs = 0;
    mIFramesInterval = 1;

    mOutputFd = -1;
    mFlags = 0;

    return OK;
}

status_t StagefrightRecorder::getMaxAmplitude(int *max) {
    *max = 0;

    return OK;
}

}  // namespace android
