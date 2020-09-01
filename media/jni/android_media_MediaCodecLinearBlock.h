/*
 * Copyright 2020 The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_MEDIACODECLINEARBLOCK_H_
#define _ANDROID_MEDIA_MEDIACODECLINEARBLOCK_H_

#include <C2Buffer.h>
#include <binder/MemoryHeapBase.h>
#include <hidl/HidlSupport.h>
#include <media/MediaCodecBuffer.h>

namespace android {

struct JMediaCodecLinearBlock {
    std::shared_ptr<C2Buffer> mBuffer;
    std::shared_ptr<C2ReadView> mReadonlyMapping;

    std::shared_ptr<C2LinearBlock> mBlock;
    std::shared_ptr<C2WriteView> mReadWriteMapping;

    sp<IMemory> mMemory;
    sp<hardware::HidlMemory> mHidlMemory;
    ssize_t mHidlMemoryOffset;
    size_t mHidlMemorySize;

    sp<MediaCodecBuffer> mLegacyBuffer;

    std::once_flag mCopyWarningFlag;

    std::shared_ptr<C2Buffer> toC2Buffer(size_t offset, size_t size) {
        if (mBuffer) {
            if (mBuffer->data().type() != C2BufferData::LINEAR) {
                return nullptr;
            }
            C2ConstLinearBlock block = mBuffer->data().linearBlocks().front();
            if (offset == 0 && size == block.capacity()) {
                return mBuffer;
            }
            return C2Buffer::CreateLinearBuffer(block.subBlock(offset, size));
        }
        if (mBlock) {
            return C2Buffer::CreateLinearBuffer(mBlock->share(offset, size, C2Fence{}));
        }
        return nullptr;
    }

    sp<hardware::HidlMemory> toHidlMemory() {
        if (mHidlMemory) {
            return mHidlMemory;
        }
        return nullptr;
    }
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIACODECLINEARBLOCK_H_
