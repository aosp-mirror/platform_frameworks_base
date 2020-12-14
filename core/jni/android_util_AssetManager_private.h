/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_UTIL_ASSETMANAGER_PRIVATE_H
#define ANDROID_UTIL_ASSETMANAGER_PRIVATE_H

#include <optional>

#include <androidfw/Errors.h>
#include <android-base/expected.h>

#include "core_jni_helpers.h"
#include "jni.h"
#include "nativehelper/JNIHelp.h"

namespace android {

constexpr const char* kResourcesNotFound = "android/content/res/Resources$NotFoundException";
constexpr const static char* kIOErrorMessage = "failed to read resources.arsc data";

template <typename T, typename E>
static bool ThrowIfIOError(JNIEnv* env, const base::expected<T, E>& result) {
  if constexpr (std::is_same<NullOrIOError, E>::value) {
    if (IsIOError(result)) {
      jniThrowException(env, kResourcesNotFound, kIOErrorMessage);
      return true;
    }
     return false;
  } else {
    if (!result.has_value()) {
      static_assert(std::is_same<IOError, E>::value, "Unknown result error type");
      jniThrowException(env, kResourcesNotFound, kIOErrorMessage);
      return true;
    }
    return false;
  }
}

} // namespace android

#endif //ANDROID_UTIL_ASSETMANAGER_PRIVATE_H
