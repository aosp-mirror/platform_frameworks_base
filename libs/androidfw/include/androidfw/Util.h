/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef UTIL_H_
#define UTIL_H_

#include <android-base/macros.h>
#include <util/map_ptr.h>

#include <cstdlib>
#include <memory>
#include <vector>

#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"
#include "utils/ByteOrder.h"

#ifdef __ANDROID__
#define ANDROID_LOG(x) LOG(x)
#else
namespace android {
// No default logging for aapt2, as it's too noisy for a command line dev tool.
struct NullLogger {
  template <class T>
  friend const NullLogger& operator<<(const NullLogger& l, const T&) { return l; }
};
}
#define ANDROID_LOG(x) (android::NullLogger{})
#endif

namespace android {
namespace util {

/**
 * Makes a std::unique_ptr<> with the template parameter inferred by the
 * compiler.
 * This will be present in C++14 and can be removed then.
 */
template <typename T, class... Args>
std::unique_ptr<T> make_unique(Args&&... args) {
  return std::unique_ptr<T>(new T{std::forward<Args>(args)...});
}

// Based on std::unique_ptr, but uses free() to release malloc'ed memory.
struct FreeDeleter {
  void operator()(void* ptr) const {
    ::free(ptr);
  }
};
template <typename T>
using unique_cptr = std::unique_ptr<T, FreeDeleter>;

void ReadUtf16StringFromDevice(const uint16_t* src, size_t len, std::string* out);

// Converts a UTF-8 string to a UTF-16 string.
std::u16string Utf8ToUtf16(StringPiece utf8);

// Converts a UTF-16 string to a UTF-8 string.
std::string Utf16ToUtf8(StringPiece16 utf16);

// Converts a UTF8 string into Modified UTF8
std::string Utf8ToModifiedUtf8(std::string_view utf8);

// Converts a Modified UTF8 string into a UTF8 string
std::string ModifiedUtf8ToUtf8(std::string_view modified_utf8);

inline uint16_t HostToDevice16(uint16_t value) {
  return htods(value);
}

inline uint32_t HostToDevice32(uint32_t value) {
  return htodl(value);
}

inline uint16_t DeviceToHost16(uint16_t value) {
  return dtohs(value);
}

inline uint32_t DeviceToHost32(uint32_t value) {
  return dtohl(value);
}

std::vector<std::string> SplitAndLowercase(android::StringPiece str, char sep);

inline bool IsFourByteAligned(const void* data) {
  return ((uintptr_t)data & 0x3U) == 0;
}

template <typename T>
inline bool IsFourByteAligned(const incfs::map_ptr<T>& data) {
  return IsFourByteAligned(data.unsafe_ptr());
}

// Helper method to extract a UTF-16 string from a StringPool. If the string is stored as UTF-8,
// the conversion to UTF-16 happens within ResStringPool.
android::StringPiece16 GetString16(const android::ResStringPool& pool, size_t idx);

// Helper method to extract a UTF-8 string from a StringPool. If the string is stored as UTF-16,
// the conversion from UTF-16 to UTF-8 does not happen in ResStringPool and is done by this method,
// which maintains no state or cache. This means we must return an std::string copy.
std::string GetString(const android::ResStringPool& pool, size_t idx);

// Copies the entire BigBuffer into a single buffer.
std::unique_ptr<uint8_t[]> Copy(const android::BigBuffer& buffer);

}  // namespace util
}  // namespace android

#endif /* UTIL_H_ */
