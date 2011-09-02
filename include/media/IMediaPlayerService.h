/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_IMEDIAPLAYERSERVICE_H
#define ANDROID_IMEDIAPLAYERSERVICE_H

#include <utils/Errors.h>  // for status_t
#include <utils/KeyedVector.h>
#include <utils/RefBase.h>
#include <utils/String8.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

#include <media/IMediaPlayerClient.h>
#include <media/IMediaPlayer.h>
#include <media/IMediaMetadataRetriever.h>

namespace android {

class IMediaRecorder;
class IOMX;
struct IStreamSource;

class IMediaPlayerService: public IInterface
{
public:
    DECLARE_META_INTERFACE(MediaPlayerService);

    virtual sp<IMediaRecorder> createMediaRecorder(pid_t pid) = 0;
    virtual sp<IMediaMetadataRetriever> createMetadataRetriever(pid_t pid) = 0;
    virtual sp<IMediaPlayer> create(pid_t pid, const sp<IMediaPlayerClient>& client, int audioSessionId = 0) = 0;

    virtual sp<IMemory>         decode(const char* url, uint32_t *pSampleRate, int* pNumChannels, int* pFormat) = 0;
    virtual sp<IMemory>         decode(int fd, int64_t offset, int64_t length, uint32_t *pSampleRate, int* pNumChannels, int* pFormat) = 0;
    virtual sp<IOMX>            getOMX() = 0;

    // codecs and audio devices usage tracking for the battery app
    enum BatteryDataBits {
        // tracking audio codec
        kBatteryDataTrackAudio          = 0x1,
        // tracking video codec
        kBatteryDataTrackVideo          = 0x2,
        // codec is started, otherwise codec is paused
        kBatteryDataCodecStarted        = 0x4,
        // tracking decoder (for media player),
        // otherwise tracking encoder (for media recorder)
        kBatteryDataTrackDecoder        = 0x8,
        // start to play an audio on an audio device
        kBatteryDataAudioFlingerStart   = 0x10,
        // stop/pause the audio playback
        kBatteryDataAudioFlingerStop    = 0x20,
        // audio is rounted to speaker
        kBatteryDataSpeakerOn           = 0x40,
        // audio is rounted to devices other than speaker
        kBatteryDataOtherAudioDeviceOn  = 0x80,
    };

    virtual void addBatteryData(uint32_t params) = 0;
    virtual status_t pullBatteryData(Parcel* reply) = 0;
};

// ----------------------------------------------------------------------------

class BnMediaPlayerService: public BnInterface<IMediaPlayerService>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif // ANDROID_IMEDIAPLAYERSERVICE_H
