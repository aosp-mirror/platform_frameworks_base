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
//#define LOG_NDEBUG 0

#include <stdint.h>
#include <sys/types.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <unistd.h>

#include "AudioDumpInterface.h"

namespace android {

// ----------------------------------------------------------------------------

AudioDumpInterface::AudioDumpInterface(AudioHardwareInterface* hw)
    : mFirstHwOutput(true), mPolicyCommands(String8("")), mFileName(String8(""))
{
    if(hw == 0) {
        LOGE("Dump construct hw = 0");
    }
    mFinalInterface = hw;
    LOGV("Constructor %p, mFinalInterface %p", this, mFinalInterface);
}


AudioDumpInterface::~AudioDumpInterface()
{
    for (size_t i = 0; i < mOutputs.size(); i++) {
        closeOutputStream((AudioStreamOut *)mOutputs[i]);
    }
    if(mFinalInterface) delete mFinalInterface;
}


AudioStreamOut* AudioDumpInterface::openOutputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate, status_t *status)
{
    AudioStreamOut* outFinal = NULL;
    int lFormat = AudioSystem::PCM_16_BIT;
    uint32_t lChannels = AudioSystem::CHANNEL_OUT_STEREO;
    uint32_t lRate = 44100;


    if (AudioSystem::isA2dpDevice((AudioSystem::audio_devices)devices) || mFirstHwOutput) {
        outFinal = mFinalInterface->openOutputStream(devices, format, channels, sampleRate, status);
        if (outFinal != 0) {
            lFormat = outFinal->format();
            lChannels = outFinal->channels();
            lRate = outFinal->sampleRate();
            if (!AudioSystem::isA2dpDevice((AudioSystem::audio_devices)devices)) {
                mFirstHwOutput = false;
            }
        }
    } else {
        if (format != 0 && *format != 0) lFormat = *format;
        if (channels != 0 && *channels != 0) lChannels = *channels;
        if (sampleRate != 0 && *sampleRate != 0) lRate = *sampleRate;
        if (status) *status = NO_ERROR;
    }
    LOGV("openOutputStream(), outFinal %p", outFinal);

    AudioStreamOutDump *dumOutput = new AudioStreamOutDump(this, mOutputs.size(), outFinal,
            devices, lFormat, lChannels, lRate);
    mOutputs.add(dumOutput);

    return dumOutput;
}

void AudioDumpInterface::closeOutputStream(AudioStreamOut* out)
{
    AudioStreamOutDump *dumpOut = (AudioStreamOutDump *)out;

    if (mOutputs.indexOf(dumpOut) < 0) {
        LOGW("Attempt to close invalid output stream");
        return;
    }
    dumpOut->standby();
    if (dumpOut->finalStream() != NULL) {
        mFinalInterface->closeOutputStream(dumpOut->finalStream());
    }

    mOutputs.remove(dumpOut);
    delete dumpOut;
}

AudioStreamIn* AudioDumpInterface::openInputStream(uint32_t devices, int *format, uint32_t *channels,
        uint32_t *sampleRate, status_t *status, AudioSystem::audio_in_acoustics acoustics)
{
    AudioStreamIn* inFinal = NULL;
    int lFormat = AudioSystem::PCM_16_BIT;
    uint32_t lChannels = AudioSystem::CHANNEL_IN_MONO;
    uint32_t lRate = 8000;


    if (mInputs.size() == 0) {
        inFinal = mFinalInterface->openInputStream(devices, format, channels, sampleRate, status, acoustics);
        if (inFinal == 0) return 0;

        lFormat = inFinal->format();
        lChannels = inFinal->channels();
        lRate = inFinal->sampleRate();
    } else {
        if (format != 0 && *format != 0) lFormat = *format;
        if (channels != 0 && *channels != 0) lChannels = *channels;
        if (sampleRate != 0 && *sampleRate != 0) lRate = *sampleRate;
        if (status) *status = NO_ERROR;
    }
    LOGV("openInputStream(), inFinal %p", inFinal);

    AudioStreamInDump *dumInput = new AudioStreamInDump(this, mInputs.size(), inFinal,
            devices, lFormat, lChannels, lRate);
    mInputs.add(dumInput);

    return dumInput;
}
void AudioDumpInterface::closeInputStream(AudioStreamIn* in)
{
    AudioStreamInDump *dumpIn = (AudioStreamInDump *)in;

    if (mInputs.indexOf(dumpIn) < 0) {
        LOGW("Attempt to close invalid input stream");
        return;
    }
    dumpIn->standby();
    if (dumpIn->finalStream() != NULL) {
        mFinalInterface->closeInputStream(dumpIn->finalStream());
    }

    mInputs.remove(dumpIn);
    delete dumpIn;
}


