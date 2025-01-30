/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <androidfw/ResourceTypes.h>
#include <utils/ByteOrder.h>

namespace android {

struct TypeVariant {
    explicit TypeVariant(const ResTable_type* data);

    class iterator {
    public:
        bool operator==(const iterator& rhs) const {
            return mTypeVariant == rhs.mTypeVariant && mIndex == rhs.mIndex;
        }

        iterator operator++(int) {
            iterator prev = *this;
            operator++();
            return prev;
        }

        const ResTable_entry* operator->() const {
            return operator*();
        }

        uint32_t index() const {
            return mIndex;
        }

        iterator& operator++();
        const ResTable_entry* operator*() const;

    private:
        friend struct TypeVariant;

        enum class Kind { Begin, End };
        iterator(const TypeVariant* tv, Kind kind)
            : mTypeVariant(tv) {
          mSparseIndex = mIndex = (kind == Kind::Begin ? 0 : tv->mLength);
          // mSparseIndex here is technically past the number of sparse entries, but it is still
          // ok as it is enough to infer that this is the end iterator.
        }

        const TypeVariant* mTypeVariant;
        uint32_t mIndex;
        uint32_t mSparseIndex;
    };

    iterator beginEntries() const {
        return iterator(this, iterator::Kind::Begin);
    }

    iterator endEntries() const {
        return iterator(this, iterator::Kind::End);
    }

    const ResTable_type* data;

private:
    // For a dense table, this is the number of the elements.
    // For a sparse table, this is the index of the last element + 1.
    // In both cases, it can be used for iteration as the upper loop bound as in |i < mLength|.
    uint32_t mLength;
    bool mSparse;
};

} // namespace android
