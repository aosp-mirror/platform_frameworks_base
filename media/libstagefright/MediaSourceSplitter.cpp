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

status_t MediaSourceSplitter::start(int client_id, MetaData *params) {
    Mutex::Autolock autoLock(mLock);

    LOGV("start client (%d)", client_id);
    if (mClientsStarted[client_id]) {
        return OK;
    }

    mNumberOfClientsStarted++;

    if (!mSourceStarted) {
        LOGV("Starting real source from client (%d)", client_id);
        status_t err = mSource->start(params);

        if (err == OK) {
            mSourceStarted = true;
            mClientsStarted.editItemAt(client_id) = true;
            mClientsDesiredReadBit.editItemAt(client_id) = !mCurrentReadBit;
        }

        return err;
    } else {
        mClientsStarted.editItemAt(client_id) = true;
        if (mLastReadCompleted) {
            // Last read was completed. So join in the threads for the next read.
            mClientsDesiredReadBit.editItemAt(client_id) = !mCurrentReadBit;
        } else {
            // Last read is ongoing. So join in the threads for the current read.
            mClientsDesiredReadBit.editItemAt(client_id) = mCurrentReadBit;
        }
        return OK;
    }
}

status_t MediaSourceSplitter::stop(int client_id) {
    Mutex::Autolock autoLock(mLock);

    LOGV("stop client (%d)", client_id);
    CHECK(client_id >= 0 && client_id < mNumberOfClients);
    CHECK(mClientsStarted[client_id]);

    if (--mNumberOfClientsStarted == 0) {
        LOGV("Stopping real source from client (%d)", client_id);
        status_t err = mSource->stop();
        mSourceStarted = false;
        mClientsStarted.editItemAt(client_id) = false;
        return err;
    } else {
        mClientsStarted.editItemAt(client_id) = false;
        if (!mLastReadCompleted) {
            // Other threads may be waiting for all the reads to complete.
            // Signal that the read has been aborted.
            signalReadComplete_lock(true);
        }
        return OK;
    }
}

sp<MetaData> MediaSourceSplitter::getFormat(int client_id) {
    Mutex::Autolock autoLock(mLock);

    LOGV("getFormat client (%d)", client_id);
    return mSource->getFormat();
}

status_t MediaSourceSplitter::read(int client_id,
        MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
    Mutex::Autolock autoLock(mLock);

    CHECK(client_id >= 0 && client_id < mNumberOfClients);

    LOGV("read client (%d)", client_id);
    *buffer = NULL;

    if (!mClientsStarted[client_id]) {
        return OK;
    }

    if (mCurrentReadBit != mClientsDesiredReadBit[client_id]) {
        // Desired buffer has not been read from source yet.

        // If the current client is the special client with client_id = 0
        // then read from source, else wait until the client 0 has finished
        // reading from source.
        if (client_id == 0) {
            // Wait for all client's last read to complete first so as to not
            // corrupt the buffer at mLastReadMediaBuffer.
            waitForAllClientsLastRead_lock(client_id);

            readFromSource_lock(options);
            *buffer = mLastReadMediaBuffer;
        } else {
            waitForReadFromSource_lock(client_id);

            *buffer = mLastReadMediaBuffer;
            (*buffer)->add_ref();
        }
        CHECK(mCurrentReadBit == mClientsDesiredReadBit[client_id]);
    } else {
        // Desired buffer has already been read from source. Use the cached data.
        CHECK(client_id != 0);

        *buffer = mLastReadMediaBuffer;
        (*buffer)->add_ref();
    }

    mClientsDesiredReadBit.editItemAt(client_id) = !mClientsDesiredReadBit[client_id];
    signalReadComplete_lock(false);

    return mLastReadStatus;
}

void MediaSourceSplitter::readFromSource_lock(const MediaSource::ReadOptions *options) {
    mLastReadStatus = mSource->read(&mLastReadMediaBuffer , options);

    mCurrentReadBit = !mCurrentReadBit;
    mLastReadCompleted = false;
    mReadFromSourceCondition.broadcast();
}

void MediaSourceSplitter::waitForReadFromSource_lock(int32_t client_id) {
    mReadFromSourceCondition.wait(mLock);
}

void MediaSourceSplitter::waitForAllClientsLastRead_lock(int32_t client_id) {
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

status_t MediaSourceSplitter::pause(int client_id) {
    return ERROR_UNSUPPORTED;
}

// Client

MediaSourceSplitter::Client::Client(
        sp<MediaSourceSplitter> splitter,
        int32_t client_id) {
    mSplitter = splitter;
    mClient_id = client_id;
}

status_t MediaSourceSplitter::Client::start(MetaData *params) {
    return mSplitter->start(mClient_id, params);
}

status_t MediaSourceSplitter::Client::stop() {
    return mSplitter->stop(mClient_id);
}

sp<MetaData> MediaSourceSplitter::Client::getFormat() {
    return mSplitter->getFormat(mClient_id);
}

status_t MediaSourceSplitter::Client::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    return mSplitter->read(mClient_id, buffer, options);
}

status_t MediaSourceSplitter::Client::pause() {
    return mSplitter->pause(mClient_id);
}

}  // namespace android
