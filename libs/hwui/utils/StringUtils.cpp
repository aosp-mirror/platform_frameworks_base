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

#include "StringUtils.h"

namespace android {
namespace uirenderer {

unordered_string_set StringUtils::split(const char* spacedList) {
    unordered_string_set set;
    const char* current = spacedList;
    const char* head = current;
    do {
        head = strchr(current, ' ');
        std::string s(current, head ? head - current : strlen(current));
        if (s.length()) {
            set.insert(std::move(s));
        }
        current = head + 1;
    } while (head);
    return set;
}

};  // namespace uirenderer
};  // namespace android
