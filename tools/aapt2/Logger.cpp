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
#include "Logger.h"
#include "Source.h"

#include <memory>
#include <iostream>

namespace aapt {

Log::Log(std::ostream& _out, std::ostream& _err) : out(_out), err(_err) {
}

std::shared_ptr<Log> Logger::sLog(std::make_shared<Log>(std::cerr, std::cerr));

void Logger::setLog(const std::shared_ptr<Log>& log) {
    sLog = log;
}

std::ostream& Logger::error() {
    return sLog->err << "error: ";
}

std::ostream& Logger::error(const Source& source) {
    return sLog->err << source << ": error: ";
}

std::ostream& Logger::error(const SourceLine& source) {
    return sLog->err << source << ": error: ";
}

std::ostream& Logger::warn() {
    return sLog->err << "warning: ";
}

std::ostream& Logger::warn(const Source& source) {
    return sLog->err << source << ": warning: ";
}

std::ostream& Logger::warn(const SourceLine& source) {
    return sLog->err << source << ": warning: ";
}

std::ostream& Logger::note() {
    return sLog->out << "note: ";
}

std::ostream& Logger::note(const Source& source) {
    return sLog->err << source << ": note: ";
}

std::ostream& Logger::note(const SourceLine& source) {
    return sLog->err << source << ": note: ";
}

SourceLogger::SourceLogger(const Source& source)
: mSource(source) {
}

std::ostream& SourceLogger::error() {
    return Logger::error(mSource);
}

std::ostream& SourceLogger::error(size_t line) {
    return Logger::error(SourceLine{ mSource.path, line });
}

std::ostream& SourceLogger::warn() {
    return Logger::warn(mSource);
}

std::ostream& SourceLogger::warn(size_t line) {
    return Logger::warn(SourceLine{ mSource.path, line });
}

std::ostream& SourceLogger::note() {
    return Logger::note(mSource);
}

std::ostream& SourceLogger::note(size_t line) {
    return Logger::note(SourceLine{ mSource.path, line });
}

} // namespace aapt
