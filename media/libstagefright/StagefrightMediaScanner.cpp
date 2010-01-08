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

#include <media/stagefright/StagefrightMediaScanner.h>

#include "include/StagefrightMetadataRetriever.h"

namespace android {

StagefrightMediaScanner::StagefrightMediaScanner()
    : mRetriever(new StagefrightMetadataRetriever) {
}

StagefrightMediaScanner::~StagefrightMediaScanner() {}

status_t StagefrightMediaScanner::processFile(
        const char *path, const char *mimeType,
        MediaScannerClient &client) {
    client.setLocale(locale());
    client.beginFile();

    if (mRetriever->setDataSource(path) == OK
            && mRetriever->setMode(
                METADATA_MODE_METADATA_RETRIEVAL_ONLY) == OK) {
        struct KeyMap {
            const char *tag;
            int key;
        };
        static const KeyMap kKeyMap[] = {
            { "tracknumber", METADATA_KEY_CD_TRACK_NUMBER },
            { "album", METADATA_KEY_ALBUM },
            { "artist", METADATA_KEY_ARTIST },
            { "composer", METADATA_KEY_COMPOSER },
            { "genre", METADATA_KEY_GENRE },
            { "title", METADATA_KEY_TITLE },
            { "year", METADATA_KEY_YEAR },
            { "duration", METADATA_KEY_DURATION },
            { "writer", METADATA_KEY_WRITER },
        };
        static const size_t kNumEntries = sizeof(kKeyMap) / sizeof(kKeyMap[0]);

        for (size_t i = 0; i < kNumEntries; ++i) {
            const char *value;
            if ((value = mRetriever->extractMetadata(kKeyMap[i].key)) != NULL) {
                client.addStringTag(kKeyMap[i].tag, value);
            }
        }
    }

    client.endFile();

    return OK;
}

char *StagefrightMediaScanner::extractAlbumArt(int fd) {
    if (mRetriever->setDataSource(fd, 0, 0) == OK
            && mRetriever->setMode(
                METADATA_MODE_FRAME_CAPTURE_ONLY) == OK) {
        MediaAlbumArt *art = mRetriever->extractAlbumArt();

        // TODO: figure out what format the result should be in.
    }

    return NULL;
}

}  // namespace android
