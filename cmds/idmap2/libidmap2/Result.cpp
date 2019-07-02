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

#include "idmap2/Result.h"

#include <cstdarg>

#include "android-base/stringprintf.h"

namespace android::idmap2 {

// NOLINTNEXTLINE(cert-dcl50-cpp)
Error::Error(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  base::StringAppendV(&msg_, fmt, ap);
  va_end(ap);
}

// NOLINTNEXTLINE(cert-dcl50-cpp)
Error::Error(const Error& parent, const char* fmt, ...) : msg_(parent.msg_) {
  msg_.append(" -> ");

  va_list ap;
  va_start(ap, fmt);
  base::StringAppendV(&msg_, fmt, ap);
  va_end(ap);
}

}  // namespace android::idmap2
