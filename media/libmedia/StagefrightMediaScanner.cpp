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

#include "StagefrightMediaScanner.h"

namespace android {

StagefrightMediaScanner::StagefrightMediaScanner() {}

StagefrightMediaScanner::~StagefrightMediaScanner() {}

status_t StagefrightMediaScanner::processFile(
        const char *path, const char *mimeType,
        MediaScannerClient &client) {
    client.setLocale(locale());
    client.beginFile();
    client.endFile();

    return OK;
}

void StagefrightMediaScanner::setLocale(const char *locale) {
    if (mLocale) {
        free(mLocale);
    }
    mLocale = strdup(locale);
}

char *StagefrightMediaScanner::extractAlbumArt(int fd) {
    return NULL;
}

}  // namespace android
