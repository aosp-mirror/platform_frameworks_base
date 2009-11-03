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


#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <hardware_legacy/AudioPolicyInterface.h>
#include <utils/threads.h>


namespace android {

// ----------------------------------------------------------------------------

#define MAX_DEVICE_ADDRESS_LEN 20
#define NUM_TEST_OUTPUTS 5

class AudioPolicyManagerGeneric: public AudioPolicyInterface
#ifdef AUDIO_POLICY_TEST
    , public Thread
#endif //AUDIO_POLICY_TEST
{

public:
                AudioPolicyManagerGeneric(AudioPolicyClientInterface *clientInterface);
        virtual ~AudioPolicyManagerGeneric();

        // AudioPolicyInterface
        virtual status_t setDeviceConnectionState(AudioSystem::audio_devices device,
                                                          AudioSystem::device_connection_state state,
                                                          const char *device_address);
        virtual AudioSystem::device_connection_state getDeviceConnectionState(AudioSystem::audio_devices device,
                                                                              const char *device_address);
        virtual void setPhoneState(int state);
        virtual void setRingerMode(uint32_t mode, uint32_t mask);
        virtual void setForceUse(AudioSystem::force_use usage, AudioSystem::forced_config config);
        virtual AudioSystem::forced_config getForceUse(AudioSystem::force_use usage);
        virtual void setSystemProperty(const char* property, const char* value);
        virtual audio_io_handle_t getOutput(AudioSystem::stream_type stream,
                                            uint32_t samplingRate,
                                            uint32_t format,
                                            uint32_t channels,
                                            AudioSystem::output_flags flags);
        virtual status_t startOutput(audio_io_handle_t output, AudioSystem::stream_type stream);
        virtual status_t stopOutput(audio_io_handle_t output, AudioSystem::stream_type stream);
        virtual void releaseOutput(audio_io_handle_t output);
        virtual audio_io_handle_t getInput(int inputSource,
                                            uint32_t samplingRate,
                                            uint32_t format,
                                            uint32_t channels,
                                            AudioSystem::audio_in_acoustics acoustics);
        // indicates to the audio policy manager that the input starts being used.
        virtual status_t startInput(audio_io_handle_t input);
        // indicates to the audio policy manager that the input stops being used.
        virtual status_t stopInput(audio_io_handle_t input);
        virtual void releaseInput(audio_io_handle_t input);
        virtual void initStreamVolume(AudioSystem::stream_type stream,
                                                    int indexMin,
                                                    int indexMax);
        virtual status_t setStreamVolumeIndex(AudioSystem::stream_type stream, int index);
        virtual status_t getStreamVolumeIndex(AudioSystem::stream_type stream, int *index);

        virtual status_t dump(int fd);

private:

        enum routing_strategy {
            STRATEGY_MEDIA,
            STRATEGY_PHONE,
            STRATEGY_SONIFICATION,
            STRATEGY_DTMF,
            NUM_STRATEGIES
        };

        // descriptor for audio outputs. Used to maintain current configuration of each opened audio output
        // and keep track of the usage of this output by each audio stream type.
        class AudioOutputDescriptor
        {
        public:
            AudioOutputDescriptor();

            status_t    dump(int fd);

            uint32_t device();
            void changeRefCount(AudioSystem::stream_type, int delta);
            bool isUsedByStream(AudioSystem::stream_type stream) { return mRefCount[stream] > 0 ? true : false; }
            uint32_t refCount();

            uint32_t mSamplingRate;             //
            uint32_t mFormat;                   //
            uint32_t mChannels;                 // output configuration
            uint32_t mLatency;                  //
            AudioSystem::output_flags mFlags;   //
            uint32_t mDevice;                   // current device this output is routed to
            uint32_t mRefCount[AudioSystem::NUM_STREAM_TYPES]; // number of streams of each type using this output
        };

        // descriptor for audio inputs. Used to maintain current configuration of each opened audio input
        // and keep track of the usage of this input.
        class AudioInputDescriptor
        {
        public:
            AudioInputDescriptor();

            status_t    dump(int fd);

            uint32_t mSamplingRate;                     //
            uint32_t mFormat;                           // input configuration
            uint32_t mChannels;                         //
            AudioSystem::audio_in_acoustics mAcoustics; //
            uint32_t mDevice;                           // current device this input is routed to
            uint32_t mRefCount;                         // number of AudioRecord clients using this output
        };

        // stream descriptor used for volume control
        class StreamDescriptor
        {
        public:
            StreamDescriptor()
            :   mIndexMin(0), mIndexMax(1), mIndexCur(1), mMuteCount(0), mCanBeMuted(true) {}

            void dump(char* buffer, size_t size);

            int mIndexMin;      // min volume index
            int mIndexMax;      // max volume index
            int mIndexCur;      // current volume index
            int mMuteCount;     // mute request counter
            bool mCanBeMuted;   // true is the stream can be muted
        };

        // return the strategy corresponding to a given stream type
        static routing_strategy getStrategy(AudioSystem::stream_type stream);
        // return the output handle of an output routed to the specified device, 0 if no output
        // is routed to the device
        float computeVolume(int stream, int index, uint32_t device);
        // Mute or unmute the stream on the specified output
        void setStreamMute(int stream, bool on, audio_io_handle_t output);
        // handle special cases for sonification strategy while in call: mute streams or replace by
        // a special tone in the device used for communication
        void handleIncallSonification(int stream, bool starting);


#ifdef AUDIO_POLICY_TEST
        virtual     bool        threadLoop();
                    void        exit();
        int testOutputIndex(audio_io_handle_t output);
#endif //AUDIO_POLICY_TEST


        AudioPolicyClientInterface *mpClientInterface;  // audio policy client interface
        audio_io_handle_t mHardwareOutput;              // hardware output handler

        KeyedVector<audio_io_handle_t, AudioOutputDescriptor *> mOutputs;   // list ot output descritors
        KeyedVector<audio_io_handle_t, AudioInputDescriptor *> mInputs;     // list of input descriptors
        uint32_t mAvailableOutputDevices;                                   // bit field of all available output devices
        uint32_t mAvailableInputDevices;                                    // bit field of all available input devices
        int mPhoneState;                                                    // current phone state
        uint32_t                 mRingerMode;                               // current ringer mode
        AudioSystem::forced_config mForceUse[AudioSystem::NUM_FORCE_USE];   // current forced use configuration

        StreamDescriptor mStreams[AudioSystem::NUM_STREAM_TYPES];           // stream descriptors for volume control

#ifdef AUDIO_POLICY_TEST
        Mutex   mLock;
        Condition mWaitWorkCV;

        int             mCurOutput;
        bool            mDirectOutput;
        audio_io_handle_t mTestOutputs[NUM_TEST_OUTPUTS];
        int             mTestInput;
        uint32_t        mTestDevice;
        uint32_t        mTestSamplingRate;
        uint32_t        mTestFormat;
        uint32_t        mTestChannels;
        uint32_t        mTestLatencyMs;
#endif //AUDIO_POLICY_TEST

};

};
