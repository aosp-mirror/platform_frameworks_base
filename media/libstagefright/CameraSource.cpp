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
#include <cutils/properties.h> // for property_get
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <ui/Camera.h>
#include <ui/CameraParameters.h>
#include <ui/GraphicBuffer.h>
#include <ui/ISurface.h>
#include <ui/Overlay.h>
#include <utils/String8.h>

namespace android {

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

struct DummySurface : public BnSurface {
    DummySurface() {}

    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx, int usage) {
        return NULL;
    }

    virtual status_t registerBuffers(const BufferHeap &buffers) {
        return OK;
    }

    virtual void postBuffer(ssize_t offset) {}
    virtual void unregisterBuffers() {}

    virtual sp<OverlayRef> createOverlay(
            uint32_t w, uint32_t h, int32_t format) {
        return NULL;
    }

protected:
    virtual ~DummySurface() {}

    DummySurface(const DummySurface &);
    DummySurface &operator=(const DummySurface &);
};

struct CameraSourceListener : public CameraListener {
    CameraSourceListener(const sp<CameraSource> &source);

    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void postData(int32_t msgType, const sp<IMemory> &dataPtr);

    virtual void postDataTimestamp(
            nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

protected:
    virtual ~CameraSourceListener();

private:
    wp<CameraSource> mSource;

    CameraSourceListener(const CameraSourceListener &);
    CameraSourceListener &operator=(const CameraSourceListener &);
};

CameraSourceListener::CameraSourceListener(const sp<CameraSource> &source)
    : mSource(source) {
}

CameraSourceListener::~CameraSourceListener() {
}

void CameraSourceListener::notify(int32_t msgType, int32_t ext1, int32_t ext2) {
    LOGV("notify(%d, %d, %d)", msgType, ext1, ext2);
}

void CameraSourceListener::postData(int32_t msgType, const sp<IMemory> &dataPtr) {
    LOGV("postData(%d, ptr:%p, size:%d)",
         msgType, dataPtr->pointer(), dataPtr->size());

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallback(msgType, dataPtr);
    }
}

void CameraSourceListener::postDataTimestamp(
        nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) {
    LOGV("postDataTimestamp(%lld, %d, ptr:%p, size:%d)",
         timestamp, msgType, dataPtr->pointer(), dataPtr->size());
}

// static
CameraSource *CameraSource::Create() {
    sp<Camera> camera = Camera::connect();

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSource(camera);
}

// static
CameraSource *CameraSource::CreateFromICamera(const sp<ICamera> &icamera) {
    sp<Camera> camera = Camera::create(icamera);

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSource(camera);
}

CameraSource::CameraSource(const sp<Camera> &camera)
    : mCamera(camera),
      mWidth(0),
      mHeight(0),
      mFirstFrameTimeUs(0),
      mNumFrames(0),
      mStarted(false) {
    char value[PROPERTY_VALUE_MAX];
    if (property_get("ro.hardware", value, NULL) && !strcmp(value, "sholes")) {
        // The hardware encoder(s) do not support yuv420, but only YCbYCr,
        // fortunately the camera also supports this, so we needn't transcode.
        mCamera->setParameters(String8("preview-format=yuv422i-yuyv"));
    }

    String8 s = mCamera->getParameters();
    printf("params: \"%s\"\n", s.string());

    CameraParameters params(s);
    params.getPreviewSize(&mWidth, &mHeight);
}

CameraSource::~CameraSource() {
    if (mStarted) {
        stop();
    }
}

void CameraSource::setPreviewSurface(const sp<ISurface> &surface) {
    mPreviewSurface = surface;
}

status_t CameraSource::start(MetaData *) {
    CHECK(!mStarted);

    mCamera->setListener(new CameraSourceListener(this));

    status_t err =
        mCamera->setPreviewDisplay(
                mPreviewSurface != NULL ? mPreviewSurface : new DummySurface);
    CHECK_EQ(err, OK);

    mCamera->setPreviewCallbackFlags(
            FRAME_CALLBACK_FLAG_ENABLE_MASK
            | FRAME_CALLBACK_FLAG_COPY_OUT_MASK);

    err = mCamera->startPreview();
    CHECK_EQ(err, OK);

    mStarted = true;

    return OK;
}

status_t CameraSource::stop() {
    CHECK(mStarted);

    mCamera->stopPreview();

    mStarted = false;

    return OK;
}

sp<MetaData> CameraSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    meta->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420SemiPlanar);
    meta->setInt32(kKeyWidth, mWidth);
    meta->setInt32(kKeyHeight, mHeight);

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
    int64_t frameTime;

    {
        Mutex::Autolock autoLock(mLock);
        while (mFrames.empty()) {
            mFrameAvailableCondition.wait(mLock);
        }

        frame = *mFrames.begin();
        mFrames.erase(mFrames.begin());

        frameTime = *mFrameTimes.begin();
        mFrameTimes.erase(mFrameTimes.begin());
    }

    *buffer = new MediaBuffer(frame->size());
    memcpy((*buffer)->data(), frame->pointer(), frame->size());
    (*buffer)->set_range(0, frame->size());

    (*buffer)->meta_data()->clear();
    (*buffer)->meta_data()->setInt64(kKeyTime, frameTime);

    return OK;
}

void CameraSource::dataCallback(int32_t msgType, const sp<IMemory> &data) {
    Mutex::Autolock autoLock(mLock);

    int64_t nowUs = getNowUs();
    if (mNumFrames == 0) {
        mFirstFrameTimeUs = nowUs;
    }
    ++mNumFrames;

    mFrames.push_back(data);
    mFrameTimes.push_back(nowUs - mFirstFrameTimeUs);
    mFrameAvailableCondition.signal();
}

}  // namespace android
