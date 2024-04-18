/*
 * Copyright (C) 2024 The Android Open Source Project
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

#ifndef SHAREDLIB_H
#define SHAREDLIB_H

#include <string>

namespace android {
namespace uirenderer {

class SharedLib {
public:
    static void* openSharedLib(std::string filename);
    static void* getSymbol(void* library, const char* symbol);
};

} /* namespace uirenderer */
} /* namespace android */

#endif  // SHAREDLIB_H
