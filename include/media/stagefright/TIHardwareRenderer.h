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

#ifndef TI_HARDWARE_RENDERER_H_

#define TI_HARDWARE_RENDERER_H_

#include <media/stagefright/VideoRenderer.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>

namespace android {

class ISurface;
class Overlay;

class TIHardwareRenderer : public VideoRenderer {
public:
    TIHardwareRenderer(
            const sp<ISurface> &surface,
            size_t displayWidth, size_t displayHeight,
            size_t decodedWidth, size_t decodedHeight);

    virtual ~TIHardwareRenderer();

    virtual void render(
            const void *data, size_t size, void *platformPrivate);

private:
    sp<ISurface> mISurface;
    size_t mDisplayWidth, mDisplayHeight;
    size_t mDecodedWidth, mDecodedHeight;
    size_t mFrameSize;
    sp<Overlay> mOverlay;
    Vector<void *> mOverlayAddresses;
    bool mIsFirstFrame;
    size_t mIndex;

    TIHardwareRenderer(const TIHardwareRenderer &);
    TIHardwareRenderer &operator=(const TIHardwareRenderer &);
};

}  // namespace android

#endif  // TI_HARDWARE_RENDERER_H_

