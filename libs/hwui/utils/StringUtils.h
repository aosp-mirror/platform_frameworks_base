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
#ifndef STRING_UTILS_H
#define STRING_UTILS_H

#include <string>
#include <unordered_set>

namespace android {
namespace uirenderer {

class StringCollection {
public:
    StringCollection(const char* spacedList);
    bool has(const char* string);
private:
    std::unordered_set<std::string> mSet;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* GLUTILS_H */
