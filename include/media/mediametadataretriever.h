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


#ifndef MEDIAMETADATARETRIEVER_H
#define MEDIAMETADATARETRIEVER_H

#include <graphics/SkBitmap.h>      // for SkBitmap

namespace android {

// Keep these in synch with the constants defined in MediaMetadataRetriever.java
// class.
enum {
    METADATA_KEY_CD_TRACK_NUMBER = 0,
    METADATA_KEY_ALBUM           = 1,
    METADATA_KEY_ARTIST          = 2,
    METADATA_KEY_AUTHOR          = 3,
    METADATA_KEY_COMPOSER        = 4,
    METADATA_KEY_DATE            = 5,
    METADATA_KEY_GENRE           = 6,
    METADATA_KEY_TITLE           = 7,
    METADATA_KEY_YEAR            = 8,
    METADATA_KEY_DURATION        = 9,
    METADATA_KEY_NUM_TRACKS      = 10,
    METADATA_KEY_IS_DRM_CRIPPLED = 11,
    METADATA_KEY_CODEC           = 12,
    // Add more here...
};

// A utility class that holds the size and actual data in album art.
class MediaAlbumArt {
public:
    MediaAlbumArt(): length(0), data(NULL) {}
    MediaAlbumArt(const MediaAlbumArt& copy) { 
        // Don't be caught by uninitialized variables!!
        length = 0; 
        data = NULL; 
        setData(copy.length, copy.data);
    }
    MediaAlbumArt(const char* url);
    ~MediaAlbumArt() { clearData(); }

    void clearData();
    status_t setData(unsigned int len, const char* buf);
    char *getData() const { return copyData(length, data); }
    unsigned int getLength() const { return length; }
    
private:
    // Disable copy assignment operator!
    MediaAlbumArt& operator=(const MediaAlbumArt& rhs);
    static char* copyData(unsigned int len, const char* buf);
    
    unsigned int length;    // Number of bytes in data.
    char *data;             // Actual binary data.
};

class MediaMetadataRetrieverImpl
{
public:
    virtual ~MediaMetadataRetrieverImpl() {};
    virtual status_t setDataSource(const char* dataSourceUrl) = 0;
    virtual SkBitmap *captureFrame() = 0;
    virtual const char* extractMetadata(int keyCode) = 0;
    virtual MediaAlbumArt* extractAlbumArt() = 0;
    virtual void setMode(int mode) = 0;
};

class MediaMetadataRetriever
{
public:
    static status_t setDataSource(const char* dataSourceUrl);
    static SkBitmap *captureFrame();
    static const char* extractMetadata(int keyCode);
    static MediaAlbumArt* extractAlbumArt();
    static void setMode(int mode);
    static void release();
    static void create();

private:
    MediaMetadataRetriever() {}
    static MediaMetadataRetrieverImpl *mRetriever;
    static void                       *mLibHandler;
};

}; // namespace android

#endif // MEDIAMETADATARETRIEVER_H
