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

#ifndef STRUTIL_H
#define STRUTIL_H

#include <string>
#include <vector>

const std::string DEFAULT_WHITESPACE = " \t";

std::string trim(const std::string& s, const std::string& whitespace = DEFAULT_WHITESPACE);
void split(const std::string& line, std::vector<std::string>* words,
    const std::string& delimiters = DEFAULT_WHITESPACE);
bool assertHeaders(const char* expected[], const std::vector<std::string>& actual);

#endif  // STRUTIL_H
