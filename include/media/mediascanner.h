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

#ifndef MEDIASCANNER_H
#define MEDIASCANNER_H

#include <utils/Log.h>
#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Errors.h>
#include <pthread.h>

namespace android {

class MediaScannerClient;
class StringArray;

struct MediaScanner {
    MediaScanner();
    virtual ~MediaScanner();

    virtual status_t processFile(
            const char *path, const char *mimeType,
            MediaScannerClient &client) = 0;

    typedef bool (*ExceptionCheck)(void* env);
    virtual status_t processDirectory(
            const char *path, const char *extensions,
            MediaScannerClient &client,
            ExceptionCheck exceptionCheck, void *exceptionEnv);

    void setLocale(const char *locale);

    // extracts album art as a block of data
    virtual char *extractAlbumArt(int fd) = 0;

protected:
    const char *locale() const;

private:
    // current locale (like "ja_JP"), created/destroyed with strdup()/free()
    char *mLocale;

    status_t doProcessDirectory(
            char *path, int pathRemaining, const char *extensions,
            MediaScannerClient &client, ExceptionCheck exceptionCheck,
            void *exceptionEnv);

    MediaScanner(const MediaScanner &);
    MediaScanner &operator=(const MediaScanner &);
};

class MediaScannerClient
{
public:
    MediaScannerClient();
    virtual ~MediaScannerClient();
    void setLocale(const char* locale);
    void beginFile();
    bool addStringTag(const char* name, const char* value);
    void endFile();

    virtual bool scanFile(const char* path, long long lastModified, long long fileSize) = 0;
    virtual bool handleStringTag(const char* name, const char* value) = 0;
    virtual bool setMimeType(const char* mimeType) = 0;
    virtual bool addNoMediaFolder(const char* path) = 0;

protected:
    void convertValues(uint32_t encoding);

protected:
    // cached name and value strings, for native encoding support.
    StringArray*    mNames;
    StringArray*    mValues;

    // default encoding based on MediaScanner::mLocale string
    uint32_t        mLocaleEncoding;
};

}; // namespace android

#endif // MEDIASCANNER_H

