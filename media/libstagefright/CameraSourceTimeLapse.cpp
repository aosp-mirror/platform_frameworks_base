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
CameraSourceTimeLapse *CameraSourceTimeLapse::CreateFromCamera(
        const sp<ICamera> &camera,
        const sp<ICameraRecordingProxy> &proxy,
        int32_t cameraId,
        Size videoSize,
        int32_t videoFrameRate,
        const sp<Surface>& surface,
        int64_t timeBetweenTimeLapseFrameCaptureUs) {

    CameraSourceTimeLapse *source = new
            CameraSourceTimeLapse(camera, proxy, cameraId,
                videoSize, videoFrameRate, surface,
                timeBetweenTimeLapseFrameCaptureUs);

    if (source != NULL) {
        if (source->initCheck() != OK) {
            delete source;
            return NULL;
        }
    }
    return source;
}

CameraSourceTimeLapse::CameraSourceTimeLapse(
        const sp<ICamera>& camera,
        const sp<ICameraRecordingProxy>& proxy,
        int32_t cameraId,
        Size videoSize,
        int32_t videoFrameRate,
        const sp<Surface>& surface,
        int64_t timeBetweenTimeLapseFrameCaptureUs)
    : CameraSource(camera, proxy, cameraId, videoSize, videoFrameRate, surface, true),
      mTimeBetweenTimeLapseFrameCaptureUs(timeBetweenTimeLapseFrameCaptureUs),
      mTimeBetweenTimeLapseVideoFramesUs(1E6/videoFrameRate),
      mLastTimeLapseFrameRealTimestampUs(0),
      mSkipCurrentFrame(false) {

    LOGD("starting time lapse mode: %lld us", mTimeBetweenTimeLapseFrameCaptureUs);
    mVideoWidth = videoSize.width;
    mVideoHeight = videoSize.height;

    if (trySettingVideoSize(videoSize.width, videoSize.height)) {
        mUseStillCameraForTimeLapse = false;
    } else {
        // TODO: Add a check to see that mTimeBetweenTimeLapseFrameCaptureUs is greater
        // than the fastest rate at which the still camera can take pictures.
        mUseStillCameraForTimeLapse = true;
        CHECK(setPictureSizeToClosestSupported(videoSize.width, videoSize.height));
        mNeedCropping = computeCropRectangleOffset();
        mMeta->setInt32(kKeyWidth, videoSize.width);
        mMeta->setInt32(kKeyHeight, videoSize.height);
    }

    // Initialize quick stop variables.
    mQuickStop = false;
    mForceRead = false;
    mLastReadBufferCopy = NULL;
    mStopWaitingForIdleCamera = false;
}

CameraSourceTimeLapse::~CameraSourceTimeLapse() {
}

void CameraSourceTimeLapse::startQuickReadReturns() {
    Mutex::Autolock autoLock(mQuickStopLock);
    LOGV("Enabling quick read returns");

    // Enable quick stop mode.
    mQuickStop = true;

    if (mUseStillCameraForTimeLapse) {
        // wake up the thread right away.
        mTakePictureCondition.signal();
    } else {
        // Force dataCallbackTimestamp() coming from the video camera to not skip the
        // next frame as we want read() to get a get a frame right away.
        mForceRead = true;
    }
}

bool CameraSourceTimeLapse::trySettingVideoSize(int32_t width, int32_t height) {
    LOGV("trySettingVideoSize: %dx%d", width, height);
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    String8 s = mCamera->getParameters();

    CameraParameters params(s);
    Vector<Size> supportedSizes;
    params.getSupportedVideoSizes(supportedSizes);
    bool videoOutputSupported = false;
    if (supportedSizes.size() == 0) {
        params.getSupportedPreviewSizes(supportedSizes);
    } else {
        videoOutputSupported = true;
    }

    bool videoSizeSupported = false;
    for (uint32_t i = 0; i < supportedSizes.size(); ++i) {
        int32_t pictureWidth = supportedSizes[i].width;
        int32_t pictureHeight = supportedSizes[i].height;

        if ((pictureWidth == width) && (pictureHeight == height)) {
            videoSizeSupported = true;
        }
    }

    bool isSuccessful = false;
    if (videoSizeSupported) {
        LOGV("Video size (%d, %d) is supported", width, height);
        if (videoOutputSupported) {
            params.setVideoSize(width, height);
        } else {
            params.setPreviewSize(width, height);
        }
        if (mCamera->setParameters(params.flatten()) == OK) {
            isSuccessful = true;
        } else {
            LOGE("Failed to set preview size to %dx%d", width, height);
            isSuccessful = false;
        }
    }

    IPCThreadState::self()->restoreCallingIdentity(token);
    return isSuccessful;
}

