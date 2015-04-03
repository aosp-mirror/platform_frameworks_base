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

#ifndef AAPT_LOGGER_H
#define AAPT_LOGGER_H

#include "Source.h"

#include <memory>
#include <ostream>
#include <string>
#include <utils/String8.h>

namespace aapt {

struct Log {
    Log(std::ostream& out, std::ostream& err);
    Log(const Log& rhs) = delete;

    std::ostream& out;
    std::ostream& err;
};

class Logger {
public:
    static void setLog(const std::shared_ptr<Log>& log);

    static std::ostream& error();
    static std::ostream& error(const Source& source);
    static std::ostream& error(const SourceLine& sourceLine);

    static std::ostream& warn();
    static std::ostream& warn(const Source& source);
    static std::ostream& warn(const SourceLine& sourceLine);

    static std::ostream& note();
    static std::ostream& note(const Source& source);
    static std::ostream& note(const SourceLine& sourceLine);

private:
    static std::shared_ptr<Log> sLog;
};

class SourceLogger {
public:
    SourceLogger(const Source& source);

    std::ostream& error();
    std::ostream& error(size_t line);

    std::ostream& warn();
    std::ostream& warn(size_t line);

    std::ostream& note();
    std::ostream& note(size_t line);

private:
    Source mSource;
};

inline ::std::ostream& operator<<(::std::ostream& out, const std::u16string& str) {
    android::String8 utf8(str.data(), str.size());
    return out.write(utf8.string(), utf8.size());
}

} // namespace aapt

#endif // AAPT_LOGGER_H
