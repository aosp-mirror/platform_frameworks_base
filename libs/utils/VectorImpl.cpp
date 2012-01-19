/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "Vector"

#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/SharedBuffer.h>
#include <utils/VectorImpl.h>

/*****************************************************************************/


namespace android {

// ----------------------------------------------------------------------------

const size_t kMinVectorCapacity = 4;

static inline size_t max(size_t a, size_t b) {
    return a>b ? a : b;
}

// ----------------------------------------------------------------------------

VectorImpl::VectorImpl(size_t itemSize, uint32_t flags)
    : mStorage(0), mCount(0), mFlags(flags), mItemSize(itemSize)
{
}

VectorImpl::VectorImpl(const VectorImpl& rhs)
    :   mStorage(rhs.mStorage), mCount(rhs.mCount),
        mFlags(rhs.mFlags), mItemSize(rhs.mItemSize)
{
    if (mStorage) {
        SharedBuffer::sharedBuffer(mStorage)->acquire();
    }
}

VectorImpl::~VectorImpl()
{
    LOG_ASSERT(!mCount,
        "[%p] "
        "subclasses of VectorImpl must call finish_vector()"
        " in their destructor. Leaking %d bytes.",
        this, (int)(mCount*mItemSize));
    // We can't call _do_destroy() here because the vtable is already gone. 
}

VectorImpl& VectorImpl::operator = (const VectorImpl& rhs)
{
    LOG_ASSERT(mItemSize == rhs.mItemSize,
        "Vector<> have different types (this=%p, rhs=%p)", this, &rhs);
    if (this != &rhs) {
        release_storage();
        if (rhs.mCount) {
            mStorage = rhs.mStorage;
            mCount = rhs.mCount;
            SharedBuffer::sharedBuffer(mStorage)->acquire();
        } else {
            mStorage = 0;
            mCount = 0;
        }
    }
    return *this;
}

void* VectorImpl::editArrayImpl()
{
    if (mStorage) {
        SharedBuffer* sb = SharedBuffer::sharedBuffer(mStorage)->attemptEdit();
        if (sb == 0) {
            sb = SharedBuffer::alloc(capacity() * mItemSize);
            if (sb) {
                _do_copy(sb->data(), mStorage, mCount);
                release_storage();
                mStorage = sb->data();
            }
        }
    }
    return mStorage;
}

size_t VectorImpl::capacity() const
{
    if (mStorage) {
        return SharedBuffer::sharedBuffer(mStorage)->size() / mItemSize;
    }
    return 0;
}

ssize_t VectorImpl::insertVectorAt(const VectorImpl& vector, size_t index)
{
    return insertArrayAt(vector.arrayImpl(), index, vector.size());
}

ssize_t VectorImpl::appendVector(const VectorImpl& vector)
{
    return insertVectorAt(vector, size());
}

ssize_t VectorImpl::insertArrayAt(const void* array, size_t index, size_t length)
{
    if (index > size())
        return BAD_INDEX;
    void* where = _grow(index, length);
    if (where) {
        _do_copy(where, array, length);
    }
    return where ? index : (ssize_t)NO_MEMORY;
}

ssize_t VectorImpl::appendArray(const void* array, size_t length)
{
    return insertArrayAt(array, size(), length);
}

ssize_t VectorImpl::insertAt(size_t index, size_t numItems)
{
    return insertAt(0, index, numItems);
}

ssize_t VectorImpl::insertAt(const void* item, size_t index, size_t numItems)
{
    if (index > size())
        return BAD_INDEX;
    void* where = _grow(index, numItems);
    if (where) {
        if (item) {
            _do_splat(where, item, numItems);
        } else {
            _do_construct(where, numItems);
        }
    }
    return where ? index : (ssize_t)NO_MEMORY;
}

static int sortProxy(const void* lhs, const void* rhs, void* func)
{
    return (*(VectorImpl::compar_t)func)(lhs, rhs);
}

status_t VectorImpl::sort(VectorImpl::compar_t cmp)
{
    return sort(sortProxy, (void*)cmp);
}

status_t VectorImpl::sort(VectorImpl::compar_r_t cmp, void* state)
{
    // the sort must be stable. we're using insertion sort which
    // is well suited for small and already sorted arrays
    // for big arrays, it could be better to use mergesort
    const ssize_t count = size();
    if (count > 1) {
        void* array = const_cast<void*>(arrayImpl());
        void* temp = 0;
        ssize_t i = 1;
        while (i < count) {
            void* item = reinterpret_cast<char*>(array) + mItemSize*(i);
            void* curr = reinterpret_cast<char*>(array) + mItemSize*(i-1);
            if (cmp(curr, item, state) > 0) {

                if (!temp) {
                    // we're going to have to modify the array...
                    array = editArrayImpl();
                    if (!array) return NO_MEMORY;
                    temp = malloc(mItemSize);
                    if (!temp) return NO_MEMORY;
                    item = reinterpret_cast<char*>(array) + mItemSize*(i);
                    curr = reinterpret_cast<char*>(array) + mItemSize*(i-1);
                } else {
                    _do_destroy(temp, 1);
                }

                _do_copy(temp, item, 1);

                ssize_t j = i-1;
                void* next = reinterpret_cast<char*>(array) + mItemSize*(i);                    
                do {
                    _do_destroy(next, 1);
                    _do_copy(next, curr, 1);
                    next = curr;
                    --j;
                    curr = reinterpret_cast<char*>(array) + mItemSize*(j);                    
                } while (j>=0 && (cmp(curr, temp, state) > 0));

                _do_destroy(next, 1);
                _do_copy(next, temp, 1);
            }
            i++;
        }
        
        if (temp) {
            _do_destroy(temp, 1);
            free(temp);
        }
    }
    return NO_ERROR;
}

void VectorImpl::pop()
{
    if (size())
        removeItemsAt(size()-1, 1);
}

void VectorImpl::push()
{
    push(0);
}

void VectorImpl::push(const void* item)
{
    insertAt(item, size());
}

ssize_t VectorImpl::add()
{
    return add(0);
}

ssize_t VectorImpl::add(const void* item)
{
    return insertAt(item, size());
}

ssize_t VectorImpl::replaceAt(size_t index)
{
    return replaceAt(0, index);
}

ssize_t VectorImpl::replaceAt(const void* prototype, size_t index)
{
    LOG_ASSERT(index<size(),
        "[%p] replace: index=%d, size=%d", this, (int)index, (int)size());

    void* item = editItemLocation(index);
    if (item != prototype) {
        if (item == 0)
            return NO_MEMORY;
        _do_destroy(item, 1);
        if (prototype == 0) {
            _do_construct(item, 1);
        } else {
            _do_copy(item, prototype, 1);
        }
    }
    return ssize_t(index);
}

ssize_t VectorImpl::removeItemsAt(size_t index, size_t count)
{
    LOG_ASSERT((index+count)<=size(),
        "[%p] remove: index=%d, count=%d, size=%d",
               this, (int)index, (int)count, (int)size());

    if ((index+count) > size())
        return BAD_VALUE;
   _shrink(index, count);
   return index;
}

void VectorImpl::finish_vector()
{
    release_storage();
    mStorage = 0;
    mCount = 0;
}

void VectorImpl::clear()
{
    _shrink(0, mCount);
}

void* VectorImpl::editItemLocation(size_t index)
{
    LOG_ASSERT(index<capacity(),
        "[%p] editItemLocation: index=%d, capacity=%d, count=%d",
        this, (int)index, (int)capacity(), (int)mCount);
            
    void* buffer = editArrayImpl();
    if (buffer)
        return reinterpret_cast<char*>(buffer) + index*mItemSize;
    return 0;
}

const void* VectorImpl::itemLocation(size_t index) const
{
    LOG_ASSERT(index<capacity(),
        "[%p] itemLocation: index=%d, capacity=%d, count=%d",
        this, (int)index, (int)capacity(), (int)mCount);

    const  void* buffer = arrayImpl();
    if (buffer)
        return reinterpret_cast<const char*>(buffer) + index*mItemSize;
    return 0;
}

ssize_t VectorImpl::setCapacity(size_t new_capacity)
{
    size_t current_capacity = capacity();
    ssize_t amount = new_capacity - size();
    if (amount <= 0) {
        // we can't reduce the capacity
        return current_capacity;
    } 
    SharedBuffer* sb = SharedBuffer::alloc(new_capacity * mItemSize);
    if (sb) {
        void* array = sb->data();
        _do_copy(array, mStorage, size());
        release_storage();
        mStorage = const_cast<void*>(array);
    } else {
        return NO_MEMORY;
    }
    return new_capacity;
}

void VectorImpl::release_storage()
{
    if (mStorage) {
        const SharedBuffer* sb = SharedBuffer::sharedBuffer(mStorage);
        if (sb->release(SharedBuffer::eKeepStorage) == 1) {
            _do_destroy(mStorage, mCount);
            SharedBuffer::dealloc(sb);
        } 
    }
}

void* VectorImpl::_grow(size_t where, size_t amount)
{
//    ALOGV("_grow(this=%p, where=%d, amount=%d) count=%d, capacity=%d",
//        this, (int)where, (int)amount, (int)mCount, (int)capacity());

    LOG_ASSERT(where <= mCount,
            "[%p] _grow: where=%d, amount=%d, count=%d",
            this, (int)where, (int)amount, (int)mCount); // caller already checked

    const size_t new_size = mCount + amount;
    if (capacity() < new_size) {
        const size_t new_capacity = max(kMinVectorCapacity, ((new_size*3)+1)/2);
//        ALOGV("grow vector %p, new_capacity=%d", this, (int)new_capacity);
        if ((mStorage) &&
            (mCount==where) &&
            (mFlags & HAS_TRIVIAL_COPY) &&
            (mFlags & HAS_TRIVIAL_DTOR))
        {
            const SharedBuffer* cur_sb = SharedBuffer::sharedBuffer(mStorage);
            SharedBuffer* sb = cur_sb->editResize(new_capacity * mItemSize);
            mStorage = sb->data();
        } else {
            SharedBuffer* sb = SharedBuffer::alloc(new_capacity * mItemSize);
            if (sb) {
                void* array = sb->data();
                if (where != 0) {
                    _do_copy(array, mStorage, where);
                }
                if (where != mCount) {
                    const void* from = reinterpret_cast<const uint8_t *>(mStorage) + where*mItemSize;
                    void* dest = reinterpret_cast<uint8_t *>(array) + (where+amount)*mItemSize;
                    _do_copy(dest, from, mCount-where);
                }
                release_storage();
                mStorage = const_cast<void*>(array);
            }
        }
    } else {
        if (where != mCount) {
            void* array = editArrayImpl();
            const void* from = reinterpret_cast<const uint8_t *>(array) + where*mItemSize;
            void* to = reinterpret_cast<uint8_t *>(array) + (where+amount)*mItemSize;
            _do_move_forward(to, from, mCount - where);
        }
    }
    mCount = new_size;
    void* free_space = const_cast<void*>(itemLocation(where));
    return free_space;
}

void VectorImpl::_shrink(size_t where, size_t amount)
{
    if (!mStorage)
        return;

//    ALOGV("_shrink(this=%p, where=%d, amount=%d) count=%d, capacity=%d",
//        this, (int)where, (int)amount, (int)mCount, (int)capacity());

    LOG_ASSERT(where + amount <= mCount,
            "[%p] _shrink: where=%d, amount=%d, count=%d",
            this, (int)where, (int)amount, (int)mCount); // caller already checked

    const size_t new_size = mCount - amount;
    if (new_size*3 < capacity()) {
        const size_t new_capacity = max(kMinVectorCapacity, new_size*2);
//        ALOGV("shrink vector %p, new_capacity=%d", this, (int)new_capacity);
        if ((where == new_size) &&
            (mFlags & HAS_TRIVIAL_COPY) &&
            (mFlags & HAS_TRIVIAL_DTOR))
        {
            const SharedBuffer* cur_sb = SharedBuffer::sharedBuffer(mStorage);
            SharedBuffer* sb = cur_sb->editResize(new_capacity * mItemSize);
            mStorage = sb->data();
        } else {
            SharedBuffer* sb = SharedBuffer::alloc(new_capacity * mItemSize);
            if (sb) {
                void* array = sb->data();
                if (where != 0) {
                    _do_copy(array, mStorage, where);
                }
                if (where != new_size) {
                    const void* from = reinterpret_cast<const uint8_t *>(mStorage) + (where+amount)*mItemSize;
                    void* dest = reinterpret_cast<uint8_t *>(array) + where*mItemSize;
                    _do_copy(dest, from, new_size - where);
                }
                release_storage();
                mStorage = const_cast<void*>(array);
            }
        }
    } else {
        void* array = editArrayImpl();
        void* to = reinterpret_cast<uint8_t *>(array) + where*mItemSize;
        _do_destroy(to, amount);
        if (where != new_size) {
            const void* from = reinterpret_cast<uint8_t *>(array) + (where+amount)*mItemSize;
            _do_move_backward(to, from, new_size - where);
        }
    }
    mCount = new_size;
}

size_t VectorImpl::itemSize() const {
    return mItemSize;
}

void VectorImpl::_do_construct(void* storage, size_t num) const
{
    if (!(mFlags & HAS_TRIVIAL_CTOR)) {
        do_construct(storage, num);
    }
}

void VectorImpl::_do_destroy(void* storage, size_t num) const
{
    if (!(mFlags & HAS_TRIVIAL_DTOR)) {
        do_destroy(storage, num);
    }
}

void VectorImpl::_do_copy(void* dest, const void* from, size_t num) const
{
    if (!(mFlags & HAS_TRIVIAL_COPY)) {
        do_copy(dest, from, num);
    } else {
        memcpy(dest, from, num*itemSize());
    }
}

void VectorImpl::_do_splat(void* dest, const void* item, size_t num) const {
    do_splat(dest, item, num);
}

void VectorImpl::_do_move_forward(void* dest, const void* from, size_t num) const {
    do_move_forward(dest, from, num);
}

void VectorImpl::_do_move_backward(void* dest, const void* from, size_t num) const {
    do_move_backward(dest, from, num);
}

void VectorImpl::reservedVectorImpl1() { }
void VectorImpl::reservedVectorImpl2() { }
void VectorImpl::reservedVectorImpl3() { }
void VectorImpl::reservedVectorImpl4() { }
void VectorImpl::reservedVectorImpl5() { }
void VectorImpl::reservedVectorImpl6() { }
void VectorImpl::reservedVectorImpl7() { }
void VectorImpl::reservedVectorImpl8() { }

/*****************************************************************************/

SortedVectorImpl::SortedVectorImpl(size_t itemSize, uint32_t flags)
    : VectorImpl(itemSize, flags)
{
}

SortedVectorImpl::SortedVectorImpl(const VectorImpl& rhs)
: VectorImpl(rhs)
{
}

SortedVectorImpl::~SortedVectorImpl()
{
}

SortedVectorImpl& SortedVectorImpl::operator = (const SortedVectorImpl& rhs)
{
    return static_cast<SortedVectorImpl&>( VectorImpl::operator = (static_cast<const VectorImpl&>(rhs)) );
}

ssize_t SortedVectorImpl::indexOf(const void* item) const
{
    return _indexOrderOf(item);
}

size_t SortedVectorImpl::orderOf(const void* item) const
{
    size_t o;
    _indexOrderOf(item, &o);
    return o;
}

ssize_t SortedVectorImpl::_indexOrderOf(const void* item, size_t* order) const
{
    // binary search
    ssize_t err = NAME_NOT_FOUND;
    ssize_t l = 0;
    ssize_t h = size()-1;
    ssize_t mid;
    const void* a = arrayImpl();
    const size_t s = itemSize();
    while (l <= h) {
        mid = l + (h - l)/2;
        const void* const curr = reinterpret_cast<const char *>(a) + (mid*s);
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
    if (order) *order = l;
    return err;
}

ssize_t SortedVectorImpl::add(const void* item)
{
    size_t order;
    ssize_t index = _indexOrderOf(item, &order);
    if (index < 0) {
        index = VectorImpl::insertAt(item, order, 1);
    } else {
        index = VectorImpl::replaceAt(item, index);
    }
    return index;
}

ssize_t SortedVectorImpl::merge(const VectorImpl& vector)
{
    // naive merge...
    if (!vector.isEmpty()) {
        const void* buffer = vector.arrayImpl();
        const size_t is = itemSize();
        size_t s = vector.size();
        for (size_t i=0 ; i<s ; i++) {
            ssize_t err = add( reinterpret_cast<const char*>(buffer) + i*is );
            if (err<0) {
                return err;
            }
        }
    }
    return NO_ERROR;
}

ssize_t SortedVectorImpl::merge(const SortedVectorImpl& vector)
{
    // we've merging a sorted vector... nice!
    ssize_t err = NO_ERROR;
    if (!vector.isEmpty()) {
        // first take care of the case where the vectors are sorted together
        if (do_compare(vector.itemLocation(vector.size()-1), arrayImpl()) <= 0) {
            err = VectorImpl::insertVectorAt(static_cast<const VectorImpl&>(vector), 0);
        } else if (do_compare(vector.arrayImpl(), itemLocation(size()-1)) >= 0) {
            err = VectorImpl::appendVector(static_cast<const VectorImpl&>(vector));
        } else {
            // this could be made a little better
            err = merge(static_cast<const VectorImpl&>(vector));
        }
    }
    return err;
}

ssize_t SortedVectorImpl::remove(const void* item)
{
    ssize_t i = indexOf(item);
    if (i>=0) {
        VectorImpl::removeItemsAt(i, 1);
    }
    return i;
}

void SortedVectorImpl::reservedSortedVectorImpl1() { };
void SortedVectorImpl::reservedSortedVectorImpl2() { };
void SortedVectorImpl::reservedSortedVectorImpl3() { };
void SortedVectorImpl::reservedSortedVectorImpl4() { };
void SortedVectorImpl::reservedSortedVectorImpl5() { };
void SortedVectorImpl::reservedSortedVectorImpl6() { };
void SortedVectorImpl::reservedSortedVectorImpl7() { };
void SortedVectorImpl::reservedSortedVectorImpl8() { };


/*****************************************************************************/

}; // namespace android