status_t AudioDumpInterface::setParameters(const String8& keyValuePairs)
{
    AudioParameter param = AudioParameter(keyValuePairs);
    String8 value;
    int valueInt;
    LOGV("setParameters %s", keyValuePairs.string());

    if (param.get(String8("test_cmd_file_name"), value) == NO_ERROR) {
        mFileName = value;
        return NO_ERROR;
    }
    if (param.get(String8("test_cmd_policy"), value) == NO_ERROR) {
        Mutex::Autolock _l(mLock);
        param.remove(String8("test_cmd_policy"));
        mPolicyCommands = param.toString();
        LOGV("test_cmd_policy command %s written", mPolicyCommands.string());
        return NO_ERROR;
    }

    if (mFinalInterface != 0 ) return mFinalInterface->setParameters(keyValuePairs);
    return NO_ERROR;
}

String8 AudioDumpInterface::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    String8 value;

//    LOGV("getParameters %s", keys.string());

    if (param.get(String8("test_cmd_file_name"), value) == NO_ERROR) {
        return mFileName;
    }
    if (param.get(String8("test_cmd_policy"), value) == NO_ERROR) {
        Mutex::Autolock _l(mLock);
//        LOGV("test_cmd_policy command %s read", mPolicyCommands.string());
        return mPolicyCommands;
    }

    if (mFinalInterface != 0 ) return mFinalInterface->getParameters(keys);
    return String8("");
}


// ----------------------------------------------------------------------------

AudioStreamOutDump::AudioStreamOutDump(AudioDumpInterface *interface,
                                        int id,
                                        AudioStreamOut* finalStream,
                                        uint32_t devices,
                                        int format,
                                        uint32_t channels,
                                        uint32_t sampleRate)
    : mInterface(interface), mId(id),
      mSampleRate(sampleRate), mFormat(format), mChannels(channels), mLatency(0), mDevice(devices),
      mBufferSize(1024), mFinalStream(finalStream), mOutFile(0), mFileCount(0)
{
    LOGV("AudioStreamOutDump Constructor %p, mInterface %p, mFinalStream %p", this, mInterface, mFinalStream);
}


AudioStreamOutDump::~AudioStreamOutDump()
{
    Close();
}

ssize_t AudioStreamOutDump::write(const void* buffer, size_t bytes)
{
    ssize_t ret;

    if (mFinalStream) {
        ret = mFinalStream->write(buffer, bytes);
    } else {
        usleep((bytes * 1000000) / frameSize() / sampleRate());
        ret = bytes;
    }
    if(!mOutFile) {
        if (mInterface->fileName() != "") {
            char name[255];
            sprintf(name, "%s_%d_%d.pcm", mInterface->fileName().string(), mId, ++mFileCount);
            mOutFile = fopen(name, "wb");
            LOGV("Opening dump file %s, fh %p", name, mOutFile);
        }
    }
    if (mOutFile) {
        fwrite(buffer, bytes, 1, mOutFile);
    }
    return ret;
}

status_t AudioStreamOutDump::standby()
{
    LOGV("AudioStreamOutDump standby(), mOutFile %p, mFinalStream %p", mOutFile, mFinalStream);

    Close();
    if (mFinalStream != 0 ) return mFinalStream->standby();
    return NO_ERROR;
}

uint32_t AudioStreamOutDump::sampleRate() const
{
    if (mFinalStream != 0 ) return mFinalStream->sampleRate();
    return mSampleRate;
}

size_t AudioStreamOutDump::bufferSize() const
{
    if (mFinalStream != 0 ) return mFinalStream->bufferSize();
    return mBufferSize;
}

uint32_t AudioStreamOutDump::channels() const
{
    if (mFinalStream != 0 ) return mFinalStream->channels();
    return mChannels;
}
int AudioStreamOutDump::format() const
{
    if (mFinalStream != 0 ) return mFinalStream->format();
    return mFormat;
}
uint32_t AudioStreamOutDump::latency() const
{
    if (mFinalStream != 0 ) return mFinalStream->latency();
    return 0;
}
status_t AudioStreamOutDump::setVolume(float left, float right)
{
    if (mFinalStream != 0 ) return mFinalStream->setVolume(left, right);
    return NO_ERROR;
}
status_t AudioStreamOutDump::setParameters(const String8& keyValuePairs)
{
    LOGV("AudioStreamOutDump::setParameters()");
    if (mFinalStream != 0 ) return mFinalStream->setParameters(keyValuePairs);
    return NO_ERROR;
}
String8 AudioStreamOutDump::getParameters(const String8& keys)
{
    String8 result = String8("");
    if (mFinalStream != 0 ) result = mFinalStream->getParameters(keys);
    return result;
}

