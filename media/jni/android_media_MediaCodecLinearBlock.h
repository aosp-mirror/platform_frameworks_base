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
    std::vector<std::string> mCodecNames;

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

    std::shared_ptr<C2Buffer> toC2Buffer(size_t offset, size_t size) const {
        if (mBuffer) {
            // TODO: if returned C2Buffer is different from mBuffer, we should
            // find a way to connect the life cycle between this C2Buffer and
            // mBuffer.
            if (mBuffer->data().type() != C2BufferData::LINEAR) {
                return nullptr;
            }
            C2ConstLinearBlock block = mBuffer->data().linearBlocks().front();
            if (offset == 0 && size == block.capacity()) {
                // Let C2Buffer be new one to queue to MediaCodec. It will allow
                // the related input slot to be released by onWorkDone from C2
                // Component. Currently, the life cycle of mBuffer should be
                // protected by different flows.
                return std::make_shared<C2Buffer>(*mBuffer);
            }

            std::shared_ptr<C2Buffer> buffer =
                C2Buffer::CreateLinearBuffer(block.subBlock(offset, size));
            for (const std::shared_ptr<const C2Info> &info : mBuffer->info()) {
                std::shared_ptr<C2Param> param = C2Param::Copy(*info);
                buffer->setInfo(std::static_pointer_cast<C2Info>(param));
            }
            return buffer;
        }
        if (mBlock) {
            return C2Buffer::CreateLinearBuffer(mBlock->share(offset, size, C2Fence{}));
        }
        return nullptr;
    }

    sp<hardware::HidlMemory> toHidlMemory() const {
        if (mHidlMemory) {
            return mHidlMemory;
        }
        return nullptr;
    }

    size_t capacity() const {
        if (mBlock) {
            return mBlock->capacity();
        }
        if (mMemory) {
            return mMemory->size();
        }
        return 0;
    }
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIACODECLINEARBLOCK_H_
