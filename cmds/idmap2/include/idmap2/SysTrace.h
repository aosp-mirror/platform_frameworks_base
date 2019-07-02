/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_SYSTRACE_H_
#define IDMAP2_INCLUDE_IDMAP2_SYSTRACE_H_

#define ATRACE_TAG ATRACE_TAG_RRO

#include <sstream>
#include <vector>

#include "cutils/trace.h"

namespace android::idmap2::utils {
#ifdef __ANDROID__

class ScopedTraceNoStart {
 public:
  ~ScopedTraceNoStart() {
    ATRACE_END();
  }
};

class ScopedTraceMessageHelper {
 public:
  ~ScopedTraceMessageHelper() {
    ATRACE_BEGIN(buffer_.str().c_str());
  }

  std::ostream& stream() {
    return buffer_;
  }

 private:
  std::ostringstream buffer_;
};

#define SYSTRACE                                               \
  android::idmap2::utils::ScopedTraceNoStart _trace##__LINE__; \
  (ATRACE_ENABLED()) && android::idmap2::utils::ScopedTraceMessageHelper().stream()

#else

class DummyStream {
 public:
  std::ostream& stream() {
    return buffer_;
  }

 private:
  std::ostringstream buffer_;
};

#define SYSTRACE android::idmap2::utils::DummyStream().stream()

#endif
}  // namespace android::idmap2::utils

template <typename T>
std::ostream& operator<<(std::ostream& stream, const std::vector<T>& vector) {
  bool first = true;
  stream << "[";
  for (const auto& item : vector) {
    if (!first) {
      stream << ", ";
    }
    stream << item;
    first = false;
  }
  stream << "]";
  return stream;
}

#endif  // IDMAP2_INCLUDE_IDMAP2_SYSTRACE_H_
