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

namespace aapt {

/**
 * Holds basic information about the app being built. Most of this information
 * will come from the app's AndroidManifest.
 */
struct AppInfo {
    /**
     * App's package name.
     */
    std::u16string package;
};

} // namespace aapt

#endif // AAPT_APP_INFO_H
