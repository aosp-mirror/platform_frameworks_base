/*
 * Copyright (C) 2010 The Android Open Source Project
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
#define LOG_TAG "CameraSourceTimeLapse"

#include <binder/IPCThreadState.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/CameraSourceTimeLapse.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/YUVImage.h>
#include <media/stagefright/YUVCanvas.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <ui/Rect.h>
#include <utils/String8.h>
#include <utils/Vector.h>
#include "OMX_Video.h"
#include <limits.h>

namespace android {

// static
CameraSourceTimeLapse *CameraSourceTimeLapse::Create(bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t width, int32_t height,
        int32_t videoFrameRate) {
    sp<Camera> camera = Camera::connect(0);

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSourceTimeLapse(camera, useStillCameraForTimeLapse,
            timeBetweenTimeLapseFrameCaptureUs, width, height, videoFrameRate);
}

// static
CameraSourceTimeLapse *CameraSourceTimeLapse::CreateFromCamera(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t width, int32_t height,
        int32_t videoFrameRate) {
    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSourceTimeLapse(camera, useStillCameraForTimeLapse,
            timeBetweenTimeLapseFrameCaptureUs, width, height, videoFrameRate);
}

CameraSourceTimeLapse::CameraSourceTimeLapse(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t width, int32_t height,
        int32_t videoFrameRate)
    : CameraSource(camera),
      mUseStillCameraForTimeLapse(useStillCameraForTimeLapse),
      mTimeBetweenTimeLapseFrameCaptureUs(timeBetweenTimeLapseFrameCaptureUs),
      mTimeBetweenTimeLapseVideoFramesUs(1E6/videoFrameRate),
      mLastTimeLapseFrameRealTimestampUs(0),
      mSkipCurrentFrame(false) {

    LOGV("starting time lapse mode");
    mVideoWidth = width;
    mVideoHeight = height;
    if (mUseStillCameraForTimeLapse) {
        CHECK(setPictureSizeToClosestSupported(width, height));
        mNeedCropping = computeCropRectangleOffset();
        mMeta->setInt32(kKeyWidth, width);
        mMeta->setInt32(kKeyHeight, height);
    }
}

CameraSourceTimeLapse::~CameraSourceTimeLapse() {
}

bool CameraSourceTimeLapse::setPictureSizeToClosestSupported(int32_t width, int32_t height) {
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    String8 s = mCamera->getParameters();
    IPCThreadState::self()->restoreCallingIdentity(token);

    CameraParameters params(s);
    Vector<Size> supportedSizes;
    params.getSupportedPictureSizes(supportedSizes);

    int32_t minPictureSize = INT_MAX;
    for (uint32_t i = 0; i < supportedSizes.size(); ++i) {
        int32_t pictureWidth = supportedSizes[i].width;
        int32_t pictureHeight = supportedSizes[i].height;

        if ((pictureWidth >= width) && (pictureHeight >= height)) {
            int32_t pictureSize = pictureWidth*pictureHeight;
            if (pictureSize < minPictureSize) {
                minPictureSize = pictureSize;
                mPictureWidth = pictureWidth;
                mPictureHeight = pictureHeight;
            }
        }
    }
    LOGV("Picture size = (%d, %d)", mPictureWidth, mPictureHeight);
    return (minPictureSize != INT_MAX);
}

bool CameraSourceTimeLapse::computeCropRectangleOffset() {
    if ((mPictureWidth == mVideoWidth) && (mPictureHeight == mVideoHeight)) {
        return false;
    }

    CHECK((mPictureWidth > mVideoWidth) && (mPictureHeight > mVideoHeight));

    int32_t widthDifference = mPictureWidth - mVideoWidth;
    int32_t heightDifference = mPictureHeight - mVideoHeight;

    mCropRectStartX = widthDifference/2;
    mCropRectStartY = heightDifference/2;

    LOGV("setting crop rectangle offset to (%d, %d)", mCropRectStartX, mCropRectStartY);

    return true;
}

// static
void *CameraSourceTimeLapse::ThreadTimeLapseWrapper(void *me) {
    CameraSourceTimeLapse *source = static_cast<CameraSourceTimeLapse *>(me);
    source->threadTimeLapseEntry();
    return NULL;
}

void CameraSourceTimeLapse::threadTimeLapseEntry() {
    while(mStarted) {
        if (mCameraIdle) {
            LOGV("threadTimeLapseEntry: taking picture");
            CHECK_EQ(OK, mCamera->takePicture());
            mCameraIdle = false;
            usleep(mTimeBetweenTimeLapseFrameCaptureUs);
        } else {
            LOGV("threadTimeLapseEntry: camera busy with old takePicture. Sleeping a little.");
            usleep(1E4);
        }
    }
}

void CameraSourceTimeLapse::startCameraRecording() {
    if (mUseStillCameraForTimeLapse) {
        LOGV("start time lapse recording using still camera");

        int64_t token = IPCThreadState::self()->clearCallingIdentity();
        String8 s = mCamera->getParameters();
        IPCThreadState::self()->restoreCallingIdentity(token);

        CameraParameters params(s);
        params.setPictureSize(mPictureWidth, mPictureHeight);
        mCamera->setParameters(params.flatten());
        mCameraIdle = true;

        // create a thread which takes pictures in a loop
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

        pthread_create(&mThreadTimeLapse, &attr, ThreadTimeLapseWrapper, this);
        pthread_attr_destroy(&attr);
    } else {
        LOGV("start time lapse recording using video camera");
        CHECK_EQ(OK, mCamera->startRecording());
    }
}

void CameraSourceTimeLapse::stopCameraRecording() {
    if (mUseStillCameraForTimeLapse) {
        void *dummy;
        pthread_join(mThreadTimeLapse, &dummy);
    } else {
        mCamera->stopRecording();
    }
}

void CameraSourceTimeLapse::releaseRecordingFrame(const sp<IMemory>& frame) {
    if (!mUseStillCameraForTimeLapse) {
        mCamera->releaseRecordingFrame(frame);
    }
}

sp<IMemory> CameraSourceTimeLapse::createIMemoryCopy(const sp<IMemory> &source_data) {
    size_t source_size = source_data->size();
    void* source_pointer = source_data->pointer();

    sp<MemoryHeapBase> newMemoryHeap = new MemoryHeapBase(source_size);
    sp<MemoryBase> newMemory = new MemoryBase(newMemoryHeap, 0, source_size);
    memcpy(newMemory->pointer(), source_pointer, source_size);
    return newMemory;
}

// Allocates IMemory of final type MemoryBase with the given size.
sp<IMemory> allocateIMemory(size_t size) {
    sp<MemoryHeapBase> newMemoryHeap = new MemoryHeapBase(size);
    sp<MemoryBase> newMemory = new MemoryBase(newMemoryHeap, 0, size);
    return newMemory;
}

// static
void *CameraSourceTimeLapse::ThreadStartPreviewWrapper(void *me) {
    CameraSourceTimeLapse *source = static_cast<CameraSourceTimeLapse *>(me);
    source->threadStartPreview();
    return NULL;
}

void CameraSourceTimeLapse::threadStartPreview() {
    CHECK_EQ(OK, mCamera->startPreview());
    mCameraIdle = true;
}

void CameraSourceTimeLapse::restartPreview() {
    // Start this in a different thread, so that the dataCallback can return
    LOGV("restartPreview");
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    pthread_t threadPreview;
    pthread_create(&threadPreview, &attr, ThreadStartPreviewWrapper, this);
    pthread_attr_destroy(&attr);
}

sp<IMemory> CameraSourceTimeLapse::cropYUVImage(const sp<IMemory> &source_data) {
    // find the YUV format
    int32_t srcFormat;
    CHECK(mMeta->findInt32(kKeyColorFormat, &srcFormat));
    YUVImage::YUVFormat yuvFormat;
    if (srcFormat == OMX_COLOR_FormatYUV420SemiPlanar) {
        yuvFormat = YUVImage::YUV420SemiPlanar;
    } else if (srcFormat == OMX_COLOR_FormatYUV420Planar) {
        yuvFormat = YUVImage::YUV420Planar;
    }

    // allocate memory for cropped image and setup a canvas using it.
    sp<IMemory> croppedImageMemory = allocateIMemory(
            YUVImage::bufferSize(yuvFormat, mVideoWidth, mVideoHeight));
    YUVImage yuvImageCropped(yuvFormat,
            mVideoWidth, mVideoHeight,
            (uint8_t *)croppedImageMemory->pointer());
    YUVCanvas yuvCanvasCrop(yuvImageCropped);

    YUVImage yuvImageSource(yuvFormat,
            mPictureWidth, mPictureHeight,
            (uint8_t *)source_data->pointer());
    yuvCanvasCrop.CopyImageRect(
            Rect(mCropRectStartX, mCropRectStartY,
                mCropRectStartX + mVideoWidth,
                mCropRectStartY + mVideoHeight),
            0, 0,
            yuvImageSource);

    return croppedImageMemory;
}

void CameraSourceTimeLapse::dataCallback(int32_t msgType, const sp<IMemory> &data) {
    if (msgType == CAMERA_MSG_COMPRESSED_IMAGE) {
        // takePicture will complete after this callback, so restart preview.
        restartPreview();
        return;
    }
    if (msgType != CAMERA_MSG_RAW_IMAGE) {
        return;
    }

    LOGV("dataCallback for timelapse still frame");
    CHECK_EQ(true, mUseStillCameraForTimeLapse);

    int64_t timestampUs;
    if (mNumFramesReceived == 0) {
        timestampUs = mStartTimeUs;
    } else {
        timestampUs = mLastFrameTimestampUs + mTimeBetweenTimeLapseVideoFramesUs;
    }

    if (mNeedCropping) {
        sp<IMemory> croppedImageData = cropYUVImage(data);
        dataCallbackTimestamp(timestampUs, msgType, croppedImageData);
    } else {
        sp<IMemory> dataCopy = createIMemoryCopy(data);
        dataCallbackTimestamp(timestampUs, msgType, dataCopy);
    }
}

bool CameraSourceTimeLapse::skipCurrentFrame(int64_t timestampUs) {
    if (mSkipCurrentFrame) {
        mSkipCurrentFrame = false;
        return true;
    } else {
        return false;
    }
}

bool CameraSourceTimeLapse::skipFrameAndModifyTimeStamp(int64_t *timestampUs) {
    if (!mUseStillCameraForTimeLapse) {
        if (mLastTimeLapseFrameRealTimestampUs == 0) {
            // First time lapse frame. Initialize mLastTimeLapseFrameRealTimestampUs
            // to current time (timestampUs) and save frame data.
            LOGV("dataCallbackTimestamp timelapse: initial frame");

            mLastTimeLapseFrameRealTimestampUs = *timestampUs;
        } else if (*timestampUs <
                (mLastTimeLapseFrameRealTimestampUs + mTimeBetweenTimeLapseFrameCaptureUs)) {
            // Skip all frames from last encoded frame until
            // sufficient time (mTimeBetweenTimeLapseFrameCaptureUs) has passed.
            // Tell the camera to release its recording frame and return.
            LOGV("dataCallbackTimestamp timelapse: skipping intermediate frame");
            return true;
        } else {
            // Desired frame has arrived after mTimeBetweenTimeLapseFrameCaptureUs time:
            // - Reset mLastTimeLapseFrameRealTimestampUs to current time.
            // - Artificially modify timestampUs to be one frame time (1/framerate) ahead
            // of the last encoded frame's time stamp.
            LOGV("dataCallbackTimestamp timelapse: got timelapse frame");

            mLastTimeLapseFrameRealTimestampUs = *timestampUs;
            *timestampUs = mLastFrameTimestampUs + mTimeBetweenTimeLapseVideoFramesUs;
        }
    }
    return false;
}

void CameraSourceTimeLapse::dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data) {
    if (!mUseStillCameraForTimeLapse) {
        mSkipCurrentFrame = skipFrameAndModifyTimeStamp(&timestampUs);
    }
    CameraSource::dataCallbackTimestamp(timestampUs, msgType, data);
}

}  // namespace android
