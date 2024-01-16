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

#include <gui/IGraphicBufferProducer.h>
#include <ui/ANativeObjectBase.h>
#include <utils/RefBase.h>
#include <system/window.h>

namespace android {

class Surface : public ANativeObjectBase<ANativeWindow, Surface, RefBase> {
public:
    explicit Surface(const sp<IGraphicBufferProducer>& bufferProducer,
                     bool controlledByApp = false) {
        ANativeWindow::perform = hook_perform;
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
    virtual int query(int what, int* value) const { return 0; }

    virtual void destroy() {}

protected:
    virtual ~Surface() {}

    static int hook_perform(ANativeWindow* window, int operation, ...) { return 0; }

private:
    // can't be copied
    Surface& operator=(const Surface& rhs);
    Surface(const Surface& rhs);
};

} // namespace android

#endif  // ANDROID_GUI_SURFACE_H
