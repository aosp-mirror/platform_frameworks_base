/* //device/servers/AudioFlinger/AudioDumpInterface.cpp
**
** Copyright 2008, The Android Open Source Project
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

#define LOG_TAG "AudioFlingerDump"

#include <stdint.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <unistd.h>

#include "AudioDumpInterface.h"

namespace android {

bool gFirst = true;       // true if first write after a standby

// ----------------------------------------------------------------------------

AudioDumpInterface::AudioDumpInterface(AudioHardwareInterface* hw)
{
    if(hw == 0) {
        LOGE("Dump construct hw = 0");
    }
    mFinalInterface = hw;
    mStreamOut = 0;
}


AudioDumpInterface::~AudioDumpInterface()
{
    if(mFinalInterface) delete mFinalInterface;
    if(mStreamOut) delete mStreamOut;
}


AudioStreamOut* AudioDumpInterface::openOutputStream(
        int format, int channelCount, uint32_t sampleRate, status_t *status)
{
    AudioStreamOut* outFinal = mFinalInterface->openOutputStream(format, channelCount, sampleRate, status);

    if(outFinal) {
        mStreamOut =  new AudioStreamOutDump(outFinal);
        return mStreamOut;
    } else {
        LOGE("Dump outFinal=0");
        return 0;
    }
}

// ----------------------------------------------------------------------------

AudioStreamOutDump::AudioStreamOutDump( AudioStreamOut* finalStream)
{
    mFinalStream = finalStream;
    mOutFile = 0;
}


AudioStreamOutDump::~AudioStreamOutDump()
{
    Close();
    delete mFinalStream;
}

ssize_t AudioStreamOutDump::write(const void* buffer, size_t bytes)
{
    ssize_t ret;

    ret = mFinalStream->write(buffer, bytes);
    if(!mOutFile && gFirst) {
        gFirst = false;
        // check if dump file exist
        mOutFile = fopen(FLINGER_DUMP_NAME, "r");
        if(mOutFile) {
            fclose(mOutFile);
            mOutFile = fopen(FLINGER_DUMP_NAME, "ab");
        }
    }
    if (mOutFile) {
        fwrite(buffer, bytes, 1, mOutFile);
    }
    return ret;
}

status_t AudioStreamOutDump::standby()
{
    Close();
    gFirst = true;
    return mFinalStream->standby();
}


void AudioStreamOutDump::Close(void)
{
    if(mOutFile) {
        fclose(mOutFile);
        mOutFile = 0;
    }
}

}; // namespace android
