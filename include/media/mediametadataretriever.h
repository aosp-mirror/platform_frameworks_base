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


#ifndef MEDIAMETADATARETRIEVER_H
#define MEDIAMETADATARETRIEVER_H

#include <utils/Errors.h>  // for status_t
#include <utils/threads.h>
#include <binder/IMemory.h>
#include <media/IMediaMetadataRetriever.h>

namespace android {

class IMediaPlayerService;
class IMediaMetadataRetriever;

// Keep these in synch with the constants defined in MediaMetadataRetriever.java
// class.
enum {
    METADATA_KEY_CD_TRACK_NUMBER = 0,
    METADATA_KEY_ALBUM           = 1,
    METADATA_KEY_ARTIST          = 2,
    METADATA_KEY_AUTHOR          = 3,
    METADATA_KEY_COMPOSER        = 4,
    METADATA_KEY_DATE            = 5,
    METADATA_KEY_GENRE           = 6,
    METADATA_KEY_TITLE           = 7,
    METADATA_KEY_YEAR            = 8,
    METADATA_KEY_DURATION        = 9,
    METADATA_KEY_NUM_TRACKS      = 10,
    METADATA_KEY_WRITER          = 11,
    METADATA_KEY_MIMETYPE        = 12,
    METADATA_KEY_ALBUMARTIST     = 13,
    METADATA_KEY_DISC_NUMBER     = 14,
    METADATA_KEY_COMPILATION     = 15,
    METADATA_KEY_HAS_AUDIO       = 16,
    METADATA_KEY_HAS_VIDEO       = 17,
    METADATA_KEY_VIDEO_WIDTH     = 18,
    METADATA_KEY_VIDEO_HEIGHT    = 19,
    METADATA_KEY_BITRATE         = 20,
    METADATA_KEY_TIMED_TEXT_LANGUAGES      = 21,
    METADATA_KEY_IS_DRM          = 22,
    METADATA_KEY_LOCATION        = 23,

    // Add more here...
};

class MediaMetadataRetriever: public RefBase
{
public:
    MediaMetadataRetriever();
    ~MediaMetadataRetriever();
    void disconnect();

    status_t setDataSource(
            const char *dataSourceUrl,
            const KeyedVector<String8, String8> *headers = NULL);

    status_t setDataSource(int fd, int64_t offset, int64_t length);
    sp<IMemory> getFrameAtTime(int64_t timeUs, int option);
    sp<IMemory> extractAlbumArt();
    const char* extractMetadata(int keyCode);

private:
    static const sp<IMediaPlayerService>& getService();

    class DeathNotifier: public IBinder::DeathRecipient
    {
    public:
        DeathNotifier() {}
        virtual ~DeathNotifier();
        virtual void binderDied(const wp<IBinder>& who);
    };

    static sp<DeathNotifier>                  sDeathNotifier;
    static Mutex                              sServiceLock;
    static sp<IMediaPlayerService>            sService;

    Mutex                                     mLock;
    sp<IMediaMetadataRetriever>               mRetriever;

};

}; // namespace android

#endif // MEDIAMETADATARETRIEVER_H
