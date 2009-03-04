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
class StringArray;

class MediaScanner 
{
public:
    MediaScanner();
    ~MediaScanner();
    
    typedef bool (*ExceptionCheck)(void* env);

    status_t processFile(const char *path, const char *mimeType, MediaScannerClient& client);
    status_t processDirectory(const char *path, const char* extensions, 
            MediaScannerClient& client, ExceptionCheck exceptionCheck, void* exceptionEnv);
    void setLocale(const char* locale);
    
    // extracts album art as a block of data
    char* extractAlbumArt(int fd);
    
    static void uninitializeForThread();

private:
    status_t doProcessDirectory(char *path, int pathRemaining, const char* extensions, 
            MediaScannerClient& client, ExceptionCheck exceptionCheck, void* exceptionEnv);
    void initializeForThread();
    
    // current locale (like "ja_JP"), created/destroyed with strdup()/free()
    char* mLocale;
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

