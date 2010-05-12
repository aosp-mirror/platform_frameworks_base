/* //device/servers/AudioFlinger/AudioDumpInterface.h
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

#ifndef ANDROID_AUDIO_DUMP_INTERFACE_H
#define ANDROID_AUDIO_DUMP_INTERFACE_H

#include <stdint.h>
#include <sys/types.h>
#include <utils/String8.h>
#include <utils/SortedVector.h>

#include <hardware_legacy/AudioHardwareBase.h>

namespace android {

#define AUDIO_DUMP_WAVE_HDR_SIZE 44

class AudioDumpInterface;

class AudioStreamOutDump : public AudioStreamOut {
public:
                        AudioStreamOutDump(AudioDumpInterface *interface,
                                            int id,
                                            AudioStreamOut* finalStream,
                                            uint32_t devices,
                                            int format,
                                            uint32_t channels,
                                            uint32_t sampleRate);
                        ~AudioStreamOutDump();

    virtual ssize_t     write(const void* buffer, size_t bytes);
    virtual uint32_t    sampleRate() const;
    virtual size_t      bufferSize() const;
    virtual uint32_t    channels() const;
    virtual int         format() const;
    virtual uint32_t    latency() const;
    virtual status_t    setVolume(float left, float right);
    virtual status_t    standby();
    virtual status_t    setParameters(const String8& keyValuePairs);
    virtual String8     getParameters(const String8& keys);
    virtual status_t    dump(int fd, const Vector<String16>& args);
    void                Close(void);
    AudioStreamOut*     finalStream() { return mFinalStream; }
    uint32_t            device() { return mDevice; }
    int                 getId()  { return mId; }
    virtual status_t    getRenderPosition(uint32_t *dspFrames);

private:
    AudioDumpInterface *mInterface;
    int                  mId;
    uint32_t mSampleRate;               //
    uint32_t mFormat;                   //
    uint32_t mChannels;                 // output configuration
    uint32_t mLatency;                  //
    uint32_t mDevice;                   // current device this output is routed to
    size_t  mBufferSize;
    AudioStreamOut      *mFinalStream;
    FILE                *mFile;      // output file
    int                 mFileCount;
};

class AudioStreamInDump : public AudioStreamIn {
public:
                        AudioStreamInDump(AudioDumpInterface *interface,
                                            int id,
                                            AudioStreamIn* finalStream,
                                            uint32_t devices,
                                            int format,
                                            uint32_t channels,
                                            uint32_t sampleRate);
                        ~AudioStreamInDump();

    virtual uint32_t    sampleRate() const;
    virtual size_t      bufferSize() const;
    virtual uint32_t    channels() const;
    virtual int         format() const;

    virtual status_t    setGain(float gain);
    virtual ssize_t     read(void* buffer, ssize_t bytes);
    virtual status_t    standby();
    virtual status_t    setParameters(const String8& keyValuePairs);
    virtual String8     getParameters(const String8& keys);
    virtual unsigned int  getInputFramesLost() const;
    virtual status_t    dump(int fd, const Vector<String16>& args);
    void                Close(void);
    AudioStreamIn*     finalStream() { return mFinalStream; }
    uint32_t            device() { return mDevice; }

private:
    AudioDumpInterface *mInterface;
    int                  mId;
    uint32_t mSampleRate;               //
    uint32_t mFormat;                   //
    uint32_t mChannels;                 // output configuration
    uint32_t mDevice;                   // current device this output is routed to
    size_t  mBufferSize;
    AudioStreamIn      *mFinalStream;
    FILE                *mFile;      // output file
    int                 mFileCount;
};

class AudioDumpInterface : public AudioHardwareBase
{

public:
                        AudioDumpInterface(AudioHardwareInterface* hw);
    virtual AudioStreamOut* openOutputStream(
                                uint32_t devices,
                                int *format=0,
                                uint32_t *channels=0,
                                uint32_t *sampleRate=0,
                                status_t *status=0);
    virtual    void        closeOutputStream(AudioStreamOut* out);

    virtual             ~AudioDumpInterface();

    virtual status_t    initCheck()
                            {return mFinalInterface->initCheck();}
    virtual status_t    setVoiceVolume(float volume)
                            {return mFinalInterface->setVoiceVolume(volume);}
    virtual status_t    setMasterVolume(float volume)
                            {return mFinalInterface->setMasterVolume(volume);}

    virtual status_t    setMode(int mode);

    // mic mute
    virtual status_t    setMicMute(bool state)
                            {return mFinalInterface->setMicMute(state);}
    virtual status_t    getMicMute(bool* state)
                            {return mFinalInterface->getMicMute(state);}

    virtual status_t    setParameters(const String8& keyValuePairs);
    virtual String8     getParameters(const String8& keys);

    virtual size_t      getInputBufferSize(uint32_t sampleRate, int format, int channelCount);

    virtual AudioStreamIn* openInputStream(uint32_t devices, int *format, uint32_t *channels,
            uint32_t *sampleRate, status_t *status, AudioSystem::audio_in_acoustics acoustics);
    virtual    void        closeInputStream(AudioStreamIn* in);

    virtual status_t    dump(int fd, const Vector<String16>& args) { return mFinalInterface->dumpState(fd, args); }

            String8     fileName() const { return mFileName; }
protected:

    AudioHardwareInterface          *mFinalInterface;
    SortedVector<AudioStreamOutDump *>   mOutputs;
    SortedVector<AudioStreamInDump *>    mInputs;
    Mutex                           mLock;
    String8                         mPolicyCommands;
    String8                         mFileName;
};

}; // namespace android

#endif // ANDROID_AUDIO_DUMP_INTERFACE_H
