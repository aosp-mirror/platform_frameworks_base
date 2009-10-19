/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "include/stagefright_string.h"

namespace android {

// static
string::size_type string::npos = (string::size_type)-1;

string::string() {
}

string::string(const char *s, size_t length)
    : mString(s, length) {
}

string::string(const string &from, size_type start, size_type length)
    : mString(from.c_str() + start, length) {
}

string::string(const char *s)
    : mString(s) {
}

const char *string::c_str() const {
    return mString.string();
}

string::size_type string::size() const {
    return mString.length();
}

void string::clear() {
    mString = String8();
}

string::size_type string::find(char c) const {
    char s[2];
    s[0] = c;
    s[1] = '\0';

    ssize_t index = mString.find(s);

    return index < 0 ? npos : (size_type)index;
}

bool string::operator<(const string &other) const {
    return mString < other.mString;
}

bool string::operator==(const string &other) const {
    return mString == other.mString;
}

string &string::operator+=(char c) {
    mString.append(&c, 1);

    return *this;
}

void string::erase(size_t from, size_t length) {
    String8 s(mString.string(), from);
    s.append(mString.string() + from + length);
    
    mString = s;
}

}  // namespace android

