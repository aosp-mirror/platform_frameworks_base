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

#include <hardware_legacy/AudioHardwareBase.h>

namespace android {

#define FLINGER_DUMP_NAME "/data/FlingerOut.pcm" // name of file used for dump

class AudioStreamOutDump : public AudioStreamOut {
public:
                        AudioStreamOutDump( AudioStreamOut* FinalStream);
                        ~AudioStreamOutDump();
                        virtual ssize_t     write(const void* buffer, size_t bytes);

    virtual uint32_t    sampleRate() const { return mFinalStream->sampleRate(); }
    virtual size_t      bufferSize() const { return mFinalStream->bufferSize(); }
    virtual int         channelCount() const { return mFinalStream->channelCount(); }
    virtual int         format() const { return mFinalStream->format(); }
    virtual uint32_t    latency() const { return mFinalStream->latency(); }
    virtual status_t    setVolume(float volume)
                            { return mFinalStream->setVolume(volume); }
    virtual status_t    standby();
    virtual status_t    dump(int fd, const Vector<String16>& args) { return mFinalStream->dump(fd, args); }
    void                Close(void);

private:
    AudioStreamOut      *mFinalStream;
    FILE                *mOutFile;     // output file
};


class AudioDumpInterface : public AudioHardwareBase
{

public:
                        AudioDumpInterface(AudioHardwareInterface* hw);
    virtual AudioStreamOut* openOutputStream(
                                int format=0,
                                int channelCount=0,
                                uint32_t sampleRate=0,
                                status_t *status=0);
    virtual             ~AudioDumpInterface();

    virtual status_t    initCheck()
                            {return mFinalInterface->initCheck();}
    virtual status_t    setVoiceVolume(float volume)
                            {return mFinalInterface->setVoiceVolume(volume);}
    virtual status_t    setMasterVolume(float volume)
                            {return mFinalInterface->setMasterVolume(volume);}

    // mic mute
    virtual status_t    setMicMute(bool state)
                            {return mFinalInterface->setMicMute(state);}
    virtual status_t    getMicMute(bool* state)
                            {return mFinalInterface->getMicMute(state);}

    virtual status_t    setParameter(const char* key, const char* value)
                            {return mFinalInterface->setParameter(key, value);}

    virtual AudioStreamIn* openInputStream( int format, int channelCount, uint32_t sampleRate, status_t *status,
                                            AudioSystem::audio_in_acoustics acoustics)
                            {return mFinalInterface->openInputStream( format, channelCount, sampleRate, status, acoustics);}

    virtual status_t    dump(int fd, const Vector<String16>& args) { return mFinalInterface->dumpState(fd, args); }

protected:
    virtual status_t    doRouting() {return mFinalInterface->setRouting(mMode, mRoutes[mMode]);}

    AudioHardwareInterface  *mFinalInterface;
    AudioStreamOutDump      *mStreamOut;

};

}; // namespace android

#endif // ANDROID_AUDIO_DUMP_INTERFACE_H
