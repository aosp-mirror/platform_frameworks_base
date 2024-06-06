/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_GUI_SURFACE_H
#define ANDROID_GUI_SURFACE_H

#include <mutex>

#include <gui/IGraphicBufferProducer.h>
#include <ui/ANativeObjectBase.h>

#include <utils/Condition.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

#include <system/window.h>

namespace android {

class Surface : public ANativeObjectBase<ANativeWindow, Surface, RefBase> {
public:
    explicit Surface(const sp<IGraphicBufferProducer>& bufferProducer,
                     bool controlledByApp = false) : mBufferProducer(bufferProducer) {
        ANativeWindow::perform = hook_perform;
        ANativeWindow::dequeueBuffer = hook_dequeueBuffer;
        ANativeWindow::query = hook_query;
    }
    static bool isValid(const sp<Surface>& surface) { return surface != nullptr; }
    void allocateBuffers() {}

    uint64_t getNextFrameNumber() const { return 0; }

    int setScalingMode(int mode) { return 0; }

    virtual int disconnect(int api,
                           IGraphicBufferProducer::DisconnectMode mode =
                                   IGraphicBufferProducer::DisconnectMode::Api) {
        return 0;
    }

    virtual int lock(ANativeWindow_Buffer* outBuffer, ARect* inOutDirtyBounds) {
        // TODO: implement this
        return 0;
    }
    virtual int unlockAndPost() { return 0; }
    virtual int query(int what, int* value) const { return mBufferProducer->query(what, value); }

    status_t setDequeueTimeout(nsecs_t timeout) { return OK; }

    nsecs_t getLastDequeueStartTime() const { return 0; }

    int getBuffersDataSpace() {
         return 0;
    }

protected:
    virtual ~Surface() {}

    static int hook_perform(ANativeWindow* window, int operation, ...) {
        va_list args;
        va_start(args, operation);
        Surface* c = getSelf(window);
        int result = c->perform(operation, args);
        va_end(args);
        return result;
    }

    static int hook_query(const ANativeWindow* window, int what, int* value) {
        const Surface* c = getSelf(window);
        return c->query(what, value);
    }

    static int hook_dequeueBuffer(ANativeWindow* window,
            ANativeWindowBuffer** buffer, int* fenceFd) {
        Surface* c = getSelf(window);
        return c->dequeueBuffer(buffer, fenceFd);
    }

    virtual int dequeueBuffer(ANativeWindowBuffer** buffer, int* fenceFd) {
        mBufferProducer->requestBuffer(0, &mBuffer);
        *buffer = mBuffer.get();
        return OK;
    }
    virtual int cancelBuffer(ANativeWindowBuffer* buffer, int fenceFd) { return 0; }
    virtual int queueBuffer(ANativeWindowBuffer* buffer, int fenceFd) { return 0; }
    virtual int perform(int operation, va_list args) { return 0; }
    virtual int setSwapInterval(int interval) { return 0; }

    virtual int lockBuffer_DEPRECATED(ANativeWindowBuffer* buffer)  { return 0; }

    virtual int setBufferCount(int bufferCount) { return 0; }
private:
    // can't be copied
    Surface& operator=(const Surface& rhs);
    Surface(const Surface& rhs);

    const sp<IGraphicBufferProducer> mBufferProducer;
    sp<GraphicBuffer> mBuffer;
};

} // namespace android

#endif  // ANDROID_GUI_SURFACE_H
