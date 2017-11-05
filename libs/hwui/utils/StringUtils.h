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

#include <iomanip>
#include <iostream>
#include <ostream>
#include <sstream>
#include <string>
#include <unordered_set>

#include <utils/Log.h>

namespace android {
namespace uirenderer {

class unordered_string_set : public std::unordered_set<std::string> {
public:
    bool has(const char* str) { return find(std::string(str)) != end(); }
};

class StringUtils {
public:
    static unordered_string_set split(const char* spacedList);
};

struct SizePrinter {
    int bytes;
    friend std::ostream& operator<<(std::ostream& stream, const SizePrinter& d) {
        static const char* SUFFIXES[] = {"B", "KiB", "MiB"};
        size_t suffix = 0;
        double temp = d.bytes;
        while (temp > 1024 && suffix < 2) {
            temp /= 1024.0;
            suffix++;
        }
        stream << std::fixed << std::setprecision(2) << temp << SUFFIXES[suffix];
        return stream;
    }
};

class LogcatStream : public std::ostream {
    class LogcatStreamBuf : public std::stringbuf {
        virtual int sync() {
            ALOGD("%s", str().c_str());
            str("");
            return 0;
        }
    };

    LogcatStreamBuf buffer;

public:
    LogcatStream() : std::ostream(&buffer) {}
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* GLUTILS_H */
