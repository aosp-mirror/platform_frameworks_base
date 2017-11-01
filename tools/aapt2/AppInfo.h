/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_APP_INFO_H
#define AAPT_APP_INFO_H

#include <string>

#include "util/Maybe.h"

namespace aapt {

// Information relevant to building an app, parsed from the app's AndroidManifest.xml.
struct AppInfo {
  // The app's package name.
  std::string package;

  // The app's minimum SDK version, if it is defined.
  Maybe<int> min_sdk_version;

  // The app's version code, if it is defined.
  Maybe<uint32_t> version_code;

  // The app's revision code, if it is defined.
  Maybe<uint32_t> revision_code;

  // The app's split name, if it is a split.
  Maybe<std::string> split_name;
};

}  // namespace aapt

#endif  // AAPT_APP_INFO_H
