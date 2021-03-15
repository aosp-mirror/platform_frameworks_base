/*
 * Copyright (C) 2019 The Android Open Source Project
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

#pragma once

#include "Stream.h"

#include <condition_variable>
#include <future>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include <utils/AndroidThreads.h>

namespace android::soundpool {

// TODO: Move helper classes to a utility file, with separate test.

/**
 * JavaThread is used like std::thread but for threads that may call the JVM.
 *
 * std::thread does not easily attach to the JVM.  We need JVM capable threads
 * from createThreadEtc() since android binder call optimization may attempt to
 * call back into Java if the SoundPool runs in system server.
 *
 *
 * No locking is required - the member variables are inherently thread-safe.
 */
class JavaThread {
public:
    JavaThread(std::function<void()> f, const char *name)
        : mF{std::move(f)} {
        createThreadEtc(staticFunction, this, name);
    }

    JavaThread(JavaThread &&) = delete; // uses "this" ptr, not moveable.

    ~JavaThread() {
        join(); // manually block until the future is ready as std::future
                // destructor doesn't block unless it comes from std::async
                // and it is the last reference to shared state.
    }

    void join() const {
        mFuture.wait();
    }

    bool isClosed() const {
        return mIsClosed;
    }

private:
    static int staticFunction(void *data) {
        JavaThread *jt = static_cast<JavaThread *>(data);
        jt->mF();
        jt->mIsClosed = true;  // set the flag that we are closed
                               // now before we allow the destructor to execute;
                               // otherwise there may be a use after free.
        jt->mPromise.set_value();
        return 0;
    }

    // No locking is provided as these variables are initialized in the constructor
    // and the members referenced are thread-safe objects.
    // (mFuture.wait() can block multiple threads.)
    // Note the order of member variables is reversed for destructor.
    const std::function<void()> mF;
    // Used in join() to block until the thread completes.
    // See https://en.cppreference.com/w/cpp/thread/promise for the void specialization of
    // promise.
    std::promise<void>          mPromise;
    std::future<void>           mFuture{mPromise.get_future()};
    std::atomic_bool            mIsClosed = false;
};

/**
 * The ThreadPool manages thread lifetimes of SoundPool worker threads.
 *
 * TODO: the (eventual) goal of ThreadPool is to transparently and cooperatively
 * maximize CPU utilization while avoiding starvation of other applications.
 * Some possibilities:
 *
 * We should create worker threads when we have SoundPool work and the system is idle.
 * CPU cycles are "use-it-or-lose-it" when the system is idle.
 *
 * We should adjust the priority of worker threads so that the second (and subsequent) worker
 * threads have lower priority (should we try to promote priority also?).
 *
 * We should throttle the spawning of new worker threads, spacing over time, to avoid
 * creating too many new threads all at once, on initialization.
 */
class ThreadPool {
public:
    ThreadPool(size_t maxThreadCount, std::string name)
        : mMaxThreadCount(maxThreadCount)
        , mName{std::move(name)} { }

    ~ThreadPool() { quit(); }

    size_t getActiveThreadCount() const { return mActiveThreadCount; }
    size_t getMaxThreadCount() const { return mMaxThreadCount; }

    void quit() {
        std::list<std::unique_ptr<JavaThread>> threads;
        {
            std::lock_guard lock(mThreadLock);
            if (mQuit) return;  // already joined.
            mQuit = true;
            threads = std::move(mThreads);
            mThreads.clear();
        }
        // mQuit set under lock, no more threads will be created.
        for (auto &thread : threads) {
            thread->join();
            thread.reset();
        }
        LOG_ALWAYS_FATAL_IF(mActiveThreadCount != 0,
                "Invalid Active Threads: %zu", (size_t)mActiveThreadCount);
    }

    // returns a non-zero id if successful, the id is to help logging messages.
    int32_t launch(std::function<void(int32_t /* id */)> f) {
        std::list<std::unique_ptr<JavaThread>> threadsToRelease; // release outside of lock.
        std::lock_guard lock(mThreadLock);
        if (mQuit) return 0;  // ignore if we have quit

        // clean up threads.
        for (auto it = mThreads.begin(); it != mThreads.end(); ) {
            if ((*it)->isClosed()) {
                threadsToRelease.emplace_back(std::move(*it));
               it = mThreads.erase(it);
            } else {
               ++it;
            }
        }

        const size_t threadCount = mThreads.size();
        if (threadCount < mMaxThreadCount) {
            // if the id wraps, we don't care about collisions.  it's just for logging.
            mNextThreadId = mNextThreadId == INT32_MAX ? 1 : ++mNextThreadId;
            const int32_t id = mNextThreadId;
            mThreads.emplace_back(std::make_unique<JavaThread>(
                    [this, id, mf = std::move(f)] { mf(id); --mActiveThreadCount; },
                    (mName + std::to_string(id)).c_str()));
            ++mActiveThreadCount;
            return id;
        }
        return 0;
    }

