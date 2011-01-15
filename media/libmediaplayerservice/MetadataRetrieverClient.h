/*
**
** Copyright (C) 2008 The Android Open Source Project 
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

#ifndef ANDROID_MEDIAMETADATARETRIEVERSERVICE_H
#define ANDROID_MEDIAMETADATARETRIEVERSERVICE_H

#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <binder/IMemory.h>

#include <media/MediaMetadataRetrieverInterface.h>


namespace android {

class IMediaPlayerService;
class MemoryDealer;

class MetadataRetrieverClient : public BnMediaMetadataRetriever
{
public:
    MetadataRetrieverClient(const sp<IMediaPlayerService>& service, pid_t pid, int32_t connId);

    // Implements IMediaMetadataRetriever interface
    // These methods are called in IMediaMetadataRetriever.cpp?
    virtual void                    disconnect();
    virtual status_t                setDataSource(const char *url);
    virtual status_t                setDataSource(int fd, int64_t offset, int64_t length);
    virtual sp<IMemory>             getFrameAtTime(int64_t timeUs, int option);
    virtual sp<IMemory>             extractAlbumArt();
    virtual const char*             extractMetadata(int keyCode);

    virtual status_t                dump(int fd, const Vector<String16>& args) const;

private:
    friend class MediaPlayerService;

    explicit MetadataRetrieverClient(pid_t pid);
    virtual ~MetadataRetrieverClient();

    mutable Mutex                          mLock;
    sp<MediaMetadataRetrieverBase>         mRetriever;
    pid_t                                  mPid;

    // Keep the shared memory copy of album art and capture frame (for thumbnail)
    sp<IMemory>                            mAlbumArt;
    sp<IMemory>                            mThumbnail;
};

}; // namespace android

#endif // ANDROID_MEDIAMETADATARETRIEVERSERVICE_H

