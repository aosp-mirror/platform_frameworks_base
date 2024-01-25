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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool::StreamManager"
#include <utils/Log.h>

#include "StreamManager.h"

#include <audio_utils/clock.h>
#include <audio_utils/roundup.h>

namespace android::soundpool {

// kMaxStreams is number that should be less than the current AudioTrack max per UID of 40.
// It is the maximum number of AudioTrack resources allowed in the SoundPool.
// We suggest a value at least 4 or greater to allow CTS tests to pass.
static constexpr int32_t kMaxStreams = 32;

// kStealActiveStream_OldestFirst = false historically (Q and earlier)
// Changing to true could break app expectations but could change behavior beneficially.
// In R, we change this to true, as it is the correct way per SoundPool documentation.
static constexpr bool kStealActiveStream_OldestFirst = true;

// Changing to false means calls to play() are almost instantaneous instead of taking around
// ~10ms to launch the AudioTrack. It is perhaps 100x faster.
static constexpr bool kPlayOnCallingThread = false;

// Amount of time for a StreamManager thread to wait before closing.
static constexpr int64_t kWaitTimeBeforeCloseNs = 9 * NANOS_PER_SECOND;

// Debug flag:
// kForceLockStreamManagerStop is set to true to force lock the StreamManager
// worker thread during stop. This limits concurrency of Stream processing.
// Normally we lock the StreamManager worker thread during stop ONLY
// for SoundPools configured with a single Stream.
//
static constexpr bool kForceLockStreamManagerStop = false;

////////////

StreamMap::StreamMap(int32_t streams) {
    ALOGV("%s(%d)", __func__, streams);
    if (streams > kMaxStreams) {
        ALOGW("%s: requested %d streams, clamping to %d", __func__, streams, kMaxStreams);
        streams = kMaxStreams;
    } else if (streams < 1) {
        ALOGW("%s: requested %d streams, clamping to 1", __func__, streams);
        streams = 1;
    }
    mStreamPoolSize = streams * 2;
    mStreamPool = std::make_unique<Stream[]>(mStreamPoolSize); // create array of streams.
    // we use a perfect hash table with 2x size to map StreamIDs to Stream pointers.
    mPerfectHash = std::make_unique<PerfectHash<int32_t, Stream *>>(roundup(mStreamPoolSize * 2));
}

Stream* StreamMap::findStream(int32_t streamID) const
{
    Stream *stream = lookupStreamFromId(streamID);
    return stream != nullptr && stream->getStreamID() == streamID ? stream : nullptr;
}

size_t StreamMap::streamPosition(const Stream* stream) const
{
    ptrdiff_t index = stream - mStreamPool.get();
    LOG_ALWAYS_FATAL_IF(index < 0 || (size_t)index >= mStreamPoolSize,
            "%s: stream position out of range: %td", __func__, index);
    return (size_t)index;
}

Stream* StreamMap::lookupStreamFromId(int32_t streamID) const
{
    return streamID > 0 ? mPerfectHash->getValue(streamID).load() : nullptr;
}

int32_t StreamMap::getNextIdForStream(Stream* stream) const {
    // even though it is const, it mutates the internal hash table.
    const int32_t id = mPerfectHash->generateKey(
        stream,
        [] (Stream *stream) {
            return stream == nullptr ? 0 : stream->getStreamID();
        }, /* getKforV() */
        stream->getStreamID() /* oldID */);
    return id;
}

////////////

// Thread safety analysis is supposed to be disabled for constructors and destructors
// but clang in R seems to have a bug.  We use pragma to disable.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wthread-safety-analysis"

StreamManager::StreamManager(
        int32_t streams, size_t threads, const audio_attributes_t& attributes,
        std::string opPackageName)
    : StreamMap(streams)
    , mAttributes([attributes](){
        audio_attributes_t attr = attributes;
        attr.flags = static_cast<audio_flags_mask_t>(attr.flags | AUDIO_FLAG_LOW_LATENCY);
        return attr; }())
    , mOpPackageName(std::move(opPackageName))
    , mLockStreamManagerStop(streams == 1 || kForceLockStreamManagerStop)
{
    ALOGV("%s(%d, %zu, ...)", __func__, streams, threads);
    forEach([this](Stream *stream) {
        stream->setStreamManager(this);
        if ((streamPosition(stream) & 1) == 0) { // put the first stream of pair as available.
            mAvailableStreams.insert(stream);
        }
    });

    mThreadPool = std::make_unique<ThreadPool>(
            std::min((size_t)streams,  // do not make more threads than streams to play
                    std::min(threads, (size_t)std::thread::hardware_concurrency())),
            "SoundPool_");
}

#pragma clang diagnostic pop

StreamManager::~StreamManager()
{
    ALOGV("%s", __func__);
    {
        std::unique_lock lock(mStreamManagerLock);
        mQuit = true;
        mStreamManagerCondition.notify_all();
    }
    mThreadPool->quit();

    // call stop on the stream pool
    forEach([](Stream *stream) { stream->stop(); });

    // This invokes the destructor on the AudioTracks -
    // we do it here to ensure that AudioTrack callbacks will not occur
    // afterwards.
    forEach([](Stream *stream) { stream->clearAudioTrack(); });
}


int32_t StreamManager::queueForPlay(const std::shared_ptr<Sound> &sound,
        int32_t soundID, float leftVolume, float rightVolume,
        int32_t priority, int32_t loop, float rate, int32_t playerIId)
{
    ALOGV(
        "%s(sound=%p, soundID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f,"
        " playerIId=%d)", __func__, sound.get(), soundID, leftVolume, rightVolume, priority,
        loop, rate, playerIId);

    bool launchThread = false;
    int32_t streamID = 0;
    std::vector<std::any> garbage;

    { // for lock
        std::unique_lock lock(mStreamManagerLock);
        Stream *newStream = nullptr;
        bool fromAvailableQueue = false;
        ALOGV("%s: mStreamManagerLock lock acquired", __func__);

        sanityCheckQueue_l();
        // find an available stream, prefer one that has matching sound id.
        if (mAvailableStreams.size() > 0) {
            for (auto stream : mAvailableStreams) {
                if (stream->getSoundID() == soundID) {
                    newStream = stream;
                    ALOGV("%s: found soundID %d in available queue", __func__, soundID);
                    break;
                }
            }
            if (newStream == nullptr) {
                ALOGV("%s: found stream in available queue", __func__);
                newStream = *mAvailableStreams.begin();
            }
            newStream->setStopTimeNs(systemTime());
            fromAvailableQueue = true;
        }

        // also look in the streams restarting (if the paired stream doesn't have a pending play)
        if (newStream == nullptr || newStream->getSoundID() != soundID) {
            for (auto [unused , stream] : mRestartStreams) {
                if (!stream->getPairStream()->hasSound()) {
                    if (stream->getSoundID() == soundID) {
                        ALOGV("%s: found soundID %d in restart queue", __func__, soundID);
                        newStream = stream;
                        fromAvailableQueue = false;
                        break;
                    } else if (newStream == nullptr) {
                        ALOGV("%s: found stream in restart queue", __func__);
                        newStream = stream;
                    }
                }
            }
        }

        // no available streams, look for one to steal from the active list
        if (newStream == nullptr) {
            for (auto stream : mActiveStreams) {
                if (stream->getPriority() <= priority) {
                    if (newStream == nullptr
                            || newStream->getPriority() > stream->getPriority()) {
                        newStream = stream;
                        ALOGV("%s: found stream in active queue", __func__);
                    }
                }
            }
            if (newStream != nullptr) { // we need to mute as it is still playing.
                (void)newStream->requestStop(newStream->getStreamID());
            }
        }

        // none found, look for a stream that is restarting, evict one.
        if (newStream == nullptr) {
            for (auto [unused, stream] : mRestartStreams) {
                if (stream->getPairPriority() <= priority) {
                    ALOGV("%s: evict stream from restart queue", __func__);
                    newStream = stream;
                    break;
                }
            }
        }

        // DO NOT LOOK into mProcessingStreams as those are held by the StreamManager threads.

        if (newStream == nullptr) {
            ALOGD("%s: unable to find stream, returning 0", __func__);
            return 0; // unable to find available stream
        }

        Stream *pairStream = newStream->getPairStream();
        streamID = getNextIdForStream(pairStream);
        ALOGV("%s: newStream:%p  pairStream:%p, streamID:%d",
                __func__, newStream, pairStream, streamID);
        pairStream->setPlay(
                streamID, sound, soundID, leftVolume, rightVolume, priority, loop, rate);
        if (fromAvailableQueue && kPlayOnCallingThread) {
            removeFromQueues_l(newStream);
            mProcessingStreams.emplace(newStream);
            lock.unlock();
            if (Stream* nextStream = newStream->playPairStream(garbage, playerIId)) {
                lock.lock();
                ALOGV("%s: starting streamID:%d", __func__, nextStream->getStreamID());
                addToActiveQueue_l(nextStream);
            } else {
                lock.lock();
                mAvailableStreams.insert(newStream);
                streamID = 0;
            }
            mProcessingStreams.erase(newStream);
        } else {
            launchThread = moveToRestartQueue_l(newStream) && needMoreThreads_l();
        }
        sanityCheckQueue_l();
        ALOGV("%s: mStreamManagerLock released", __func__);
    } // lock

    if (launchThread) {
        const int32_t id = mThreadPool->launch([this](int32_t id) { run(id); });
        (void)id; // avoid clang warning -Wunused-variable -Wused-but-marked-unused
        ALOGV_IF(id != 0, "%s: launched thread %d", __func__, id);
    }
    ALOGV("%s: returning %d", __func__, streamID);
    // garbage is cleared here outside mStreamManagerLock.
    return streamID;
}

void StreamManager::moveToRestartQueue(
        Stream* stream, int32_t activeStreamIDToMatch)
{
    ALOGV("%s(stream(ID)=%d, activeStreamIDToMatch=%d)",
            __func__, stream->getStreamID(), activeStreamIDToMatch);
    bool restart;
    {
        std::lock_guard lock(mStreamManagerLock);
        sanityCheckQueue_l();
        if (mProcessingStreams.count(stream) > 0 ||
                mProcessingStreams.count(stream->getPairStream()) > 0) {
            ALOGD("%s: attempting to restart processing stream(%d)",
                    __func__, stream->getStreamID());
            restart = false;
        } else {
            moveToRestartQueue_l(stream, activeStreamIDToMatch);
            restart = needMoreThreads_l();
        }
        sanityCheckQueue_l();
    }
    if (restart) {
        const int32_t id = mThreadPool->launch([this](int32_t id) { run(id); });
        (void)id; // avoid clang warning -Wunused-variable -Wused-but-marked-unused
        ALOGV_IF(id != 0, "%s: launched thread %d", __func__, id);
    }
}

bool StreamManager::moveToRestartQueue_l(
        Stream* stream, int32_t activeStreamIDToMatch)
{
    ALOGV("%s(stream(ID)=%d, activeStreamIDToMatch=%d)",
            __func__, stream->getStreamID(), activeStreamIDToMatch);
    if (activeStreamIDToMatch > 0 && stream->getStreamID() != activeStreamIDToMatch) {
        return false;
    }
    const ssize_t found = removeFromQueues_l(stream, activeStreamIDToMatch);
    if (found < 0) return false;

    LOG_ALWAYS_FATAL_IF(found > 1, "stream on %zd > 1 stream lists", found);

    addToRestartQueue_l(stream);
    mStreamManagerCondition.notify_one();
    return true;
}

ssize_t StreamManager::removeFromQueues_l(
        Stream* stream, int32_t activeStreamIDToMatch) {
    size_t found = 0;
    for (auto it = mActiveStreams.begin(); it != mActiveStreams.end(); ++it) {
        if (*it == stream) {
            mActiveStreams.erase(it); // we erase the iterator and break (otherwise it not safe).
            ++found;
            break;
        }
    }
    // activeStreamIDToMatch is nonzero indicates we proceed only if found.
    if (found == 0 && activeStreamIDToMatch > 0) {
        return -1;  // special code: not present on active streams, ignore restart request
    }

    for (auto it = mRestartStreams.begin(); it != mRestartStreams.end(); ++it) {
        if (it->second == stream) {
            mRestartStreams.erase(it);
            ++found;
            break;
        }
    }
    found += mAvailableStreams.erase(stream);

    // streams on mProcessingStreams are undergoing processing by the StreamManager thread
    // and do not participate in normal stream migration.
    return (ssize_t)found;
}

void StreamManager::addToRestartQueue_l(Stream *stream) {
    mRestartStreams.emplace(stream->getStopTimeNs(), stream);
}

void StreamManager::addToActiveQueue_l(Stream *stream) {
    if (kStealActiveStream_OldestFirst) {
        mActiveStreams.push_back(stream);  // oldest to newest
    } else {
        mActiveStreams.push_front(stream); // newest to oldest
    }
}

void StreamManager::run(int32_t id)
{
    ALOGV("%s(%d) entering", __func__, id);
    int64_t waitTimeNs = 0;  // on thread start, mRestartStreams can be non-empty.
    std::vector<std::any> garbage; // used for garbage collection
    std::unique_lock lock(mStreamManagerLock);
    while (!mQuit) {
        if (waitTimeNs > 0) {
            mStreamManagerCondition.wait_for(
                    lock, std::chrono::duration<int64_t, std::nano>(waitTimeNs));
        }
        ALOGV("%s(%d) awake lock waitTimeNs:%lld", __func__, id, (long long)waitTimeNs);

        sanityCheckQueue_l();

        if (mQuit || (mRestartStreams.empty() && waitTimeNs == kWaitTimeBeforeCloseNs)) {
            break;  // end the thread
        }

        waitTimeNs = kWaitTimeBeforeCloseNs;
        while (!mQuit && !mRestartStreams.empty()) {
            const nsecs_t nowNs = systemTime();
            auto it = mRestartStreams.begin();
            Stream* const stream = it->second;
            const int64_t diffNs = stream->getStopTimeNs() - nowNs;
            if (diffNs > 0) {
                waitTimeNs = std::min(waitTimeNs, diffNs);
                break;
            }
            mRestartStreams.erase(it);
            mProcessingStreams.emplace(stream);
            if (!mLockStreamManagerStop) lock.unlock();
            stream->stop();
            ALOGV("%s(%d) stopping streamID:%d", __func__, id, stream->getStreamID());
            if (Stream* nextStream = stream->playPairStream(garbage)) {
                ALOGV("%s(%d) starting streamID:%d", __func__, id, nextStream->getStreamID());
                if (!mLockStreamManagerStop) lock.lock();
                if (nextStream->getStopTimeNs() > 0) {
                    // the next stream was stopped before we can move it to the active queue.
                    ALOGV("%s(%d) stopping started streamID:%d",
                            __func__, id, nextStream->getStreamID());
                    moveToRestartQueue_l(nextStream);
                } else {
                    addToActiveQueue_l(nextStream);
                }
            } else {
                if (!mLockStreamManagerStop) lock.lock();
                mAvailableStreams.insert(stream);
            }
            mProcessingStreams.erase(stream);
            sanityCheckQueue_l();
            if (!garbage.empty()) {
                lock.unlock();
                // garbage audio tracks (etc) are cleared here outside mStreamManagerLock.
                garbage.clear();
                lock.lock();
            }
        }
    }
    ALOGV("%s(%d) exiting", __func__, id);
}

void StreamManager::dump() const
{
    forEach([](const Stream *stream) { stream->dump(); });
}

void StreamManager::sanityCheckQueue_l() const
{
    // We want to preserve the invariant that each stream pair is exactly on one of the queues.
    const size_t availableStreams = mAvailableStreams.size();
    const size_t restartStreams = mRestartStreams.size();
    const size_t activeStreams = mActiveStreams.size();
    const size_t processingStreams = mProcessingStreams.size();
    const size_t managedStreams = availableStreams + restartStreams + activeStreams
                + processingStreams;
    const size_t totalStreams = getStreamMapSize() >> 1;
    LOG_ALWAYS_FATAL_IF(managedStreams != totalStreams,
            "%s: mAvailableStreams:%zu + mRestartStreams:%zu + "
            "mActiveStreams:%zu + mProcessingStreams:%zu = %zu != total streams %zu",
            __func__, availableStreams, restartStreams, activeStreams, processingStreams,
            managedStreams, totalStreams);
    ALOGV("%s: mAvailableStreams:%zu + mRestartStreams:%zu + "
            "mActiveStreams:%zu + mProcessingStreams:%zu = %zu (total streams: %zu)",
            __func__, availableStreams, restartStreams, activeStreams, processingStreams,
            managedStreams, totalStreams);
}

} // namespace android::soundpool