    // TODO: launch only if load average is low.
    // This gets the load average
    // See also std::thread::hardware_concurrency() for the concurrent capability.
    static double getLoadAvg() {
        double loadAvg[1];
        if (getloadavg(loadAvg, std::size(loadAvg)) > 0) {
            return loadAvg[0];
        }
        return -1.;
    }

private:
    const size_t            mMaxThreadCount;
    const std::string       mName;

    std::atomic_size_t      mActiveThreadCount = 0;

    std::mutex              mThreadLock;
    bool                    mQuit GUARDED_BY(mThreadLock) = false;
    int32_t                 mNextThreadId GUARDED_BY(mThreadLock) = 0;
    std::list<std::unique_ptr<JavaThread>> mThreads GUARDED_BY(mThreadLock);
};

/**
 * A Perfect HashTable for IDs (key) to pointers (value).
 *
 * There are no collisions.  Why? because we generate the IDs for you to look up :-).
 *
 * The goal of this hash table is to map an integer ID handle > 0 to a pointer.
 * We give these IDs in monotonic order (though we may skip if it were to cause a collision).
 *
 * The size of the hashtable must be large enough to accommodate the max number of keys.
 * We suggest 2x.
 *
 * Readers are lockless
 * Single writer could be lockless, but we allow multiple writers through an internal lock.
 *
 * For the Key type K, valid keys generated are > 0 (signed or unsigned)
 * For the Value type V, values are pointers - nullptr means empty.
 */
template <typename K, typename V>
class PerfectHash {
public:
    PerfectHash(size_t hashCapacity)
        : mHashCapacity(hashCapacity)
        , mK2V{new std::atomic<V>[hashCapacity]()} {
    }

    // Generate a key for a value V.
    // There is a testing function getKforV() which checks what the value reports as its key.
    //
    // Calls back into getKforV under lock.
    //
    // We expect that the hashCapacity is 2x the number of stored keys in order
    // to have one or two tries to find an empty slot
    K generateKey(V value, std::function<K(V)> getKforV, K oldKey = 0) {
        std::lock_guard lock(mHashLock);
        // try to remove the old key.
        if (oldKey > 0) {  // key valid
            const V v = getValue(oldKey);
            if (v != nullptr) {  // value still valid
                const K atPosition = getKforV(v);
                if (atPosition < 0 ||            // invalid value
                        atPosition == oldKey ||  // value's key still valid and matches old key
                        ((atPosition ^ oldKey) & (mHashCapacity - 1)) != 0) { // stale key entry
                    getValue(oldKey) = nullptr;  // invalidate
                }
            } // else if value is invalid, no need to invalidate.
        }
        // check if we are invalidating only.
        if (value == nullptr) return 0;
        // now insert the new value and return the key.
        size_t tries = 0;
        for (; tries < mHashCapacity; ++tries) {
            mNextKey = mNextKey == std::numeric_limits<K>::max() ? 1 : mNextKey + 1;
            const V v = getValue(mNextKey);
            //ALOGD("tries: %zu, key:%d value:%p", tries, (int)mNextKey, v);
            if (v == nullptr) break; // empty
            const K atPosition = getKforV(v);
            //ALOGD("tries: %zu  key atPosition:%d", tries, (int)atPosition);
            if (atPosition < 0 || // invalid value
                    ((atPosition ^ mNextKey) & (mHashCapacity - 1)) != 0) { // stale key entry
                break;
           }
        }
        LOG_ALWAYS_FATAL_IF(tries == mHashCapacity, "hash table overflow!");
        //ALOGD("%s: found after %zu tries", __func__, tries);
        getValue(mNextKey) = value;
        return mNextKey;
    }

    std::atomic<V> &getValue(K key) { return mK2V[key & (mHashCapacity - 1)]; }
    const std::atomic_int32_t &getValue(K key) const { return mK2V[key & (mHashCapacity - 1)]; }

private:
    mutable std::mutex          mHashLock;
    const size_t                mHashCapacity; // size of mK2V no lock needed.
    std::unique_ptr<std::atomic<V>[]> mK2V;    // no lock needed for read access.
    K                           mNextKey GUARDED_BY(mHashLock) {};
};

