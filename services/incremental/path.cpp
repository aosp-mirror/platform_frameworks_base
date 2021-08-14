/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "path.h"

#include <android-base/strings.h>
#include <android-base/logging.h>

#include <algorithm>
#include <iterator>
#include <limits>
#include <memory>

#include <dirent.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

using namespace std::literals;

namespace android::incremental::path {

bool PathCharsLess::operator()(char l, char r) const {
    int ll = l == '/' ? std::numeric_limits<char>::min() - 1 : l;
    int rr = r == '/' ? std::numeric_limits<char>::min() - 1 : r;
    return ll < rr;
}

bool PathLess::operator()(std::string_view l, std::string_view r) const {
    return std::lexicographical_compare(std::begin(l), std::end(l), std::begin(r), std::end(r),
                                        PathCharsLess());
}

static void preparePathComponent(std::string_view& path, bool trimAll) {
    // need to check for double front slash as a single one has a separate meaning in front
    while (!path.empty() && path.front() == '/' &&
           (trimAll || (path.size() > 1 && path[1] == '/'))) {
        path.remove_prefix(1);
    }
    // for the back we don't care about double-vs-single slash difference
    while (path.size() > !trimAll && path.back() == '/') {
        path.remove_suffix(1);
    }
}

void details::append_next_path(std::string& target, std::string_view path) {
    preparePathComponent(path, !target.empty());
    if (path.empty()) {
        return;
    }
    if (!target.empty() && !target.ends_with('/')) {
        target.push_back('/');
    }
    target += path;
}

std::string_view relativize(std::string_view parent, std::string_view nested) {
    if (!nested.starts_with(parent)) {
        return nested;
    }
    if (nested.size() == parent.size()) {
        return {};
    }
    if (nested[parent.size()] != '/') {
        return nested;
    }
    auto relative = nested.substr(parent.size());
    while (relative.front() == '/') {
        relative.remove_prefix(1);
    }
    return relative;
}

bool isAbsolute(std::string_view path) {
    return !path.empty() && path[0] == '/';
}

std::string normalize(std::string_view path) {
    if (path.empty()) {
        return {};
    }
    if (path.starts_with("../"sv)) {
        return {};
    }

    std::string result;
    if (isAbsolute(path)) {
        path.remove_prefix(1);
    } else {
        char buffer[PATH_MAX];
        if (!::getcwd(buffer, sizeof(buffer))) {
            return {};
        }
        result += buffer;
    }

    size_t start = 0;
    size_t end = 0;
    for (; end != path.npos; start = end + 1) {
        end = path.find('/', start);
        // Next component, excluding the separator
        auto part = path.substr(start, end - start);
        if (part.empty() || part == "."sv) {
            continue;
        }
        if (part == ".."sv) {
            if (result.empty()) {
                return {};
            }
            auto lastPos = result.rfind('/');
            if (lastPos == result.npos) {
                result.clear();
            } else {
                result.resize(lastPos);
            }
            continue;
        }
        result += '/';
        result += part;
    }

    return result;
}

std::string_view basename(std::string_view path) {
    if (path.empty()) {
        return {};
    }
    if (path == "/"sv) {
        return "/"sv;
    }
    auto pos = path.rfind('/');
    while (!path.empty() && pos == path.size() - 1) {
        path.remove_suffix(1);
        pos = path.rfind('/');
    }
    if (pos == path.npos) {
        return path.empty() ? "/"sv : path;
    }
    return path.substr(pos + 1);
}

std::string_view dirname(std::string_view path) {
    if (path.empty()) {
        return {};
    }
    if (path == "/"sv) {
        return "/"sv;
    }
    const auto pos = path.rfind('/');
    if (pos == 0) {
        return "/"sv;
    }
    if (pos == path.npos) {
        return "."sv;
    }
    return path.substr(0, pos);
}

details::CStrWrapper::CStrWrapper(std::string_view sv) {
    if (!sv.data()) {
        mCstr = "";
    } else if (sv[sv.size()] == '\0') {
        mCstr = sv.data();
    } else {
        mCopy.emplace(sv);
        mCstr = mCopy->c_str();
    }
}

std::optional<bool> isEmptyDir(std::string_view dir) {
    const auto d = std::unique_ptr<DIR, decltype(&::closedir)>{::opendir(c_str(dir)), ::closedir};
    if (!d) {
        if (errno == EPERM || errno == EACCES) {
            return std::nullopt;
        }
        return false;
    }
    while (auto entry = ::readdir(d.get())) {
        if (entry->d_type != DT_DIR) {
            return false;
        }
        if (entry->d_name != "."sv && entry->d_name != ".."sv) {
            return false;
        }
    }
    return true;
}

bool startsWith(std::string_view path, std::string_view prefix) {
    if (!base::StartsWith(path, prefix)) {
        return false;
    }
    return path.size() == prefix.size() || path[prefix.size()] == '/';
}

} // namespace android::incremental::path
