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

struct dirent;

namespace android {

class MediaScannerClient;
class StringArray;

enum MediaScanResult {
    // This file or directory was scanned successfully.
    MEDIA_SCAN_RESULT_OK,
    // This file or directory was skipped because it was not found, could
    // not be opened, was of an unsupported type, or was malfored in some way.
    MEDIA_SCAN_RESULT_SKIPPED,
    // The scan should be aborted due to a fatal error such as out of memory
    // or an exception.
    MEDIA_SCAN_RESULT_ERROR,
};

struct MediaScanner {
    MediaScanner();
    virtual ~MediaScanner();

    virtual MediaScanResult processFile(
            const char *path, const char *mimeType, MediaScannerClient &client) = 0;

    virtual MediaScanResult processDirectory(
            const char *path, MediaScannerClient &client);

    void setLocale(const char *locale);

    // extracts album art as a block of data
    virtual char *extractAlbumArt(int fd) = 0;

protected:
    const char *locale() const;

private:
    // current locale (like "ja_JP"), created/destroyed with strdup()/free()
    char *mLocale;
    char *mSkipList;
    int *mSkipIndex;

    MediaScanResult doProcessDirectory(
            char *path, int pathRemaining, MediaScannerClient &client, bool noMedia);
    MediaScanResult doProcessDirectoryEntry(
            char *path, int pathRemaining, MediaScannerClient &client, bool noMedia,
            struct dirent* entry, char* fileSpot);
    void loadSkipList();
    bool shouldSkipDirectory(char *path);


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
    status_t addStringTag(const char* name, const char* value);
    void endFile();

    virtual status_t scanFile(const char* path, long long lastModified,
            long long fileSize, bool isDirectory, bool noMedia) = 0;
    virtual status_t handleStringTag(const char* name, const char* value) = 0;
    virtual status_t setMimeType(const char* mimeType) = 0;

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
