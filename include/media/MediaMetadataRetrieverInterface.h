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
    virtual status_t    setMode(int mode) = 0;
    virtual status_t    getMode(int* mode) const = 0;
    virtual VideoFrame* captureFrame() = 0;
    virtual MediaAlbumArt* extractAlbumArt() = 0;
    virtual const char* extractMetadata(int keyCode) = 0;
};

// MediaMetadataRetrieverInterface
class MediaMetadataRetrieverInterface : public MediaMetadataRetrieverBase
{
public:
    virtual             ~MediaMetadataRetrieverInterface() {}

    // @param mode The intended mode of operations:
    // can be any of the following:
    // METADATA_MODE_NOOP: Experimental - just add and remove data source.
    // METADATA_MODE_FRAME_CAPTURE_ONLY: For capture frame/thumbnail only.
    // METADATA_MODE_METADATA_RETRIEVAL_ONLY: For meta data retrieval only.
    // METADATA_MODE_FRAME_CAPTURE_AND_METADATA_RETRIEVAL: For both frame
    //     capture and meta data retrieval.
    virtual status_t    setMode(int mode) {
                            if (mode < METADATA_MODE_NOOP ||
                                mode > METADATA_MODE_FRAME_CAPTURE_AND_METADATA_RETRIEVAL) {
                                return BAD_VALUE;
                            }
                            return NO_ERROR;
                        }

    virtual status_t    getMode(int* mode) const { *mode = mMode; return NO_ERROR; }
    virtual VideoFrame* captureFrame() { return NULL; }
    virtual MediaAlbumArt* extractAlbumArt() { return NULL; }
    virtual const char* extractMetadata(int keyCode) { return NULL; }

    uint32_t mMode;
};

}; // namespace android

#endif // ANDROID_MEDIAMETADATARETRIEVERINTERFACE_H

