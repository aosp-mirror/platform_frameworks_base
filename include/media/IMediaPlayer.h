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

#ifndef ANDROID_IMEDIAPLAYER_H
#define ANDROID_IMEDIAPLAYER_H

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <utils/KeyedVector.h>
#include <system/audio.h>

namespace android {

class Parcel;
class Surface;
class IStreamSource;
class ISurfaceTexture;

class IMediaPlayer: public IInterface
{
public:
    DECLARE_META_INTERFACE(MediaPlayer);

    virtual void            disconnect() = 0;

    virtual status_t        setDataSource(const char *url,
                                    const KeyedVector<String8, String8>* headers) = 0;
    virtual status_t        setDataSource(int fd, int64_t offset, int64_t length) = 0;
    virtual status_t        setDataSource(const sp<IStreamSource>& source) = 0;
    virtual status_t        setVideoSurfaceTexture(
                                    const sp<ISurfaceTexture>& surfaceTexture) = 0;
    virtual status_t        prepareAsync() = 0;
    virtual status_t        start() = 0;
    virtual status_t        stop() = 0;
    virtual status_t        pause() = 0;
    virtual status_t        isPlaying(bool* state) = 0;
    virtual status_t        seekTo(int msec) = 0;
    virtual status_t        getCurrentPosition(int* msec) = 0;
    virtual status_t        getDuration(int* msec) = 0;
    virtual status_t        reset() = 0;
    virtual status_t        setAudioStreamType(audio_stream_type_t type) = 0;
    virtual status_t        setLooping(int loop) = 0;
    virtual status_t        setVolume(float leftVolume, float rightVolume) = 0;
    virtual status_t        setAuxEffectSendLevel(float level) = 0;
    virtual status_t        attachAuxEffect(int effectId) = 0;
    virtual status_t        setParameter(int key, const Parcel& request) = 0;
    virtual status_t        getParameter(int key, Parcel* reply) = 0;

    // Invoke a generic method on the player by using opaque parcels
    // for the request and reply.
    // @param request Parcel that must start with the media player
    // interface token.
    // @param[out] reply Parcel to hold the reply data. Cannot be null.
    // @return OK if the invocation was made successfully.
    virtual status_t        invoke(const Parcel& request, Parcel *reply) = 0;

    // Set a new metadata filter.
    // @param filter A set of allow and drop rules serialized in a Parcel.
    // @return OK if the invocation was made successfully.
    virtual status_t        setMetadataFilter(const Parcel& filter) = 0;

    // Retrieve a set of metadata.
    // @param update_only Include only the metadata that have changed
    //                    since the last invocation of getMetadata.
    //                    The set is built using the unfiltered
    //                    notifications the native player sent to the
    //                    MediaPlayerService during that period of
    //                    time. If false, all the metadatas are considered.
    // @param apply_filter If true, once the metadata set has been built based
    //                     on the value update_only, the current filter is
    //                     applied.
    // @param[out] metadata On exit contains a set (possibly empty) of metadata.
    //                      Valid only if the call returned OK.
    // @return OK if the invocation was made successfully.
    virtual status_t        getMetadata(bool update_only,
                                        bool apply_filter,
                                        Parcel *metadata) = 0;
};

// ----------------------------------------------------------------------------

class BnMediaPlayer: public BnInterface<IMediaPlayer>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

}; // namespace android

#endif // ANDROID_IMEDIAPLAYER_H
