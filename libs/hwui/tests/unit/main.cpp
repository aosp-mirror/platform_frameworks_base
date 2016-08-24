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

#include "gtest/gtest.h"

#include "Caches.h"
#include "thread/TaskManager.h"
#include "tests/common/TestUtils.h"

#include <memunreachable/memunreachable.h>

#include <cstdio>
#include <iostream>
#include <map>
#include <unordered_set>
#include <signal.h>
#include <unistd.h>

using namespace std;
using namespace android;
using namespace android::uirenderer;

static auto CRASH_SIGNALS = {
        SIGABRT,
        SIGSEGV,
        SIGBUS,
};

static map<int, struct sigaction> gSigChain;

static void gtestSigHandler(int sig, siginfo_t* siginfo, void* context) {
    auto testinfo = ::testing::UnitTest::GetInstance()->current_test_info();
    printf("[  FAILED  ] %s.%s\n", testinfo->test_case_name(),
            testinfo->name());
    printf("[  FATAL!  ] Process crashed, aborting tests!\n");
    fflush(stdout);

    // restore the default sighandler and re-raise
    struct sigaction sa = gSigChain[sig];
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

static void logUnreachable(initializer_list<UnreachableMemoryInfo> infolist) {
    // merge them all
    UnreachableMemoryInfo merged;
    unordered_set<uintptr_t> addrs;
    merged.allocation_bytes = 0;
    merged.leak_bytes = 0;
    merged.num_allocations = 0;
    merged.num_leaks = 0;
    for (auto& info : infolist) {
        // We'll be a little hazzy about these ones and just hope the biggest
        // is the most accurate
        merged.allocation_bytes = max(merged.allocation_bytes, info.allocation_bytes);
        merged.num_allocations = max(merged.num_allocations, info.num_allocations);
        for (auto& leak : info.leaks) {
             if (addrs.find(leak.begin) == addrs.end()) {
                 merged.leaks.push_back(leak);
                 merged.num_leaks++;
                 merged.leak_bytes += leak.size;
                 addrs.insert(leak.begin);
             }
        }
    }

    // Now log the result
    if (merged.num_leaks) {
        cout << endl << "Leaked memory!" << endl;
        if (!merged.leaks[0].backtrace.num_frames) {
            cout << "Re-run with 'setprop libc.debug.malloc.program hwui_unit_test'"
                    << endl << "and 'setprop libc.debug.malloc.options backtrace=8'"
                    << " to get backtraces" << endl;
        }
        cout << merged.ToString(false);
    }
}

static void checkForLeaks() {
    // TODO: Until we can shutdown the RT thread we need to do this in
    // two passes as GetUnreachableMemory has limited insight into
    // thread-local caches so some leaks will not be properly tagged as leaks
    nsecs_t before = systemTime();
    UnreachableMemoryInfo rtMemInfo;
    TestUtils::runOnRenderThread([&rtMemInfo](renderthread::RenderThread& thread) {
        if (Caches::hasInstance()) {
            Caches::getInstance().tasks.stop();
        }
        // Check for leaks
        if (!GetUnreachableMemory(rtMemInfo)) {
            cerr << "Failed to get unreachable memory!" << endl;
            return;
        }
    });
    UnreachableMemoryInfo uiMemInfo;
    if (!GetUnreachableMemory(uiMemInfo)) {
        cerr << "Failed to get unreachable memory!" << endl;
        return;
    }
    logUnreachable({rtMemInfo, uiMemInfo});
    nsecs_t after = systemTime();
    cout << "Leak check took " << ns2ms(after - before) << "ms" << endl;
}

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

    // Run the tests
    testing::InitGoogleTest(&argc, argv);
    int ret = RUN_ALL_TESTS();
    checkForLeaks();
    return ret;
}