bool CameraSourceTimeLapse::setPictureSizeToClosestSupported(int32_t width, int32_t height) {
    LOGV("setPictureSizeToClosestSupported: %dx%d", width, height);
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

void CameraSourceTimeLapse::signalBufferReturned(MediaBuffer* buffer) {
    Mutex::Autolock autoLock(mQuickStopLock);
    if (mQuickStop && (buffer == mLastReadBufferCopy)) {
        buffer->setObserver(NULL);
        buffer->release();
    } else {
        return CameraSource::signalBufferReturned(buffer);
    }
}

void createMediaBufferCopy(const MediaBuffer& sourceBuffer, int64_t frameTime, MediaBuffer **newBuffer) {
    size_t sourceSize = sourceBuffer.size();
    void* sourcePointer = sourceBuffer.data();

    (*newBuffer) = new MediaBuffer(sourceSize);
    memcpy((*newBuffer)->data(), sourcePointer, sourceSize);

    (*newBuffer)->meta_data()->setInt64(kKeyTime, frameTime);
}

void CameraSourceTimeLapse::fillLastReadBufferCopy(MediaBuffer& sourceBuffer) {
    int64_t frameTime;
    CHECK(sourceBuffer.meta_data()->findInt64(kKeyTime, &frameTime));
    createMediaBufferCopy(sourceBuffer, frameTime, &mLastReadBufferCopy);
    mLastReadBufferCopy->add_ref();
    mLastReadBufferCopy->setObserver(this);
}

status_t CameraSourceTimeLapse::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    if (mLastReadBufferCopy == NULL) {
        mLastReadStatus = CameraSource::read(buffer, options);

        // mQuickStop may have turned to true while read was blocked. Make a copy of
        // the buffer in that case.
        Mutex::Autolock autoLock(mQuickStopLock);
        if (mQuickStop && *buffer) {
            fillLastReadBufferCopy(**buffer);
        }
        return mLastReadStatus;
    } else {
        (*buffer) = mLastReadBufferCopy;
        (*buffer)->add_ref();
        return mLastReadStatus;
    }
}

// static
void *CameraSourceTimeLapse::ThreadTimeLapseWrapper(void *me) {
    CameraSourceTimeLapse *source = static_cast<CameraSourceTimeLapse *>(me);
    source->threadTimeLapseEntry();
    return NULL;
}

void CameraSourceTimeLapse::threadTimeLapseEntry() {
    while (mStarted) {
        {
            Mutex::Autolock autoLock(mCameraIdleLock);
            if (!mCameraIdle) {
                mCameraIdleCondition.wait(mCameraIdleLock);
            }
            CHECK(mCameraIdle);
            mCameraIdle = false;
        }

        // Even if mQuickStop == true we need to take one more picture
        // as a read() may be blocked, waiting for a frame to get available.
        // After this takePicture, if mQuickStop == true, we can safely exit
        // this thread as read() will make a copy of this last frame and keep
        // returning it in the quick stop mode.
        Mutex::Autolock autoLock(mQuickStopLock);
        CHECK_EQ(OK, mCamera->takePicture(CAMERA_MSG_RAW_IMAGE));
        if (mQuickStop) {
            LOGV("threadTimeLapseEntry: Exiting due to mQuickStop = true");
            return;
        }
        mTakePictureCondition.waitRelative(mQuickStopLock,
                mTimeBetweenTimeLapseFrameCaptureUs * 1000);
    }
    LOGV("threadTimeLapseEntry: Exiting due to mStarted = false");
}

