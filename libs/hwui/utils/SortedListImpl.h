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

#ifndef ANDROID_HWUI_SORTED_LIST_IMPL_H
#define ANDROID_HWUI_SORTED_LIST_IMPL_H

#include <utils/VectorImpl.h>

namespace android {
namespace uirenderer {

class SortedListImpl: public VectorImpl {
public:
    SortedListImpl(size_t itemSize, uint32_t flags);
    SortedListImpl(const VectorImpl& rhs);
    virtual ~SortedListImpl();

    SortedListImpl& operator =(const SortedListImpl& rhs);

    ssize_t indexOf(const void* item) const;
    size_t orderOf(const void* item) const;
    ssize_t add(const void* item);
    ssize_t merge(const VectorImpl& vector);
    ssize_t merge(const SortedListImpl& vector);
    ssize_t remove(const void* item);

protected:
    virtual int do_compare(const void* lhs, const void* rhs) const = 0;

private:
    ssize_t _indexOrderOf(const void* item, size_t* order = 0) const;

    // these are made private, because they can't be used on a SortedVector
    // (they don't have an implementation either)
    ssize_t add();
    void pop();
    void push();
    void push(const void* item);
    ssize_t insertVectorAt(const VectorImpl& vector, size_t index);
    ssize_t appendVector(const VectorImpl& vector);
    ssize_t insertArrayAt(const void* array, size_t index, size_t length);
    ssize_t appendArray(const void* array, size_t length);
    ssize_t insertAt(size_t where, size_t numItems = 1);
    ssize_t insertAt(const void* item, size_t where, size_t numItems = 1);
    ssize_t replaceAt(size_t index);
    ssize_t replaceAt(const void* item, size_t index);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SORTED_LIST_IMPL_H
