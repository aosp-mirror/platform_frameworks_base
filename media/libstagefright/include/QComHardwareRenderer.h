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

#ifndef QCOM_HARDWARE_RENDERER_H_

#define QCOM_HARDWARE_RENDERER_H_

#include <media/stagefright/VideoRenderer.h>
#include <utils/RefBase.h>

namespace android {

class ISurface;
class MemoryHeapPmem;

class QComHardwareRenderer : public VideoRenderer {
public:
    QComHardwareRenderer(
            const sp<ISurface> &surface,
            size_t displayWidth, size_t displayHeight,
            size_t decodedWidth, size_t decodedHeight);

    virtual ~QComHardwareRenderer();

    virtual void render(
            const void *data, size_t size, void *platformPrivate);

private:
    sp<ISurface> mISurface;
    size_t mDisplayWidth, mDisplayHeight;
    size_t mDecodedWidth, mDecodedHeight;
    size_t mFrameSize;
    sp<MemoryHeapPmem> mMemoryHeap;

    bool getOffset(void *platformPrivate, size_t *offset);
    void publishBuffers(uint32_t pmem_fd);

    QComHardwareRenderer(const QComHardwareRenderer &);
    QComHardwareRenderer &operator=(const QComHardwareRenderer &);
};

}  // namespace android

#endif  // QCOM_HARDWARE_RENDERER_H_