void CameraSourceTimeLapse::startCameraRecording() {
    if (mUseStillCameraForTimeLapse) {
        LOGV("start time lapse recording using still camera");

        int64_t token = IPCThreadState::self()->clearCallingIdentity();
        String8 s = mCamera->getParameters();

        CameraParameters params(s);
        params.setPictureSize(mPictureWidth, mPictureHeight);
        mCamera->setParameters(params.flatten());
        mCameraIdle = true;
        mStopWaitingForIdleCamera = false;

        // disable shutter sound and play the recording sound.
        mCamera->sendCommand(CAMERA_CMD_ENABLE_SHUTTER_SOUND, 0, 0);
        mCamera->sendCommand(CAMERA_CMD_PLAY_RECORDING_SOUND, 0, 0);
        IPCThreadState::self()->restoreCallingIdentity(token);

        // create a thread which takes pictures in a loop
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

        pthread_create(&mThreadTimeLapse, &attr, ThreadTimeLapseWrapper, this);
        pthread_attr_destroy(&attr);
    } else {
        LOGV("start time lapse recording using video camera");
        CameraSource::startCameraRecording();
    }
}

void CameraSourceTimeLapse::stopCameraRecording() {
    if (mUseStillCameraForTimeLapse) {
        void *dummy;
        pthread_join(mThreadTimeLapse, &dummy);

        // Last takePicture may still be underway. Wait for the camera to get
        // idle.
        Mutex::Autolock autoLock(mCameraIdleLock);
        mStopWaitingForIdleCamera = true;
        if (!mCameraIdle) {
            mCameraIdleCondition.wait(mCameraIdleLock);
        }
        CHECK(mCameraIdle);
        mCamera->setListener(NULL);

        // play the recording sound.
        mCamera->sendCommand(CAMERA_CMD_PLAY_RECORDING_SOUND, 0, 0);
    } else {
        CameraSource::stopCameraRecording();
    }
    if (mLastReadBufferCopy) {
        mLastReadBufferCopy->release();
        mLastReadBufferCopy = NULL;
    }
}

void CameraSourceTimeLapse::releaseRecordingFrame(const sp<IMemory>& frame) {
    if (!mUseStillCameraForTimeLapse) {
        CameraSource::releaseRecordingFrame(frame);
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
    Mutex::Autolock autoLock(mCameraIdleLock);
    mCameraIdle = true;
    mCameraIdleCondition.signal();
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
    } else {
        CHECK_EQ(srcFormat, OMX_COLOR_FormatYUV420Planar);
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
            return false;
        }

        {
            Mutex::Autolock autoLock(mQuickStopLock);

            // mForceRead may be set to true by startQuickReadReturns(). In that
            // case don't skip this frame.
            if (mForceRead) {
                LOGV("dataCallbackTimestamp timelapse: forced read");
                mForceRead = false;
                *timestampUs =
                    mLastFrameTimestampUs + mTimeBetweenTimeLapseVideoFramesUs;
                return false;
            }
        }

        // Workaround to bypass the first 2 input frames for skipping.
        // The first 2 output frames from the encoder are: decoder specific info and
        // the compressed video frame data for the first input video frame.
        if (mNumFramesEncoded >= 1 && *timestampUs <
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
            return false;
        }
    }
    return false;
}

void CameraSourceTimeLapse::dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data) {
    if (!mUseStillCameraForTimeLapse) {
        mSkipCurrentFrame = skipFrameAndModifyTimeStamp(&timestampUs);
    } else {
        Mutex::Autolock autoLock(mCameraIdleLock);
        // If we are using the still camera and stop() has been called, it may
        // be waiting for the camera to get idle. In that case return
        // immediately. Calling CameraSource::dataCallbackTimestamp() will lead
        // to a deadlock since it tries to access CameraSource::mLock which in
        // this case is held by CameraSource::stop() currently waiting for the
        // camera to get idle. And camera will not get idle until this call
        // returns.
        if (mStopWaitingForIdleCamera) {
            return;
        }
    }
    CameraSource::dataCallbackTimestamp(timestampUs, msgType, data);
}

}  // namespace android
