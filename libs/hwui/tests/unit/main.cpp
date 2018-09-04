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

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "Caches.h"
#include "debug/GlesDriver.h"
#include "debug/NullGlesDriver.h"
#include "hwui/Typeface.h"
#include "Properties.h"
#include "tests/common/LeakChecker.h"
#include "thread/TaskManager.h"

#include <signal.h>

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

    // Replace the default GLES driver
    debug::GlesDriver::replace(std::make_unique<debug::NullGlesDriver>());
    Properties::isolatedProcess = true;

    // Run the tests
    testing::InitGoogleTest(&argc, argv);
    testing::InitGoogleMock(&argc, argv);

    testing::AddGlobalTestEnvironment(new TypefaceEnvironment());

    int ret = RUN_ALL_TESTS();
    test::LeakChecker::checkForLeaks();
    return ret;
}
