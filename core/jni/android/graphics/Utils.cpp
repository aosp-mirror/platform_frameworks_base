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

bool AssetStreamAdaptor::rewind() {
    off64_t pos = fAsset->seek(0, SEEK_SET);
    if (pos == (off64_t)-1) {
        SkDebugf("----- fAsset->seek(rewind) failed\n");
        return false;
    }
    return true;
}

size_t AssetStreamAdaptor::read(void* buffer, size_t size) {
    ssize_t amount;

    if (NULL == buffer) {
        if (0 == size) {  // caller is asking us for our total length
            return fAsset->getLength();
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

jobject android::nullObjectReturn(const char msg[]) {
    if (msg) {
        SkDebugf("--- %s\n", msg);
    }
    return NULL;
}
