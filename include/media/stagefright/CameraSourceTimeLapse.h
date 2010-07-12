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
class ISurface;
class Camera;

class CameraSourceTimeLapse : public CameraSource {
public:
    static CameraSourceTimeLapse *Create(bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate);

    static CameraSourceTimeLapse *CreateFromCamera(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate);

    virtual ~CameraSourceTimeLapse();

private:
    // If true, will use still camera takePicture() for time lapse frames
    // If false, will use the videocamera frames instead.
    bool mUseStillCameraForTimeLapse;

    // Time between capture of two frames during time lapse recording
    // Negative value indicates that timelapse is disabled.
    int64_t mTimeBetweenTimeLapseFrameCaptureUs;

    // Time between two frames in final video (1/frameRate)
    int64_t mTimeBetweenTimeLapseVideoFramesUs;

    // Real timestamp of the last encoded time lapse frame
    int64_t mLastTimeLapseFrameRealTimestampUs;

    // Thread id of thread which takes still picture and sleeps in a loop.
    pthread_t mThreadTimeLapse;

    // Variable set in dataCallbackTimestamp() to help skipCurrentFrame()
    // to know if current frame needs to be skipped.
    bool mSkipCurrentFrame;

    CameraSourceTimeLapse(const sp<Camera> &camera,
        bool useStillCameraForTimeLapse,
        int64_t timeBetweenTimeLapseFrameCaptureUs,
        int32_t videoFrameRate);

    // For still camera case starts a thread which calls camera's takePicture()
    // in a loop. For video camera case, just starts the camera's video recording.
    virtual void startCameraRecording();

    // For still camera case joins the thread created in startCameraRecording().
    // For video camera case, just stops the camera's video recording.
    virtual void stopCameraRecording();

    // For still camera case don't need to do anything as memory is locally
    // allocated with refcounting.
    // For video camera case just tell the camera to release the frame.
    virtual void releaseRecordingFrame(const sp<IMemory>& frame);

    // mSkipCurrentFrame is set to true in dataCallbackTimestamp() if the current
    // frame needs to be skipped and this function just returns the value of mSkipCurrentFrame.
    virtual bool skipCurrentFrame(int64_t timestampUs);

    // Handles the callback to handle raw frame data from the still camera.
    // Creates a copy of the frame data as the camera can reuse the frame memory
    // once this callback returns. The function also sets a new timstamp corresponding
    // to one frame time ahead of the last encoded frame's time stamp. It then
    // calls dataCallbackTimestamp() of the base class with the copied data and the
    // modified timestamp, which will think that it recieved the frame from a video
    // camera and proceed as usual.
    virtual void dataCallback(int32_t msgType, const sp<IMemory> &data);

    // In the video camera case calls skipFrameAndModifyTimeStamp() to modify
    // timestamp and set mSkipCurrentFrame.
    // Then it calls the base CameraSource::dataCallbackTimestamp()
    virtual void dataCallbackTimestamp(int64_t timestampUs, int32_t msgType,
            const sp<IMemory> &data);

    // When video camera is used for time lapse capture, returns true
    // until enough time has passed for the next time lapse frame. When
    // the frame needs to be encoded, it returns false and also modifies
    // the time stamp to be one frame time ahead of the last encoded
    // frame's time stamp.
    bool skipFrameAndModifyTimeStamp(int64_t *timestampUs);

    // Wrapper to enter threadTimeLapseEntry()
    static void *ThreadTimeLapseWrapper(void *me);

    // Runs a loop which sleeps until a still picture is required
    // and then calls mCamera->takePicture() to take the still picture.
    // Used only in the case mUseStillCameraForTimeLapse = true.
    void threadTimeLapseEntry();

    // Creates a copy of source_data into a new memory of final type MemoryBase.
    sp<IMemory> createIMemoryCopy(const sp<IMemory> &source_data);

    CameraSourceTimeLapse(const CameraSourceTimeLapse &);
    CameraSourceTimeLapse &operator=(const CameraSourceTimeLapse &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_TIME_LAPSE_H_
