/*
**
** Copyright 2008, The Android Open Source Project
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

#include <media/mediametadataretriever.h>

#ifdef LOG_TAG
#undef LOG_TAG
#define LOG_TAG "MediaMetadataRetriever"
#endif

#include <utils/Log.h>
#include <dlfcn.h>

namespace android {

// Factory class function in shared libpvplayer.so
typedef MediaMetadataRetrieverImpl* (*createRetriever_f)();

MediaMetadataRetrieverImpl *MediaMetadataRetriever::mRetriever = NULL;
void                       *MediaMetadataRetriever::mLibHandler = NULL;

void MediaMetadataRetriever::create()
{
    // Load libpvplayer library once and only once.
    if (!mLibHandler) {
        mLibHandler = dlopen("libopencoreplayer.so", RTLD_NOW);
        if (!mLibHandler) {
            LOGE("setDataSource: dlopen failed on libopencoreplayer.so");
            return;
        }
    }
    
    // Each time create a new MediaMetadataRetrieverImpl object.
    if (mRetriever) {
        delete mRetriever;
    }
    createRetriever_f createRetriever = reinterpret_cast<createRetriever_f>(dlsym(mLibHandler, "createRetriever"));
    if (!createRetriever) {
        LOGE("setDataSource: dlsym failed on createRetriever in libpvplayer.so");
        return;
    }
    mRetriever = createRetriever();
    if (!mRetriever) {
        LOGE("setDataSource: createRetriever failed in libpvplayer.so");
    }
}

status_t MediaMetadataRetriever::setDataSource(const char* srcUrl)
{
    if (srcUrl == NULL) {
        return UNKNOWN_ERROR;
    }
    
    if (mRetriever) {
        return mRetriever->setDataSource(srcUrl);
    }
    return UNKNOWN_ERROR;
}

const char* MediaMetadataRetriever::extractMetadata(int keyCode)
{
    if (mRetriever) {
        return mRetriever->extractMetadata(keyCode);
    }
    return NULL;
}

MediaAlbumArt* MediaMetadataRetriever::extractAlbumArt()
{
    if (mRetriever) {
        return mRetriever->extractAlbumArt();
    }
    return NULL;
}

SkBitmap* MediaMetadataRetriever::captureFrame()
{
    if (mRetriever) {
        return mRetriever->captureFrame();
    }
    return NULL;
}

void MediaMetadataRetriever::setMode(int mode)
{
    if (mRetriever) {
        mRetriever->setMode(mode);
    }
}

void MediaMetadataRetriever::release()
{
    if (!mLibHandler) {
        dlclose(mLibHandler);
        mLibHandler = NULL;
    }
    if (!mRetriever) {
        delete mRetriever;
        mRetriever = NULL;
    }
}

void MediaAlbumArt::clearData() {
    if (data != NULL) {
        delete []data;
        data = NULL;
    }
    length = 0;
}


MediaAlbumArt::MediaAlbumArt(const char* url)
{
    length = 0;
    data = NULL;
    FILE *in = fopen(url, "r");
    if (!in) {
        LOGE("extractExternalAlbumArt: Failed to open external album art url: %s.", url);
        return;
    }
    fseek(in, 0, SEEK_END);
    length = ftell(in);  // Allocating buffer of size equals to the external file size.
    if (length == 0 || (data = new char[length]) == NULL) {
        if (length == 0) {
            LOGE("extractExternalAlbumArt: External album art url: %s has a size of 0.", url);
        } else if (data == NULL) {
            LOGE("extractExternalAlbumArt: No enough memory for storing the retrieved album art.");
            length = 0;
        }
        fclose(in);
        return;
    }
    rewind(in);
    if (fread(data, 1, length, in) != length) {  // Read failed.
        length = 0;
        delete []data;
        data = NULL;
        LOGE("extractExternalAlbumArt: Failed to retrieve the contents of an external album art.");
    }
    fclose(in);
}

status_t MediaAlbumArt::setData(unsigned int len, const char* buf) {
    clearData();
    length = len;
    data = copyData(len, buf);
    return (data != NULL)? OK: UNKNOWN_ERROR;
}

char* MediaAlbumArt::copyData(unsigned int len, const char* buf) {
    if (len == 0 || !buf) {
        if (len == 0) {
            LOGE("copyData: Length is 0.");
        } else if (!buf) {
            LOGE("copyData: buf is NULL pointer");
        }
        return NULL;
    }
    char* copy = new char[len];
    if (!copy) {
        LOGE("copyData: No enough memory to copy out the data.");
        return NULL;
    }
    memcpy(copy, buf, len);
    return copy;
}

}; // namespace android
