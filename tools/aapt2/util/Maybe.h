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

#include "util/TypeTraits.h"

#include <cassert>
#include <type_traits>
#include <utility>

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
    Maybe(const Maybe<U>& rhs);

    Maybe(Maybe&& rhs);

    template <typename U>
    Maybe(Maybe<U>&& rhs);

    Maybe& operator=(const Maybe& rhs);

    template <typename U>
    Maybe& operator=(const Maybe<U>& rhs);

    Maybe& operator=(Maybe&& rhs);

    template <typename U>
    Maybe& operator=(Maybe<U>&& rhs);

    /**
     * Construct a Maybe holding a value.
     */
    Maybe(const T& value);

    /**
     * Construct a Maybe holding a value.
     */
    Maybe(T&& value);

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

private:
    template <typename U>
    friend class Maybe;

    template <typename U>
    Maybe& copy(const Maybe<U>& rhs);

    template <typename U>
    Maybe& move(Maybe<U>&& rhs);

    void destroy();

    bool mNothing;

    typename std::aligned_storage<sizeof(T), alignof(T)>::type mStorage;
};

template <typename T>
Maybe<T>::Maybe()
: mNothing(true) {
}

template <typename T>
Maybe<T>::~Maybe() {
    if (!mNothing) {
        destroy();
    }
}

template <typename T>
Maybe<T>::Maybe(const Maybe& rhs)
: mNothing(rhs.mNothing) {
    if (!rhs.mNothing) {
        new (&mStorage) T(reinterpret_cast<const T&>(rhs.mStorage));
    }
}

template <typename T>
template <typename U>
Maybe<T>::Maybe(const Maybe<U>& rhs)
: mNothing(rhs.mNothing) {
    if (!rhs.mNothing) {
        new (&mStorage) T(reinterpret_cast<const U&>(rhs.mStorage));
    }
}

template <typename T>
Maybe<T>::Maybe(Maybe&& rhs)
: mNothing(rhs.mNothing) {
    if (!rhs.mNothing) {
        rhs.mNothing = true;

        // Move the value from rhs.
        new (&mStorage) T(std::move(reinterpret_cast<T&>(rhs.mStorage)));
        rhs.destroy();
    }
}

template <typename T>
template <typename U>
Maybe<T>::Maybe(Maybe<U>&& rhs)
: mNothing(rhs.mNothing) {
    if (!rhs.mNothing) {
        rhs.mNothing = true;

        // Move the value from rhs.
        new (&mStorage) T(std::move(reinterpret_cast<U&>(rhs.mStorage)));
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
    if (mNothing && rhs.mNothing) {
        // Both are nothing, nothing to do.
        return *this;
    } else if  (!mNothing && !rhs.mNothing) {
        // We both are something, so assign rhs to us.
        reinterpret_cast<T&>(mStorage) = reinterpret_cast<const U&>(rhs.mStorage);
    } else if (mNothing) {
        // We are nothing but rhs is something.
        mNothing = rhs.mNothing;

        // Copy the value from rhs.
        new (&mStorage) T(reinterpret_cast<const U&>(rhs.mStorage));
    } else {
        // We are something but rhs is nothing, so destroy our value.
        mNothing = rhs.mNothing;
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
    if (mNothing && rhs.mNothing) {
        // Both are nothing, nothing to do.
        return *this;
    } else if  (!mNothing && !rhs.mNothing) {
        // We both are something, so move assign rhs to us.
        rhs.mNothing = true;
        reinterpret_cast<T&>(mStorage) = std::move(reinterpret_cast<U&>(rhs.mStorage));
        rhs.destroy();
    } else if (mNothing) {
        // We are nothing but rhs is something.
        mNothing = false;
        rhs.mNothing = true;

        // Move the value from rhs.
        new (&mStorage) T(std::move(reinterpret_cast<U&>(rhs.mStorage)));
        rhs.destroy();
    } else {
        // We are something but rhs is nothing, so destroy our value.
        mNothing = true;
        destroy();
    }
    return *this;
}

template <typename T>
Maybe<T>::Maybe(const T& value)
: mNothing(false) {
    new (&mStorage) T(value);
}

template <typename T>
Maybe<T>::Maybe(T&& value)
: mNothing(false) {
    new (&mStorage) T(std::forward<T>(value));
}

template <typename T>
Maybe<T>::operator bool() const {
    return !mNothing;
}

template <typename T>
T& Maybe<T>::value() {
    assert(!mNothing && "Maybe<T>::value() called on Nothing");
    return reinterpret_cast<T&>(mStorage);
}

template <typename T>
const T& Maybe<T>::value() const {
    assert(!mNothing && "Maybe<T>::value() called on Nothing");
    return reinterpret_cast<const T&>(mStorage);
}

template <typename T>
void Maybe<T>::destroy() {
    reinterpret_cast<T&>(mStorage).~T();
}

template <typename T>
inline Maybe<typename std::remove_reference<T>::type> make_value(T&& value) {
    return Maybe<typename std::remove_reference<T>::type>(std::forward<T>(value));
}

template <typename T>
inline Maybe<T> make_nothing() {
    return Maybe<T>();
}

/**
 * Define the == operator between Maybe<T> and Maybe<U> only if the operator T == U is defined.
 * That way the compiler will show an error at the callsite when comparing two Maybe<> objects
 * whose inner types can't be compared.
 */
template <typename T, typename U>
typename std::enable_if<
        has_eq_op<T, U>::value,
        bool
>::type operator==(const Maybe<T>& a, const Maybe<U>& b) {
    if (a && b) {
        return a.value() == b.value();
    } else if (!a && !b) {
        return true;
    }
    return false;
}

/**
 * Same as operator== but negated.
 */
template <typename T, typename U>
typename std::enable_if<
        has_eq_op<T, U>::value,
        bool
>::type operator!=(const Maybe<T>& a, const Maybe<U>& b) {
    return !(a == b);
}

} // namespace aapt

#endif // AAPT_MAYBE_H
