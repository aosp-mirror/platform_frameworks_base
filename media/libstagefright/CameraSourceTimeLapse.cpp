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

namespace android {

// static
CameraSourceTimeLapse *CameraSourceTimeLapse::Create(bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate) {
    sp<Camera> camera = Camera::connect(0);

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSourceTimeLapse(camera, useStillCameraForTimeLapse,
            timeBetweenTimeLapseFrameCaptureUs, videoFrameRate);
}

// static
CameraSourceTimeLapse *CameraSourceTimeLapse::CreateFromCamera(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate) {
    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSourceTimeLapse(camera, useStillCameraForTimeLapse,
            timeBetweenTimeLapseFrameCaptureUs, videoFrameRate);
}

CameraSourceTimeLapse::CameraSourceTimeLapse(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate)
    : CameraSource(camera),
      mUseStillCameraForTimeLapse(useStillCameraForTimeLapse),
      mTimeBetweenTimeLapseFrameCaptureUs(timeBetweenTimeLapseFrameCaptureUs),
      mTimeBetweenTimeLapseVideoFramesUs(1E6/videoFrameRate),
      mLastTimeLapseFrameRealTimestampUs(0),
      mSkipCurrentFrame(false) {

    LOGV("starting time lapse mode");
    if(mUseStillCameraForTimeLapse) {
        // Currently hardcoded the picture size. Will need to choose
        // automatically or pass in from the app.
        int32_t width, height;
        width = 1024;
        height = 768;
        mMeta->setInt32(kKeyWidth, width);
        mMeta->setInt32(kKeyHeight, height);
    }
}

CameraSourceTimeLapse::~CameraSourceTimeLapse() {
}

// static
void *CameraSourceTimeLapse::ThreadTimeLapseWrapper(void *me) {
    CameraSourceTimeLapse *source = static_cast<CameraSourceTimeLapse *>(me);
    source->threadTimeLapseEntry();
    return NULL;
}

void CameraSourceTimeLapse::threadTimeLapseEntry() {
    while(mStarted) {
        LOGV("threadTimeLapseEntry loop");
        sleep(mTimeBetweenTimeLapseFrameCaptureUs/1E6);
        CHECK_EQ(OK, mCamera->takePicture());
    }
}

void CameraSourceTimeLapse::startCameraRecording() {
    if(mUseStillCameraForTimeLapse) {
        LOGV("start time lapse recording using still camera");

        int32_t width;
        int32_t height;
        mMeta->findInt32(kKeyWidth, &width);
        mMeta->findInt32(kKeyHeight, &height);

        int64_t token = IPCThreadState::self()->clearCallingIdentity();
        String8 s = mCamera->getParameters();
        IPCThreadState::self()->restoreCallingIdentity(token);

        CameraParameters params(s);

        params.setPictureSize(width, height);
        mCamera->setParameters(params.flatten());

        CHECK_EQ(OK, mCamera->takePicture());

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
    if(mUseStillCameraForTimeLapse) {
        void *dummy;
        pthread_join(mThreadTimeLapse, &dummy);
    } else {
        mCamera->stopRecording();
    }
}

void CameraSourceTimeLapse::releaseRecordingFrame(const sp<IMemory>& frame) {
    if(!mUseStillCameraForTimeLapse) {
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

void CameraSourceTimeLapse::dataCallback(int32_t msgType, const sp<IMemory> &data) {
    if(msgType != CAMERA_MSG_RAW_IMAGE) {
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
    sp<IMemory> dataCopy = createIMemoryCopy(data);
    dataCallbackTimestamp(timestampUs, msgType, dataCopy);
}

bool CameraSourceTimeLapse::skipCurrentFrame(int64_t timestampUs) {
    if(mSkipCurrentFrame) {
        mSkipCurrentFrame = false;
        return true;
    } else {
        return false;
    }
}

bool CameraSourceTimeLapse::skipFrameAndModifyTimeStamp(int64_t *timestampUs) {
    if(!mUseStillCameraForTimeLapse) {
        if(mLastTimeLapseFrameRealTimestampUs == 0) {
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
    if(!mUseStillCameraForTimeLapse) {
        mSkipCurrentFrame = skipFrameAndModifyTimeStamp(&timestampUs);
    }
    CameraSource::dataCallbackTimestamp(timestampUs, msgType, data);
}

}  // namespace android
