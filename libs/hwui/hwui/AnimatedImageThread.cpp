/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "AnimatedImageThread.h"

#include <sys/resource.h>

namespace android {
namespace uirenderer {

AnimatedImageThread& AnimatedImageThread::getInstance() {
    static sp<AnimatedImageThread> sInstance = []() {
        sp<AnimatedImageThread> thread = sp<AnimatedImageThread>::make();
        thread->start("AnimatedImageThread");
        return thread;
    }();
    return *sInstance;
}

AnimatedImageThread::AnimatedImageThread() {
    setpriority(PRIO_PROCESS, 0, PRIORITY_NORMAL + PRIORITY_MORE_FAVORABLE);
}

std::future<AnimatedImageDrawable::Snapshot> AnimatedImageThread::decodeNextFrame(
        const sk_sp<AnimatedImageDrawable>& drawable) {
    return queue().async([drawable]() { return drawable->decodeNextFrame(); });
}

std::future<AnimatedImageDrawable::Snapshot> AnimatedImageThread::reset(
        const sk_sp<AnimatedImageDrawable>& drawable) {
    return queue().async([drawable]() { return drawable->reset(); });
}

}  // namespace uirenderer
}  // namespace android
