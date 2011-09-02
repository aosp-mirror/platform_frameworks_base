/*
 * Copyright (C) 2010 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "stream"
#include "utils/Log.h"

#include <binder/ProcessState.h>

#include <media/IStreamSource.h>
#include <media/mediaplayer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MPEG2TSWriter.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#include <binder/IServiceManager.h>
#include <media/IMediaPlayerService.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <fcntl.h>

using namespace android;

struct MyStreamSource : public BnStreamSource {
    // Object assumes ownership of fd.
    MyStreamSource(int fd);

    virtual void setListener(const sp<IStreamListener> &listener);
    virtual void setBuffers(const Vector<sp<IMemory> > &buffers);

    virtual void onBufferAvailable(size_t index);

protected:
    virtual ~MyStreamSource();

private:
    int mFd;
    off64_t mFileSize;
    uint64_t mNumPacketsSent;

    sp<IStreamListener> mListener;
    Vector<sp<IMemory> > mBuffers;

    DISALLOW_EVIL_CONSTRUCTORS(MyStreamSource);
};

MyStreamSource::MyStreamSource(int fd)
    : mFd(fd),
      mFileSize(0),
      mNumPacketsSent(0) {
    CHECK_GE(fd, 0);

    mFileSize = lseek64(fd, 0, SEEK_END);
    lseek64(fd, 0, SEEK_SET);
}

MyStreamSource::~MyStreamSource() {
    close(mFd);
    mFd = -1;
}

void MyStreamSource::setListener(const sp<IStreamListener> &listener) {
    mListener = listener;
}

void MyStreamSource::setBuffers(const Vector<sp<IMemory> > &buffers) {
    mBuffers = buffers;
}

void MyStreamSource::onBufferAvailable(size_t index) {
    CHECK_LT(index, mBuffers.size());

#if 0
    if (mNumPacketsSent >= 20000) {
        LOGI("signalling discontinuity now");

        off64_t offset = 0;
        CHECK((offset % 188) == 0);

        lseek(mFd, offset, SEEK_SET);

        sp<AMessage> extra = new AMessage;
        extra->setInt32(IStreamListener::kKeyFormatChange, 0);

        mListener->issueCommand(
                IStreamListener::DISCONTINUITY, false /* synchronous */, extra);

        mNumPacketsSent = 0;
    }
#endif

    sp<IMemory> mem = mBuffers.itemAt(index);

    ssize_t n = read(mFd, mem->pointer(), mem->size());
    if (n <= 0) {
        mListener->issueCommand(IStreamListener::EOS, false /* synchronous */);
    } else {
        mListener->queueBuffer(index, n);

        mNumPacketsSent += n / 188;
    }
}
////////////////////////////////////////////////////////////////////////////////

struct MyConvertingStreamSource : public BnStreamSource {
    MyConvertingStreamSource(const char *filename);

    virtual void setListener(const sp<IStreamListener> &listener);
    virtual void setBuffers(const Vector<sp<IMemory> > &buffers);

    virtual void onBufferAvailable(size_t index);

protected:
    virtual ~MyConvertingStreamSource();

private:
    Mutex mLock;
    Condition mCondition;

    sp<IStreamListener> mListener;
    Vector<sp<IMemory> > mBuffers;

    sp<MPEG2TSWriter> mWriter;

    ssize_t mCurrentBufferIndex;
    size_t mCurrentBufferOffset;

    List<size_t> mBufferQueue;

    static ssize_t WriteDataWrapper(void *me, const void *data, size_t size);
    ssize_t writeData(const void *data, size_t size);

    DISALLOW_EVIL_CONSTRUCTORS(MyConvertingStreamSource);
};

////////////////////////////////////////////////////////////////////////////////

MyConvertingStreamSource::MyConvertingStreamSource(const char *filename)
    : mCurrentBufferIndex(-1),
      mCurrentBufferOffset(0) {
    sp<DataSource> dataSource = DataSource::CreateFromURI(filename);
    CHECK(dataSource != NULL);

    sp<MediaExtractor> extractor = MediaExtractor::Create(dataSource);
    CHECK(extractor != NULL);

    mWriter = new MPEG2TSWriter(
            this, &MyConvertingStreamSource::WriteDataWrapper);

    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        const sp<MetaData> &meta = extractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (strncasecmp("video/", mime, 6) && strncasecmp("audio/", mime, 6)) {
            continue;
        }

        CHECK_EQ(mWriter->addSource(extractor->getTrack(i)), (status_t)OK);
    }

    CHECK_EQ(mWriter->start(), (status_t)OK);
}

MyConvertingStreamSource::~MyConvertingStreamSource() {
}

void MyConvertingStreamSource::setListener(
        const sp<IStreamListener> &listener) {
    mListener = listener;
}

void MyConvertingStreamSource::setBuffers(
        const Vector<sp<IMemory> > &buffers) {
    mBuffers = buffers;
}

