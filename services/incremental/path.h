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

#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace android::incremental::path {

namespace details {

class CStrWrapper {
public:
    CStrWrapper(std::string_view sv);

    CStrWrapper(const CStrWrapper&) = delete;
    void operator=(const CStrWrapper&) = delete;
    CStrWrapper(CStrWrapper&&) = delete;
    void operator=(CStrWrapper&&) = delete;

    const char* get() const { return mCstr; }
    operator const char*() const { return get(); }

private:
    const char* mCstr;
    std::optional<std::string> mCopy;
};

void append_next_path(std::string& res, std::string_view c);

} // namespace details

//
// An std::map<> comparator that makes all nested paths to be ordered before the parents.
//

struct PathCharsLess {
    bool operator()(char l, char r) const;
};

struct PathLess {
    using is_transparent = void;
    bool operator()(std::string_view l, std::string_view r) const;
};

//
// Returns a zero-terminated version of a passed string view
// Only makes a copy if it wasn't zero-terminated already
// Useful for passing string view parameters to system functions.
//
inline details::CStrWrapper c_str(std::string_view sv) {
    return {sv};
}

std::string_view relativize(std::string_view parent, std::string_view nested);
inline std::string_view relativize(const char* parent, const char* nested) {
    return relativize(std::string_view(parent), std::string_view(nested));
}
inline std::string_view relativize(std::string_view parent, const char* nested) {
    return relativize(parent, std::string_view(nested));
}
inline std::string_view relativize(const char* parent, std::string_view nested) {
    return relativize(std::string_view(parent), nested);
}

std::string_view relativize(std::string&& parent, std::string_view nested) = delete;
std::string_view relativize(std::string_view parent, std::string&& nested) = delete;

bool isAbsolute(std::string_view path);
std::string normalize(std::string_view path);
std::string_view dirname(std::string_view path);
std::string_view basename(std::string_view path);
std::optional<bool> isEmptyDir(std::string_view dir);
bool startsWith(std::string_view path, std::string_view prefix);

template <class... Paths>
std::string join(std::string_view first, std::string_view second, Paths&&... paths) {
    std::string result;
    {
        using std::size;
        result.reserve(first.size() + second.size() + 1 + (sizeof...(paths) + ... + size(paths)));
    }
    result.assign(first);
    (details::append_next_path(result, second), ..., details::append_next_path(result, paths));
    return result;
}

} // namespace android::incremental::path
