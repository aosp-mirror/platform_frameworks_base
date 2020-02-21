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

#ifndef RENDERTASK_H_
#define RENDERTASK_H_

#include <cutils/compiler.h>
#include <utils/Timers.h>

namespace android {
class Mutex;
class Condition;
namespace uirenderer {
namespace renderthread {

#define METHOD_INVOKE_PAYLOAD_SIZE (8 * sizeof(void*))

/*
 * Notes about memory management
 *
 * RenderThread will only invoke RenderTask::run(). It is the responsibility
 * of the RenderTask to know if it needs to suicide at the end of run() or
 * if some other lifecycle is being used. As such, it is not valid to reference
 * anything on RenderTask after the first call to run().
 *
 * For example SignalingRenderTask
 * is expected to be stack allocated by the calling thread, so it does not
 * suicide in run() but instead relies on the caller to destroy it.
 *
 * MethodInvokeRenderTask however is currently allocated with new, so it will
 * suicide at the end of run(). TODO: Replace this with a small pool to avoid
 * malloc/free churn of small objects?
 */

class RenderTask {
public:
    RenderTask() : mNext(nullptr), mRunAt(0) {}
    virtual ~RenderTask() {}

    virtual void run() = 0;

    RenderTask* mNext;
    nsecs_t mRunAt;  // nano-seconds on the SYSTEM_TIME_MONOTONIC clock
};

class SignalingRenderTask : public RenderTask {
public:
    // Takes ownership of task, caller owns lock and signal
    SignalingRenderTask(RenderTask* task, Mutex* lock, Condition* signal)
            : mTask(task), mLock(lock), mSignal(signal), mHasRun(false) {}
    virtual void run() override;
    bool hasRun() const { return mHasRun; }

private:
    RenderTask* mTask;
    Mutex* mLock;
    Condition* mSignal;
    bool mHasRun;
};

typedef void* (*RunnableMethod)(void* data);

class MethodInvokeRenderTask : public RenderTask {
public:
    explicit MethodInvokeRenderTask(RunnableMethod method) : mMethod(method), mReturnPtr(nullptr) {}

    void* payload() { return mData; }
    void setReturnPtr(void** retptr) { mReturnPtr = retptr; }

    virtual void run() override {
        void* retval = mMethod(mData);
        if (mReturnPtr) {
            *mReturnPtr = retval;
        }
        // Commit suicide
        delete this;
    }

private:
    RunnableMethod mMethod;
    char mData[METHOD_INVOKE_PAYLOAD_SIZE];
    void** mReturnPtr;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTASK_H_ */
