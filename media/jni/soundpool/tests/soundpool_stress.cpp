/*
 * Copyright (C) 2014 The Android Open Source Project
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
#define LOG_TAG "soundpool"

#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>

#include <atomic>
#include <future>
#include <mutex>
#include <set>
#include <vector>

#include <audio_utils/clock.h>
#include <binder/ProcessState.h>
#include <media/stagefright/MediaExtractorFactory.h>
#include <soundpool/SoundPool.h> // direct include, this is not an NDK feature.
#include <system/audio.h>
#include <utils/Log.h>

using namespace android;

// Errors and diagnostic messages all go to stdout.

namespace {

void usage(const char *name)
{
    printf("Usage: %s "
            "[-i #iterations] [-l #loop] [-p #playback_seconds] [-s #streams] [-t #threads] "
            "[-z #snoozeSec] <input-file>+\n", name);
    printf("Uses soundpool to load and play a file (the first 10 seconds)\n");
    printf("    -i #iterations, default 1\n");
    printf("    -l #loop looping mode, -1 forever\n");
    printf("    -p #playback_seconds, default 10\n");
    printf("    -r #repeat soundIDs (0 or more times), default 0\n");
    printf("    -s #streams for concurrent sound playback, default 20\n");
    printf("    -t #threads, default 1\n");
    printf("    -z #snoozeSec after stopping, -1 forever, default 0\n");
    printf("    <input-file>+ files to be played\n");
}

std::atomic_int32_t gErrors{};
std::atomic_int32_t gWarnings{};

void printEvent(const SoundPoolEvent *event) {
    printf("{ msg:%d  id:%d  status:%d }\n", event->mMsg, event->mArg1, event->mArg2);
}

class CallbackManager {
public:
    int32_t getNumberEvents(int32_t soundID) {
        std::lock_guard lock(mLock);
        return mEvents[soundID] > 0;
    }

    void setSoundPool(SoundPool* soundPool) {
        std::lock_guard lock(mLock);
        mSoundPool = soundPool;
    }

    void callback(SoundPoolEvent event, const SoundPool *soundPool) {
        std::lock_guard lock(mLock);
        printEvent(&event);
        if (soundPool != mSoundPool) {
            printf("ERROR: mismatched soundpool: %p\n", soundPool);
            ++gErrors;
            return;
        }
        if (event.mMsg != 1 /* SoundPoolEvent::SOUND_LOADED */) {
            printf("ERROR: invalid event msg: %d\n", event.mMsg);
            ++gErrors;
            return;
        }
        if (event.mArg2 != 0) {
            printf("ERROR: event status(%d) != 0\n", event.mArg2);
            ++gErrors;
            return;
        }
        if (event.mArg1 <= 0) {
            printf("ERROR: event soundID(%d) < 0\n", event.mArg1);
            ++gErrors;
            return;
        }
        ++mEvents[event.mArg1];
    }

private:
    std::mutex mLock;
    SoundPool *mSoundPool = nullptr;
    std::map<int32_t /* soundID */, int32_t /* count */> mEvents;
} gCallbackManager;


void StaticCallbackManager(SoundPoolEvent event, SoundPool* soundPool, void* user) {
    ((CallbackManager *)user)->callback(event, soundPool);
}

