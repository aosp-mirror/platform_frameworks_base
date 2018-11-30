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

#ifndef ANDROID_HWUI_TASK_MANAGER_H
#define ANDROID_HWUI_TASK_MANAGER_H

#include <utils/Mutex.h>
#include <utils/String8.h>
#include <utils/Thread.h>

#include "Signal.h"

#include <vector>

namespace android {
namespace uirenderer {

template <typename T>
class Task;
class TaskBase;

template <typename T>
class TaskProcessor;
class TaskProcessorBase;

class TaskManager {
public:
    TaskManager();
    ~TaskManager();

    /**
     * Returns true if this task  manager can run tasks,
     * false otherwise. This method will typically return
     * false on a single CPU core device.
     */
    bool canRunTasks() const;

    /**
     * Stops all allocated threads. Adding tasks will start
     * the threads again as necessary.
     */
    void stop();

private:
    template <typename T>
    friend class TaskProcessor;

    template <typename T>
    bool addTask(const sp<Task<T> >& task, const sp<TaskProcessor<T> >& processor) {
        return addTaskBase(sp<TaskBase>(task), sp<TaskProcessorBase>(processor));
    }

    bool addTaskBase(const sp<TaskBase>& task, const sp<TaskProcessorBase>& processor);

    struct TaskWrapper {
        TaskWrapper() : mTask(), mProcessor() {}

        TaskWrapper(const sp<TaskBase>& task, const sp<TaskProcessorBase>& processor)
                : mTask(task), mProcessor(processor) {}

        sp<TaskBase> mTask;
        sp<TaskProcessorBase> mProcessor;
    };

    class WorkerThread : public Thread {
    public:
        explicit WorkerThread(const String8& name) : mSignal(Condition::WAKE_UP_ONE), mName(name) {}

        bool addTask(const TaskWrapper& task);
        size_t getTaskCount() const;
        void exit();

    private:
        virtual status_t readyToRun() override;
        virtual bool threadLoop() override;

        // Lock for the list of tasks
        mutable Mutex mLock;
        std::vector<TaskWrapper> mTasks;

        // Signal used to wake up the thread when a new
        // task is available in the list
        mutable Signal mSignal;

        const String8 mName;
    };

    std::vector<sp<WorkerThread> > mThreads;
};

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_TASK_MANAGER_H
