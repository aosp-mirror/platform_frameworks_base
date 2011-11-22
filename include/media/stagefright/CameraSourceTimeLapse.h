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

#ifndef CAMERA_SOURCE_TIME_LAPSE_H_

#define CAMERA_SOURCE_TIME_LAPSE_H_

#include <pthread.h>

#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class ICamera;
class IMemory;
class Camera;

class CameraSourceTimeLapse : public CameraSource {
public:
    static CameraSourceTimeLapse *CreateFromCamera(
        const sp<ICamera> &camera,
        const sp<ICameraRecordingProxy> &proxy,
        int32_t cameraId,
        Size videoSize,
        int32_t videoFrameRate,
        const sp<Surface>& surface,
        int64_t timeBetweenTimeLapseFrameCaptureUs);

    virtual ~CameraSourceTimeLapse();

    // If the frame capture interval is large, read will block for a long time.
    // Due to the way the mediaRecorder framework works, a stop() call from
    // mediaRecorder waits until the read returns, causing a long wait for
    // stop() to return. To avoid this, we can make read() return a copy of the
    // last read frame with the same time stamp frequently. This keeps the
    // read() call from blocking too long. Calling this function quickly
    // captures another frame, keeps its copy, and enables this mode of read()
    // returning quickly.
    void startQuickReadReturns();

private:
    // size of the encoded video.
    int32_t mVideoWidth;
    int32_t mVideoHeight;

    // Time between two frames in final video (1/frameRate)
    int64_t mTimeBetweenTimeLapseVideoFramesUs;

    // Real timestamp of the last encoded time lapse frame
    int64_t mLastTimeLapseFrameRealTimestampUs;

    // Variable set in dataCallbackTimestamp() to help skipCurrentFrame()
    // to know if current frame needs to be skipped.
    bool mSkipCurrentFrame;

    // Lock for accessing mCameraIdle
    Mutex mCameraIdleLock;

    // Condition variable to wait on if camera is is not yet idle. Once the
    // camera gets idle, this variable will be signalled.
    Condition mCameraIdleCondition;

    // True if camera is in preview mode and ready for takePicture().
    // False after a call to takePicture() but before the final compressed
    // data callback has been called and preview has been restarted.
    volatile bool mCameraIdle;

    // True if stop() is waiting for camera to get idle, i.e. for the last
    // takePicture() to complete. This is needed so that dataCallbackTimestamp()
    // can return immediately.
    volatile bool mStopWaitingForIdleCamera;

    // Lock for accessing quick stop variables.
    Mutex mQuickStopLock;

    // mQuickStop is set to true if we use quick read() returns, otherwise it is set
    // to false. Once in this mode read() return a copy of the last read frame
    // with the same time stamp. See startQuickReadReturns().
    volatile bool mQuickStop;

    // Forces the next frame passed to dataCallbackTimestamp() to be read
    // as a time lapse frame. Used by startQuickReadReturns() so that the next
    // frame wakes up any blocking read.
    volatile bool mForceRead;

    // Stores a copy of the MediaBuffer read in the last read() call after
    // mQuickStop was true.
    MediaBuffer* mLastReadBufferCopy;

    // Status code for last read.
    status_t mLastReadStatus;

    CameraSourceTimeLapse(
        const sp<ICamera> &camera,
        const sp<ICameraRecordingProxy> &proxy,
        int32_t cameraId,
        Size videoSize,
        int32_t videoFrameRate,
        const sp<Surface>& surface,
        int64_t timeBetweenTimeLapseFrameCaptureUs);

    // Wrapper over CameraSource::signalBufferReturned() to implement quick stop.
    // It only handles the case when mLastReadBufferCopy is signalled. Otherwise
    // it calls the base class' function.
    virtual void signalBufferReturned(MediaBuffer* buffer);

    // Wrapper over CameraSource::read() to implement quick stop.
    virtual status_t read(MediaBuffer **buffer, const ReadOptions *options = NULL);

    // For video camera case, just stops the camera's video recording.
    virtual void stopCameraRecording();

    // mSkipCurrentFrame is set to true in dataCallbackTimestamp() if the current
    // frame needs to be skipped and this function just returns the value of mSkipCurrentFrame.
    virtual bool skipCurrentFrame(int64_t timestampUs);

    // In the video camera case calls skipFrameAndModifyTimeStamp() to modify
    // timestamp and set mSkipCurrentFrame.
    // Then it calls the base CameraSource::dataCallbackTimestamp()
    virtual void dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data);

    // Convenience function to fill mLastReadBufferCopy from the just read
    // buffer.
    void fillLastReadBufferCopy(MediaBuffer& sourceBuffer);

    // If the passed in size (width x height) is a supported video/preview size,
    // the function sets the camera's video/preview size to it and returns true.
    // Otherwise returns false.
    bool trySettingVideoSize(int32_t width, int32_t height);

    // When video camera is used for time lapse capture, returns true
    // until enough time has passed for the next time lapse frame. When
    // the frame needs to be encoded, it returns false and also modifies
    // the time stamp to be one frame time ahead of the last encoded
    // frame's time stamp.
    bool skipFrameAndModifyTimeStamp(int64_t *timestampUs);

    // Wrapper to enter threadTimeLapseEntry()
    static void *ThreadTimeLapseWrapper(void *me);

    // Creates a copy of source_data into a new memory of final type MemoryBase.
    sp<IMemory> createIMemoryCopy(const sp<IMemory> &source_data);

    CameraSourceTimeLapse(const CameraSourceTimeLapse &);
    CameraSourceTimeLapse &operator=(const CameraSourceTimeLapse &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_TIME_LAPSE_H_