status_t AudioStreamOutDump::dump(int fd, const Vector<String16>& args)
{
    if (mFinalStream != 0 ) return mFinalStream->dump(fd, args);
    return NO_ERROR;
}

void AudioStreamOutDump::Close()
{
    if(mOutFile) {
        fclose(mOutFile);
        mOutFile = 0;
    }
}

// ----------------------------------------------------------------------------

AudioStreamInDump::AudioStreamInDump(AudioDumpInterface *interface,
                                        int id,
                                        AudioStreamIn* finalStream,
                                        uint32_t devices,
                                        int format,
                                        uint32_t channels,
                                        uint32_t sampleRate)
    : mInterface(interface), mId(id),
      mSampleRate(sampleRate), mFormat(format), mChannels(channels), mDevice(devices),
      mBufferSize(1024), mFinalStream(finalStream), mInFile(0)
{
    LOGV("AudioStreamInDump Constructor %p, mInterface %p, mFinalStream %p", this, mInterface, mFinalStream);
}


AudioStreamInDump::~AudioStreamInDump()
{
    Close();
}

ssize_t AudioStreamInDump::read(void* buffer, ssize_t bytes)
{
    if (mFinalStream) {
        return mFinalStream->read(buffer, bytes);
    }

    usleep((bytes * 1000000) / frameSize() / sampleRate());

    if(!mInFile) {
        char name[255];
        strcpy(name, "/sdcard/music/sine440");
        if (channels() == AudioSystem::CHANNEL_IN_MONO) {
            strcat(name, "_mo");
        } else {
            strcat(name, "_st");
        }
        if (format() == AudioSystem::PCM_16_BIT) {
            strcat(name, "_16b");
        } else {
            strcat(name, "_8b");
        }
        if (sampleRate() < 16000) {
            strcat(name, "_8k");
        } else if (sampleRate() < 32000) {
            strcat(name, "_22k");
        } else if (sampleRate() < 48000) {
            strcat(name, "_44k");
        } else {
            strcat(name, "_48k");
        }
        strcat(name, ".wav");
        mInFile = fopen(name, "rb");
        LOGV("Opening dump file %s, fh %p", name, mInFile);
        if (mInFile) {
            fseek(mInFile, AUDIO_DUMP_WAVE_HDR_SIZE, SEEK_SET);
        }

    }
    if (mInFile) {
        ssize_t bytesRead = fread(buffer, bytes, 1, mInFile);
        if (bytesRead != bytes) {
            fseek(mInFile, AUDIO_DUMP_WAVE_HDR_SIZE, SEEK_SET);
            fread((uint8_t *)buffer+bytesRead, bytes-bytesRead, 1, mInFile);
        }
    }
    return bytes;
}

status_t AudioStreamInDump::standby()
{
    LOGV("AudioStreamInDump standby(), mInFile %p, mFinalStream %p", mInFile, mFinalStream);

    Close();
    if (mFinalStream != 0 ) return mFinalStream->standby();
    return NO_ERROR;
}

status_t AudioStreamInDump::setGain(float gain)
{
    if (mFinalStream != 0 ) return mFinalStream->setGain(gain);
    return NO_ERROR;
}

uint32_t AudioStreamInDump::sampleRate() const
{
    if (mFinalStream != 0 ) return mFinalStream->sampleRate();
    return mSampleRate;
}

size_t AudioStreamInDump::bufferSize() const
{
    if (mFinalStream != 0 ) return mFinalStream->bufferSize();
    return mBufferSize;
}

uint32_t AudioStreamInDump::channels() const
{
    if (mFinalStream != 0 ) return mFinalStream->channels();
    return mChannels;
}

int AudioStreamInDump::format() const
{
    if (mFinalStream != 0 ) return mFinalStream->format();
    return mFormat;
}

status_t AudioStreamInDump::setParameters(const String8& keyValuePairs)
{
    LOGV("AudioStreamInDump::setParameters()");
    if (mFinalStream != 0 ) return mFinalStream->setParameters(keyValuePairs);
    return NO_ERROR;
}

String8 AudioStreamInDump::getParameters(const String8& keys)
{
    String8 result = String8("");
    if (mFinalStream != 0 ) result = mFinalStream->getParameters(keys);
    return result;
}

status_t AudioStreamInDump::dump(int fd, const Vector<String16>& args)
{
    if (mFinalStream != 0 ) return mFinalStream->dump(fd, args);
    return NO_ERROR;
}

void AudioStreamInDump::Close()
{
    if(mInFile) {
        fclose(mInFile);
        mInFile = 0;
    }
}
}; // namespace android
