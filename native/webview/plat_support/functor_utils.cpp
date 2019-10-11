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

#include "functor_utils.h"

#include <errno.h>
#include <string.h>
#include <sys/resource.h>
#include <utils/Log.h>

namespace android {

void RaiseFileNumberLimit() {
  static bool have_raised_limit = false;
  if (have_raised_limit)
    return;

  have_raised_limit = true;
  struct rlimit limit_struct;
  limit_struct.rlim_cur = 0;
  limit_struct.rlim_max = 0;
  if (getrlimit(RLIMIT_NOFILE, &limit_struct) == 0) {
    limit_struct.rlim_cur = limit_struct.rlim_max;
    if (setrlimit(RLIMIT_NOFILE, &limit_struct) != 0) {
      ALOGE("setrlimit failed: %s", strerror(errno));
    }
  } else {
    ALOGE("getrlimit failed: %s", strerror(errno));
  }
}

}  // namespace android