ssize_t MyConvertingStreamSource::WriteDataWrapper(
        void *me, const void *data, size_t size) {
    return static_cast<MyConvertingStreamSource *>(me)->writeData(data, size);
}

ssize_t MyConvertingStreamSource::writeData(const void *data, size_t size) {
    size_t totalWritten = 0;

    while (size > 0) {
        Mutex::Autolock autoLock(mLock);

        if (mCurrentBufferIndex < 0) {
            while (mBufferQueue.empty()) {
                mCondition.wait(mLock);
            }

            mCurrentBufferIndex = *mBufferQueue.begin();
            mCurrentBufferOffset = 0;

            mBufferQueue.erase(mBufferQueue.begin());
        }

        sp<IMemory> mem = mBuffers.itemAt(mCurrentBufferIndex);

        size_t copy = size;
        if (copy + mCurrentBufferOffset > mem->size()) {
            copy = mem->size() - mCurrentBufferOffset;
        }

        memcpy((uint8_t *)mem->pointer() + mCurrentBufferOffset, data, copy);
        mCurrentBufferOffset += copy;

        if (mCurrentBufferOffset == mem->size()) {
            mListener->queueBuffer(mCurrentBufferIndex, mCurrentBufferOffset);
            mCurrentBufferIndex = -1;
        }

        data = (const uint8_t *)data + copy;
        size -= copy;

        totalWritten += copy;
    }

    return (ssize_t)totalWritten;
}

void MyConvertingStreamSource::onBufferAvailable(size_t index) {
    Mutex::Autolock autoLock(mLock);

    mBufferQueue.push_back(index);
    mCondition.signal();

    if (mWriter->reachedEOS()) {
        if (mCurrentBufferIndex >= 0) {
            mListener->queueBuffer(mCurrentBufferIndex, mCurrentBufferOffset);
            mCurrentBufferIndex = -1;
        }

        mListener->issueCommand(IStreamListener::EOS, false /* synchronous */);
    }
}

////////////////////////////////////////////////////////////////////////////////

struct MyClient : public BnMediaPlayerClient {
    MyClient()
        : mEOS(false) {
    }

    virtual void notify(int msg, int ext1, int ext2, const Parcel *obj) {
        Mutex::Autolock autoLock(mLock);

        if (msg == MEDIA_ERROR || msg == MEDIA_PLAYBACK_COMPLETE) {
            mEOS = true;
            mCondition.signal();
        }
    }

    void waitForEOS() {
        Mutex::Autolock autoLock(mLock);
        while (!mEOS) {
            mCondition.wait(mLock);
        }
    }

protected:
    virtual ~MyClient() {
    }

private:
    Mutex mLock;
    Condition mCondition;

    bool mEOS;

    DISALLOW_EVIL_CONSTRUCTORS(MyClient);
};

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    DataSource::RegisterDefaultSniffers();

    if (argc != 2) {
        fprintf(stderr, "Usage: %s filename\n", argv[0]);
        return 1;
    }

    sp<SurfaceComposerClient> composerClient = new SurfaceComposerClient;
    CHECK_EQ(composerClient->initCheck(), (status_t)OK);

    ssize_t displayWidth = composerClient->getDisplayWidth(0);
    ssize_t displayHeight = composerClient->getDisplayHeight(0);

    LOGV("display is %d x %d\n", displayWidth, displayHeight);

    sp<SurfaceControl> control =
        composerClient->createSurface(
                String8("A Surface"),
                0,
                displayWidth,
                displayHeight,
                PIXEL_FORMAT_RGB_565,
                0);

    CHECK(control != NULL);
    CHECK(control->isValid());

    SurfaceComposerClient::openGlobalTransaction();
    CHECK_EQ(control->setLayer(30000), (status_t)OK);
    CHECK_EQ(control->show(), (status_t)OK);
    SurfaceComposerClient::closeGlobalTransaction();

    sp<Surface> surface = control->getSurface();
    CHECK(surface != NULL);

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

    CHECK(service.get() != NULL);

    sp<MyClient> client = new MyClient;

    sp<IStreamSource> source;

    size_t len = strlen(argv[1]);
    if (len >= 3 && !strcasecmp(".ts", &argv[1][len - 3])) {
        int fd = open(argv[1], O_RDONLY);

        if (fd < 0) {
            fprintf(stderr, "Failed to open file '%s'.", argv[1]);
            return 1;
        }

        source = new MyStreamSource(fd);
    } else {
        printf("Converting file to transport stream for streaming...\n");

        source = new MyConvertingStreamSource(argv[1]);
    }

    sp<IMediaPlayer> player =
        service->create(getpid(), client, 0);

    if (player != NULL && player->setDataSource(source) == NO_ERROR) {
        player->setVideoSurface(surface);
        player->start();

        client->waitForEOS();

        player->stop();
    } else {
        fprintf(stderr, "failed to instantiate player.\n");
    }

    composerClient->dispose();

    return 0;
}
