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

#include "SortedListImpl.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Sorted list implementation, not for direct use
///////////////////////////////////////////////////////////////////////////////

SortedListImpl::SortedListImpl(size_t itemSize, uint32_t flags): VectorImpl(itemSize, flags) {
}

SortedListImpl::SortedListImpl(const VectorImpl& rhs): VectorImpl(rhs) {
}

SortedListImpl::~SortedListImpl() {
}

SortedListImpl& SortedListImpl::operator =(const SortedListImpl& rhs) {
    return static_cast<SortedListImpl&>
            (VectorImpl::operator =(static_cast<const VectorImpl&> (rhs)));
}

ssize_t SortedListImpl::indexOf(const void* item) const {
    return _indexOrderOf(item);
}

size_t SortedListImpl::orderOf(const void* item) const {
    size_t o;
    _indexOrderOf(item, &o);
    return o;
}

ssize_t SortedListImpl::_indexOrderOf(const void* item, size_t* order) const {
    // binary search
    ssize_t err = NAME_NOT_FOUND;
    ssize_t l = 0;
    ssize_t h = size() - 1;
    ssize_t mid;
    const void* a = arrayImpl();
    const size_t s = itemSize();
    while (l <= h) {
        mid = l + (h - l) / 2;
        const void* const curr = reinterpret_cast<const char *> (a) + (mid * s);
        const int c = do_compare(curr, item);
        if (c == 0) {
            err = l = mid;
            break;
        } else if (c < 0) {
            l = mid + 1;
        } else {
            h = mid - 1;
        }
    }
    if (order) {
        *order = l;
    }
    return err;
}

ssize_t SortedListImpl::add(const void* item) {
    size_t order;
    ssize_t index = _indexOrderOf(item, &order);
    index = VectorImpl::insertAt(item, order, 1);
    return index;
}

ssize_t SortedListImpl::merge(const VectorImpl& vector) {
    // naive merge...
    if (!vector.isEmpty()) {
        const void* buffer = vector.arrayImpl();
        const size_t is = itemSize();
        size_t s = vector.size();
        for (size_t i = 0; i < s; i++) {
            ssize_t err = add(reinterpret_cast<const char*> (buffer) + i * is);
            if (err < 0) {
                return err;
            }
        }
    }
    return NO_ERROR;
}

ssize_t SortedListImpl::merge(const SortedListImpl& vector) {
    // we've merging a sorted vector... nice!
    ssize_t err = NO_ERROR;
    if (!vector.isEmpty()) {
        // first take care of the case where the vectors are sorted together
        if (do_compare(vector.itemLocation(vector.size() - 1), arrayImpl()) <= 0) {
            err = VectorImpl::insertVectorAt(static_cast<const VectorImpl&> (vector), 0);
        } else if (do_compare(vector.arrayImpl(), itemLocation(size() - 1)) >= 0) {
            err = VectorImpl::appendVector(static_cast<const VectorImpl&> (vector));
        } else {
            // this could be made a little better
            err = merge(static_cast<const VectorImpl&> (vector));
        }
    }
    return err;
}

ssize_t SortedListImpl::remove(const void* item) {
    ssize_t i = indexOf(item);
    if (i >= 0) {
        VectorImpl::removeItemsAt(i, 1);
    }
    return i;
}

}; // namespace uirenderer
}; // namespace android
