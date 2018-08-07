/*
 * Copyright (C) 2017 The Android Open Source Project
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

#pragma once

#include <string>

namespace android {
namespace os {
namespace statsd {

extern uint32_t Hash32(const char *data, size_t n, uint32_t seed);
extern uint64_t Hash64(const char* data, size_t n, uint64_t seed);

inline uint32_t Hash32(const char *data, size_t n) {
  return Hash32(data, n, 0xBEEF);
}

inline uint32_t Hash32(const std::string &input) {
  return Hash32(input.data(), input.size());
}

inline uint64_t Hash64(const char* data, size_t n) {
  return Hash64(data, n, 0xDECAFCAFFE);
}

inline uint64_t Hash64(const std::string& str) {
  return Hash64(str.data(), str.size());
}

}  // namespace statsd
}  // namespace os
}  // namespace android
