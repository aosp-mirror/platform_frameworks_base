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

#ifndef AAPT_MAYBE_H
#define AAPT_MAYBE_H

#include <type_traits>
#include <utility>

#include "android-base/logging.h"

#include "util/TypeTraits.h"

namespace aapt {

/**
 * Either holds a valid value of type T, or holds Nothing.
 * The value is stored inline in this structure, so no
 * heap memory is used when creating a Maybe<T> object.
 */
template <typename T>
class Maybe {
 public:
  /**
   * Construct Nothing.
   */
  Maybe();

  ~Maybe();

  Maybe(const Maybe& rhs);

  template <typename U>
  Maybe(const Maybe<U>& rhs);  // NOLINT(implicit)

  Maybe(Maybe&& rhs);

  template <typename U>
  Maybe(Maybe<U>&& rhs);  // NOLINT(implicit)

  Maybe& operator=(const Maybe& rhs);

  template <typename U>
  Maybe& operator=(const Maybe<U>& rhs);

  Maybe& operator=(Maybe&& rhs);

  template <typename U>
  Maybe& operator=(Maybe<U>&& rhs);

  /**
   * Construct a Maybe holding a value.
   */
  Maybe(const T& value);  // NOLINT(implicit)

  /**
   * Construct a Maybe holding a value.
   */
  Maybe(T&& value);  // NOLINT(implicit)

  /**
   * True if this holds a value, false if
   * it holds Nothing.
   */
  explicit operator bool() const;

  /**
   * Gets the value if one exists, or else
   * panics.
   */
  T& value();

  /**
   * Gets the value if one exists, or else
   * panics.
   */
  const T& value() const;

  T value_or_default(const T& def) const;

 private:
  template <typename U>
  friend class Maybe;

  template <typename U>
  Maybe& copy(const Maybe<U>& rhs);

  template <typename U>
  Maybe& move(Maybe<U>&& rhs);

  void destroy();

  bool nothing_;

