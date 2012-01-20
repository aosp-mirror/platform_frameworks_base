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
#include <cutils/properties.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <android_runtime/ActivityManager.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <media/MediaMetadataRetrieverInterface.h>
#include <media/MediaPlayerInterface.h>
#include <private/media/VideoFrame.h>
#include "MidiMetadataRetriever.h"
#include "MetadataRetrieverClient.h"
#include "StagefrightMetadataRetriever.h"

namespace android {

extern player_type getPlayerType(const char* url);
extern player_type getPlayerType(int fd, int64_t offset, int64_t length);

MetadataRetrieverClient::MetadataRetrieverClient(pid_t pid)
{
    ALOGV("MetadataRetrieverClient constructor pid(%d)", pid);
    mPid = pid;
    mThumbnail = NULL;
    mAlbumArt = NULL;
    mRetriever = NULL;
}

MetadataRetrieverClient::~MetadataRetrieverClient()
{
    ALOGV("MetadataRetrieverClient destructor");
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
    ALOGV("disconnect from pid %d", mPid);
    Mutex::Autolock lock(mLock);
    mRetriever.clear();
    mThumbnail.clear();
    mAlbumArt.clear();
    IPCThreadState::self()->flushCommands();
}

static sp<MediaMetadataRetrieverBase> createRetriever(player_type playerType)
{
    sp<MediaMetadataRetrieverBase> p;
    switch (playerType) {
        case STAGEFRIGHT_PLAYER:
        {
            p = new StagefrightMetadataRetriever;
            break;
        }
        case SONIVOX_PLAYER:
            ALOGV("create midi metadata retriever");
            p = new MidiMetadataRetriever();
            break;
        default:
            // TODO:
            // support for TEST_PLAYER
            LOGE("player type %d is not supported",  playerType);
            break;
    }
    if (p == NULL) {
        LOGE("failed to create a retriever object");
    }
    return p;
}

status_t MetadataRetrieverClient::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers)
{
    ALOGV("setDataSource(%s)", url);
    Mutex::Autolock lock(mLock);
    if (url == NULL) {
        return UNKNOWN_ERROR;
    }
    player_type playerType = getPlayerType(url);
    ALOGV("player type = %d", playerType);
    sp<MediaMetadataRetrieverBase> p = createRetriever(playerType);
    if (p == NULL) return NO_INIT;
    status_t ret = p->setDataSource(url, headers);
    if (ret == NO_ERROR) mRetriever = p;
    return ret;
}

status_t MetadataRetrieverClient::setDataSource(int fd, int64_t offset, int64_t length)
{
    ALOGV("setDataSource fd=%d, offset=%lld, length=%lld", fd, offset, length);
    Mutex::Autolock lock(mLock);
    struct stat sb;
    int ret = fstat(fd, &sb);
    if (ret != 0) {
        LOGE("fstat(%d) failed: %d, %s", fd, ret, strerror(errno));
        return BAD_VALUE;
    }
    ALOGV("st_dev  = %llu", sb.st_dev);
    ALOGV("st_mode = %u", sb.st_mode);
    ALOGV("st_uid  = %lu", sb.st_uid);
    ALOGV("st_gid  = %lu", sb.st_gid);
    ALOGV("st_size = %llu", sb.st_size);

    if (offset >= sb.st_size) {
        LOGE("offset (%lld) bigger than file size (%llu)", offset, sb.st_size);
        ::close(fd);
        return BAD_VALUE;
    }
    if (offset + length > sb.st_size) {
        length = sb.st_size - offset;
        ALOGV("calculated length = %lld", length);
    }

    player_type playerType = getPlayerType(fd, offset, length);
    ALOGV("player type = %d", playerType);
    sp<MediaMetadataRetrieverBase> p = createRetriever(playerType);
    if (p == NULL) {
        ::close(fd);
        return NO_INIT;
    }
    status_t status = p->setDataSource(fd, offset, length);
    if (status == NO_ERROR) mRetriever = p;
    ::close(fd);
    return status;
}

sp<IMemory> MetadataRetrieverClient::getFrameAtTime(int64_t timeUs, int option)
{
    ALOGV("getFrameAtTime: time(%lld us) option(%d)", timeUs, option);
    Mutex::Autolock lock(mLock);
    mThumbnail.clear();
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    VideoFrame *frame = mRetriever->getFrameAtTime(timeUs, option);
    if (frame == NULL) {
        LOGE("failed to capture a video frame");
        return NULL;
    }
    size_t size = sizeof(VideoFrame) + frame->mSize;
    sp<MemoryHeapBase> heap = new MemoryHeapBase(size, 0, "MetadataRetrieverClient");
    if (heap == NULL) {
        LOGE("failed to create MemoryDealer");
        delete frame;
        return NULL;
    }
    mThumbnail = new MemoryBase(heap, 0, size);
    if (mThumbnail == NULL) {
        LOGE("not enough memory for VideoFrame size=%u", size);
        delete frame;
        return NULL;
    }
    VideoFrame *frameCopy = static_cast<VideoFrame *>(mThumbnail->pointer());
    frameCopy->mWidth = frame->mWidth;
    frameCopy->mHeight = frame->mHeight;
    frameCopy->mDisplayWidth = frame->mDisplayWidth;
    frameCopy->mDisplayHeight = frame->mDisplayHeight;
    frameCopy->mSize = frame->mSize;
    frameCopy->mRotationAngle = frame->mRotationAngle;
    ALOGV("rotation: %d", frameCopy->mRotationAngle);
    frameCopy->mData = (uint8_t *)frameCopy + sizeof(VideoFrame);
    memcpy(frameCopy->mData, frame->mData, frame->mSize);
    delete frame;  // Fix memory leakage
    return mThumbnail;
}

sp<IMemory> MetadataRetrieverClient::extractAlbumArt()
{
    ALOGV("extractAlbumArt");
    Mutex::Autolock lock(mLock);
    mAlbumArt.clear();
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
    sp<MemoryHeapBase> heap = new MemoryHeapBase(size, 0, "MetadataRetrieverClient");
    if (heap == NULL) {
        LOGE("failed to create MemoryDealer object");
        delete albumArt;
        return NULL;
    }
    mAlbumArt = new MemoryBase(heap, 0, size);
    if (mAlbumArt == NULL) {
        LOGE("not enough memory for MediaAlbumArt size=%u", size);
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
    ALOGV("extractMetadata");
    Mutex::Autolock lock(mLock);
    if (mRetriever == NULL) {
        LOGE("retriever is not initialized");
        return NULL;
    }
    return mRetriever->extractMetadata(keyCode);
}

}; // namespace android
