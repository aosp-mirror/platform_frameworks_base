/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "SpotController"

//#define LOG_NDEBUG 0

// Log debug messages about spot updates
#define DEBUG_SPOT_UPDATES 0

#include "SpotController.h"

#include <cutils/log.h>

namespace android {

// --- SpotController ---

SpotController::SpotController(const sp<Looper>& looper,
        const sp<SpriteController>& spriteController) :
        mLooper(looper), mSpriteController(spriteController) {
    mHandler = new WeakMessageHandler(this);
}

SpotController::~SpotController() {
    mLooper->removeMessages(mHandler);
}

void SpotController:: handleMessage(const Message& message) {
}

} // namespace android
