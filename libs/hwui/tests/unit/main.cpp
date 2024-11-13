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

#include <getopt.h>
#include <log/log.h>
#include <signal.h>

#include "Properties.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "hwui/Typeface.h"
#include "tests/common/LeakChecker.h"

using namespace std;
using namespace android;
using namespace android::uirenderer;

static auto CRASH_SIGNALS = {
        SIGABRT, SIGSEGV, SIGBUS,
};

static map<int, struct sigaction> gSigChain;

static void gtestSigHandler(int sig, siginfo_t* siginfo, void* context) {
    auto testinfo = ::testing::UnitTest::GetInstance()->current_test_info();
    printf("[  FAILED  ] %s.%s\n", testinfo->test_case_name(), testinfo->name());
    printf("[  FATAL!  ] Process crashed, aborting tests!\n");
    fflush(stdout);

    // restore the default sighandler and re-raise
    struct sigaction sa = gSigChain[sig];
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

// For options that only exist in long-form. Anything in the
// 0-255 range is reserved for short options (which just use their ASCII value)
namespace LongOpts {
enum {
    Reserved = 255,
    Renderer,
};
}

static const struct option LONG_OPTIONS[] = {
        {"renderer", required_argument, nullptr, LongOpts::Renderer}, {0, 0, 0, 0}};

static RenderPipelineType parseRenderer(const char* renderer) {
    // Anything that's not skiavk is skiagl
    if (!strcmp(renderer, "skiavk")) {
        return RenderPipelineType::SkiaVulkan;
    }
    return RenderPipelineType::SkiaGL;
}

static constexpr const char* renderPipelineTypeName(const RenderPipelineType renderPipelineType) {
    switch (renderPipelineType) {
        case RenderPipelineType::SkiaGL:
            return "SkiaGL";
        case RenderPipelineType::SkiaVulkan:
            return "SkiaVulkan";
        case RenderPipelineType::SkiaCpu:
            return "SkiaCpu";
        case RenderPipelineType::NotInitialized:
            return "NotInitialized";
    }
}

struct Options {
    RenderPipelineType renderer = RenderPipelineType::SkiaGL;
};

Options parseOptions(int argc, char* argv[]) {
    int c;
    opterr = 0;
    Options opts;

    while (true) {
        /* getopt_long stores the option index here. */
        int option_index = 0;

        c = getopt_long(argc, argv, "", LONG_OPTIONS, &option_index);

        if (c == -1) break;

        switch (c) {
            case 0:
                // Option set a flag, don't need to do anything
                // (although none of the current LONG_OPTIONS do this...)
                break;

            case LongOpts::Renderer:
                opts.renderer = parseRenderer(optarg);
                break;
        }
    }
    return opts;
}

class TypefaceEnvironment : public testing::Environment {
public:
    virtual void SetUp() { Typeface::setRobotoTypefaceForTest(); }
};

int main(int argc, char* argv[]) {
    // Register a crash handler
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = &gtestSigHandler;
    sa.sa_flags = SA_SIGINFO;
    for (auto sig : CRASH_SIGNALS) {
        struct sigaction old_sa;
        sigaction(sig, &sa, &old_sa);
        gSigChain.insert(pair<int, struct sigaction>(sig, old_sa));
    }

    // Avoid talking to SF
    Properties::isolatedProcess = true;

    auto opts = parseOptions(argc, argv);
    Properties::overrideRenderPipelineType(opts.renderer);
    ALOGI("Starting HWUI unit tests with %s pipeline", renderPipelineTypeName(opts.renderer));

    // Run the tests
    testing::InitGoogleTest(&argc, argv);
    testing::InitGoogleMock(&argc, argv);

    testing::AddGlobalTestEnvironment(new TypefaceEnvironment());

    int ret = RUN_ALL_TESTS();
    test::LeakChecker::checkForLeaks();
    return ret;
}
