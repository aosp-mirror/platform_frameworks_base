/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef ANDROIDFW_MUTEXGUARD_H
#define ANDROIDFW_MUTEXGUARD_H

#include <mutex>
#include <optional>
#include <type_traits>

#include "android-base/macros.h"

namespace android {

template <typename T>
class ScopedLock;

// Owns the guarded object and protects access to it via a mutex.
// The guarded object is inaccessible via this class.
// The mutex is locked and the object accessed via the ScopedLock<T> class.
//
// NOTE: The template parameter T should not be a raw pointer, since ownership
// is ambiguous and error-prone. Instead use an std::unique_ptr<>.
//
// Example use:
//
//   Guarded<std::string> shared_string("hello");
//   {
//     ScopedLock<std::string> locked_string(shared_string);
//     *locked_string += " world";
//   }
//
template <typename T>
class Guarded {
  static_assert(!std::is_pointer<T>::value, "T must not be a raw pointer");

 public:
  Guarded() : guarded_(std::in_place, T()) {
  }

  explicit Guarded(const T& guarded) : guarded_(std::in_place, guarded) {
  }

  explicit Guarded(T&& guarded) : guarded_(std::in_place, std::forward<T>(guarded)) {
  }

  ~Guarded() {
    std::lock_guard<std::mutex> scoped_lock(lock_);
    guarded_.reset();
  }

 private:
  friend class ScopedLock<T>;
  DISALLOW_COPY_AND_ASSIGN(Guarded);

  std::mutex lock_;
  std::optional<T> guarded_;
};

template <typename T>
class ScopedLock {
 public:
  explicit ScopedLock(Guarded<T>& guarded) : lock_(guarded.lock_), guarded_(*guarded.guarded_) {
  }

  T& operator*() {
    return guarded_;
  }

  T* operator->() {
    return &guarded_;
  }

  T* get() {
    return &guarded_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ScopedLock);

  std::lock_guard<std::mutex> lock_;
  T& guarded_;
};

}  // namespace android

#endif  // ANDROIDFW_MUTEXGUARD_H
