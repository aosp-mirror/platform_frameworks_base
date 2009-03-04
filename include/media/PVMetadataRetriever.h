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

#ifndef ANDROID_PVMETADATARETRIEVER_H
#define ANDROID_PVMETADATARETRIEVER_H

#include <utils/Errors.h>
#include <media/MediaMetadataRetrieverInterface.h>
#include <private/media/VideoFrame.h>

namespace android {

class MetadataDriver;

class PVMetadataRetriever : public MediaMetadataRetrieverInterface
{
public:
                        PVMetadataRetriever();
    virtual             ~PVMetadataRetriever();

    virtual status_t    setDataSource(const char *url);
    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setMode(int mode);
    virtual status_t    getMode(int* mode) const;
    virtual VideoFrame* captureFrame();
    virtual MediaAlbumArt* extractAlbumArt();
    virtual const char* extractMetadata(int keyCode);

private:
    mutable Mutex       mLock;
    MetadataDriver*     mMetadataDriver;
    char*               mDataSourcePath;
};

}; // namespace android

#endif // ANDROID_PVMETADATARETRIEVER_H
