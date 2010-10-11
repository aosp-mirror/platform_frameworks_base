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

#ifndef ES_QUEUE_H_

#define ES_QUEUE_H_

#include <media/stagefright/foundation/ABase.h>
#include <utils/List.h>
#include <utils/RefBase.h>

namespace android {

struct ABuffer;
struct MetaData;

struct ElementaryStreamQueue {
    enum Mode {
        H264,
        AAC
    };
    ElementaryStreamQueue(Mode mode);

    status_t appendData(const void *data, size_t size, int64_t timeUs);
    void clear();

    sp<ABuffer> dequeueAccessUnit();

    sp<MetaData> getFormat();

private:
    Mode mMode;

    sp<ABuffer> mBuffer;
    List<int64_t> mTimestamps;

    sp<MetaData> mFormat;

    sp<ABuffer> dequeueAccessUnitH264();
    sp<ABuffer> dequeueAccessUnitAAC();

    static sp<MetaData> MakeAACCodecSpecificData(
            unsigned profile, unsigned sampling_freq_index,
            unsigned channel_configuration);

    DISALLOW_EVIL_CONSTRUCTORS(ElementaryStreamQueue);
};

}  // namespace android

#endif  // ES_QUEUE_H_
