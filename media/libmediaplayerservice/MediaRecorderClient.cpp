/*
 ** Copyright 2008, HTC Inc.
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
#define LOG_TAG "MediaRecorderService"
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <string.h>
#include <cutils/atomic.h>
#include <android_runtime/ActivityManager.h>
#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>
#include <utils/MemoryHeapBase.h>
#include <utils/MemoryBase.h>
#include <media/PVMediaRecorder.h>

#include "MediaRecorderClient.h"

namespace android {

status_t MediaRecorderClient::setCamera(const sp<ICamera>& camera)
{
    LOGV("setCamera");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setCamera(camera);
}

status_t MediaRecorderClient::setPreviewSurface(const sp<ISurface>& surface)
{
    LOGV("setPreviewSurface");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setPreviewSurface(surface);
}

status_t MediaRecorderClient::setVideoSource(int vs)
{
    LOGV("setVideoSource(%d)", vs);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL)	{
        LOGE("recorder is not initialized");
    }
    return mRecorder->setVideoSource((video_source)vs);
}

status_t MediaRecorderClient::setAudioSource(int as)
{
    LOGV("setAudioSource(%d)", as);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL)  {
        LOGE("recorder is not initialized");
    }
    return mRecorder->setAudioSource((audio_source)as);
}

status_t MediaRecorderClient::setOutputFormat(int of)
{
    LOGV("setOutputFormat(%d)", of);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setOutputFormat((output_format)of);
}

status_t MediaRecorderClient::setVideoEncoder(int ve)
{
    LOGV("setVideoEncoder(%d)", ve);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setVideoEncoder((video_encoder)ve);
}

status_t MediaRecorderClient::setAudioEncoder(int ae)
{
    LOGV("setAudioEncoder(%d)", ae);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setAudioEncoder((audio_encoder)ae);
}

status_t MediaRecorderClient::setOutputFile(const char* path)
{
    LOGV("setOutputFile(%s)", path);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setOutputFile(path);
}

status_t MediaRecorderClient::setOutputFile(int fd, int64_t offset, int64_t length)
{
    LOGV("setOutputFile(%d, %lld, %lld)", fd, offset, length);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setOutputFile(fd, offset, length);
}

status_t MediaRecorderClient::setVideoSize(int width, int height)
{
    LOGV("setVideoSize(%dx%d)", width, height);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setVideoSize(width, height);
}

status_t MediaRecorderClient::setVideoFrameRate(int frames_per_second)
{
    LOGV("setVideoFrameRate(%d)", frames_per_second);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setVideoFrameRate(frames_per_second);
}

status_t MediaRecorderClient::prepare()
{
    LOGV("prepare");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->prepare();
}


status_t MediaRecorderClient::getMaxAmplitude(int* max)
{
    LOGV("getMaxAmplitude");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->getMaxAmplitude(max);
}

status_t MediaRecorderClient::start()
{
    LOGV("start");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->start();

}

status_t MediaRecorderClient::stop()
{
    LOGV("stop");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->stop();
}

status_t MediaRecorderClient::init()
{
    LOGV("init");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->init();
}

status_t MediaRecorderClient::close()
{
    LOGV("close");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->close();
}


status_t MediaRecorderClient::reset()
{
    LOGV("reset");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->reset();
}

status_t MediaRecorderClient::release()
{
    LOGV("release");
    Mutex::Autolock lock(mLock);
    if (mRecorder != NULL) {
        delete mRecorder;
        mRecorder = NULL;
    }
    return NO_ERROR;
}

MediaRecorderClient::MediaRecorderClient(pid_t pid)
{
    LOGV("Client constructor");
    mPid = pid;
    mRecorder = new PVMediaRecorder();
}

MediaRecorderClient::~MediaRecorderClient()
{
    LOGV("Client destructor");
    release();
}

}; // namespace android

