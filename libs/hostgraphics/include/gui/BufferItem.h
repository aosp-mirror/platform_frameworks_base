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

#ifndef ANDROID_GUI_BUFFERITEM_H
#define ANDROID_GUI_BUFFERITEM_H

#include <system/graphics.h>
#include <ui/Fence.h>
#include <ui/Rect.h>
#include <utils/StrongPointer.h>

namespace android {

class Fence;

class GraphicBuffer;

// The only thing we need here for layoutlib is mGraphicBuffer. The rest of the fields are added
// just to satisfy the calls from the android_media_ImageReader.h

class BufferItem {
public:
    enum { INVALID_BUFFER_SLOT = -1 };

    BufferItem() : mGraphicBuffer(nullptr), mFence(Fence::NO_FENCE) {}

    ~BufferItem() {}

    sp<GraphicBuffer> mGraphicBuffer;

    sp<Fence> mFence;

    Rect mCrop;

    uint32_t mTransform;

    uint32_t mScalingMode;

    int64_t mTimestamp;

    android_dataspace mDataSpace;

    uint64_t mFrameNumber;

    int mSlot;

    bool mTransformToDisplayInverse;
};

} // namespace android

#endif // ANDROID_GUI_BUFFERITEM_H
