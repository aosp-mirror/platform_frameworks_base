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

class IMemory;
class ISurface;
class Camera;

class CameraSource : public MediaSource {
public:
    static CameraSource *Create();

    virtual ~CameraSource();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

private:
    friend class CameraSourceListener;

    sp<Camera> mCamera;

    Mutex mLock;
    Condition mFrameAvailableCondition;
    List<sp<IMemory> > mFrames;
    List<int64_t> mFrameTimes;

    int mWidth, mHeight;
    int64_t mFirstFrameTimeUs;
    int32_t mNumFrames;
    bool mStarted;

    CameraSource(const sp<Camera> &camera);

    void dataCallback(int32_t msgType, const sp<IMemory> &data);

    CameraSource(const CameraSource &);
    CameraSource &operator=(const CameraSource &);
};

}  // namespace android

#endif  // CAMERA_SOURCE_H_
