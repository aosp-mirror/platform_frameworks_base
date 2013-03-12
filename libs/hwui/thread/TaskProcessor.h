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

#ifndef ANDROID_HWUI_TASK_PROCESSOR_H
#define ANDROID_HWUI_TASK_PROCESSOR_H

#include <utils/RefBase.h>

#include "Task.h"
#include "TaskManager.h"

namespace android {
namespace uirenderer {

class TaskProcessorBase: public RefBase {
public:
    TaskProcessorBase() { }
    virtual ~TaskProcessorBase() { };

private:
    friend class TaskManager;

    virtual void process(const sp<TaskBase>& task) = 0;
};

template<typename T>
class TaskProcessor: public TaskProcessorBase {
public:
    TaskProcessor(TaskManager* manager): mManager(manager) { }
    virtual ~TaskProcessor() { }

    bool add(const sp<Task<T> >& task);

    virtual void onProcess(const sp<Task<T> >& task) = 0;

private:
    virtual void process(const sp<TaskBase>& task) {
        sp<Task<T> > realTask = static_cast<Task<T>* >(task.get());
        // This is the right way to do it but sp<> doesn't play nice
        // sp<Task<T> > realTask = static_cast<sp<Task<T> > >(task);
        onProcess(realTask);
    }

    TaskManager* mManager;
};

template<typename T>
bool TaskProcessor<T>::add(const sp<Task<T> >& task) {
    if (mManager) {
        sp<TaskProcessor<T> > self(this);
        return mManager->addTask(task, self);
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TASK_PROCESSOR_H
