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

#ifndef A_BUFFER_H_

#define A_BUFFER_H_

#include <sys/types.h>
#include <stdint.h>

#include <media/stagefright/foundation/ABase.h>
#include <utils/RefBase.h>

namespace android {

struct AMessage;

struct ABuffer : public RefBase {
    ABuffer(size_t capacity);
    ABuffer(void *data, size_t capacity);

    void setFarewellMessage(const sp<AMessage> msg);

    uint8_t *base() { return (uint8_t *)mData; }
    uint8_t *data() { return (uint8_t *)mData + mRangeOffset; }
    size_t capacity() const { return mCapacity; }
    size_t size() const { return mRangeLength; }
    size_t offset() const { return mRangeOffset; }

    void setRange(size_t offset, size_t size);

    void setInt32Data(int32_t data) { mInt32Data = data; }
    int32_t int32Data() const { return mInt32Data; }

    sp<AMessage> meta();

protected:
    virtual ~ABuffer();

private:
    sp<AMessage> mFarewell;
    sp<AMessage> mMeta;

    void *mData;
    size_t mCapacity;
    size_t mRangeOffset;
    size_t mRangeLength;

    int32_t mInt32Data;

    bool mOwnsData;

    DISALLOW_EVIL_CONSTRUCTORS(ABuffer);
};

}  // namespace android

#endif  // A_BUFFER_H_
