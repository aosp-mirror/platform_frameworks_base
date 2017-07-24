/*
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "incident_helper"

#include "strutil.h"

#include <sstream>

std::string trim(const std::string& s, const std::string& whitespace) {
    const auto head = s.find_first_not_of(whitespace);
    if (head == std::string::npos) return "";

    const auto tail = s.find_last_not_of(whitespace);
    return s.substr(head, tail - head + 1);
}

// This is similiar to Split in android-base/file.h, but it won't add empty string
void split(const std::string& line, std::vector<std::string>* words, const std::string& delimiters) {
    words->clear();  // clear the buffer before split

    size_t base = 0;
    size_t found;
    while (true) {
        found = line.find_first_of(delimiters, base);
        if (found != base) { // ignore empty string
            // one char before found
            words->push_back(line.substr(base, found - base));
        }
        if (found == line.npos) break;
        base = found + 1;
    }
}

bool assertHeaders(const char* expected[], const std::vector<std::string>& actual) {
    for (size_t i = 0; i < actual.size(); i++) {
        if (expected[i] == NULL || std::string(expected[i]) != actual.at(i)) {
            return false;
        }
    }
    return true;
}
