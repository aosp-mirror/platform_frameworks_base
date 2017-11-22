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
#pragma once

#include <iostream>

namespace android {
namespace os {
namespace statsd {
/*
 * Dump the heap memory of the calling process, sorted by total size
 * (allocation size * number of allocations).
 *
 *    limit is the number of unique allocations to return.
 */
extern std::string dumpMemInfo(size_t limit);

}  // namespace statsd
}  // namespace os
}  // namespace android