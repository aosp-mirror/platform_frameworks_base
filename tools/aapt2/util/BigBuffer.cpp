/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "util/BigBuffer.h"

#include <algorithm>
#include <memory>
#include <vector>

namespace aapt {

void* BigBuffer::nextBlockImpl(size_t size) {
    if (!mBlocks.empty()) {
        Block& block = mBlocks.back();
        if (block.mBlockSize - block.size >= size) {
            void* outBuffer = block.buffer.get() + block.size;
            block.size += size;
            mSize += size;
            return outBuffer;
        }
    }

    const size_t actualSize = std::max(mBlockSize, size);

    Block block = {};

    // Zero-allocate the block's buffer.
    block.buffer = std::unique_ptr<uint8_t[]>(new uint8_t[actualSize]());
    assert(block.buffer);

    block.size = size;
    block.mBlockSize = actualSize;

    mBlocks.push_back(std::move(block));
    mSize += size;
    return mBlocks.back().buffer.get();
}

} // namespace aapt
