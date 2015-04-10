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

#ifndef AAPT_FILES_H
#define AAPT_FILES_H

#include "Logger.h"
#include "Source.h"
#include "StringPiece.h"

#include <cassert>
#include <string>
#include <vector>

namespace aapt {

#ifdef _WIN32
constexpr const char sDirSep = '\\';
#else
constexpr const char sDirSep = '/';
#endif

enum class FileType {
    kUnknown = 0,
    kNonexistant,
    kRegular,
    kDirectory,
    kCharDev,
    kBlockDev,
    kFifo,
    kSymlink,
    kSocket,
};

FileType getFileType(const StringPiece& path);

/*
 * Lists files under the directory `root`. Files are listed
 * with just their leaf (filename) names.
 */
std::vector<std::string> listFiles(const StringPiece& root);

/*
 * Appends a path to `base`, separated by the directory separator.
 */
void appendPath(std::string* base, const StringPiece& part);

/*
 * Appends a series of paths to `base`, separated by the
 * system directory separator.
 */
template <typename... Ts >
void appendPath(std::string* base, const StringPiece& part, const Ts&... parts);

/*
 * Makes all the directories in `path`. The last element in the path
 * is interpreted as a directory.
 */
bool mkdirs(const StringPiece& path);

/**
 * Returns all but the last part of the path.
 */
std::string getStem(const StringPiece& path);

/*
 * Filter that determines which resource files/directories are
 * processed by AAPT. Takes a pattern string supplied by the user.
 * Pattern format is specified in the
 * FileFilter::setPattern(const std::string&) method.
 */
class FileFilter {
public:
    /*
     * Patterns syntax:
     * - Delimiter is :
     * - Entry can start with the flag ! to avoid printing a warning
     *   about the file being ignored.
     * - Entry can have the flag "<dir>" to match only directories
     *   or <file> to match only files. Default is to match both.
     * - Entry can be a simplified glob "<prefix>*" or "*<suffix>"
     *   where prefix/suffix must have at least 1 character (so that
     *   we don't match a '*' catch-all pattern.)
     * - The special filenames "." and ".." are always ignored.
     * - Otherwise the full string is matched.
     * - match is not case-sensitive.
     */
    bool setPattern(const StringPiece& pattern);

    /**
     * Applies the filter, returning true for pass, false for fail.
     */
    bool operator()(const std::string& filename, FileType type) const;

private:
    std::vector<std::string> mPatternTokens;
};

inline void appendPath(std::string* base, const StringPiece& part) {
    assert(base);
    *base += sDirSep;
    base->append(part.data(), part.size());
}

template <typename... Ts >
void appendPath(std::string* base, const StringPiece& part, const Ts&... parts) {
    assert(base);
    *base += sDirSep;
    base->append(part.data(), part.size());
    appendPath(base, parts...);
}

} // namespace aapt

#endif // AAPT_FILES_H
