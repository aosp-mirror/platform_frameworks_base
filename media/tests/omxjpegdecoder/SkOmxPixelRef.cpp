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

#include <media/stagefright/foundation/ADebug.h>
#include <SkBitmap.h>

#include "SkOmxPixelRef.h"

using namespace android;

SkOmxPixelRef::SkOmxPixelRef(SkColorTable* ctable, MediaBuffer* buffer,
        sp<MediaSource> decoder)  {
    mBuffer = buffer;
    mDecoder = decoder;
    mSize = buffer->size();
    mCTable = ctable;
    SkSafeRef(mCTable);
}

SkOmxPixelRef::~SkOmxPixelRef() {
    mBuffer->release();
    CHECK_EQ(mDecoder->stop(), (status_t)OK);
    SkSafeUnref(mCTable);
}

void* SkOmxPixelRef::onLockPixels(SkColorTable** ct) {
    *ct = mCTable;
    return mBuffer->data();
}

void SkOmxPixelRef::onUnlockPixels() {
    // nothing to do
}
