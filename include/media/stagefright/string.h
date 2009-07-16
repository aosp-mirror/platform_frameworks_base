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

#ifndef STRING_H_

#define STRING_H_

#include <utils/String8.h>

namespace android {

class string {
public:
    typedef size_t size_type;
    static size_type npos;

    string();
    string(const char *s);
    string(const char *s, size_t length);
    string(const string &from, size_type start, size_type length = npos);

    const char *c_str() const;
    size_type size() const;

    void clear();
    void erase(size_type from, size_type length);

    size_type find(char c) const;

    bool operator<(const string &other) const;
    bool operator==(const string &other) const;

    string &operator+=(char c);

private:
    String8 mString;
};

}  // namespace android

#endif  // STRING_H_
