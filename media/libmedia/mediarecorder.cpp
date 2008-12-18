/*
 **
 ** Copyright (c) 2008 The Android Open Source Project
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
#define LOG_TAG "MediaRecorder"
#include <utils/Log.h>
#include <ui/Surface.h>
#include <media/mediarecorder.h>
#include <utils/IServiceManager.h>
#include <media/IMediaPlayerService.h>
#include <media/IMediaRecorder.h>

namespace android {

status_t MediaRecorder::setCamera(const sp<ICamera>& camera)
{
    LOGV("setCamera(%p)", camera.get());
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_IDLE)) {
        LOGE("setCamera called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setCamera(camera);
    if (OK != ret) {
        LOGV("setCamera failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    return ret;
}

status_t MediaRecorder::setPreviewSurface(const sp<Surface>& surface)
{
    LOGV("setPreviewSurface(%p)", surface.get());
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setPreviewSurface called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setPreviewSurface(surface->getISurface());
    if (OK != ret) {
        LOGV("setPreviewSurface failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    return ret;
}

status_t MediaRecorder::init()
{
    LOGV("init");
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_IDLE)) {
        LOGE("init called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->init();
    if (OK != ret) {
        LOGV("init failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mCurrentState = MEDIA_RECORDER_INITIALIZED;
    return ret;
}

status_t MediaRecorder::setVideoSource(int vs)
{
    LOGV("setVideoSource(%d)", vs);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mIsVideoSourceSet) {
        LOGE("video source has already been set");
        return INVALID_OPERATION;
    }
    if (mCurrentState & MEDIA_RECORDER_IDLE) {
        LOGV("Call init() since the media recorder is not initialized yet");
        status_t ret = init();
        if (OK != ret) {
            return ret;
        }
    }
    if (!(mCurrentState & MEDIA_RECORDER_INITIALIZED)) {
        LOGE("setVideoSource called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setVideoSource(vs);
    if (OK != ret) {
        LOGV("setVideoSource failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mIsVideoSourceSet = true;
    return ret;
}

status_t MediaRecorder::setAudioSource(int as)
{
    LOGV("setAudioSource(%d)", as);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mCurrentState & MEDIA_RECORDER_IDLE) {
        LOGV("Call init() since the media recorder is not initialized yet");
        status_t ret = init();
        if (OK != ret) {
            return ret;
        }
    }
    if (mIsAudioSourceSet) {
        LOGE("audio source has already been set");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_INITIALIZED)) {
        LOGE("setAudioSource called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setAudioSource(as);
    if (OK != ret) {
        LOGV("setAudioSource failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mIsAudioSourceSet = true;
    return ret;
}

status_t MediaRecorder::setOutputFormat(int of)
{
    LOGV("setOutputFormat(%d)", of);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_INITIALIZED)) {
        LOGE("setOutputFormat called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setOutputFormat(of);
    if (OK != ret) {
        LOGE("setOutputFormat failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mCurrentState = MEDIA_RECORDER_DATASOURCE_CONFIGURED;
    return ret;
}

status_t MediaRecorder::setVideoEncoder(int ve)
{
    LOGV("setVideoEncoder(%d)", ve);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mIsVideoEncoderSet) {
        LOGE("video encoder has already been set");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setVideoEncoder called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setVideoEncoder(ve);
    if (OK != ret) {
        LOGV("setVideoEncoder failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mIsVideoEncoderSet = true;
    return ret;
}

status_t MediaRecorder::setAudioEncoder(int ae)
{
    LOGV("setAudioEncoder(%d)", ae);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mIsAudioEncoderSet) {
        LOGE("audio encoder has already been set");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setAudioEncoder called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setAudioEncoder(ae);
    if (OK != ret) {
        LOGV("setAudioEncoder failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mIsAudioEncoderSet = true;
    return ret;
}

status_t MediaRecorder::setOutputFile(const char* path)
{
    LOGV("setOutputFile(%s)", path);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mIsOutputFileSet) {
        LOGE("output file has already been set");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setOutputFile called in an invalid state(%d)", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setOutputFile(path);
    if (OK != ret) {
        LOGV("setAudioEncoder failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mIsOutputFileSet = true;
    return ret;
}

status_t MediaRecorder::setVideoSize(int width, int height)
{
    LOGV("setVideoSize(%d, %d)", width, height);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setVideoSize called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setVideoSize(width, height);
    if (OK != ret) {
        LOGE("setVideoSize failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    return ret;
}

status_t MediaRecorder::setVideoFrameRate(int frames_per_second)
{
    LOGV("setVideoFrameRate(%d)", frames_per_second);
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setVideoFrameRate called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->setVideoFrameRate(frames_per_second);
    if (OK != ret) {
        LOGE("setVideoFrameRate failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    return ret;
}

status_t MediaRecorder::prepare()
{
    LOGV("prepare");
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_DATASOURCE_CONFIGURED)) {
        LOGE("setVideoFrameRate called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->prepare();
    if (OK != ret) {
        LOGE("prepare failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mCurrentState = MEDIA_RECORDER_PREPARED;
    return ret;
}

status_t MediaRecorder::getMaxAmplitude(int* max)
{
    LOGV("getMaxAmplitude");
    if(mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (mCurrentState & MEDIA_RECORDER_ERROR) {
        LOGE("setVideoFrameRate called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->getMaxAmplitude(max);
    if (OK != ret) {
        LOGE("getMaxAmplitude failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    return ret;
}

status_t MediaRecorder::start()
{
    LOGV("start");
    if (mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_PREPARED)) {
        LOGE("start called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->start();
    if (OK != ret) {
        LOGE("start failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mCurrentState = MEDIA_RECORDER_RECORDING;
    return ret;
}

status_t MediaRecorder::stop()
{
    LOGV("stop");
    if (mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    if (!(mCurrentState & MEDIA_RECORDER_RECORDING)) {
        LOGE("stop called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }

    status_t ret = mMediaRecorder->stop();
    if (OK != ret) {
        LOGE("stop failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    }
    mCurrentState = MEDIA_RECORDER_IDLE;
    return ret;
}

// Reset should be OK in any state
status_t MediaRecorder::reset()
{
    LOGV("reset");
    if (mMediaRecorder == NULL) {
        LOGE("media recorder is not initialized yet");
        return INVALID_OPERATION;
    }
    
    doCleanUp();
    status_t ret = UNKNOWN_ERROR;
    switch(mCurrentState) {
        case MEDIA_RECORDER_IDLE:
            ret = OK;
            break;

        case MEDIA_RECORDER_RECORDING:
        case MEDIA_RECORDER_DATASOURCE_CONFIGURED:
        case MEDIA_RECORDER_PREPARED:
        case MEDIA_RECORDER_ERROR: {
            ret = doReset();
            if (OK != ret) {
               return ret;  // No need to continue
            }
        }  // Intentional fall through
        case MEDIA_RECORDER_INITIALIZED:
            ret = close();
            break;

        default: {
            LOGE("Unexpected non-existing state: %d", mCurrentState);
            break;
        }
    }
    return ret;
}

status_t MediaRecorder::close()
{
    LOGV("close");
    if (!(mCurrentState & MEDIA_RECORDER_INITIALIZED)) {
        LOGE("close called in an invalid state: %d", mCurrentState);
        return INVALID_OPERATION;
    }
    status_t ret = mMediaRecorder->close();
    if (OK != ret) {
        LOGE("close failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    } else {
        mCurrentState = MEDIA_RECORDER_IDLE;
    }
    return ret;
}

status_t MediaRecorder::doReset()
{
    LOGV("doReset");
    status_t ret = mMediaRecorder->reset();
    if (OK != ret) {
        LOGE("doReset failed: %d", ret);
        mCurrentState = MEDIA_RECORDER_ERROR;
        return UNKNOWN_ERROR;
    } else {
        mCurrentState = MEDIA_RECORDER_INITIALIZED;
    }
    return ret;
}

void MediaRecorder::doCleanUp()
{
    LOGV("doCleanUp");
    mIsAudioSourceSet  = false;
    mIsVideoSourceSet  = false;
    mIsAudioEncoderSet = false;
    mIsVideoEncoderSet = false;
    mIsOutputFileSet   = false;
}

// Release should be OK in any state
status_t MediaRecorder::release()
{
    LOGV("release");
    if (mMediaRecorder != NULL) {
        return mMediaRecorder->release();
    }
    return INVALID_OPERATION;
}

MediaRecorder::MediaRecorder()
{
    LOGV("constructor");
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder;

    do {
        binder = sm->getService(String16("media.player"));
        if (binder != NULL) {
            break;
        }
        LOGW("MediaPlayerService not published, waiting...");
        usleep(500000); // 0.5 s
    } while(true);

    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);
    if (service != NULL) {
        mMediaRecorder = service->createMediaRecorder(getpid());
    }

    mMediaRecorder = service->createMediaRecorder(getpid());
    if (mMediaRecorder != NULL) {
        mCurrentState = MEDIA_RECORDER_IDLE;
    }
    doCleanUp();
}

status_t MediaRecorder::initCheck()
{
    return mMediaRecorder != 0 ? NO_ERROR : NO_INIT;
}

MediaRecorder::~MediaRecorder()
{
    LOGV("destructor");
    if (mMediaRecorder != NULL) {
        mMediaRecorder.clear();
    }
}

}; // namespace android

