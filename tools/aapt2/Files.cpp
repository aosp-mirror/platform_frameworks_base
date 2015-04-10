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

#include "Files.h"
#include "Util.h"

#include <cerrno>
#include <dirent.h>
#include <string>
#include <sys/stat.h>

#ifdef HAVE_MS_C_RUNTIME
// Windows includes.
#include <direct.h>
#endif

namespace aapt {

FileType getFileType(const StringPiece& path) {
    struct stat sb;
    if (stat(path.data(), &sb) < 0) {
        if (errno == ENOENT || errno == ENOTDIR) {
            return FileType::kNonexistant;
        }
        return FileType::kUnknown;
    }

    if (S_ISREG(sb.st_mode)) {
        return FileType::kRegular;
    } else if (S_ISDIR(sb.st_mode)) {
        return FileType::kDirectory;
    } else if (S_ISCHR(sb.st_mode)) {
        return FileType::kCharDev;
    } else if (S_ISBLK(sb.st_mode)) {
        return FileType::kBlockDev;
    } else if (S_ISFIFO(sb.st_mode)) {
        return FileType::kFifo;
#if defined(S_ISLNK)
    } else if (S_ISLNK(sb.st_mode)) {
        return FileType::kSymlink;
#endif
#if defined(S_ISSOCK)
    } else if (S_ISSOCK(sb.st_mode)) {
        return FileType::kSocket;
#endif
    } else {
        return FileType::kUnknown;
    }
}

std::vector<std::string> listFiles(const StringPiece& root) {
    DIR* dir = opendir(root.data());
    if (dir == nullptr) {
        Logger::error(Source{ root.toString() })
            << "unable to open file: "
            << strerror(errno)
            << "."
            << std::endl;
        return {};
    }

    std::vector<std::string> files;
    dirent* entry;
    while ((entry = readdir(dir))) {
        files.emplace_back(entry->d_name);
    }

    closedir(dir);
    return files;
}

inline static int mkdirImpl(const StringPiece& path) {
#ifdef HAVE_MS_C_RUNTIME
    return _mkdir(path.toString().c_str());
#else
    return mkdir(path.toString().c_str(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif
}

bool mkdirs(const StringPiece& path) {
    const char* start = path.begin();
    const char* end = path.end();
    for (const char* current = start; current != end; ++current) {
        if (*current == sDirSep) {
            StringPiece parentPath(start, current - start);
            int result = mkdirImpl(parentPath);
            if (result < 0 && errno != EEXIST) {
                return false;
            }
        }
    }
    return mkdirImpl(path) == 0 || errno == EEXIST;
}

std::string getStem(const StringPiece& path) {
    const char* start = path.begin();
    const char* end = path.end();
    for (const char* current = end - 1; current != start - 1; --current) {
        if (*current == sDirSep) {
            return std::string(start, current - start);
        }
    }
    return {};
}

bool FileFilter::setPattern(const StringPiece& pattern) {
    mPatternTokens = util::splitAndLowercase(pattern, ':');
    return true;
}

bool FileFilter::operator()(const std::string& filename, FileType type) const {
    if (filename == "." || filename == "..") {
        return false;
    }

    const char kDir[] = "dir";
    const char kFile[] = "file";
    const size_t filenameLen = filename.length();
    bool chatty = true;
    for (const std::string& token : mPatternTokens) {
        const char* tokenStr = token.c_str();
        if (*tokenStr == '!') {
            chatty = false;
            tokenStr++;
        }

        if (strncasecmp(tokenStr, kDir, sizeof(kDir)) == 0) {
            if (type != FileType::kDirectory) {
                continue;
            }
            tokenStr += sizeof(kDir);
        }

        if (strncasecmp(tokenStr, kFile, sizeof(kFile)) == 0) {
            if (type != FileType::kRegular) {
                continue;
            }
            tokenStr += sizeof(kFile);
        }

        bool ignore = false;
        size_t n = strlen(tokenStr);
        if (*tokenStr == '*') {
            // Math suffix.
            tokenStr++;
            n--;
            if (n <= filenameLen) {
                ignore = strncasecmp(tokenStr, filename.c_str() + filenameLen - n, n) == 0;
            }
        } else if (n > 1 && tokenStr[n - 1] == '*') {
            // Match prefix.
            ignore = strncasecmp(tokenStr, filename.c_str(), n - 1) == 0;
        } else {
            ignore = strcasecmp(tokenStr, filename.c_str()) == 0;
        }

        if (ignore) {
            if (chatty) {
                Logger::warn()
                    << "skipping " <<
                    (type == FileType::kDirectory ? "dir '" : "file '")
                    << filename
                    << "' due to ignore pattern '"
                    << token
                    << "'."
                    << std::endl;
            }
            return false;
        }
    }
    return true;
}


} // namespace aapt
