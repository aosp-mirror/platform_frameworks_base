/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "BootAnimation"

#include <stdint.h>
#include <inttypes.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <sys/resource.h>
#include <utils/Log.h>
#include <utils/SystemClock.h>
#include <utils/threads.h>
#include <android-base/properties.h>

#include "BootAnimation.h"
#include "audioplay.h"

using namespace android;

// ---------------------------------------------------------------------------

namespace {

// Create a typedef for readability.
typedef android::BootAnimation::Animation Animation;

static const char PLAY_SOUND_PROP_NAME[] = "persist.sys.bootanim.play_sound";
static const char BOOT_COMPLETED_PROP_NAME[] = "sys.boot_completed";
static const char POWER_CTL_PROP_NAME[] = "sys.powerctl";
static const char BOOTREASON_PROP_NAME[] = "ro.boot.bootreason";
static const std::vector<std::string> PLAY_SOUND_BOOTREASON_BLACKLIST {
  "kernel_panic",
  "Panic",
  "Watchdog",
};

class InitAudioThread : public Thread {
public:
    InitAudioThread(uint8_t* exampleAudioData, int exampleAudioLength)
        : Thread(false),
          mExampleAudioData(exampleAudioData),
          mExampleAudioLength(exampleAudioLength) {}
private:
    virtual bool threadLoop() {
        audioplay::create(mExampleAudioData, mExampleAudioLength);
        // Exit immediately
        return false;
    }

    uint8_t* mExampleAudioData;
    int mExampleAudioLength;
};

bool playSoundsAllowed() {
    // Only play sounds for system boots, not runtime restarts.
    if (android::base::GetBoolProperty(BOOT_COMPLETED_PROP_NAME, false)) {
        return false;
    }
    // no audio while shutting down
    if (!android::base::GetProperty(POWER_CTL_PROP_NAME, "").empty()) {
        return false;
    }
    // Read the system property to see if we should play the sound.
    // If it's not present, default to allowed.
    if (!property_get_bool(PLAY_SOUND_PROP_NAME, 1)) {
        return false;
    }

    // Don't play sounds if this is a reboot due to an error.
    char bootreason[PROPERTY_VALUE_MAX];
    if (property_get(BOOTREASON_PROP_NAME, bootreason, nullptr) > 0) {
        for (const auto& str : PLAY_SOUND_BOOTREASON_BLACKLIST) {
            if (strcasecmp(str.c_str(), bootreason) == 0) {
                return false;
            }
        }
    }
    return true;
}

}  // namespace


int main()
{
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);

    char value[PROPERTY_VALUE_MAX];
    property_get("debug.sf.nobootanimation", value, "0");
    int noBootAnimation = atoi(value);
    if (!noBootAnimation) {
        property_get("ro.boot.quiescent", value, "0");
        noBootAnimation = atoi(value);
    }
    ALOGI_IF(noBootAnimation,  "boot animation disabled");
    if (!noBootAnimation) {

        sp<ProcessState> proc(ProcessState::self());
        ProcessState::self()->startThreadPool();

        // TODO: replace this with better waiting logic in future, b/35253872
        int64_t waitStartTime = elapsedRealtime();
        sp<IServiceManager> sm = defaultServiceManager();
        const String16 name("SurfaceFlinger");
        const int SERVICE_WAIT_SLEEP_MS = 100;
        const int LOG_PER_RETRIES = 10;
        int retry = 0;
        while (sm->checkService(name) == nullptr) {
            retry++;
            if ((retry % LOG_PER_RETRIES) == 0) {
                ALOGW("Waiting for SurfaceFlinger, waited for %" PRId64 " ms",
                      elapsedRealtime() - waitStartTime);
            }
            usleep(SERVICE_WAIT_SLEEP_MS * 1000);
        };
        int64_t totalWaited = elapsedRealtime() - waitStartTime;
        if (totalWaited > SERVICE_WAIT_SLEEP_MS) {
            ALOGI("Waiting for SurfaceFlinger took %" PRId64 " ms", totalWaited);
        }

        // TODO: Move audio code to a new class that just exports the callbacks.
        sp<InitAudioThread> initAudioThread = nullptr;

        auto initCallback = [&](const Vector<Animation::Part>& parts) {
            const Animation::Part* partWithAudio = nullptr;
            for (const Animation::Part& part : parts) {
                if (part.audioData != nullptr) {
                    partWithAudio = &part;
                }
            }

            if (partWithAudio == nullptr) {
                return;
            }

            ALOGD("found audio.wav, creating playback engine");
            initAudioThread = new InitAudioThread(partWithAudio->audioData,
                    partWithAudio->audioLength);
            initAudioThread->run("BootAnimation::InitAudioThread", PRIORITY_NORMAL);

        };

        auto partCallback = [&](int partNumber, const Animation::Part& part,
                                int playNumber) {
            // only play audio file the first time we animate the part
            if (playNumber == 0 && part.audioData && playSoundsAllowed()) {
                ALOGD("playing clip for part%d, size=%d",
                      partNumber, part.audioLength);
                // Block until the audio engine is finished initializing.
                if (initAudioThread != nullptr) {
                    initAudioThread->join();
                }
                audioplay::playClip(part.audioData, part.audioLength);
            }
        };

        // create the boot animation object
        sp<BootAnimation> boot = new BootAnimation(initCallback, partCallback);

        IPCThreadState::self()->joinThreadPool();

        // we've finally played everything we're going to play
        audioplay::setPlaying(false);
        audioplay::destroy();
    }
    return 0;
}
