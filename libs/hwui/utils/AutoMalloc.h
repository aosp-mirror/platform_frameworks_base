/**
 * Copyright (C) 2023 The Android Open Source Project
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

#include <cstdlib>
#include <memory>
#include <type_traits>

namespace android {
namespace uirenderer {

/** Manages an array of T elements, freeing the array in the destructor.
 *  Does NOT call any constructors/destructors on T (T must be POD).
 */
template <typename T,
          typename = std::enable_if_t<std::is_trivially_default_constructible<T>::value &&
                                      std::is_trivially_destructible<T>::value>>
class AutoTMalloc {
public:
    /** Takes ownership of the ptr. The ptr must be a value which can be passed to std::free. */
    explicit AutoTMalloc(T* ptr = nullptr) : fPtr(ptr) {}

    /** Allocates space for 'count' Ts. */
    explicit AutoTMalloc(size_t count) : fPtr(mallocIfCountThrowOnFail(count)) {}

    AutoTMalloc(AutoTMalloc&&) = default;
    AutoTMalloc& operator=(AutoTMalloc&&) = default;

    /** Resize the memory area pointed to by the current ptr preserving contents. */
    void realloc(size_t count) { fPtr.reset(reallocIfCountThrowOnFail(count)); }

    /** Resize the memory area pointed to by the current ptr without preserving contents. */
    T* reset(size_t count = 0) {
        fPtr.reset(mallocIfCountThrowOnFail(count));
        return this->get();
    }

    T* get() const { return fPtr.get(); }

    operator T*() { return fPtr.get(); }

    operator const T*() const { return fPtr.get(); }

    T& operator[](int index) { return fPtr.get()[index]; }

    const T& operator[](int index) const { return fPtr.get()[index]; }

    /**
     *  Transfer ownership of the ptr to the caller, setting the internal
     *  pointer to NULL. Note that this differs from get(), which also returns
     *  the pointer, but it does not transfer ownership.
     */
    T* release() { return fPtr.release(); }

private:
    struct FreeDeleter {
        void operator()(uint8_t* p) { std::free(p); }
    };
    std::unique_ptr<T, FreeDeleter> fPtr;

    T* mallocIfCountThrowOnFail(size_t count) {
        T* newPtr = nullptr;
        if (count) {
            newPtr = (T*)std::malloc(count * sizeof(T));
            LOG_ALWAYS_FATAL_IF(!newPtr, "failed to malloc %zu bytes", count * sizeof(T));
        }
        return newPtr;
    }
    T* reallocIfCountThrowOnFail(size_t count) {
        T* newPtr = nullptr;
        if (count) {
            newPtr = (T*)std::realloc(fPtr.release(), count * sizeof(T));
            LOG_ALWAYS_FATAL_IF(!newPtr, "failed to realloc %zu bytes", count * sizeof(T));
        }
        return newPtr;
    }
};

}  // namespace uirenderer
}  // namespace android
