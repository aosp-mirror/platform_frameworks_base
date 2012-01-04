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
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <utils/String8.h>
#include <utils/Vector.h>

namespace android {

// static
CameraSourceTimeLapse *CameraSourceTimeLapse::CreateFromCamera(
        const sp<ICamera> &camera,
        const sp<ICameraRecordingProxy> &proxy,
        int32_t cameraId,
        Size videoSize,
        int32_t videoFrameRate,
        const sp<Surface>& surface,
        int64_t timeBetweenFrameCaptureUs) {

    CameraSourceTimeLapse *source = new
            CameraSourceTimeLapse(camera, proxy, cameraId,
                videoSize, videoFrameRate, surface,
                timeBetweenFrameCaptureUs);

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
        int64_t timeBetweenFrameCaptureUs)
    : CameraSource(camera, proxy, cameraId, videoSize, videoFrameRate, surface, true),
      mTimeBetweenTimeLapseVideoFramesUs(1E6/videoFrameRate),
      mLastTimeLapseFrameRealTimestampUs(0),
      mSkipCurrentFrame(false) {

    mTimeBetweenFrameCaptureUs = timeBetweenFrameCaptureUs;
    ALOGD("starting time lapse mode: %lld us",
        mTimeBetweenFrameCaptureUs);

    mVideoWidth = videoSize.width;
    mVideoHeight = videoSize.height;

    if (!trySettingVideoSize(videoSize.width, videoSize.height)) {
        mInitCheck = NO_INIT;
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
    ALOGV("startQuickReadReturns");
    Mutex::Autolock autoLock(mQuickStopLock);

    // Enable quick stop mode.
    mQuickStop = true;

    // Force dataCallbackTimestamp() coming from the video camera to
    // not skip the next frame as we want read() to get a get a frame
    // right away.
    mForceRead = true;
}

bool CameraSourceTimeLapse::trySettingVideoSize(
        int32_t width, int32_t height) {

    ALOGV("trySettingVideoSize");
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
        ALOGV("Video size (%d, %d) is supported", width, height);
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

void CameraSourceTimeLapse::signalBufferReturned(MediaBuffer* buffer) {
    ALOGV("signalBufferReturned");
    Mutex::Autolock autoLock(mQuickStopLock);
    if (mQuickStop && (buffer == mLastReadBufferCopy)) {
        buffer->setObserver(NULL);
        buffer->release();
    } else {
        return CameraSource::signalBufferReturned(buffer);
    }
}

void createMediaBufferCopy(
        const MediaBuffer& sourceBuffer,
        int64_t frameTime,
        MediaBuffer **newBuffer) {

    ALOGV("createMediaBufferCopy");
    size_t sourceSize = sourceBuffer.size();
    void* sourcePointer = sourceBuffer.data();

    (*newBuffer) = new MediaBuffer(sourceSize);
    memcpy((*newBuffer)->data(), sourcePointer, sourceSize);

    (*newBuffer)->meta_data()->setInt64(kKeyTime, frameTime);
}

void CameraSourceTimeLapse::fillLastReadBufferCopy(MediaBuffer& sourceBuffer) {
    ALOGV("fillLastReadBufferCopy");
    int64_t frameTime;
    CHECK(sourceBuffer.meta_data()->findInt64(kKeyTime, &frameTime));
    createMediaBufferCopy(sourceBuffer, frameTime, &mLastReadBufferCopy);
    mLastReadBufferCopy->add_ref();
    mLastReadBufferCopy->setObserver(this);
}

status_t CameraSourceTimeLapse::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    ALOGV("read");
    if (mLastReadBufferCopy == NULL) {
        mLastReadStatus = CameraSource::read(buffer, options);

        // mQuickStop may have turned to true while read was blocked.
        // Make a copy of the buffer in that case.
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

void CameraSourceTimeLapse::stopCameraRecording() {
    ALOGV("stopCameraRecording");
    CameraSource::stopCameraRecording();
    if (mLastReadBufferCopy) {
        mLastReadBufferCopy->release();
        mLastReadBufferCopy = NULL;
    }
}

sp<IMemory> CameraSourceTimeLapse::createIMemoryCopy(
        const sp<IMemory> &source_data) {

    ALOGV("createIMemoryCopy");
    size_t source_size = source_data->size();
    void* source_pointer = source_data->pointer();

    sp<MemoryHeapBase> newMemoryHeap = new MemoryHeapBase(source_size);
    sp<MemoryBase> newMemory = new MemoryBase(newMemoryHeap, 0, source_size);
    memcpy(newMemory->pointer(), source_pointer, source_size);
    return newMemory;
}

bool CameraSourceTimeLapse::skipCurrentFrame(int64_t timestampUs) {
    ALOGV("skipCurrentFrame");
    if (mSkipCurrentFrame) {
        mSkipCurrentFrame = false;
        return true;
    } else {
        return false;
    }
}

bool CameraSourceTimeLapse::skipFrameAndModifyTimeStamp(int64_t *timestampUs) {
    ALOGV("skipFrameAndModifyTimeStamp");
    if (mLastTimeLapseFrameRealTimestampUs == 0) {
        // First time lapse frame. Initialize mLastTimeLapseFrameRealTimestampUs
        // to current time (timestampUs) and save frame data.
        ALOGV("dataCallbackTimestamp timelapse: initial frame");

        mLastTimeLapseFrameRealTimestampUs = *timestampUs;
        return false;
    }

    {
        Mutex::Autolock autoLock(mQuickStopLock);

        // mForceRead may be set to true by startQuickReadReturns(). In that
        // case don't skip this frame.
        if (mForceRead) {
            ALOGV("dataCallbackTimestamp timelapse: forced read");
            mForceRead = false;
            *timestampUs =
                mLastFrameTimestampUs + mTimeBetweenTimeLapseVideoFramesUs;

            // Really make sure that this video recording frame will not be dropped.
            if (*timestampUs < mStartTimeUs) {
                ALOGI("set timestampUs to start time stamp %lld us", mStartTimeUs);
                *timestampUs = mStartTimeUs;
            }
            return false;
        }
    }

    // Workaround to bypass the first 2 input frames for skipping.
    // The first 2 output frames from the encoder are: decoder specific info and
    // the compressed video frame data for the first input video frame.
    if (mNumFramesEncoded >= 1 && *timestampUs <
        (mLastTimeLapseFrameRealTimestampUs + mTimeBetweenFrameCaptureUs)) {
        // Skip all frames from last encoded frame until
        // sufficient time (mTimeBetweenFrameCaptureUs) has passed.
        // Tell the camera to release its recording frame and return.
        ALOGV("dataCallbackTimestamp timelapse: skipping intermediate frame");
        return true;
    } else {
        // Desired frame has arrived after mTimeBetweenFrameCaptureUs time:
        // - Reset mLastTimeLapseFrameRealTimestampUs to current time.
        // - Artificially modify timestampUs to be one frame time (1/framerate) ahead
        // of the last encoded frame's time stamp.
        ALOGV("dataCallbackTimestamp timelapse: got timelapse frame");

        mLastTimeLapseFrameRealTimestampUs = *timestampUs;
        *timestampUs = mLastFrameTimestampUs + mTimeBetweenTimeLapseVideoFramesUs;
        return false;
    }
    return false;
}

void CameraSourceTimeLapse::dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data) {
    ALOGV("dataCallbackTimestamp");
    mSkipCurrentFrame = skipFrameAndModifyTimeStamp(&timestampUs);
    CameraSource::dataCallbackTimestamp(timestampUs, msgType, data);
}

}  // namespace android