/**
 * StreamMap contains the all the valid streams available to SoundPool.
 *
 * There is no Lock required for this class because the streams are
 * allocated in the constructor, the lookup is lockless, and the Streams
 * returned are locked internally.
 *
 * The lookup uses a perfect hash.
 * It is possible to use a lockless hash table or to use a stripe-locked concurrent
 * hashmap for essentially lock-free lookup.
 *
 * This follows Map-Reduce parallelism model.
 * https://en.wikipedia.org/wiki/MapReduce
 *
 * Conceivably the forEach could be parallelized using std::for_each with a
 * std::execution::par policy.
 *
 * https://en.cppreference.com/w/cpp/algorithm/for_each
 */
class StreamMap {
public:
    explicit StreamMap(int32_t streams);

    // Returns the stream associated with streamID or nullptr if not found.
    // This need not be locked.
    // The stream ID will never migrate to another Stream, but it may change
    // underneath you.  The Stream operations that take a streamID will confirm
    // that the streamID matches under the Stream lock before executing otherwise
    // it ignores the command as stale.
    Stream* findStream(int32_t streamID) const;

    // Iterates through the stream pool applying the function f.
    // Since this enumerates over every single stream, it is unlocked.
    //
    // See related: https://en.cppreference.com/w/cpp/algorithm/for_each
    void forEach(std::function<void(const Stream *)>f) const {
        for (size_t i = 0; i < mStreamPoolSize; ++i) {
            f(&mStreamPool[i]);
        }
    }

    void forEach(std::function<void(Stream *)>f) {
        for (size_t i = 0; i < mStreamPoolSize; ++i) {
            f(&mStreamPool[i]);
        }
    }

    // Returns the pair stream for a given Stream.
    // This need not be locked as it is a property of the pointer address.
    Stream* getPairStream(const Stream* stream) const {
        const size_t index = streamPosition(stream);
        return &mStreamPool[index ^ 1];
    }

    // find the position of the stream in mStreamPool array.
    size_t streamPosition(const Stream* stream) const; // no lock needed

    size_t getStreamMapSize() const {
        return mStreamPoolSize;
    }

    // find the next valid ID for a stream and store in hash table.
    int32_t getNextIdForStream(Stream* stream) const;

private:

    // use the hash table to attempt to find the stream.
    // nullptr is returned if the lookup fails.
    Stream* lookupStreamFromId(int32_t streamID) const;

    // The stream pool is initialized in the constructor, effectively const.
    // no locking required for access.
    //
    // The constructor parameter "streams" results in streams pairs of streams.
    // We have twice as many streams because we wish to return a streamID "handle"
    // back to the app immediately, while we may be stopping the other stream in the
    // pair to get its AudioTrack :-).
    //
    // Of the stream pair, only one of the streams may have an AudioTrack.
    // The fixed association of a stream pair allows callbacks from the AudioTrack
    // to be associated properly to either one or the other of the stream pair.
    //
    // TODO: The stream pair arrangement can be removed if we have better AudioTrack
    // callback handling (being able to remove and change the callback after construction).
    //
    // Streams may be accessed anytime off of the stream pool
    // as there is internal locking on each stream.
    std::unique_ptr<Stream[]>   mStreamPool;        // no lock needed for access.
    size_t                      mStreamPoolSize;    // no lock needed for access.

    // In order to find the Stream from a StreamID, we could do a linear lookup in mStreamPool.
    // As an alternative, one could use stripe-locked or lock-free concurrent hashtables.
    //
    // When considering linear search vs hashmap, verify the typical use-case size.
    // Linear search is faster than std::unordered_map (circa 2018) for less than 40 elements.
    // [ Skarupke, M. (2018), "You Can Do Better than std::unordered_map: New and Recent
    // Improvements to Hash Table Performance." C++Now 2018. cppnow.org, see
    // https://www.youtube.com/watch?v=M2fKMP47slQ ]
    //
    // Here, we use a PerfectHash of Id to Stream *, since we can control the
    // StreamID returned to the user.  This allows O(1) read access to mStreamPool lock-free.
    //
    // We prefer that the next stream ID is monotonic for aesthetic reasons
    // (if we didn't care about monotonicity, a simple method is to apply a generation count
    // to each stream in the unused upper bits of its index in mStreamPool for the id).
    //
    std::unique_ptr<PerfectHash<int32_t, Stream *>> mPerfectHash;
};

/**
 * StreamManager is used to manage the streams (accessed by StreamID from Java).
 *
 * Locking order (proceeds from application to component).
 *  SoundPool mApiLock (if needed) -> StreamManager mStreamManagerLock
 *                                 -> pair Stream mLock -> queued Stream mLock
 */
