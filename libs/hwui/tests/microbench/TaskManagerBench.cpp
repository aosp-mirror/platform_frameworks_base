/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <benchmark/Benchmark.h>

#include "thread/Task.h"
#include "thread/TaskManager.h"
#include "thread/TaskProcessor.h"
#include "tests/microbench/MicroBench.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;

class TrivialTask : public Task<char> {};

class TrivialProcessor : public TaskProcessor<char> {
public:
    TrivialProcessor(TaskManager* manager)
            : TaskProcessor(manager) {}
    virtual ~TrivialProcessor() {}
    virtual void onProcess(const sp<Task<char> >& task) override {
        TrivialTask* t = static_cast<TrivialTask*>(task.get());
        t->setResult(reinterpret_cast<intptr_t>(t) % 16 == 0 ? 'a' : 'b');
    }
};

BENCHMARK_NO_ARG(BM_TaskManager_allocateTask);
void BM_TaskManager_allocateTask::Run(int iters) {
    std::vector<sp<TrivialTask> > tasks;
    tasks.reserve(iters);

    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        tasks.emplace_back(new TrivialTask);
        MicroBench::DoNotOptimize(tasks.back());
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_TaskManager_enqueueTask);
void BM_TaskManager_enqueueTask::Run(int iters) {
    TaskManager taskManager;
    sp<TrivialProcessor> processor(new TrivialProcessor(&taskManager));
    std::vector<sp<TrivialTask> > tasks;
    tasks.reserve(iters);

    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        tasks.emplace_back(new TrivialTask);
        MicroBench::DoNotOptimize(tasks.back());
        processor->add(tasks.back());
    }
    StopBenchmarkTiming();

    for (sp<TrivialTask>& task : tasks) {
        task->getResult();
    }
}

BENCHMARK_NO_ARG(BM_TaskManager_enqueueRunDeleteTask);
void BM_TaskManager_enqueueRunDeleteTask::Run(int iters) {
    TaskManager taskManager;
    sp<TrivialProcessor> processor(new TrivialProcessor(&taskManager));
    std::vector<sp<TrivialTask> > tasks;
    tasks.reserve(iters);

    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        tasks.emplace_back(new TrivialTask);
        MicroBench::DoNotOptimize(tasks.back());
        processor->add(tasks.back());
    }
    for (sp<TrivialTask>& task : tasks) {
        MicroBench::DoNotOptimize(task->getResult());
    }
    tasks.clear();
    StopBenchmarkTiming();
}
