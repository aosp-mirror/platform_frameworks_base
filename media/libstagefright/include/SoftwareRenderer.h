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

#include <media/stagefright/ColorConverter.h>
#include <media/stagefright/VideoRenderer.h>
#include <utils/RefBase.h>

namespace android {

class Surface;
class MemoryHeapBase;

class SoftwareRenderer : public VideoRenderer {
public:
    SoftwareRenderer(
            OMX_COLOR_FORMATTYPE colorFormat,
            const sp<Surface> &surface,
            size_t displayWidth, size_t displayHeight,
            size_t decodedWidth, size_t decodedHeight);

    virtual ~SoftwareRenderer();

    virtual void render(
            const void *data, size_t size, void *platformPrivate);

private:
    enum YUVMode {
        None,
        YUV420ToYUV420sp,
        YUV420spToYUV420sp,
    };

    OMX_COLOR_FORMATTYPE mColorFormat;
    ColorConverter *mConverter;
    YUVMode mYUVMode;
    sp<Surface> mSurface;
    size_t mDisplayWidth, mDisplayHeight;
    size_t mDecodedWidth, mDecodedHeight;

    SoftwareRenderer(const SoftwareRenderer &);
    SoftwareRenderer &operator=(const SoftwareRenderer &);
};

}  // namespace android

#endif  // SOFTWARE_RENDERER_H_
