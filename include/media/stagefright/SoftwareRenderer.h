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

#ifndef SOFTWARE_RENDERER_H_

#define SOFTWARE_RENDERER_H_

#include <OMX_Video.h>
#include <media/stagefright/VideoRenderer.h>
#include <utils/RefBase.h>

namespace android {

class ISurface;
class MemoryHeapBase;

class SoftwareRenderer : public VideoRenderer {
public:
    SoftwareRenderer(
            OMX_COLOR_FORMATTYPE colorFormat,
            const sp<ISurface> &surface,
            size_t displayWidth, size_t displayHeight,
            size_t decodedWidth, size_t decodedHeight);

    virtual ~SoftwareRenderer();

    virtual void render(
            const void *data, size_t size, void *platformPrivate);

private:
    uint8_t *initClip();

    void renderCbYCrY(const void *data, size_t size);
    void renderYUV420Planar(const void *data, size_t size);
    void renderQCOMYUV420SemiPlanar(const void *data, size_t size);

    OMX_COLOR_FORMATTYPE mColorFormat;
    sp<ISurface> mISurface;
    size_t mDisplayWidth, mDisplayHeight;
    size_t mDecodedWidth, mDecodedHeight;
    size_t mFrameSize;
    sp<MemoryHeapBase> mMemoryHeap;
    int mIndex;

    uint8_t *mClip;

    SoftwareRenderer(const SoftwareRenderer &);
    SoftwareRenderer &operator=(const SoftwareRenderer &);
};

}  // namespace android

#endif  // SOFTWARE_RENDERER_H_
