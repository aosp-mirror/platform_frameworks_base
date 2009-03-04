/*
**
** Copyright (C) 2008 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MetadataRetrieverClient"
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>

#include <string.h>
#include <cutils/atomic.h>
#include <utils/MemoryDealer.h>
#include <android_runtime/ActivityManager.h>
#include <utils/IPCThreadState.h>
#include <utils/IServiceManager.h>
#include <media/MediaMetadataRetrieverInterface.h>
#include <media/MediaPlayerInterface.h>
#include <media/PVMetadataRetriever.h>
#include <private/media/VideoFrame.h>

#include "MetadataRetrieverClient.h"


namespace android {

MetadataRetrieverClient::MetadataRetrieverClient(pid_t pid)
{
    LOGV("MetadataRetrieverClient constructor pid(%d)", pid);
    mPid = pid;
    mThumbnailDealer = NULL;
    mAlbumArtDealer = NULL;
    mThumbnail = NULL;
    mAlbumArt = NULL;

    mRetriever = new PVMetadataRetriever();
    if (mRetriever == NULL) {
        LOGE("failed to initialize the retriever");
    }
}

MetadataRetrieverClient::~MetadataRetrieverClient()
{
    LOGV("MetadataRetrieverClient destructor");
    disconnect();
}

status_t MetadataRetrieverClient::dump(int fd, const Vector<String16>& args) const
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    result.append(" MetadataRetrieverClient\n");
    snprintf(buffer, 255, "  pid(%d)\n", mPid);
    result.append(buffer);
    write(fd, result.string(), result.size());
    write(fd, "\n", 1);
    return NO_ERROR;
}

void MetadataRetrieverClient::disconnect()
{
    LOGV("disconnect from pid %d", mPid);
    Mutex::Autolock lock(mLock);
    mRetriever.clear();
    mThumbnailDealer.clear();
    mAlbumArtDealer.clear();
    mThumbnail.clear();
    mAlbumArt.clear();
    IPCThreadState::self()->flushCommands();
}

status_t MetadataRetrieverClient::setDataSource(const char *url)
{
    LOGV("setDataSource(%s)", url);
    Mutex::Autolock lock(mLock);
    if (url == NULL) {
        return UNKNOWN_ERROR;
    }
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NO_INIT;
    }
    return mRetriever->setDataSource(url);
}

status_t MetadataRetrieverClient::setDataSource(int fd, int64_t offset, int64_t length)
{
    LOGV("setDataSource fd=%d, offset=%lld, length=%lld", fd, offset, length);
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        ::close(fd);
        return NO_INIT;
    }

    struct stat sb;
    int ret = fstat(fd, &sb);
    if (ret != 0) {
        LOGE("fstat(%d) failed: %d, %s", fd, ret, strerror(errno));
        return UNKNOWN_ERROR;
    }
    LOGV("st_dev  = %llu", sb.st_dev);
    LOGV("st_mode = %u", sb.st_mode);
    LOGV("st_uid  = %lu", sb.st_uid);
    LOGV("st_gid  = %lu", sb.st_gid);
    LOGV("st_size = %llu", sb.st_size);

    if (offset >= sb.st_size) {
        LOGE("offset (%lld) bigger than file size (%llu)", offset, sb.st_size);
        ::close(fd);
        return UNKNOWN_ERROR;
    }
    if (offset + length > sb.st_size) {
        length = sb.st_size - offset;
        LOGE("calculated length = %lld", length);
    }
    status_t status = mRetriever->setDataSource(fd, offset, length);
    ::close(fd);
    return status;
}

status_t MetadataRetrieverClient::setMode(int mode)
{
    LOGV("setMode");
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NO_INIT;
    }
    return mRetriever->setMode(mode);
}

status_t MetadataRetrieverClient::getMode(int* mode) const
{
    LOGV("getMode");
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NO_INIT;
    }
    return mRetriever->getMode(mode);
}

sp<IMemory> MetadataRetrieverClient::captureFrame()
{
    LOGV("captureFrame");
    Mutex::Autolock lock(mLock);
    mThumbnail.clear();
    mThumbnailDealer.clear();
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    VideoFrame *frame = mRetriever->captureFrame();
    if (frame == NULL) {
        LOGE("failed to capture a video frame");
        return NULL;
    }
    size_t size = sizeof(VideoFrame) + frame->mSize;
    mThumbnailDealer = new MemoryDealer(size);
    if (mThumbnailDealer == NULL) {
        LOGE("failed to create MemoryDealer");
        delete frame;
        return NULL;
    }
    mThumbnail = mThumbnailDealer->allocate(size);
    if (mThumbnail == NULL) {
        LOGE("not enough memory for VideoFrame size=%u", size);
        mThumbnailDealer.clear();
        delete frame;
        return NULL;
    }
    VideoFrame *frameCopy = static_cast<VideoFrame *>(mThumbnail->pointer());
    frameCopy->mWidth = frame->mWidth;
    frameCopy->mHeight = frame->mHeight;
    frameCopy->mDisplayWidth = frame->mDisplayWidth;
    frameCopy->mDisplayHeight = frame->mDisplayHeight;
    frameCopy->mSize = frame->mSize;
    frameCopy->mData = (uint8_t *)frameCopy + sizeof(VideoFrame);
    memcpy(frameCopy->mData, frame->mData, frame->mSize);
    delete frame;  // Fix memory leakage
    return mThumbnail;
}

sp<IMemory> MetadataRetrieverClient::extractAlbumArt()
{
    LOGV("extractAlbumArt");
    Mutex::Autolock lock(mLock);
    mAlbumArt.clear();
    mAlbumArtDealer.clear();
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    MediaAlbumArt *albumArt = mRetriever->extractAlbumArt();
    if (albumArt == NULL) {
        LOGE("failed to extract an album art");
        return NULL;
    }
    size_t size = sizeof(MediaAlbumArt) + albumArt->mSize;
    mAlbumArtDealer = new MemoryDealer(size);
    if (mAlbumArtDealer == NULL) {
        LOGE("failed to create MemoryDealer object");
        delete albumArt;
        return NULL;
    }
    mAlbumArt = mAlbumArtDealer->allocate(size);
    if (mAlbumArt == NULL) {
        LOGE("not enough memory for MediaAlbumArt size=%u", size);
        mAlbumArtDealer.clear();
        delete albumArt;
        return NULL;
    }
    MediaAlbumArt *albumArtCopy = static_cast<MediaAlbumArt *>(mAlbumArt->pointer());
    albumArtCopy->mSize = albumArt->mSize;
    albumArtCopy->mData = (uint8_t *)albumArtCopy + sizeof(MediaAlbumArt);
    memcpy(albumArtCopy->mData, albumArt->mData, albumArt->mSize);
    delete albumArt;  // Fix memory leakage
    return mAlbumArt;
}

const char* MetadataRetrieverClient::extractMetadata(int keyCode)
{
    LOGV("extractMetadata");
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    return mRetriever->extractMetadata(keyCode);
}

}; // namespace android
