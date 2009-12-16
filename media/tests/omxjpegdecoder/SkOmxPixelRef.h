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

#ifndef SKOMXPIXELREF_DEFINED
#define SKOMXPIXELREF_DEFINED

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <SkPixelRef.h>

namespace android {

class SkOmxPixelRef : public SkPixelRef {
public:
    SkOmxPixelRef(SkColorTable* ctable, MediaBuffer* buffer,
            sp<MediaSource> decoder);
    virtual ~SkOmxPixelRef();

     //! Return the allocation size for the pixels
    size_t getSize() const { return mSize; }

protected:
    // overrides from SkPixelRef
    virtual void* onLockPixels(SkColorTable**);
    virtual void onUnlockPixels();

private:
    MediaBuffer* mBuffer;
    sp<MediaSource> mDecoder;
    size_t          mSize;
    SkColorTable*   mCTable;

    typedef SkPixelRef INHERITED;
};

} // namespace android
#endif // SKOMXPIXELREF_DEFINED
