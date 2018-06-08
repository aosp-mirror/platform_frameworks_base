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

#ifndef ANDROID_HWUI_TASK_H
#define ANDROID_HWUI_TASK_H

#include <utils/RefBase.h>
#include <utils/Trace.h>

#include "Future.h"

namespace android {
namespace uirenderer {

class TaskBase : public RefBase {
public:
    TaskBase() {}
    virtual ~TaskBase() {}
};

template <typename T>
class Task : public TaskBase {
public:
    Task() : mFuture(new Future<T>()) {}
    virtual ~Task() {}

    T getResult() const { return mFuture->get(); }

    void setResult(T result) { mFuture->produce(result); }

protected:
    const sp<Future<T> >& future() const { return mFuture; }

private:
    sp<Future<T> > mFuture;
};

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_TASK_H
