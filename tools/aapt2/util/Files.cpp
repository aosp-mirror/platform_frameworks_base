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

#include "util/Files.h"
#include "util/Util.h"

#include <algorithm>
#include <android-base/file.h>
#include <cerrno>
#include <cstdio>
#include <dirent.h>
#include <string>
#include <sys/stat.h>

#ifdef _WIN32
// Windows includes.
#include <direct.h>
#endif

namespace aapt {
namespace file {

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

std::vector<std::string> listFiles(const StringPiece& root, std::string* outError) {
    DIR* dir = opendir(root.data());
    if (dir == nullptr) {
        if (outError) {
            std::stringstream errorStr;
            errorStr << "unable to open file: " << strerror(errno);
            *outError = errorStr.str();
            return {};
        }
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
#ifdef _WIN32
    return _mkdir(path.toString().c_str());
#else
    return mkdir(path.toString().c_str(), S_IRUSR|S_IWUSR|S_IXUSR|S_IRGRP|S_IXGRP);
#endif
}

bool mkdirs(const StringPiece& path) {
    const char* start = path.begin();
    const char* end = path.end();
    for (const char* current = start; current != end; ++current) {
        if (*current == sDirSep && current != start) {
            StringPiece parentPath(start, current - start);
            int result = mkdirImpl(parentPath);
            if (result < 0 && errno != EEXIST) {
                return false;
            }
        }
    }
    return mkdirImpl(path) == 0 || errno == EEXIST;
}

StringPiece getStem(const StringPiece& path) {
    const char* start = path.begin();
    const char* end = path.end();
    for (const char* current = end - 1; current != start - 1; --current) {
        if (*current == sDirSep) {
            return StringPiece(start, current - start);
        }
    }
    return {};
}

StringPiece getFilename(const StringPiece& path) {
    const char* end = path.end();
    const char* lastDirSep = path.begin();
    for (const char* c = path.begin(); c != end; ++c) {
        if (*c == sDirSep) {
            lastDirSep = c + 1;
        }
    }
    return StringPiece(lastDirSep, end - lastDirSep);
}

StringPiece getExtension(const StringPiece& path) {
    StringPiece filename = getFilename(path);
    const char* const end = filename.end();
    const char* c = std::find(filename.begin(), end, '.');
    if (c != end) {
        return StringPiece(c, end - c);
    }
    return {};
}

void appendPath(std::string* base, StringPiece part) {
    assert(base);
    const bool baseHasTrailingSep = (!base->empty() && *(base->end() - 1) == sDirSep);
    const bool partHasLeadingSep = (!part.empty() && *(part.begin()) == sDirSep);
    if (baseHasTrailingSep && partHasLeadingSep) {
        // Remove the part's leading sep
        part = part.substr(1, part.size() - 1);
    } else if (!baseHasTrailingSep && !partHasLeadingSep) {
        // None of the pieces has a separator.
        *base += sDirSep;
    }
    base->append(part.data(), part.size());
}

std::string packageToPath(const StringPiece& package) {
    std::string outPath;
    for (StringPiece part : util::tokenize<char>(package, '.')) {
        appendPath(&outPath, part);
    }
    return outPath;
}

Maybe<android::FileMap> mmapPath(const StringPiece& path, std::string* outError) {
    std::unique_ptr<FILE, decltype(fclose)*> f = { fopen(path.data(), "rb"), fclose };
    if (!f) {
        if (outError) *outError = strerror(errno);
        return {};
    }

    int fd = fileno(f.get());

    struct stat fileStats = {};
    if (fstat(fd, &fileStats) != 0) {
        if (outError) *outError = strerror(errno);
        return {};
    }

    android::FileMap fileMap;
    if (fileStats.st_size == 0) {
        // mmap doesn't like a length of 0. Instead we return an empty FileMap.
        return std::move(fileMap);
    }

    if (!fileMap.create(path.data(), fd, 0, fileStats.st_size, true)) {
        if (outError) *outError = strerror(errno);
        return {};
    }
    return std::move(fileMap);
}

bool appendArgsFromFile(const StringPiece& path, std::vector<std::string>* outArgList,
                        std::string* outError) {
    std::string contents;
    if (!android::base::ReadFileToString(path.toString(), &contents)) {
        if (outError) *outError = "failed to read argument-list file";
        return false;
    }

    for (StringPiece line : util::tokenize<char>(contents, ' ')) {
        line = util::trimWhitespace(line);
        if (!line.empty()) {
            outArgList->push_back(line.toString());
        }
    }
    return true;
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
                mDiag->warn(DiagMessage() << "skipping "
                            << (type == FileType::kDirectory ? "dir '" : "file '")
                            << filename << "' due to ignore pattern '"
                            << token << "'");
            }
            return false;
        }
    }
    return true;
}

} // namespace file
} // namespace aapt
