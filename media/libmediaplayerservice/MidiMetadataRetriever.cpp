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

//#define LOG_NDEBUG 0
#define LOG_TAG "MidiMetadataRetriever"
#include <utils/Log.h>

#include "MidiMetadataRetriever.h"
#include <media/mediametadataretriever.h>

namespace android {

static status_t ERROR_NOT_OPEN = -1;
static status_t ERROR_OPEN_FAILED = -2;
static status_t ERROR_EAS_FAILURE = -3;
static status_t ERROR_ALLOCATE_FAILED = -4;

void MidiMetadataRetriever::clearMetadataValues()
{
    ALOGV("clearMetadataValues");
    mMetadataValues[0][0] = '\0';
}

status_t MidiMetadataRetriever::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers)
{
    ALOGV("setDataSource: %s", url? url: "NULL pointer");
    Mutex::Autolock lock(mLock);
    clearMetadataValues();
    if (mMidiPlayer == 0) {
        mMidiPlayer = new MidiFile();
    }
    return mMidiPlayer->setDataSource(url, headers);
}

status_t MidiMetadataRetriever::setDataSource(int fd, int64_t offset, int64_t length)
{
    ALOGV("setDataSource: fd(%d), offset(%lld), and length(%lld)", fd, offset, length);
    Mutex::Autolock lock(mLock);
    clearMetadataValues();
    if (mMidiPlayer == 0) {
        mMidiPlayer = new MidiFile();
    }
    return mMidiPlayer->setDataSource(fd, offset, length);;
}

const char* MidiMetadataRetriever::extractMetadata(int keyCode)
{
    ALOGV("extractMetdata: key(%d)", keyCode);
    Mutex::Autolock lock(mLock);
    if (mMidiPlayer == 0 || mMidiPlayer->initCheck() != NO_ERROR) {
        ALOGE("Midi player is not initialized yet");
        return NULL;
    }
    switch (keyCode) {
    case METADATA_KEY_DURATION:
        {
            if (mMetadataValues[0][0] == '\0') {
                int duration = -1;
                if (mMidiPlayer->getDuration(&duration) != NO_ERROR) {
                    ALOGE("failed to get duration");
                    return NULL;
                }
                snprintf(mMetadataValues[0], MAX_METADATA_STRING_LENGTH, "%d", duration);
            }

            ALOGV("duration: %s ms", mMetadataValues[0]);
            return mMetadataValues[0];
        }
    default:
        ALOGE("Unsupported key code (%d)", keyCode);
        return NULL;
    }
    return NULL;
}

};

