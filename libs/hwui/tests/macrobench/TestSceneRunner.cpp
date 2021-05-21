/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <gui/TraceUtils.h>
#include "AnimationContext.h"
#include "RenderNode.h"
#include "renderthread/RenderProxy.h"
#include "renderthread/RenderTask.h"
#include "tests/common/TestContext.h"
#include "tests/common/TestScene.h"
#include "tests/common/scenes/TestSceneBase.h"

#include <benchmark/benchmark.h>
#include <gui/Surface.h>
#include <log/log.h>
#include <ui/PixelFormat.h>

// These are unstable internal APIs in google-benchmark. We should just implement our own variant
// of these instead, but this was quicker. Disabled-by-default to avoid any breakages when
// google-benchmark updates if they change anything
#if 0
#define USE_SKETCHY_INTERNAL_STATS
namespace benchmark {
std::vector<BenchmarkReporter::Run> ComputeStats(
        const std::vector<BenchmarkReporter::Run> &reports);
double StatisticsMean(const std::vector<double>& v);
double StatisticsMedian(const std::vector<double>& v);
double StatisticsStdDev(const std::vector<double>& v);
}
#endif

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override {
        return new AnimationContext(clock);
    }
};

template <class T>
class ModifiedMovingAverage {
public:
    explicit ModifiedMovingAverage(int weight) : mWeight(weight) {}

    T add(T today) {
        if (!mHasValue) {
            mAverage = today;
        } else {
            mAverage = (((mWeight - 1) * mAverage) + today) / mWeight;
        }
        return mAverage;
    }

    T average() { return mAverage; }

private:
    bool mHasValue = false;
    int mWeight;
    T mAverage;
};

using BenchmarkResults = std::vector<benchmark::BenchmarkReporter::Run>;

void outputBenchmarkReport(const TestScene::Info& info, const TestScene::Options& opts,
                           double durationInS, int repetationIndex, BenchmarkResults* reports) {
    using namespace benchmark;
    benchmark::BenchmarkReporter::Run report;
    report.repetitions = opts.repeatCount;
    report.repetition_index = repetationIndex;
    report.run_name.function_name = info.name;
    report.iterations = static_cast<int64_t>(opts.frameCount);
    report.real_accumulated_time = durationInS;
    report.cpu_accumulated_time = durationInS;
    report.counters["FPS"] = opts.frameCount / durationInS;
    if (opts.reportGpuMemoryUsage) {
        size_t cpuUsage, gpuUsage;
        RenderProxy::getMemoryUsage(&cpuUsage, &gpuUsage);
        report.counters["Rendering RAM"] = Counter{static_cast<double>(cpuUsage + gpuUsage),
                                                   Counter::kDefaults, Counter::kIs1024};
    }
    reports->push_back(report);
}

static void doRun(const TestScene::Info& info, const TestScene::Options& opts, int repetitionIndex,
                  BenchmarkResults* reports) {
    if (opts.reportGpuMemoryUsage) {
        // If we're reporting GPU memory usage we need to first start with a clean slate
        RenderProxy::purgeCaches();
    }
    Properties::forceDrawFrame = true;
    TestContext testContext;
    testContext.setRenderOffscreen(opts.renderOffscreen);

    // create the native surface
    const ui::Size& resolution = getActiveDisplayResolution();
    const int width = resolution.getWidth();
    const int height = resolution.getHeight();
    sp<Surface> surface = testContext.surface();

    std::unique_ptr<TestScene> scene(info.createScene(opts));
    scene->renderTarget = surface;

    sp<RenderNode> rootNode = TestUtils::createNode(
            0, 0, width, height, [&scene, width, height](RenderProperties& props, Canvas& canvas) {
                props.setClipToBounds(false);
                scene->createContent(width, height, canvas);
            });

    ContextFactory factory;
    std::unique_ptr<RenderProxy> proxy(new RenderProxy(false, rootNode.get(), &factory));
    proxy->loadSystemProperties();
    proxy->setSurface(surface.get());
    float lightX = width / 2.0;
    proxy->setLightAlpha(255 * 0.075, 255 * 0.15);
    proxy->setLightGeometry((Vector3){lightX, dp(-200.0f), dp(800.0f)}, dp(800.0f));

    // Do a few cold runs then reset the stats so that the caches are all hot
    int warmupFrameCount = 5;
    if (opts.renderOffscreen) {
        // Do a few more warmups to try and boost the clocks up
        warmupFrameCount = 10;
    }
    for (int i = 0; i < warmupFrameCount; i++) {
        testContext.waitForVsync();
        nsecs_t vsync = systemTime(SYSTEM_TIME_MONOTONIC);
        UiFrameInfoBuilder(proxy->frameInfo())
            .setVsync(vsync, vsync, UiFrameInfoBuilder::INVALID_VSYNC_ID,
                      UiFrameInfoBuilder::UNKNOWN_DEADLINE,
                      UiFrameInfoBuilder::UNKNOWN_FRAME_INTERVAL);
        proxy->syncAndDrawFrame();
    }

    proxy->resetProfileInfo();
    proxy->fence();

    ModifiedMovingAverage<double> avgMs(opts.reportFrametimeWeight);

    nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
    for (int i = 0; i < opts.frameCount; i++) {
        testContext.waitForVsync();
        nsecs_t vsync = systemTime(SYSTEM_TIME_MONOTONIC);
        {
            ATRACE_NAME("UI-Draw Frame");
            UiFrameInfoBuilder(proxy->frameInfo())
                .setVsync(vsync, vsync, UiFrameInfoBuilder::INVALID_VSYNC_ID,
                          UiFrameInfoBuilder::UNKNOWN_DEADLINE,
                          UiFrameInfoBuilder::UNKNOWN_FRAME_INTERVAL);
            scene->doFrame(i);
            proxy->syncAndDrawFrame();
        }
        if (opts.reportFrametimeWeight) {
            proxy->fence();
            nsecs_t done = systemTime(SYSTEM_TIME_MONOTONIC);
            avgMs.add((done - vsync) / 1000000.0);
            if (i % 10 == 9) {
                printf("Average frametime %.3fms\n", avgMs.average());
            }
        }
    }
    proxy->fence();
    nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);

    if (reports) {
        outputBenchmarkReport(info, opts, (end - start) / (double)s2ns(1), repetitionIndex,
                              reports);
    } else {
        proxy->dumpProfileInfo(STDOUT_FILENO, DumpFlags::JankStats);
    }
}

void run(const TestScene::Info& info, const TestScene::Options& opts,
         benchmark::BenchmarkReporter* reporter) {
    BenchmarkResults results;
    for (int i = 0; i < opts.repeatCount; i++) {
        doRun(info, opts, i, reporter ? &results : nullptr);
    }
    if (reporter) {
        reporter->ReportRuns(results);
        if (results.size() > 1) {
#ifdef USE_SKETCHY_INTERNAL_STATS
            std::vector<benchmark::internal::Statistics> stats;
            stats.reserve(3);
            stats.emplace_back("mean", benchmark::StatisticsMean);
            stats.emplace_back("median", benchmark::StatisticsMedian);
            stats.emplace_back("stddev", benchmark::StatisticsStdDev);
            for (auto& it : results) {
                it.statistics = &stats;
            }
            auto summary = benchmark::ComputeStats(results);
            reporter->ReportRuns(summary);
#endif
        }
    }
    if (opts.reportGpuMemoryUsageVerbose) {
        RenderProxy::dumpGraphicsMemory(STDOUT_FILENO, false);
    }
}