class StreamManager : public StreamMap {
public:
    // Note: the SoundPool pointer is only used for stream initialization.
    // It is not stored in StreamManager.
    StreamManager(int32_t streams, size_t threads, const audio_attributes_t* attributes,
            std::string opPackageName);
    ~StreamManager();

    // Returns positive streamID on success, 0 on failure.  This is locked.
    int32_t queueForPlay(const std::shared_ptr<Sound> &sound,
            int32_t soundID, float leftVolume, float rightVolume,
            int32_t priority, int32_t loop, float rate)
            NO_THREAD_SAFETY_ANALYSIS; // uses unique_lock

    ///////////////////////////////////////////////////////////////////////
    // Called from soundpool::Stream

    const audio_attributes_t* getAttributes() const { return &mAttributes; }

    const std::string& getOpPackageName() const { return mOpPackageName; }

    // Moves the stream to the restart queue (called upon BUFFER_END of the static track)
    // this is locked internally.
    // If activeStreamIDToMatch is nonzero, it will only move to the restart queue
    // if the streamIDToMatch is found on the active queue.
    void moveToRestartQueue(Stream* stream, int32_t activeStreamIDToMatch = 0);

private:

    void run(int32_t id) NO_THREAD_SAFETY_ANALYSIS; // worker thread, takes unique_lock.
    void dump() const;                           // no lock needed

    // returns true if more worker threads are needed.
    bool needMoreThreads_l() REQUIRES(mStreamManagerLock) {
        return mRestartStreams.size() > 0 &&
                (mThreadPool->getActiveThreadCount() == 0
                || std::distance(mRestartStreams.begin(),
                        mRestartStreams.upper_bound(systemTime()))
                        > (ptrdiff_t)mThreadPool->getActiveThreadCount());
    }

    // returns true if the stream was added.
    bool moveToRestartQueue_l(
            Stream* stream, int32_t activeStreamIDToMatch = 0) REQUIRES(mStreamManagerLock);
    // returns number of queues the stream was removed from (should be 0 or 1);
    // a special code of -1 is returned if activeStreamIDToMatch is > 0 and
    // the stream wasn't found on the active queue.
    ssize_t removeFromQueues_l(
            Stream* stream, int32_t activeStreamIDToMatch = 0) REQUIRES(mStreamManagerLock);
    void addToRestartQueue_l(Stream *stream) REQUIRES(mStreamManagerLock);
    void addToActiveQueue_l(Stream *stream) REQUIRES(mStreamManagerLock);
    void sanityCheckQueue_l() const REQUIRES(mStreamManagerLock);

    const audio_attributes_t mAttributes;
    std::unique_ptr<ThreadPool> mThreadPool;                  // locked internally

    // mStreamManagerLock is used to lock access for transitions between the
    // 4 stream queues by the Manager Thread or by the user initiated play().
    // A stream pair has exactly one stream on exactly one of the queues.
    std::mutex                  mStreamManagerLock;
    std::condition_variable     mStreamManagerCondition GUARDED_BY(mStreamManagerLock);

    bool                        mQuit GUARDED_BY(mStreamManagerLock) = false;

    // There are constructor arg "streams" pairs of streams, only one of each
    // pair on the 4 stream queues below.  The other stream in the pair serves as
    // placeholder to accumulate user changes, pending actual availability of the
    // AudioTrack, as it may be in use, requiring stop-then-restart.
    //
    // The 4 queues are implemented in the appropriate STL container based on perceived
    // optimality.

    // 1) mRestartStreams: Streams awaiting stop.
    // The paired stream may be active (but with no AudioTrack), and will be restarted
    // with an active AudioTrack when the current stream is stopped.
    std::multimap<int64_t /* stopTimeNs */, Stream*>
                                mRestartStreams GUARDED_BY(mStreamManagerLock);

    // 2) mActiveStreams: Streams that are active.
    // The paired stream will be inactive.
    // This is in order of specified by kStealActiveStream_OldestFirst
    std::list<Stream*>          mActiveStreams GUARDED_BY(mStreamManagerLock);

    // 3) mAvailableStreams: Streams that are inactive.
    // The paired stream will also be inactive.
    // No particular order.
    std::unordered_set<Stream*> mAvailableStreams GUARDED_BY(mStreamManagerLock);

    // 4) mProcessingStreams: Streams that are being processed by the ManagerThreads
    // When on this queue, the stream and its pair are not available for stealing.
    // Each ManagerThread will have at most one stream on the mProcessingStreams queue.
    // The paired stream may be active or restarting.
    // No particular order.
    std::unordered_set<Stream*> mProcessingStreams GUARDED_BY(mStreamManagerLock);

    const std::string           mOpPackageName;
};

} // namespace android::soundpool
