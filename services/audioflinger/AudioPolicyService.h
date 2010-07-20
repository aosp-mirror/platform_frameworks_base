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

#ifndef ANDROID_AUDIOPOLICYSERVICE_H
#define ANDROID_AUDIOPOLICYSERVICE_H

#include <media/IAudioPolicyService.h>
#include <hardware_legacy/AudioPolicyInterface.h>
#include <media/ToneGenerator.h>
#include <utils/Vector.h>

namespace android {

class String8;

// ----------------------------------------------------------------------------

class AudioPolicyService: public BnAudioPolicyService, public AudioPolicyClientInterface,
    public IBinder::DeathRecipient
{

public:
    static  void        instantiate();

    virtual status_t    dump(int fd, const Vector<String16>& args);

    //
    // BnAudioPolicyService (see AudioPolicyInterface for method descriptions)
    //

    virtual status_t setDeviceConnectionState(AudioSystem::audio_devices device,
                                              AudioSystem::device_connection_state state,
                                              const char *device_address);
    virtual AudioSystem::device_connection_state getDeviceConnectionState(
                                                                AudioSystem::audio_devices device,
                                                                const char *device_address);
    virtual status_t setPhoneState(int state);
    virtual status_t setRingerMode(uint32_t mode, uint32_t mask);
    virtual status_t setForceUse(AudioSystem::force_use usage, AudioSystem::forced_config config);
    virtual AudioSystem::forced_config getForceUse(AudioSystem::force_use usage);
    virtual audio_io_handle_t getOutput(AudioSystem::stream_type stream,
                                        uint32_t samplingRate = 0,
                                        uint32_t format = AudioSystem::FORMAT_DEFAULT,
                                        uint32_t channels = 0,
                                        AudioSystem::output_flags flags =
                                                AudioSystem::OUTPUT_FLAG_INDIRECT);
    virtual status_t startOutput(audio_io_handle_t output,
                                 AudioSystem::stream_type stream,
                                 int session = 0);
    virtual status_t stopOutput(audio_io_handle_t output,
                                AudioSystem::stream_type stream,
                                int session = 0);
    virtual void releaseOutput(audio_io_handle_t output);
    virtual audio_io_handle_t getInput(int inputSource,
                                    uint32_t samplingRate = 0,
                                    uint32_t format = AudioSystem::FORMAT_DEFAULT,
                                    uint32_t channels = 0,
                                    AudioSystem::audio_in_acoustics acoustics =
                                            (AudioSystem::audio_in_acoustics)0);
    virtual status_t startInput(audio_io_handle_t input);
    virtual status_t stopInput(audio_io_handle_t input);
    virtual void releaseInput(audio_io_handle_t input);
    virtual status_t initStreamVolume(AudioSystem::stream_type stream,
                                      int indexMin,
                                      int indexMax);
    virtual status_t setStreamVolumeIndex(AudioSystem::stream_type stream, int index);
    virtual status_t getStreamVolumeIndex(AudioSystem::stream_type stream, int *index);

    virtual uint32_t getStrategyForStream(AudioSystem::stream_type stream);

    virtual audio_io_handle_t getOutputForEffect(effect_descriptor_t *desc);
    virtual status_t registerEffect(effect_descriptor_t *desc,
                                    audio_io_handle_t output,
                                    uint32_t strategy,
                                    int session,
                                    int id);
    virtual status_t unregisterEffect(int id);

    virtual     status_t    onTransact(
                                uint32_t code,
                                const Parcel& data,
                                Parcel* reply,
                                uint32_t flags);

    // IBinder::DeathRecipient
    virtual     void        binderDied(const wp<IBinder>& who);

    //
    // AudioPolicyClientInterface
    //
    virtual audio_io_handle_t openOutput(uint32_t *pDevices,
                                    uint32_t *pSamplingRate,
                                    uint32_t *pFormat,
                                    uint32_t *pChannels,
                                    uint32_t *pLatencyMs,
                                    AudioSystem::output_flags flags);
    virtual audio_io_handle_t openDuplicateOutput(audio_io_handle_t output1,
                                                  audio_io_handle_t output2);
    virtual status_t closeOutput(audio_io_handle_t output);
    virtual status_t suspendOutput(audio_io_handle_t output);
    virtual status_t restoreOutput(audio_io_handle_t output);
    virtual audio_io_handle_t openInput(uint32_t *pDevices,
                                    uint32_t *pSamplingRate,
                                    uint32_t *pFormat,
                                    uint32_t *pChannels,
                                    uint32_t acoustics);
    virtual status_t closeInput(audio_io_handle_t input);
    virtual status_t setStreamVolume(AudioSystem::stream_type stream,
                                     float volume,
                                     audio_io_handle_t output,
                                     int delayMs = 0);
    virtual status_t setStreamOutput(AudioSystem::stream_type stream, audio_io_handle_t output);
    virtual void setParameters(audio_io_handle_t ioHandle,
                               const String8& keyValuePairs,
                               int delayMs = 0);
    virtual String8 getParameters(audio_io_handle_t ioHandle, const String8& keys);
    virtual status_t startTone(ToneGenerator::tone_type tone, AudioSystem::stream_type stream);
    virtual status_t stopTone();
    virtual status_t setVoiceVolume(float volume, int delayMs = 0);
    virtual status_t moveEffects(int session,
                                     audio_io_handle_t srcOutput,
                                     audio_io_handle_t dstOutput);

private:
                        AudioPolicyService();
    virtual             ~AudioPolicyService();

            status_t dumpInternals(int fd);

    // Thread used for tone playback and to send audio config commands to audio flinger
    // For tone playback, using a separate thread is necessary to avoid deadlock with mLock because startTone()
    // and stopTone() are normally called with mLock locked and requesting a tone start or stop will cause
    // calls to AudioPolicyService and an attempt to lock mLock.
    // For audio config commands, it is necessary because audio flinger requires that the calling process (user)
    // has permission to modify audio settings.
    class AudioCommandThread : public Thread {
        class AudioCommand;
    public:

        // commands for tone AudioCommand
        enum {
            START_TONE,
            STOP_TONE,
            SET_VOLUME,
            SET_PARAMETERS,
            SET_VOICE_VOLUME
        };

        AudioCommandThread (String8 name);
        virtual             ~AudioCommandThread();

                    status_t    dump(int fd);

        // Thread virtuals
        virtual     void        onFirstRef();
        virtual     bool        threadLoop();

                    void        exit();
                    void        startToneCommand(int type = 0, int stream = 0);
                    void        stopToneCommand();
                    status_t    volumeCommand(int stream, float volume, int output, int delayMs = 0);
                    status_t    parametersCommand(int ioHandle, const String8& keyValuePairs, int delayMs = 0);
                    status_t    voiceVolumeCommand(float volume, int delayMs = 0);
                    void        insertCommand_l(AudioCommand *command, int delayMs = 0);

    private:
        // descriptor for requested tone playback event
        class AudioCommand {

        public:
            AudioCommand()
            : mCommand(-1) {}

            void dump(char* buffer, size_t size);

            int mCommand;   // START_TONE, STOP_TONE ...
            nsecs_t mTime;  // time stamp
            Condition mCond; // condition for status return
            status_t mStatus; // command status
            bool mWaitStatus; // true if caller is waiting for status
            void *mParam;     // command parameter (ToneData, VolumeData, ParametersData)
        };

        class ToneData {
        public:
            int mType;      // tone type (START_TONE only)
            int mStream;    // stream type (START_TONE only)
        };

        class VolumeData {
        public:
            int mStream;
            float mVolume;
            int mIO;
        };

        class ParametersData {
        public:
            int mIO;
            String8 mKeyValuePairs;
        };

        class VoiceVolumeData {
        public:
            float mVolume;
        };

        Mutex   mLock;
        Condition mWaitWorkCV;
        Vector <AudioCommand *> mAudioCommands; // list of pending commands
        ToneGenerator *mpToneGenerator;     // the tone generator
        AudioCommand mLastCommand;          // last processed command (used by dump)
        String8 mName;                      // string used by wake lock fo delayed commands
    };

    // Internal dump utilities.
    status_t dumpPermissionDenial(int fd);


    Mutex   mLock;      // prevents concurrent access to AudioPolicy manager functions changing device
                        // connection stated our routing
    AudioPolicyInterface* mpPolicyManager;          // the platform specific policy manager
    sp <AudioCommandThread> mAudioCommandThread;    // audio commands thread
    sp <AudioCommandThread> mTonePlaybackThread;     // tone playback thread
};

}; // namespace android

#endif // ANDROID_AUDIOPOLICYSERVICE_H








