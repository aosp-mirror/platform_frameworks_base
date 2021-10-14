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

#ifndef ANIMATEDIMAGETHREAD_H_
#define ANIMATEDIMAGETHREAD_H_

#include "AnimatedImageDrawable.h"
#include "thread/ThreadBase.h"

#include <SkRefCnt.h>

namespace android {

namespace uirenderer {

class AnimatedImageThread : private ThreadBase {
    PREVENT_COPY_AND_ASSIGN(AnimatedImageThread);

public:
    static AnimatedImageThread& getInstance();

    std::future<AnimatedImageDrawable::Snapshot> decodeNextFrame(
            const sk_sp<AnimatedImageDrawable>&);
    std::future<AnimatedImageDrawable::Snapshot> reset(const sk_sp<AnimatedImageDrawable>&);

private:
    friend sp<AnimatedImageThread>;
    AnimatedImageThread();
};

}  // namespace uirenderer

}  // namespace android

#endif  // ANIMATEDIMAGETHREAD_H_
