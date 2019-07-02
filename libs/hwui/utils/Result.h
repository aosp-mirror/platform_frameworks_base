/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <variant>
#include <log/log.h>

namespace android::uirenderer {

template <typename E>
struct Error {
    E error;
};

template <typename R, typename E>
class Result {
public:
    Result(const R& r) : result(std::forward<R>(r)) {}
    Result(R&& r) : result(std::forward<R>(r)) {}
    Result(Error<E>&& error) : result(std::forward<Error<E>>(error)) {}

    operator bool() const {
        return result.index() == 0;
    }

    R unwrap() const {
        LOG_ALWAYS_FATAL_IF(result.index() == 1, "unwrap called on error value!");
        return std::get<R>(result);
    }

    E error() const {
        LOG_ALWAYS_FATAL_IF(result.index() == 0, "No error to get from Result");
        return std::get<Error<E>>(result).error;
    }

private:
    std::variant<R, Error<E>> result;
};

} // namespace android::uirenderer
