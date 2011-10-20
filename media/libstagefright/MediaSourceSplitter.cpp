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
#define LOG_TAG "MediaSourceSplitter"
#include <utils/Log.h>

#include <media/stagefright/MediaSourceSplitter.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>

namespace android {

MediaSourceSplitter::MediaSourceSplitter(sp<MediaSource> mediaSource) {
    mNumberOfClients = 0;
    mSource = mediaSource;
    mSourceStarted = false;

    mNumberOfClientsStarted = 0;
    mNumberOfCurrentReads = 0;
    mCurrentReadBit = 0;
    mLastReadCompleted = true;
}

MediaSourceSplitter::~MediaSourceSplitter() {
}

sp<MediaSource> MediaSourceSplitter::createClient() {
    Mutex::Autolock autoLock(mLock);

    sp<MediaSource> client = new Client(this, mNumberOfClients++);
    mClientsStarted.push(false);
    mClientsDesiredReadBit.push(0);
    return client;
}

status_t MediaSourceSplitter::start(int clientId, MetaData *params) {
    Mutex::Autolock autoLock(mLock);

    ALOGV("start client (%d)", clientId);
    if (mClientsStarted[clientId]) {
        return OK;
    }

    mNumberOfClientsStarted++;

    if (!mSourceStarted) {
        ALOGV("Starting real source from client (%d)", clientId);
        status_t err = mSource->start(params);

        if (err == OK) {
            mSourceStarted = true;
            mClientsStarted.editItemAt(clientId) = true;
            mClientsDesiredReadBit.editItemAt(clientId) = !mCurrentReadBit;
        }

        return err;
    } else {
        mClientsStarted.editItemAt(clientId) = true;
        if (mLastReadCompleted) {
            // Last read was completed. So join in the threads for the next read.
            mClientsDesiredReadBit.editItemAt(clientId) = !mCurrentReadBit;
        } else {
            // Last read is ongoing. So join in the threads for the current read.
            mClientsDesiredReadBit.editItemAt(clientId) = mCurrentReadBit;
        }
        return OK;
    }
}

status_t MediaSourceSplitter::stop(int clientId) {
    Mutex::Autolock autoLock(mLock);

    ALOGV("stop client (%d)", clientId);
    CHECK(clientId >= 0 && clientId < mNumberOfClients);
    CHECK(mClientsStarted[clientId]);

    if (--mNumberOfClientsStarted == 0) {
        ALOGV("Stopping real source from client (%d)", clientId);
        status_t err = mSource->stop();
        mSourceStarted = false;
        mClientsStarted.editItemAt(clientId) = false;
        return err;
    } else {
        mClientsStarted.editItemAt(clientId) = false;
        if (!mLastReadCompleted && (mClientsDesiredReadBit[clientId] == mCurrentReadBit)) {
            // !mLastReadCompleted implies that buffer has been read from source, but all
            // clients haven't read it.
            // mClientsDesiredReadBit[clientId] == mCurrentReadBit implies that this
            // client would have wanted to read from this buffer. (i.e. it has not yet
            // called read() for the current read buffer.)
            // Since other threads may be waiting for all the clients' reads to complete,
            // signal that this read has been aborted.
            signalReadComplete_lock(true);
        }
        return OK;
    }
}

sp<MetaData> MediaSourceSplitter::getFormat(int clientId) {
    Mutex::Autolock autoLock(mLock);

    ALOGV("getFormat client (%d)", clientId);
    return mSource->getFormat();
}

status_t MediaSourceSplitter::read(int clientId,
        MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
    Mutex::Autolock autoLock(mLock);

    CHECK(clientId >= 0 && clientId < mNumberOfClients);

    ALOGV("read client (%d)", clientId);
    *buffer = NULL;

    if (!mClientsStarted[clientId]) {
        return OK;
    }

    if (mCurrentReadBit != mClientsDesiredReadBit[clientId]) {
        // Desired buffer has not been read from source yet.

        // If the current client is the special client with clientId = 0
        // then read from source, else wait until the client 0 has finished
        // reading from source.
        if (clientId == 0) {
            // Wait for all client's last read to complete first so as to not
            // corrupt the buffer at mLastReadMediaBuffer.
            waitForAllClientsLastRead_lock(clientId);

            readFromSource_lock(options);
            *buffer = mLastReadMediaBuffer;
        } else {
            waitForReadFromSource_lock(clientId);

            *buffer = mLastReadMediaBuffer;
            (*buffer)->add_ref();
        }
        CHECK(mCurrentReadBit == mClientsDesiredReadBit[clientId]);
    } else {
        // Desired buffer has already been read from source. Use the cached data.
        CHECK(clientId != 0);

        *buffer = mLastReadMediaBuffer;
        (*buffer)->add_ref();
    }

    mClientsDesiredReadBit.editItemAt(clientId) = !mClientsDesiredReadBit[clientId];
    signalReadComplete_lock(false);

    return mLastReadStatus;
}

void MediaSourceSplitter::readFromSource_lock(const MediaSource::ReadOptions *options) {
    mLastReadStatus = mSource->read(&mLastReadMediaBuffer , options);

    mCurrentReadBit = !mCurrentReadBit;
    mLastReadCompleted = false;
    mReadFromSourceCondition.broadcast();
}

void MediaSourceSplitter::waitForReadFromSource_lock(int32_t clientId) {
    mReadFromSourceCondition.wait(mLock);
}

void MediaSourceSplitter::waitForAllClientsLastRead_lock(int32_t clientId) {
    if (mLastReadCompleted) {
        return;
    }
    mAllReadsCompleteCondition.wait(mLock);
    CHECK(mLastReadCompleted);
}

void MediaSourceSplitter::signalReadComplete_lock(bool readAborted) {
    if (!readAborted) {
        mNumberOfCurrentReads++;
    }

    if (mNumberOfCurrentReads == mNumberOfClientsStarted) {
        mLastReadCompleted = true;
        mNumberOfCurrentReads = 0;
        mAllReadsCompleteCondition.broadcast();
    }
}

status_t MediaSourceSplitter::pause(int clientId) {
    return ERROR_UNSUPPORTED;
}

// Client

MediaSourceSplitter::Client::Client(
        sp<MediaSourceSplitter> splitter,
        int32_t clientId) {
    mSplitter = splitter;
    mClientId = clientId;
}

status_t MediaSourceSplitter::Client::start(MetaData *params) {
    return mSplitter->start(mClientId, params);
}

status_t MediaSourceSplitter::Client::stop() {
    return mSplitter->stop(mClientId);
}

sp<MetaData> MediaSourceSplitter::Client::getFormat() {
    return mSplitter->getFormat(mClientId);
}

status_t MediaSourceSplitter::Client::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    return mSplitter->read(mClientId, buffer, options);
}

status_t MediaSourceSplitter::Client::pause() {
    return mSplitter->pause(mClientId);
}

}  // namespace android
