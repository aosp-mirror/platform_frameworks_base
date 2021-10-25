/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_LOGINFO_H_
#define IDMAP2_INCLUDE_IDMAP2_LOGINFO_H_

#include <algorithm>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

#if __ANDROID__
#include "android-base/logging.h"
#else
#include <iostream>
#endif

namespace android::idmap2 {

class LogMessage {
 public:
  LogMessage() = default;

  template <typename T>
  LogMessage& operator<<(const T& value) {
    stream_ << value;
    return *this;
  }

  std::string GetString() const {
    return stream_.str();
  }

 private:
  std::stringstream stream_;
};

class LogInfo {
 public:
  LogInfo() = default;

  inline void Info(const LogMessage& msg) {
    lines_.push_back("I " + msg.GetString());
  }

  inline void Warning(const LogMessage& msg) {
#ifdef __ANDROID__
    LOG(WARNING) << msg.GetString();
#else
    std::cerr << "W " << msg.GetString() << std::endl;
#endif
    lines_.push_back("W " + msg.GetString());
  }

  inline std::string GetString() const {
    std::ostringstream stream;
    std::copy(lines_.begin(), lines_.end(), std::ostream_iterator<std::string>(stream, "\n"));
    return stream.str();
  }

 private:
  std::vector<std::string> lines_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_LOGINFO_H_
