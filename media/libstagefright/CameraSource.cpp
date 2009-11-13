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

#include <sys/time.h>

#include <OMX_Component.h>

#include <binder/IServiceManager.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <ui/ICameraClient.h>
#include <ui/ICameraService.h>
#include <ui/Overlay.h>
#include <utils/String16.h>

namespace android {

class CameraBuffer : public MediaBuffer {
public:
    CameraBuffer(const sp<IMemory> &frame)
        : MediaBuffer(frame->pointer(), frame->size()),
          mFrame(frame) {
    }

    sp<IMemory> releaseFrame() {
        sp<IMemory> frame = mFrame;
        mFrame.clear();
        return frame;
    }

private:
    sp<IMemory> mFrame;
};

class CameraSourceClient : public BnCameraClient {
public:
    CameraSourceClient()
        : mSource(NULL) {
    }

    virtual void notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2) {
        CHECK(mSource != NULL);
        mSource->notifyCallback(msgType, ext1, ext2);
    }

    virtual void dataCallback(int32_t msgType, const sp<IMemory> &data) {
        CHECK(mSource != NULL);
        mSource->dataCallback(msgType, data);
    }

    void setCameraSource(CameraSource *source) {
        mSource = source;
    }

private:
    CameraSource *mSource;
};

class DummySurface : public BnSurface {
public:
    DummySurface() {}

    virtual status_t registerBuffers(const BufferHeap &buffers) {
        return OK;
    }

    virtual void postBuffer(ssize_t offset) {
    }

    virtual void unregisterBuffers() {
    }
    
    virtual sp<OverlayRef> createOverlay(
            uint32_t w, uint32_t h, int32_t format) {
        return NULL;
    }
};

// static
CameraSource *CameraSource::Create() {
    sp<IServiceManager> sm = defaultServiceManager();

    sp<ICameraService> service =
        interface_cast<ICameraService>(
                sm->getService(String16("media.camera")));

    sp<CameraSourceClient> client = new CameraSourceClient;
    sp<ICamera> camera = service->connect(client);

    CameraSource *source = new CameraSource(camera, client);
    client->setCameraSource(source);

    return source;
}

CameraSource::CameraSource(
        const sp<ICamera> &camera, const sp<ICameraClient> &client)
    : mCamera(camera),
      mCameraClient(client),
      mNumFrames(0),
      mStarted(false) {
    printf("params: \"%s\"\n", mCamera->getParameters().string());
}

CameraSource::~CameraSource() {
    if (mStarted) {
        stop();
    }

    mCamera->disconnect();
}

status_t CameraSource::start(MetaData *) {
    CHECK(!mStarted);

    status_t err = mCamera->lock();
    CHECK_EQ(err, OK);

    err = mCamera->setPreviewDisplay(new DummySurface);
    CHECK_EQ(err, OK);
    mCamera->setPreviewCallbackFlag(1);
    mCamera->startPreview();
    CHECK_EQ(err, OK);

    mStarted = true;

    return OK;
}

status_t CameraSource::stop() {
    CHECK(mStarted);

    mCamera->stopPreview();
    mCamera->unlock();

    mStarted = false;

    return OK;
}

sp<MetaData> CameraSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    meta->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420SemiPlanar);
    meta->setInt32(kKeyWidth, 480);
    meta->setInt32(kKeyHeight, 320);

    return meta;
}

status_t CameraSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    CHECK(mStarted);

    *buffer = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        return ERROR_UNSUPPORTED;
    }

    sp<IMemory> frame;

    {
        Mutex::Autolock autoLock(mLock);
        while (mFrames.empty()) {
            mFrameAvailableCondition.wait(mLock);
        }

        frame = *mFrames.begin();
        mFrames.erase(mFrames.begin());
    }

    int count = mNumFrames++;

    *buffer = new CameraBuffer(frame);

    (*buffer)->meta_data()->clear();
    (*buffer)->meta_data()->setInt32(kKeyTimeScale, 15);
    (*buffer)->meta_data()->setInt32(kKeyTimeUnits, count);

    (*buffer)->add_ref();
    (*buffer)->setObserver(this);

    return OK;
}

void CameraSource::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2) {
    printf("notifyCallback %d, %d, %d\n", msgType, ext1, ext2);
}

void CameraSource::dataCallback(int32_t msgType, const sp<IMemory> &data) {
    Mutex::Autolock autoLock(mLock);

    mFrames.push_back(data);
    mFrameAvailableCondition.signal();
}

void CameraSource::signalBufferReturned(MediaBuffer *_buffer) {
    CameraBuffer *buffer = static_cast<CameraBuffer *>(_buffer);

    mCamera->releaseRecordingFrame(buffer->releaseFrame());

    buffer->setObserver(NULL);
    buffer->release();
    buffer = NULL;
}

}  // namespace android
