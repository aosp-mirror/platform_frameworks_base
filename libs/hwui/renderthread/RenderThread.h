/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef RENDERTHREAD_H_
#define RENDERTHREAD_H_

#include "RenderTask.h"
#include <cutils/compiler.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/Singleton.h>
#include <utils/Thread.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class ANDROID_API RenderThread : public Thread, public Singleton<RenderThread> {
public:
    // RenderThread takes complete ownership of tasks that are queued
    // and will delete them after they are run
    ANDROID_API void queue(RenderTask* task);

protected:
    virtual bool threadLoop();

private:
    friend class Singleton<RenderThread>;

    RenderThread();
    virtual ~RenderThread();

    RenderTask* nextTask();

    sp<Looper> mLooper;
    Mutex mLock;

    RenderTask* mQueueHead;
    RenderTask* mQueueTail;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTHREAD_H_ */
