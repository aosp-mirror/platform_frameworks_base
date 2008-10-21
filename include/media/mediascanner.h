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

#include <utils.h>
#include <pthread.h>

namespace android {

class MediaScannerClient;

class MediaScanner 
{
public:
    MediaScanner();
    ~MediaScanner();
    
    typedef bool (*ExceptionCheck)(void* env);

    status_t processFile(const char *path, const char *mimeType, MediaScannerClient& client);
    status_t processDirectory(const char *path, const char* extensions, 
            MediaScannerClient& client, ExceptionCheck exceptionCheck, void* exceptionEnv);
    
    // extracts album art as a block of data
    char* extractAlbumArt(int fd);
    
    static void uninitializeForThread();

private:
    status_t doProcessDirectory(char *path, int pathRemaining, const char* extensions, 
            MediaScannerClient& client, ExceptionCheck exceptionCheck, void* exceptionEnv);
    void initializeForThread();
};


class MediaScannerClient
{
public:
	virtual ~MediaScannerClient() {}
	virtual bool scanFile(const char* path, long long lastModified, long long fileSize) = 0;
	virtual bool handleStringTag(const char* name, const char* value) = 0;
	virtual bool setMimeType(const char* mimeType) = 0;
};

}; // namespace android

#endif // MEDIASCANNER_H

