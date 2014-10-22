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

#ifndef __TYPE_WRAPPERS_H
#define __TYPE_WRAPPERS_H

#include <androidfw/ResourceTypes.h>

namespace android {

struct TypeVariant {
    TypeVariant(const ResTable_type* data)
        : data(data) {}

    class iterator {
    public:
        iterator& operator=(const iterator& rhs) {
            mTypeVariant = rhs.mTypeVariant;
            mIndex = rhs.mIndex;
        }

        bool operator==(const iterator& rhs) const {
            return mTypeVariant == rhs.mTypeVariant && mIndex == rhs.mIndex;
        }

        bool operator!=(const iterator& rhs) const {
            return mTypeVariant != rhs.mTypeVariant || mIndex != rhs.mIndex;
        }

        iterator operator++(int) {
            uint32_t prevIndex = mIndex;
            operator++();
            return iterator(mTypeVariant, prevIndex);
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
        iterator(const TypeVariant* tv, uint32_t index)
            : mTypeVariant(tv), mIndex(index) {}
        const TypeVariant* mTypeVariant;
        uint32_t mIndex;
    };

    iterator beginEntries() const {
        return iterator(this, 0);
    }

    iterator endEntries() const {
        return iterator(this, dtohl(data->entryCount));
    }

    const ResTable_type* data;
};

} // namespace android

#endif // __TYPE_WRAPPERS_H
