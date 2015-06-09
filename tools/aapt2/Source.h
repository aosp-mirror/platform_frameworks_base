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

#ifndef AAPT_SOURCE_H
#define AAPT_SOURCE_H

#include <ostream>
#include <string>
#include <tuple>

namespace aapt {

struct SourceLineColumn;
struct SourceLine;

/**
 * Represents a file on disk. Used for logging and
 * showing errors.
 */
struct Source {
    std::string path;

    inline SourceLine line(size_t line) const;
};

/**
 * Represents a file on disk and a line number in that file.
 * Used for logging and showing errors.
 */
struct SourceLine {
    std::string path;
    size_t line;

    inline SourceLineColumn column(size_t column) const;
};

/**
 * Represents a file on disk and a line:column number in that file.
 * Used for logging and showing errors.
 */
struct SourceLineColumn {
    std::string path;
    size_t line;
    size_t column;
};

//
// Implementations
//

SourceLine Source::line(size_t line) const {
    return SourceLine{ path, line };
}

SourceLineColumn SourceLine::column(size_t column) const {
    return SourceLineColumn{ path, line, column };
}

inline ::std::ostream& operator<<(::std::ostream& out, const Source& source) {
    return out << source.path;
}

inline ::std::ostream& operator<<(::std::ostream& out, const SourceLine& source) {
    return out << source.path << ":" << source.line;
}

inline ::std::ostream& operator<<(::std::ostream& out, const SourceLineColumn& source) {
    return out << source.path << ":" << source.line << ":" << source.column;
}

inline bool operator<(const SourceLine& lhs, const SourceLine& rhs) {
    return std::tie(lhs.path, lhs.line) < std::tie(rhs.path, rhs.line);
}

} // namespace aapt

#endif // AAPT_SOURCE_H
