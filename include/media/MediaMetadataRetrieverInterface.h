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

#ifndef ANDROID_MEDIAMETADATARETRIEVERINTERFACE_H
#define ANDROID_MEDIAMETADATARETRIEVERINTERFACE_H

#include <utils/RefBase.h>
#include <media/mediametadataretriever.h>
#include <private/media/VideoFrame.h>

namespace android {

// Abstract base class
class MediaMetadataRetrieverBase : public RefBase
{
public:
                        MediaMetadataRetrieverBase() {}
    virtual             ~MediaMetadataRetrieverBase() {}
    virtual status_t    setDataSource(const char *url) = 0;
    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length) = 0;
    virtual VideoFrame* getFrameAtTime(int64_t timeUs, int option) = 0;
    virtual MediaAlbumArt* extractAlbumArt() = 0;
    virtual const char* extractMetadata(int keyCode) = 0;
};

// MediaMetadataRetrieverInterface
class MediaMetadataRetrieverInterface : public MediaMetadataRetrieverBase
{
public:
    MediaMetadataRetrieverInterface() {}

    virtual             ~MediaMetadataRetrieverInterface() {}
    virtual VideoFrame* getFrameAtTime(int64_t timeUs, int option) { return NULL; }
    virtual MediaAlbumArt* extractAlbumArt() { return NULL; }
    virtual const char* extractMetadata(int keyCode) { return NULL; }
};

}; // namespace android

#endif // ANDROID_MEDIAMETADATARETRIEVERINTERFACE_H

