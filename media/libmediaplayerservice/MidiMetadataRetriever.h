/*
**
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_MIDIMETADATARETRIEVER_H
#define ANDROID_MIDIMETADATARETRIEVER_H

#include <utils/threads.h>
#include <utils/Errors.h>
#include <media/MediaMetadataRetrieverInterface.h>

#include "MidiFile.h"

namespace android {

class MidiMetadataRetriever : public MediaMetadataRetrieverInterface {
public:
                                   MidiMetadataRetriever() {}
                                   ~MidiMetadataRetriever() {}

    virtual status_t                setDataSource(
            const char *url, const KeyedVector<String8, String8> *headers);

    virtual status_t                setDataSource(int fd, int64_t offset, int64_t length);
    virtual const char*             extractMetadata(int keyCode);

private:
    static const uint32_t MAX_METADATA_STRING_LENGTH = 128;
    void clearMetadataValues();

    Mutex               mLock;
    sp<MidiFile>        mMidiPlayer;
    char                mMetadataValues[1][MAX_METADATA_STRING_LENGTH];
};

}; // namespace android

#endif // ANDROID_MIDIMETADATARETRIEVER_H
