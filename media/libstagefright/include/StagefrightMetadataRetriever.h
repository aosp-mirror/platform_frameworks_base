/*
**
** Copyright 2009, The Android Open Source Project
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

#ifndef STAGEFRIGHT_METADATA_RETRIEVER_H_

#define STAGEFRIGHT_METADATA_RETRIEVER_H_

#include <media/MediaMetadataRetrieverInterface.h>

#include <media/stagefright/OMXClient.h>

namespace android {

class MediaExtractor;

struct StagefrightMetadataRetriever : public MediaMetadataRetrieverInterface {
    StagefrightMetadataRetriever();
    virtual ~StagefrightMetadataRetriever();

    virtual status_t setDataSource(const char *url);
    virtual status_t setDataSource(int fd, int64_t offset, int64_t length);

    virtual VideoFrame *captureFrame();
    virtual MediaAlbumArt *extractAlbumArt();
    virtual const char *extractMetadata(int keyCode);

private:
    OMXClient mClient;
    sp<MediaExtractor> mExtractor;

    StagefrightMetadataRetriever(const StagefrightMetadataRetriever &);

    StagefrightMetadataRetriever &operator=(
            const StagefrightMetadataRetriever &);
};

}  // namespace android

#endif  // STAGEFRIGHT_METADATA_RETRIEVER_H_
