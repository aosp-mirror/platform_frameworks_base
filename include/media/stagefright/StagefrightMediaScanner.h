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

#ifndef STAGEFRIGHT_MEDIA_SCANNER_H_

#define STAGEFRIGHT_MEDIA_SCANNER_H_

#include <media/mediascanner.h>

namespace android {

struct StagefrightMediaScanner : public MediaScanner {
    StagefrightMediaScanner();
    virtual ~StagefrightMediaScanner();

    virtual status_t processFile(
            const char *path, const char *mimeType,
            MediaScannerClient &client);

    virtual char *extractAlbumArt(int fd);

private:
    StagefrightMediaScanner(const StagefrightMediaScanner &);
    StagefrightMediaScanner &operator=(const StagefrightMediaScanner &);
};

}  // namespace android

#endif  // STAGEFRIGHT_MEDIA_SCANNER_H_
