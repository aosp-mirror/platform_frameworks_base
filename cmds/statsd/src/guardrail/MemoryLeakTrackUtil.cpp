/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <sstream>
#include "MemoryLeakTrackUtil.h"

/*
 * The code here originally resided in MediaPlayerService.cpp
 */

// Figure out the abi based on defined macros.
#if defined(__arm__)
#define ABI_STRING "arm"
#elif defined(__aarch64__)
#define ABI_STRING "arm64"
#elif defined(__mips__) && !defined(__LP64__)
#define ABI_STRING "mips"
#elif defined(__mips__) && defined(__LP64__)
#define ABI_STRING "mips64"
#elif defined(__i386__)
#define ABI_STRING "x86"
#elif defined(__x86_64__)
#define ABI_STRING "x86_64"
#else
#error "Unsupported ABI"
#endif

extern std::string backtrace_string(const uintptr_t* frames, size_t frame_count);

namespace android {
namespace os {
namespace statsd {

extern "C" void get_malloc_leak_info(uint8_t** info, size_t* overallSize, size_t* infoSize,
                                     size_t* totalMemory, size_t* backtraceSize);

extern "C" void free_malloc_leak_info(uint8_t* info);

std::string dumpMemInfo(size_t limit) {
    uint8_t* info;
    size_t overallSize;
    size_t infoSize;
    size_t totalMemory;
    size_t backtraceSize;
    get_malloc_leak_info(&info, &overallSize, &infoSize, &totalMemory, &backtraceSize);

    size_t count;
    if (info == nullptr || overallSize == 0 || infoSize == 0 ||
        (count = overallSize / infoSize) == 0) {
        VLOG("no malloc info, libc.debug.malloc.program property should be set");
        return std::string();
    }

    std::ostringstream oss;
    oss << totalMemory << " bytes in " << count << " allocations\n";
    oss << "  ABI: '" ABI_STRING "'"
        << "\n\n";
    if (count > limit) count = limit;

    // The memory is sorted based on total size which is useful for finding
    // worst memory offenders. For diffs, sometimes it is preferable to sort
    // based on the backtrace.
    for (size_t i = 0; i < count; i++) {
        struct AllocEntry {
            size_t size;  // bit 31 is set if this is zygote allocated memory
            size_t allocations;
            uintptr_t backtrace[];
        };

        const AllocEntry* const e = (AllocEntry*)(info + i * infoSize);

        oss << (e->size * e->allocations) << " bytes ( " << e->size << " bytes * " << e->allocations
            << " allocations )\n";
        oss << backtrace_string(e->backtrace, backtraceSize) << "\n";
    }
    oss << "\n";
    free_malloc_leak_info(info);
    return oss.str();
}

}  // namespace statsd
}  // namespace os
}  // namespace android