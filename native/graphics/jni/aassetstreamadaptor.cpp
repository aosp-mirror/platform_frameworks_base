/*
 * Copyright 2019 The Android Open Source Project
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

#include "aassetstreamadaptor.h"

#include <log/log.h>

AAssetStreamAdaptor::AAssetStreamAdaptor(AAsset* asset)
    : mAAsset(asset)
{
}

bool AAssetStreamAdaptor::rewind() {
    off64_t pos = AAsset_seek64(mAAsset, 0, SEEK_SET);
    if (pos == (off64_t)-1) {
        ALOGE("rewind failed!");
        return false;
    }
    return true;
}

size_t AAssetStreamAdaptor::getLength() const {
    return AAsset_getLength64(mAAsset);
}

bool AAssetStreamAdaptor::isAtEnd() const {
    return AAsset_getRemainingLength64(mAAsset) == 0;
}

SkStreamRewindable* AAssetStreamAdaptor::onDuplicate() const {
    // Cannot sensibly create a duplicate, since each AAssetStreamAdaptor
    // would be modifying the same AAsset.
    //return new AAssetStreamAdaptor(mAAsset);
    return nullptr;
}

bool AAssetStreamAdaptor::hasPosition() const {
    return AAsset_seek64(mAAsset, 0, SEEK_CUR) != -1;
}

size_t AAssetStreamAdaptor::getPosition() const {
    const off64_t offset = AAsset_seek64(mAAsset, 0, SEEK_CUR);
    if (offset == -1) {
        ALOGE("getPosition failed!");
        return 0;
    }

    return offset;
}

bool AAssetStreamAdaptor::seek(size_t position) {
    if (AAsset_seek64(mAAsset, position, SEEK_SET) == -1) {
        ALOGE("seek failed!");
        return false;
    }

    return true;
}

bool AAssetStreamAdaptor::move(long offset) {
    if (AAsset_seek64(mAAsset, offset, SEEK_CUR) == -1) {
        ALOGE("move failed!");
        return false;
    }

    return true;
}

size_t AAssetStreamAdaptor::read(void* buffer, size_t size) {
    ssize_t amount;

    if (!buffer) {
        if (!size) {
            return 0;
        }

        // asset->seek returns new total offset
        // we want to return amount that was skipped
        const off64_t oldOffset = AAsset_seek64(mAAsset, 0, SEEK_CUR);
        if (oldOffset == -1) {
            ALOGE("seek(oldOffset) failed!");
            return 0;
        }

        const off64_t newOffset = AAsset_seek64(mAAsset, size, SEEK_CUR);
        if (-1 == newOffset) {
            ALOGE("seek(%zu) failed!", size);
            return 0;
        }
        amount = newOffset - oldOffset;
    } else {
        amount = AAsset_read(mAAsset, buffer, size);
    }

    if (amount < 0) {
        amount = 0;
    }
    return amount;
}

const void* AAssetStreamAdaptor::getMemoryBase() {
    return AAsset_getBuffer(mAAsset);
}

