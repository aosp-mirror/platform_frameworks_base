/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef A_STRING_H_

#define A_STRING_H_

#include <sys/types.h>

namespace android {

struct AString {
    AString();
    AString(const char *s);
    AString(const char *s, size_t size);
    AString(const AString &from);
    AString(const AString &from, size_t offset, size_t n);
    ~AString();

    AString &operator=(const AString &from);
    void setTo(const char *s);
    void setTo(const char *s, size_t size);
    void setTo(const AString &from, size_t offset, size_t n);

    size_t size() const;
    const char *c_str() const;

    bool empty() const;

    void clear();
    void trim();
    void erase(size_t start, size_t n);

    void append(char c) { append(&c, 1); }
    void append(const char *s);
    void append(const char *s, size_t size);
    void append(const AString &from);
    void append(const AString &from, size_t offset, size_t n);
    void append(int x);
    void append(unsigned x);
    void append(long x);
    void append(unsigned long x);
    void append(long long x);
    void append(unsigned long long x);
    void append(float x);
    void append(double x);
    void append(void *x);

    void insert(const AString &from, size_t insertionPos);
    void insert(const char *from, size_t size, size_t insertionPos);

    ssize_t find(const char *substring, size_t start = 0) const;

    size_t hash() const;

    bool operator==(const AString &other) const;
    bool operator<(const AString &other) const;
    bool operator>(const AString &other) const;

    int compare(const AString &other) const;

    bool startsWith(const char *prefix) const;
    bool endsWith(const char *suffix) const;

    void tolower();

private:
    static const char *kEmptyString;

    char *mData;
    size_t mSize;
    size_t mAllocSize;

    void makeMutable();
};

AString StringPrintf(const char *format, ...);

}  // namespace android

#endif  // A_STRING_H_