void testStreams(SoundPool *soundPool, const std::vector<const char *> &filenames,
                 int loop, int repeat, int playSec)
{
    const int64_t startTimeNs = systemTime();
    std::vector<int32_t> soundIDs;
    for (auto filename : filenames) {
        struct stat st;
        if (stat(filename, &st) < 0) {
            printf("ERROR: cannot stat %s\n", filename);
            return;
        }
        const uint64_t length = uint64_t(st.st_size);
        const int inp = open(filename, O_RDONLY);
        if (inp < 0) {
            printf("ERROR: cannot open %s\n", filename);
            return;
        }
        printf("loading (%s) size (%llu)\n", filename, (unsigned long long)length);
        const int32_t soundID = soundPool->load(
                inp, 0 /*offset*/, length, 0 /*priority - unused*/);
        if (soundID == 0) {
            printf("ERROR: cannot load %s\n", filename);
            return;
        }
        close(inp);
        soundIDs.emplace_back(soundID);
        printf("loaded %s soundID(%d)\n", filename, soundID);
    }
    const int64_t requestLoadTimeNs = systemTime();
    printf("\nrequestLoadTimeMs: %d\n",
            (int)((requestLoadTimeNs - startTimeNs) / NANOS_PER_MILLISECOND));

    // create stream & get Id (playing)
    const float maxVol = 1.f;
    const float silentVol = 0.f;
    const int priority = 0; // lowest
    const float rate = 1.f;  // normal

    // Loading is done by a SoundPool Worker thread.
    // TODO: Use SoundPool::setCallback() for wait

    for (int32_t soundID : soundIDs) {
        for (int i = 0; i <= repeat; ++i) {
            while (true) {
                const int32_t streamID =
                    soundPool->play(soundID, silentVol, silentVol, priority, 0 /*loop*/, rate);
                if (streamID != 0) {
                    const int32_t events = gCallbackManager.getNumberEvents(soundID);
                    if (events != 1) {
                       printf("WARNING: successful play for streamID:%d soundID:%d"
                              " but callback events(%d) != 1\n", streamID, soundID, events);
                       ++gWarnings;
                    }
                    soundPool->stop(streamID);
                    break;
                }
                usleep(1000);
            }
            printf("[%d]", soundID);
            fflush(stdout);
        }
    }

    const int64_t loadTimeNs = systemTime();
    printf("\nloadTimeMs: %d\n", (int)((loadTimeNs - startTimeNs) / NANOS_PER_MILLISECOND));

    // check and play (overlap with above).
    std::vector<int32_t> streamIDs;
    for (int32_t soundID : soundIDs) {
        for (int i = 0; i <= repeat; ++i) {
            printf("\nplaying soundID=%d", soundID);
            const int32_t streamID =
                    soundPool->play(soundID, maxVol, maxVol, priority, loop, rate);
            if (streamID == 0) {
                printf(" failed!  ERROR");
                ++gErrors;
            } else {
                printf(" streamID=%d", streamID);
                streamIDs.emplace_back(streamID);
            }
        }
    }
    const int64_t playTimeNs = systemTime();
    printf("\nplayTimeMs: %d\n", (int)((playTimeNs - loadTimeNs) / NANOS_PER_MILLISECOND));

    for (int i = 0; i < playSec; ++i) {
        sleep(1);
        printf(".");
        fflush(stdout);
    }

    for (int32_t streamID : streamIDs) {
        soundPool->stop(streamID);
    }

    for (int32_t soundID : soundIDs) {
        soundPool->unload(soundID);
    }
    printf("\nDone!\n");
}

} // namespace

int main(int argc, char *argv[])
{
    const char * const me = argv[0];

    int iterations = 1;
    int loop = 0;        // disable looping
    int maxStreams = 40; // change to have more concurrent playback streams
    int playSec = 10;
    int repeat = 0;
    int snoozeSec = 0;
    int threadCount = 1;
    for (int ch; (ch = getopt(argc, argv, "i:l:p:r:s:t:z:")) != -1; ) {
        switch (ch) {
        case 'i':
            iterations = atoi(optarg);
            break;
        case 'l':
            loop = atoi(optarg);
            break;
        case 'p':
            playSec = atoi(optarg);
            break;
        case 'r':
            repeat = atoi(optarg);
            break;
        case 's':
            maxStreams = atoi(optarg);
            break;
        case 't':
            threadCount = atoi(optarg);
            break;
        case 'z':
            snoozeSec = atoi(optarg);
            break;
        default:
            usage(me);
            return EXIT_FAILURE;
        }
    }

    argc -= optind;
    argv += optind;
    if (argc <= 0) {
        usage(me);
        return EXIT_FAILURE;
    }

    std::vector<const char *> filenames(argv, argv + argc);

    android::ProcessState::self()->startThreadPool();

    // O and later requires data sniffer registration for proper file type detection
    MediaExtractorFactory::LoadExtractors();

    // create soundpool
    audio_attributes_t aa = {
        .content_type = AUDIO_CONTENT_TYPE_MUSIC,
        .usage = AUDIO_USAGE_MEDIA,
    };
    auto soundPool = std::make_unique<SoundPool>(maxStreams, aa);

    gCallbackManager.setSoundPool(soundPool.get());
    soundPool->setCallback(StaticCallbackManager, &gCallbackManager);

    const int64_t startTimeNs = systemTime();

    for (int it = 0; it < iterations; ++it) {
        // One instance:
        // testStreams(soundPool.get(), filenames, loop, playSec);

        // Test multiple instances
        std::vector<std::future<void>> threads(threadCount);
        printf("testing %zu threads\n", threads.size());
        for (auto &thread : threads) {
            thread = std::async(std::launch::async,
                    [&]{ testStreams(soundPool.get(), filenames, loop, repeat, playSec);});
        }
        // automatically joins.
    }

    const int64_t endTimeNs = systemTime();

    // snooze before cleaning up to examine soundpool dumpsys state after stop
    for (int i = 0; snoozeSec < 0 || i < snoozeSec; ++i) {
        printf("z");
        fflush(stdout);
        sleep(1);
    };

    gCallbackManager.setSoundPool(nullptr);
    soundPool.reset();

    printf("total time in ms: %lld\n", (endTimeNs - startTimeNs) / NANOS_PER_MILLISECOND);
    if (gWarnings != 0) {
        printf("%d warnings!\n", gWarnings.load());
    }
    if (gErrors != 0) {
        printf("%d errors!\n", gErrors.load());
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
