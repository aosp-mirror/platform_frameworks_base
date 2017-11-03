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

#include <benchmark/benchmark.h>

#include "thread/Task.h"
#include "thread/TaskManager.h"
#include "thread/TaskProcessor.h"
#include "thread/ThreadBase.h"

#include <atomic>
#include <vector>

using namespace android;
using namespace android::uirenderer;

class TrivialTask : public Task<char> {};

class TrivialProcessor : public TaskProcessor<char> {
public:
    explicit TrivialProcessor(TaskManager* manager) : TaskProcessor(manager) {}
    virtual ~TrivialProcessor() {}
    virtual void onProcess(const sp<Task<char>>& task) override {
        TrivialTask* t = static_cast<TrivialTask*>(task.get());
        t->setResult(reinterpret_cast<intptr_t>(t) % 16 == 0 ? 'a' : 'b');
    }
};

class TestThread : public ThreadBase, public virtual RefBase {};

void BM_TaskManager_allocateTask(benchmark::State& state) {
    std::vector<sp<TrivialTask>> tasks;
    tasks.reserve(state.max_iterations);

    while (state.KeepRunning()) {
        tasks.emplace_back(new TrivialTask);
        benchmark::DoNotOptimize(tasks.back());
    }
}
BENCHMARK(BM_TaskManager_allocateTask);

void BM_TaskManager_enqueueTask(benchmark::State& state) {
    TaskManager taskManager;
    sp<TrivialProcessor> processor(new TrivialProcessor(&taskManager));
    std::vector<sp<TrivialTask>> tasks;
    tasks.reserve(state.max_iterations);

    while (state.KeepRunning()) {
        tasks.emplace_back(new TrivialTask);
        benchmark::DoNotOptimize(tasks.back());
        processor->add(tasks.back());
    }

    for (sp<TrivialTask>& task : tasks) {
        task->getResult();
    }
}
BENCHMARK(BM_TaskManager_enqueueTask);

void BM_TaskManager_enqueueRunDeleteTask(benchmark::State& state) {
    TaskManager taskManager;
    sp<TrivialProcessor> processor(new TrivialProcessor(&taskManager));
    std::vector<sp<TrivialTask>> tasks;
    tasks.reserve(state.max_iterations);

    while (state.KeepRunning()) {
        tasks.emplace_back(new TrivialTask);
        benchmark::DoNotOptimize(tasks.back());
        processor->add(tasks.back());
    }
    state.ResumeTiming();
    for (sp<TrivialTask>& task : tasks) {
        benchmark::DoNotOptimize(task->getResult());
    }
    tasks.clear();
    state.PauseTiming();
}
BENCHMARK(BM_TaskManager_enqueueRunDeleteTask);

void BM_Thread_enqueueTask(benchmark::State& state) {
    sp<TestThread> thread{new TestThread};
    thread->start();

    atomic_int counter(0);
    int expected = 0;
    while (state.KeepRunning()) {
        expected++;
        thread->queue().post([&counter]() { counter++; });
    }
    thread->queue().runSync([]() {});

    thread->requestExit();
    thread->join();
    if (counter != expected) {
        printf("Ran %d lambads, should have been %d\n", counter.load(), expected);
    }
}
BENCHMARK(BM_Thread_enqueueTask);

void BM_Thread_enqueueRunDeleteTask(benchmark::State& state) {
    sp<TestThread> thread{new TestThread};
    thread->start();
    std::vector<std::future<int>> tasks;
    tasks.reserve(state.max_iterations);

    int expected = 0;
    while (state.KeepRunning()) {
        tasks.emplace_back(thread->queue().async([expected]() -> int { return expected + 1; }));
        expected++;
    }
    state.ResumeTiming();
    expected = 0;
    for (auto& future : tasks) {
        if (future.get() != ++expected) {
            printf("Mismatch expected %d vs. observed %d\n", expected, future.get());
        }
    }
    tasks.clear();
    state.PauseTiming();
}
BENCHMARK(BM_Thread_enqueueRunDeleteTask);