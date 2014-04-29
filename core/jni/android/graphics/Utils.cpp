/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "Utils.h"
#include "SkUtils.h"

using namespace android;

AssetStreamAdaptor::AssetStreamAdaptor(Asset* asset, OwnAsset ownAsset,
                                       HasMemoryBase hasMemoryBase)
    : fAsset(asset)
    , fMemoryBase(kYes_HasMemoryBase == hasMemoryBase ?
                  asset->getBuffer(false) : NULL)
    , fOwnAsset(ownAsset)
{
}

AssetStreamAdaptor::~AssetStreamAdaptor() {
    if (kYes_OwnAsset == fOwnAsset) {
        delete fAsset;
    }
}

bool AssetStreamAdaptor::rewind() {
    off64_t pos = fAsset->seek(0, SEEK_SET);
    if (pos == (off64_t)-1) {
        SkDebugf("----- fAsset->seek(rewind) failed\n");
        return false;
    }
    return true;
}

size_t AssetStreamAdaptor::getLength() const {
    return fAsset->getLength();
}

bool AssetStreamAdaptor::isAtEnd() const {
    return fAsset->getRemainingLength() == 0;
}

SkStreamRewindable* AssetStreamAdaptor::duplicate() const {
    SkASSERT(false);
    // Cannot create a duplicate, since each AssetStreamAdaptor
    // would be modifying the Asset.
    //return new AssetStreamAdaptor(fAsset);
    return NULL;
}

size_t AssetStreamAdaptor::read(void* buffer, size_t size) {
    ssize_t amount;

    if (NULL == buffer) {
        if (0 == size) {
            return 0;
        }
        // asset->seek returns new total offset
        // we want to return amount that was skipped

        off64_t oldOffset = fAsset->seek(0, SEEK_CUR);
        if (-1 == oldOffset) {
            SkDebugf("---- fAsset->seek(oldOffset) failed\n");
            return 0;
        }
        off64_t newOffset = fAsset->seek(size, SEEK_CUR);
        if (-1 == newOffset) {
            SkDebugf("---- fAsset->seek(%d) failed\n", size);
            return 0;
        }
        amount = newOffset - oldOffset;
    } else {
        amount = fAsset->read(buffer, size);
        if (amount <= 0) {
            SkDebugf("---- fAsset->read(%d) returned %d\n", size, amount);
        }
    }

    if (amount < 0) {
        amount = 0;
    }
    return amount;
}

SkMemoryStream* android::CopyAssetToStream(Asset* asset) {
    if (NULL == asset) {
        return NULL;
    }

    off64_t size = asset->seek(0, SEEK_SET);
    if ((off64_t)-1 == size) {
        SkDebugf("---- copyAsset: asset rewind failed\n");
        return NULL;
    }

    size = asset->getLength();
    if (size <= 0) {
        SkDebugf("---- copyAsset: asset->getLength() returned %d\n", size);
        return NULL;
    }

    SkMemoryStream* stream = new SkMemoryStream(size);
    void* data = const_cast<void*>(stream->getMemoryBase());
    off64_t len = asset->read(data, size);
    if (len != size) {
        SkDebugf("---- copyAsset: asset->read(%d) returned %d\n", size, len);
        delete stream;
        stream = NULL;
    }
    return stream;
}

jobject android::nullObjectReturn(const char msg[]) {
    if (msg) {
        SkDebugf("--- %s\n", msg);
    }
    return NULL;
}