  typename std::aligned_storage<sizeof(T), alignof(T)>::type storage_;
};

template <typename T>
Maybe<T>::Maybe() : nothing_(true) {}

template <typename T>
Maybe<T>::~Maybe() {
  if (!nothing_) {
    destroy();
  }
}

template <typename T>
Maybe<T>::Maybe(const Maybe& rhs) : nothing_(rhs.nothing_) {
  if (!rhs.nothing_) {
    new (&storage_) T(reinterpret_cast<const T&>(rhs.storage_));
  }
}

template <typename T>
template <typename U>
Maybe<T>::Maybe(const Maybe<U>& rhs) : nothing_(rhs.nothing_) {
  if (!rhs.nothing_) {
    new (&storage_) T(reinterpret_cast<const U&>(rhs.storage_));
  }
}

template <typename T>
Maybe<T>::Maybe(Maybe&& rhs) : nothing_(rhs.nothing_) {
  if (!rhs.nothing_) {
    rhs.nothing_ = true;

    // Move the value from rhs.
    new (&storage_) T(std::move(reinterpret_cast<T&>(rhs.storage_)));
    rhs.destroy();
  }
}

template <typename T>
template <typename U>
Maybe<T>::Maybe(Maybe<U>&& rhs) : nothing_(rhs.nothing_) {
  if (!rhs.nothing_) {
    rhs.nothing_ = true;

    // Move the value from rhs.
    new (&storage_) T(std::move(reinterpret_cast<U&>(rhs.storage_)));
    rhs.destroy();
  }
}

template <typename T>
inline Maybe<T>& Maybe<T>::operator=(const Maybe& rhs) {
  // Delegate to the actual assignment.
  return copy(rhs);
}

template <typename T>
template <typename U>
inline Maybe<T>& Maybe<T>::operator=(const Maybe<U>& rhs) {
  return copy(rhs);
}

template <typename T>
template <typename U>
Maybe<T>& Maybe<T>::copy(const Maybe<U>& rhs) {
  if (nothing_ && rhs.nothing_) {
    // Both are nothing, nothing to do.
    return *this;
  } else if (!nothing_ && !rhs.nothing_) {
    // We both are something, so assign rhs to us.
    reinterpret_cast<T&>(storage_) = reinterpret_cast<const U&>(rhs.storage_);
  } else if (nothing_) {
    // We are nothing but rhs is something.
    nothing_ = rhs.nothing_;

    // Copy the value from rhs.
    new (&storage_) T(reinterpret_cast<const U&>(rhs.storage_));
  } else {
    // We are something but rhs is nothing, so destroy our value.
    nothing_ = rhs.nothing_;
    destroy();
  }
  return *this;
}

template <typename T>
inline Maybe<T>& Maybe<T>::operator=(Maybe&& rhs) {
  // Delegate to the actual assignment.
  return move(std::forward<Maybe<T>>(rhs));
}

template <typename T>
template <typename U>
inline Maybe<T>& Maybe<T>::operator=(Maybe<U>&& rhs) {
  return move(std::forward<Maybe<U>>(rhs));
}

template <typename T>
template <typename U>
Maybe<T>& Maybe<T>::move(Maybe<U>&& rhs) {
  if (nothing_ && rhs.nothing_) {
    // Both are nothing, nothing to do.
    return *this;
  } else if (!nothing_ && !rhs.nothing_) {
    // We both are something, so move assign rhs to us.
    rhs.nothing_ = true;
    reinterpret_cast<T&>(storage_) =
        std::move(reinterpret_cast<U&>(rhs.storage_));
    rhs.destroy();
  } else if (nothing_) {
    // We are nothing but rhs is something.
    nothing_ = false;
    rhs.nothing_ = true;

    // Move the value from rhs.
    new (&storage_) T(std::move(reinterpret_cast<U&>(rhs.storage_)));
    rhs.destroy();
  } else {
    // We are something but rhs is nothing, so destroy our value.
    nothing_ = true;
    destroy();
  }
  return *this;
}

template <typename T>
Maybe<T>::Maybe(const T& value) : nothing_(false) {
  new (&storage_) T(value);
}

template <typename T>
Maybe<T>::Maybe(T&& value) : nothing_(false) {
  new (&storage_) T(std::forward<T>(value));
}

template <typename T>
Maybe<T>::operator bool() const {
  return !nothing_;
}

template <typename T>
T& Maybe<T>::value() {
  CHECK(!nothing_) << "Maybe<T>::value() called on Nothing";
  return reinterpret_cast<T&>(storage_);
}

template <typename T>
const T& Maybe<T>::value() const {
  CHECK(!nothing_) << "Maybe<T>::value() called on Nothing";
  return reinterpret_cast<const T&>(storage_);
}

template <typename T>
T Maybe<T>::value_or_default(const T& def) const {
  if (nothing_) {
    return def;
  }
  return reinterpret_cast<const T&>(storage_);
}

template <typename T>
void Maybe<T>::destroy() {
  reinterpret_cast<T&>(storage_).~T();
}

template <typename T>
inline Maybe<typename std::remove_reference<T>::type> make_value(T&& value) {
  return Maybe<typename std::remove_reference<T>::type>(std::forward<T>(value));
}

template <typename T>
inline Maybe<T> make_nothing() {
  return Maybe<T>();
}

// Define the == operator between Maybe<T> and Maybe<U> only if the operator T == U is defined.
// That way the compiler will show an error at the callsite when comparing two Maybe<> objects
// whose inner types can't be compared.
template <typename T, typename U>
typename std::enable_if<has_eq_op<T, U>::value, bool>::type operator==(const Maybe<T>& a,
                                                                       const Maybe<U>& b) {
  if (a && b) {
    return a.value() == b.value();
  } else if (!a && !b) {
    return true;
  }
  return false;
}

template <typename T, typename U>
typename std::enable_if<has_eq_op<T, U>::value, bool>::type operator==(const Maybe<T>& a,
                                                                       const U& b) {
  return a ? a.value() == b : false;
}

// Same as operator== but negated.
template <typename T, typename U>
typename std::enable_if<has_eq_op<T, U>::value, bool>::type operator!=(const Maybe<T>& a,
                                                                       const Maybe<U>& b) {
  return !(a == b);
}

template <typename T, typename U>
typename std::enable_if<has_lt_op<T, U>::value, bool>::type operator<(const Maybe<T>& a,
                                                                      const Maybe<U>& b) {
  if (a && b) {
    return a.value() < b.value();
  } else if (!a && !b) {
    return false;
  }
  return !a;
}

}  // namespace aapt

#endif  // AAPT_MAYBE_H
