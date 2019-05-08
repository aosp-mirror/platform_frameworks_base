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

#include "AnimationContext.h"
#include "RenderNode.h"
#include "renderthread/RenderProxy.h"
#include "renderthread/RenderTask.h"
#include "tests/common/TestContext.h"
#include "tests/common/TestScene.h"
#include "tests/common/scenes/TestSceneBase.h"
#include "utils/TraceUtils.h"

#include <benchmark/benchmark.h>
#include <gui/Surface.h>
#include <log/log.h>
#include <ui/PixelFormat.h>

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

void outputBenchmarkReport(const TestScene::Info& info, const TestScene::Options& opts,
                           benchmark::BenchmarkReporter* reporter, RenderProxy* proxy,
                           double durationInS) {
    using namespace benchmark;

    struct ReportInfo {
        int percentile;
        const char* suffix;
    };

    static std::array<ReportInfo, 4> REPORTS = {
            ReportInfo{50, "_50th"}, ReportInfo{90, "_90th"}, ReportInfo{95, "_95th"},
            ReportInfo{99, "_99th"},
    };

    // Although a vector is used, it must stay with only a single element
    // otherwise the BenchmarkReporter will automatically compute
    // mean and stddev which doesn't make sense for our usage
    std::vector<BenchmarkReporter::Run> reports;
    BenchmarkReporter::Run report;
    report.run_name.function_name = info.name;
    report.iterations = static_cast<int64_t>(opts.count);
    report.real_accumulated_time = durationInS;
    report.cpu_accumulated_time = durationInS;
    report.counters["items_per_second"] = opts.count / durationInS;
    reports.push_back(report);
    reporter->ReportRuns(reports);

    // Pretend the percentiles are single-iteration runs of the test
    // If rendering offscreen skip this as it's fps that's more interesting
    // in that test case than percentiles.
    if (!opts.renderOffscreen) {
        for (auto& ri : REPORTS) {
            reports[0].run_name.function_name = info.name;
            reports[0].run_name.function_name += ri.suffix;
            durationInS = proxy->frameTimePercentile(ri.percentile) / 1000.0;
            reports[0].real_accumulated_time = durationInS;
            reports[0].cpu_accumulated_time = durationInS;
            reports[0].iterations = 1;
            reports[0].counters["items_per_second"] = 0;
            reporter->ReportRuns(reports);
        }
    }
}

void run(const TestScene::Info& info, const TestScene::Options& opts,
         benchmark::BenchmarkReporter* reporter) {
    // Switch to the real display
    gDisplay = getInternalDisplay();

    Properties::forceDrawFrame = true;
    TestContext testContext;
    testContext.setRenderOffscreen(opts.renderOffscreen);

    // create the native surface
    const int width = gDisplay.w;
    const int height = gDisplay.h;
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
    proxy->setSurface(surface);
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
        nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
        UiFrameInfoBuilder(proxy->frameInfo()).setVsync(vsync, vsync);
        proxy->syncAndDrawFrame();
    }

    proxy->resetProfileInfo();
    proxy->fence();

    if (opts.renderAhead) {
        usleep(33000);
    }
    proxy->setRenderAheadDepth(opts.renderAhead);

    ModifiedMovingAverage<double> avgMs(opts.reportFrametimeWeight);

    nsecs_t start = systemTime(CLOCK_MONOTONIC);
    for (int i = 0; i < opts.count; i++) {
        testContext.waitForVsync();
        nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
        {
            ATRACE_NAME("UI-Draw Frame");
            UiFrameInfoBuilder(proxy->frameInfo()).setVsync(vsync, vsync);
            scene->doFrame(i);
            proxy->syncAndDrawFrame();
        }
        if (opts.reportFrametimeWeight) {
            proxy->fence();
            nsecs_t done = systemTime(CLOCK_MONOTONIC);
            avgMs.add((done - vsync) / 1000000.0);
            if (i % 10 == 9) {
                printf("Average frametime %.3fms\n", avgMs.average());
            }
        }
    }
    proxy->fence();
    nsecs_t end = systemTime(CLOCK_MONOTONIC);

    if (reporter) {
        outputBenchmarkReport(info, opts, reporter, proxy.get(), (end - start) / (double)s2ns(1));
    } else {
        proxy->dumpProfileInfo(STDOUT_FILENO, DumpFlags::JankStats);
    }
}
