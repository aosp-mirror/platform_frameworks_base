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

#include "util/Maybe.h"
#include "util/StringPiece.h"

#include <ostream>
#include <string>

namespace aapt {

/**
 * Represents a file on disk. Used for logging and
 * showing errors.
 */
struct Source {
    std::string path;
    Maybe<size_t> line;

    Source() = default;

    inline Source(const StringPiece& path) : path(path.toString()) {
    }

    inline Source(const StringPiece& path, size_t line) : path(path.toString()), line(line) {
    }

    inline Source withLine(size_t line) const {
        return Source(path, line);
    }
};

//
// Implementations
//

inline ::std::ostream& operator<<(::std::ostream& out, const Source& source) {
    out << source.path;
    if (source.line) {
        out << ":" << source.line.value();
    }
    return out;
}

inline bool operator==(const Source& lhs, const Source& rhs) {
    return lhs.path == rhs.path && lhs.line == rhs.line;
}

inline bool operator<(const Source& lhs, const Source& rhs) {
    int cmp = lhs.path.compare(rhs.path);
    if (cmp < 0) return true;
    if (cmp > 0) return false;
    if (lhs.line) {
        if (rhs.line) {
            return lhs.line.value() < rhs.line.value();
        }
        return false;
    }
    return bool(rhs.line);
}

} // namespace aapt

#endif // AAPT_SOURCE_H
