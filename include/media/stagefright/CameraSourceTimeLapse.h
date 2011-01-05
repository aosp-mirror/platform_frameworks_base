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
    // If true, will use still camera takePicture() for time lapse frames
    // If false, will use the videocamera frames instead.
    bool mUseStillCameraForTimeLapse;

    // Size of picture taken from still camera. This may be larger than the size
    // of the video, as still camera may not support the exact video resolution
    // demanded. See setPictureSizeToClosestSupported().
    int32_t mPictureWidth;
    int32_t mPictureHeight;

    // size of the encoded video.
    int32_t mVideoWidth;
    int32_t mVideoHeight;

    // True if we need to crop the still camera image to get the video frame.
    bool mNeedCropping;

    // Start location of the cropping rectangle.
    int32_t mCropRectStartX;
    int32_t mCropRectStartY;

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

    // Condition variable to wake up still picture thread.
    Condition mTakePictureCondition;

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

    // Convenience function to fill mLastReadBufferCopy from the just read
    // buffer.
    void fillLastReadBufferCopy(MediaBuffer& sourceBuffer);

    // If the passed in size (width x height) is a supported video/preview size,
    // the function sets the camera's video/preview size to it and returns true.
    // Otherwise returns false.
    bool trySettingVideoSize(int32_t width, int32_t height);

    // The still camera may not support the demanded video width and height.
    // We look for the supported picture sizes from the still camera and
    // choose the smallest one with either dimensions higher than the corresponding
    // video dimensions. The still picture will be cropped to get the video frame.
    // The function returns true if the camera supports picture sizes greater than
    // or equal to the passed in width and height, and false otherwise.
    bool setPictureSizeToClosestSupported(int32_t width, int32_t height);

    // Computes the offset of the rectangle from where to start cropping the
    // still image into the video frame. We choose the center of the image to be
    // cropped. The offset is stored in (mCropRectStartX, mCropRectStartY).
    bool computeCropRectangleOffset();

    // Crops the source data into a smaller image starting at
    // (mCropRectStartX, mCropRectStartY) and of the size of the video frame.
    // The data is returned into a newly allocated IMemory.
    sp<IMemory> cropYUVImage(const sp<IMemory> &source_data);

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

    // Wrapper to enter threadStartPreview()
    static void *ThreadStartPreviewWrapper(void *me);

    // Starts the camera's preview.
    void threadStartPreview();

    // Starts thread ThreadStartPreviewWrapper() for restarting preview.
    // Needs to be done in a thread so that dataCallback() which calls this function
    // can return, and the camera can know that takePicture() is done.
    void restartPreview();

    // Creates a copy of source_data into a new memory of final type MemoryBase.
    sp<IMemory> createIMemoryCopy(const sp<IMemory> &source_data);

    CameraSourceTimeLapse(const CameraSourceTimeLapse &);
    CameraSourceTimeLapse &operator=(const CameraSourceTimeLapse &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_TIME_LAPSE_H_
