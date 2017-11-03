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

#include "LeakChecker.h"

#include "Caches.h"
#include "TestUtils.h"

#include <memunreachable/memunreachable.h>
#include <unistd.h>
#include <cstdio>
#include <iostream>
#include <unordered_set>

using namespace std;

namespace android {
namespace uirenderer {
namespace test {

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
            cout << "Re-run with 'export LIBC_DEBUG_MALLOC_OPTIONS=backtrace' to get backtraces"
                 << endl;
        }
        cout << merged.ToString(false);
    }
}

void LeakChecker::checkForLeaks() {
    // TODO: Until we can shutdown the RT thread we need to do this in
    // two passes as GetUnreachableMemory has limited insight into
    // thread-local caches so some leaks will not be properly tagged as leaks
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
}

} /* namespace test */
} /* namespace uirenderer */
} /* namespace android */
