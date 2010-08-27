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

// This class provides a way to split a single media source into multiple sources.
// The constructor takes in the real mediaSource and createClient() can then be
// used to create multiple sources served from this real mediaSource.
//
// Usage:
// - Create MediaSourceSplitter by passing in a real mediaSource from which
// multiple duplicate channels are needed.
// - Create a client using createClient() and use it as any other mediaSource.
//
// Note that multiple clients can be created using createClient() and
// started/stopped in any order. MediaSourceSplitter stops the real source only
// when all clients have been stopped.
//
// If a new client is created/started after some existing clients have already
// started, the new client will start getting its read frames from the current
// time.

#ifndef MEDIA_SOURCE_SPLITTER_H_

#define MEDIA_SOURCE_SPLITTER_H_

#include <media/stagefright/MediaSource.h>
#include <utils/threads.h>
#include <utils/Vector.h>
#include <utils/RefBase.h>

namespace android {

class MediaBuffer;
class MetaData;

class MediaSourceSplitter : public RefBase {
public:
    // Constructor
    // mediaSource: The real mediaSource. The class keeps a reference to it to
    // implement the various clients.
    MediaSourceSplitter(sp<MediaSource> mediaSource);

    ~MediaSourceSplitter();

    // Creates a new client of base type MediaSource. Multiple clients can be
    // created which get their data through the same real mediaSource. These
    // clients can then be used like any other MediaSource, all of which provide
    // data from the same real source.
    sp<MediaSource> createClient();

private:
    // Total number of clients created through createClient().
    int32_t mNumberOfClients;

    // reference to the real MediaSource passed to the constructor.
    sp<MediaSource> mSource;

    // Stores pointer to the MediaBuffer read from the real MediaSource.
    // All clients use this to implement the read() call.
    MediaBuffer *mLastReadMediaBuffer;

    // Status code for read from the real MediaSource. All clients return
    // this for their read().
    status_t mLastReadStatus;

    // Boolean telling whether the real MediaSource has started.
    bool mSourceStarted;

    // List of booleans, one for each client, storing whether the corresponding
    // client's start() has been called.
    Vector<bool> mClientsStarted;

    // Stores the number of clients which are currently started.
    int32_t mNumberOfClientsStarted;

    // Since different clients call read() asynchronously, we need to keep track
    // of what data is currently read into the mLastReadMediaBuffer.
    // mCurrentReadBit stores the bit for the current read buffer. This bit
    // flips each time a new buffer is read from the source.
    // mClientsDesiredReadBit stores the bit for the next desired read buffer
    // for each client. This bit flips each time read() is completed for this
    // client.
    bool mCurrentReadBit;
    Vector<bool> mClientsDesiredReadBit;

    // Number of clients whose current read has been completed.
    int32_t mNumberOfCurrentReads;

    // Boolean telling whether the last read has been completed for all clients.
    // The variable is reset to false each time buffer is read from the real
    // source.
    bool mLastReadCompleted;

    // A global mutex for access to critical sections.
    Mutex mLock;

    // Condition variable for waiting on read from source to complete.
    Condition mReadFromSourceCondition;

    // Condition variable for waiting on all client's last read to complete.
    Condition mAllReadsCompleteCondition;

    // Functions used by Client to implement the MediaSource interface.

    // If the real source has not been started yet by any client, starts it.
    status_t start(int clientId, MetaData *params);

    // Stops the real source after all clients have called stop().
    status_t stop(int clientId);

    // returns the real source's getFormat().
    sp<MetaData> getFormat(int clientId);

    // If the client's desired buffer has already been read into
    // mLastReadMediaBuffer, points the buffer to that. Otherwise if it is the
    // master client, reads the buffer from source or else waits for the master
    // client to read the buffer and uses that.
    status_t read(int clientId,
            MediaBuffer **buffer, const MediaSource::ReadOptions *options = NULL);

    // Not implemented right now.
    status_t pause(int clientId);

    // Function which reads a buffer from the real source into
    // mLastReadMediaBuffer
    void readFromSource_lock(const MediaSource::ReadOptions *options);

    // Waits until read from the real source has been completed.
    // _lock means that the function should be called when the thread has already
    // obtained the lock for the mutex mLock.
    void waitForReadFromSource_lock(int32_t clientId);

    // Waits until all clients have read the current buffer in
    // mLastReadCompleted.
    void waitForAllClientsLastRead_lock(int32_t clientId);

    // Each client calls this after it completes its read(). Once all clients
    // have called this for the current buffer, the function calls
    // mAllReadsCompleteCondition.broadcast() to signal the waiting clients.
    void signalReadComplete_lock(bool readAborted);

    // Make these constructors private.
    MediaSourceSplitter();
    MediaSourceSplitter(const MediaSourceSplitter &);
    MediaSourceSplitter &operator=(const MediaSourceSplitter &);

    // This class implements the MediaSource interface. Each client stores a
    // reference to the parent MediaSourceSplitter and uses it to complete the
    // various calls.
    class Client : public MediaSource {
    public:
        // Constructor stores reference to the parent MediaSourceSplitter and it
        // client id.
        Client(sp<MediaSourceSplitter> splitter, int32_t clientId);

        // MediaSource interface
        virtual status_t start(MetaData *params = NULL);

        virtual status_t stop();

        virtual sp<MetaData> getFormat();

        virtual status_t read(
                MediaBuffer **buffer, const ReadOptions *options = NULL);

        virtual status_t pause();

    private:
        // Refernce to the parent MediaSourceSplitter
        sp<MediaSourceSplitter> mSplitter;

        // Id of this client.
        int32_t mClientId;
    };

    friend class Client;
};

}  // namespace android

#endif  // MEDIA_SOURCE_SPLITTER_H_
