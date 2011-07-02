/*
 ** Copyright 2008, The Android Open Source Project
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
#include <cutils/properties.h> // for property_get
#include <android_runtime/ActivityManager.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/MemoryHeapBase.h>
#include <binder/MemoryBase.h>

#include <utils/String16.h>

#include <media/AudioTrack.h>

#include <system/audio.h>

#include "MediaRecorderClient.h"
#include "MediaPlayerService.h"

#include "StagefrightRecorder.h"
#include <gui/ISurfaceTexture.h>

namespace android {

const char* cameraPermission = "android.permission.CAMERA";
const char* recordAudioPermission = "android.permission.RECORD_AUDIO";

static bool checkPermission(const char* permissionString) {
#ifndef HAVE_ANDROID_OS
    return true;
#endif
    if (getpid() == IPCThreadState::self()->getCallingPid()) return true;
    bool ok = checkCallingPermission(String16(permissionString));
    if (!ok) LOGE("Request requires %s", permissionString);
    return ok;
}


sp<ISurfaceTexture> MediaRecorderClient::querySurfaceMediaSource()
{
    LOGV("Query SurfaceMediaSource");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NULL;
    }
    return mRecorder->querySurfaceMediaSource();
}



status_t MediaRecorderClient::setCamera(const sp<ICamera>& camera,
                                        const sp<ICameraRecordingProxy>& proxy)
{
    LOGV("setCamera");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setCamera(camera, proxy);
}

status_t MediaRecorderClient::setPreviewSurface(const sp<Surface>& surface)
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
    if (!checkPermission(cameraPermission)) {
        return PERMISSION_DENIED;
    }
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL)	{
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setVideoSource((video_source)vs);
}

status_t MediaRecorderClient::setAudioSource(int as)
{
    LOGV("setAudioSource(%d)", as);
    if (!checkPermission(recordAudioPermission)) {
        return PERMISSION_DENIED;
    }
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL)  {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setAudioSource((audio_source_t)as);
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

status_t MediaRecorderClient::setOutputFileAuxiliary(int fd)
{
    LOGV("setOutputFileAuxiliary(%d)", fd);
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setOutputFileAuxiliary(fd);
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

status_t MediaRecorderClient::setParameters(const String8& params) {
    LOGV("setParameters(%s)", params.string());
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setParameters(params);
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
        wp<MediaRecorderClient> client(this);
        mMediaPlayerService->removeMediaRecorderClient(client);
    }
    return NO_ERROR;
}

MediaRecorderClient::MediaRecorderClient(const sp<MediaPlayerService>& service, pid_t pid)
{
    LOGV("Client constructor");
    mPid = pid;
    mRecorder = new StagefrightRecorder;
    mMediaPlayerService = service;
}

MediaRecorderClient::~MediaRecorderClient()
{
    LOGV("Client destructor");
    release();
}

status_t MediaRecorderClient::setListener(const sp<IMediaRecorderClient>& listener)
{
    LOGV("setListener");
    Mutex::Autolock lock(mLock);
    if (mRecorder == NULL) {
        LOGE("recorder is not initialized");
        return NO_INIT;
    }
    return mRecorder->setListener(listener);
}

status_t MediaRecorderClient::dump(int fd, const Vector<String16>& args) const {
    if (mRecorder != NULL) {
        return mRecorder->dump(fd, args);
    }
    return OK;
}

}; // namespace android
