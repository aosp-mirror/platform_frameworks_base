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
#define LOG_TAG "CameraSource"
#include <utils/Log.h>

#include <OMX_Component.h>

#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <utils/String8.h>

namespace android {

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
}

void CameraSourceListener::postDataTimestamp(
        nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) {

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallbackTimestamp(timestamp/1000, msgType, dataPtr);
    }
}

// static
CameraSource *CameraSource::Create() {
    sp<Camera> camera = Camera::connect(0);

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSource(camera);
}

// static
CameraSource *CameraSource::CreateFromCamera(const sp<Camera> &camera) {
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
      mLastFrameTimestampUs(0),
      mNumFramesReceived(0),
      mNumFramesEncoded(0),
      mNumFramesDropped(0),
      mBufferGroup(NULL),
      mStarted(false) {
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

static int bytesPerPixelTimes10(const char *colorFormat) {
    LOGI("color format: %s", colorFormat);
    return 20;
#if 0
    // XXX: Fix Camera Hal bug?
    // On sholes, it returns CameraParameters::PIXEL_FORMAT_YUV420SP???
    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422SP) ||
        !strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422I)  ||
        !strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_RGB565)) {
        return 20;
    } else if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV420SP)) {
        return 15;
    }
    CHECK_EQ(0, "Unknown color format");
#endif
}

status_t CameraSource::start(MetaData *) {
    LOGV("start");
    CHECK(!mStarted);

    mCamera->setListener(new CameraSourceListener(this));
    CHECK_EQ(OK, mCamera->startRecording());

    mStarted = true;
    mBufferGroup = new MediaBufferGroup();
    String8 s = mCamera->getParameters();
    CameraParameters params(s);
    const char *colorFormat = params.getPreviewFormat();
    const int size = (mWidth * mHeight * bytesPerPixelTimes10(colorFormat))/10;
    mBufferGroup->add_buffer(new MediaBuffer(size));

    return OK;
}

status_t CameraSource::stop() {
    LOGV("stop");
    Mutex::Autolock autoLock(mLock);
    mStarted = false;
    mFrameAvailableCondition.signal();
    mCamera->setListener(NULL);
    mCamera->stopRecording();

    releaseQueuedFrames();
    delete mBufferGroup;
    mBufferGroup = NULL;
    LOGI("Frames received/encoded/dropped: %d/%d/%d, timestamp (us) last/first: %lld/%lld",
            mNumFramesReceived, mNumFramesEncoded, mNumFramesDropped,
            mLastFrameTimestampUs, mFirstFrameTimeUs);

    CHECK_EQ(mNumFramesReceived, mNumFramesEncoded + mNumFramesDropped);
    return OK;
}

void CameraSource::releaseQueuedFrames() {
    List<sp<IMemory> >::iterator it;
    while (!mFrames.empty()) {
        it = mFrames.begin();
        mCamera->releaseRecordingFrame(*it);
        mFrames.erase(it);
        ++mNumFramesDropped;
    }
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
    LOGV("read");

    *buffer = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        return ERROR_UNSUPPORTED;
    }

    sp<IMemory> frame;
    int64_t frameTime;

    {
        Mutex::Autolock autoLock(mLock);
        while (mStarted && mFrames.empty()) {
            mFrameAvailableCondition.wait(mLock);
        }
        if (!mStarted) {
            return OK;
        }
        frame = *mFrames.begin();
        mFrames.erase(mFrames.begin());

        frameTime = *mFrameTimes.begin();
        mFrameTimes.erase(mFrameTimes.begin());
        ++mNumFramesEncoded;
    }

    mBufferGroup->acquire_buffer(buffer);
    memcpy((*buffer)->data(), frame->pointer(), frame->size());
    (*buffer)->set_range(0, frame->size());
    mCamera->releaseRecordingFrame(frame);

    (*buffer)->meta_data()->clear();
    (*buffer)->meta_data()->setInt64(kKeyTime, frameTime);

    return OK;
}

void CameraSource::dataCallbackTimestamp(int64_t timestampUs,
        int32_t msgType, const sp<IMemory> &data) {
    LOGV("dataCallbackTimestamp: timestamp %lld us", timestampUs);
    mLastFrameTimestampUs = timestampUs;
    Mutex::Autolock autoLock(mLock);
    if (!mStarted) {
        mCamera->releaseRecordingFrame(data);
        ++mNumFramesReceived;
        ++mNumFramesDropped;
        return;
    }

    if (mNumFramesReceived == 0) {
        mFirstFrameTimeUs = timestampUs;
    }
    ++mNumFramesReceived;

    mFrames.push_back(data);
    mFrameTimes.push_back(timestampUs - mFirstFrameTimeUs);
    mFrameAvailableCondition.signal();
}

}  // namespace android
