/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FUTURE_H
#define ANDROID_HWUI_FUTURE_H

#include <utils/RefBase.h>

#include "Barrier.h"

namespace android {
namespace uirenderer {

template <typename T>
class Future : public LightRefBase<Future<T> > {
public:
    explicit Future(Condition::WakeUpType type = Condition::WAKE_UP_ONE)
            : mBarrier(type), mResult() {}
    ~Future() {}

    /**
     * Returns the result of this future, blocking if
     * the result is not available yet.
     */
    T get() const {
        mBarrier.wait();
        return mResult;
    }

    /**
     * This method must be called only once.
     */
    void produce(T result) {
        mResult = result;
        mBarrier.open();
    }

private:
    Barrier mBarrier;
    T mResult;
};

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_FUTURE_H
