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

#ifndef IDMAP2_INCLUDE_IDMAP2_RESULT_H_
#define IDMAP2_INCLUDE_IDMAP2_RESULT_H_

#include <optional>
#include <string>
#include <utility>
#include <variant>

#include "android-base/logging.h"  // CHECK

namespace android::idmap2 {

template <typename T>
using Result = std::optional<T>;

static constexpr std::nullopt_t kResultError = std::nullopt;

namespace v2 {

using Unit = std::monostate;

class Error {
 public:
  explicit Error(const Error& parent) = default;

  // NOLINTNEXTLINE(cert-dcl50-cpp)
  explicit Error(const char* fmt, ...) __attribute__((__format__(printf, 2, 3)));

  // NOLINTNEXTLINE(cert-dcl50-cpp)
  explicit Error(const Error& parent, const char* fmt, ...)
      __attribute__((__format__(printf, 3, 4)));

  inline std::string GetMessage() const {
    return msg_;
  }

 private:
  std::string msg_;
};

template <typename T>
class Result {
 public:
  Result(const T& value);      // NOLINT(runtime/explicit)
  Result(T&& value) noexcept;  // NOLINT(runtime/explicit)

  Result(const Error& error);      // NOLINT(runtime/explicit)
  Result(Error&& error) noexcept;  // NOLINT(runtime/explicit)

  Result(const Result& error) = default;

  Result& operator=(const Result& rhs) = default;
  Result& operator=(Result&& rhs) noexcept = default;

  explicit operator bool() const;

  constexpr const T& operator*() const&;
  T& operator*() &;

  constexpr const T* operator->() const&;
  T* operator->() &;

  std::string GetErrorMessage() const;
  Error GetError() const;

 private:
  bool is_ok() const;

  std::variant<T, Error> data_;
};

template <typename T>
Result<T>::Result(const T& value) : data_(std::in_place_type<T>, value) {
}

template <typename T>
Result<T>::Result(T&& value) noexcept : data_(std::in_place_type<T>, std::forward<T>(value)) {
}

template <typename T>
Result<T>::Result(const Error& error) : data_(std::in_place_type<Error>, error) {
}

template <typename T>
Result<T>::Result(Error&& error) noexcept
    : data_(std::in_place_type<Error>, std::forward<Error>(error)) {
}

template <typename T>
Result<T>::operator bool() const {
  return is_ok();
}

template <typename T>
constexpr const T& Result<T>::operator*() const& {
  CHECK(is_ok()) << "Result<T>::operator* called in ERROR state";
  return std::get<T>(data_);
}

template <typename T>
T& Result<T>::operator*() & {
  CHECK(is_ok()) << "Result<T>::operator* called in ERROR state";
  return std::get<T>(data_);
}

template <typename T>
constexpr const T* Result<T>::operator->() const& {
  CHECK(is_ok()) << "Result<T>::operator-> called in ERROR state";
  return &std::get<T>(data_);
}

template <typename T>
T* Result<T>::operator->() & {
  CHECK(is_ok()) << "Result<T>::operator-> called in ERROR state";
  return &std::get<T>(data_);
}

template <typename T>
inline std::string Result<T>::GetErrorMessage() const {
  CHECK(!is_ok()) << "Result<T>::GetErrorMessage called in OK state";
  return std::get<Error>(data_).GetMessage();
}

template <typename T>
inline Error Result<T>::GetError() const {
  CHECK(!is_ok()) << "Result<T>::GetError called in OK state";
  return Error(std::get<Error>(data_));
}

template <typename T>
inline bool Result<T>::is_ok() const {
  return std::holds_alternative<T>(data_);
}

}  // namespace v2

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESULT_H_
