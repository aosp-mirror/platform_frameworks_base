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

#ifndef CAMERA_SOURCE_H_

#define CAMERA_SOURCE_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <utils/List.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class ICamera;
class IMemory;
class Camera;

class CameraSource : public MediaSource, public MediaBufferObserver {
public:
    static CameraSource *Create();
    static CameraSource *CreateFromCamera(const sp<Camera> &camera);

    virtual ~CameraSource();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    virtual void signalBufferReturned(MediaBuffer* buffer);

private:
    friend class CameraSourceListener;

    sp<Camera> mCamera;
    sp<MetaData> mMeta;

    Mutex mLock;
    Condition mFrameAvailableCondition;
    Condition mFrameCompleteCondition;
    List<sp<IMemory> > mFramesReceived;
    List<sp<IMemory> > mFramesBeingEncoded;
    List<int64_t> mFrameTimes;

    int64_t mFirstFrameTimeUs;
    int64_t mLastFrameTimestampUs;
    int32_t mNumFramesReceived;
    int32_t mNumFramesEncoded;
    int32_t mNumFramesDropped;
    bool mCollectStats;
    bool mStarted;

    CameraSource(const sp<Camera> &camera);

    void dataCallbackTimestamp(
            int64_t timestampUs, int32_t msgType, const sp<IMemory> &data);

    void releaseQueuedFrames();

    CameraSource(const CameraSource &);
    CameraSource &operator=(const CameraSource &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_H_
