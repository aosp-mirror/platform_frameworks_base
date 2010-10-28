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

#ifndef ANDROID_HWUI_SORTED_LIST_H
#define ANDROID_HWUI_SORTED_LIST_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Vector.h>
#include <utils/TypeHelpers.h>

#include "SortedListImpl.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Sorted list
///////////////////////////////////////////////////////////////////////////////

template<class TYPE>
class SortedList: private SortedListImpl {
public:
    typedef TYPE value_type;

    SortedList();
    SortedList(const SortedList<TYPE>& rhs);
    virtual ~SortedList();

    const SortedList<TYPE>& operator =(const SortedList<TYPE>& rhs) const;
    SortedList<TYPE>& operator =(const SortedList<TYPE>& rhs);

    inline void clear() {
        VectorImpl::clear();
    }

    inline size_t size() const {
        return VectorImpl::size();
    }

    inline bool isEmpty() const {
        return VectorImpl::isEmpty();
    }

    inline size_t capacity() const {
        return VectorImpl::capacity();
    }

    inline ssize_t setCapacity(size_t size) {
        return VectorImpl::setCapacity(size);
    }

    inline const TYPE* array() const;

    TYPE* editArray();

    ssize_t indexOf(const TYPE& item) const;
    size_t orderOf(const TYPE& item) const;

    inline const TYPE& operator [](size_t index) const;
    inline const TYPE& itemAt(size_t index) const;
    const TYPE& top() const;
    const TYPE& mirrorItemAt(ssize_t index) const;

    ssize_t add(const TYPE& item);

    TYPE& editItemAt(size_t index) {
        return *(static_cast<TYPE *> (VectorImpl::editItemLocation(index)));
    }

    ssize_t merge(const Vector<TYPE>& vector);
    ssize_t merge(const SortedList<TYPE>& vector);

    ssize_t remove(const TYPE&);

    inline ssize_t removeItemsAt(size_t index, size_t count = 1);
    inline ssize_t removeAt(size_t index) {
        return removeItemsAt(index);
    }

protected:
    virtual void do_construct(void* storage, size_t num) const;
    virtual void do_destroy(void* storage, size_t num) const;
    virtual void do_copy(void* dest, const void* from, size_t num) const;
    virtual void do_splat(void* dest, const void* item, size_t num) const;
    virtual void do_move_forward(void* dest, const void* from, size_t num) const;
    virtual void do_move_backward(void* dest, const void* from, size_t num) const;
    virtual int do_compare(const void* lhs, const void* rhs) const;
}; // class SortedList

///////////////////////////////////////////////////////////////////////////////
// Implementation
///////////////////////////////////////////////////////////////////////////////

template<class TYPE>
inline SortedList<TYPE>::SortedList():
        SortedListImpl(sizeof(TYPE), ((traits<TYPE>::has_trivial_ctor ? HAS_TRIVIAL_CTOR : 0)
            | (traits<TYPE>::has_trivial_dtor ? HAS_TRIVIAL_DTOR : 0)
            | (traits<TYPE>::has_trivial_copy ? HAS_TRIVIAL_COPY : 0))) {
}

template<class TYPE>
inline SortedList<TYPE>::SortedList(const SortedList<TYPE>& rhs): SortedListImpl(rhs) {
}

template<class TYPE> inline SortedList<TYPE>::~SortedList() {
    finish_vector();
}

template<class TYPE>
inline SortedList<TYPE>& SortedList<TYPE>::operator =(const SortedList<TYPE>& rhs) {
    SortedListImpl::operator =(rhs);
    return *this;
}

template<class TYPE>
inline const SortedList<TYPE>& SortedList<TYPE>::operator =(
        const SortedList<TYPE>& rhs) const {
    SortedListImpl::operator =(rhs);
    return *this;
}

template<class TYPE>
inline const TYPE* SortedList<TYPE>::array() const {
    return static_cast<const TYPE *> (arrayImpl());
}

template<class TYPE>
inline TYPE* SortedList<TYPE>::editArray() {
    return static_cast<TYPE *> (editArrayImpl());
}

template<class TYPE>
inline const TYPE& SortedList<TYPE>::operator[](size_t index) const {
    assert( index<size() );
    return *(array() + index);
}

template<class TYPE>
inline const TYPE& SortedList<TYPE>::itemAt(size_t index) const {
    return operator[](index);
}

template<class TYPE>
inline const TYPE& SortedList<TYPE>::mirrorItemAt(ssize_t index) const {
    assert( (index>0 ? index : -index)<size() );
    return *(array() + ((index < 0) ? (size() - index) : index));
}

template<class TYPE>
inline const TYPE& SortedList<TYPE>::top() const {
    return *(array() + size() - 1);
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::add(const TYPE& item) {
    return SortedListImpl::add(&item);
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::indexOf(const TYPE& item) const {
    return SortedListImpl::indexOf(&item);
}

template<class TYPE>
inline size_t SortedList<TYPE>::orderOf(const TYPE& item) const {
    return SortedListImpl::orderOf(&item);
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::merge(const Vector<TYPE>& vector) {
    return SortedListImpl::merge(reinterpret_cast<const VectorImpl&> (vector));
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::merge(const SortedList<TYPE>& vector) {
    return SortedListImpl::merge(reinterpret_cast<const SortedListImpl&> (vector));
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::remove(const TYPE& item) {
    return SortedListImpl::remove(&item);
}

template<class TYPE>
inline ssize_t SortedList<TYPE>::removeItemsAt(size_t index, size_t count) {
    return VectorImpl::removeItemsAt(index, count);
}

template<class TYPE>
void SortedList<TYPE>::do_construct(void* storage, size_t num) const {
    construct_type(reinterpret_cast<TYPE*> (storage), num);
}

template<class TYPE>
void SortedList<TYPE>::do_destroy(void* storage, size_t num) const {
    destroy_type(reinterpret_cast<TYPE*> (storage), num);
}

template<class TYPE>
void SortedList<TYPE>::do_copy(void* dest, const void* from, size_t num) const {
    copy_type(reinterpret_cast<TYPE*> (dest), reinterpret_cast<const TYPE*> (from), num);
}

template<class TYPE>
void SortedList<TYPE>::do_splat(void* dest, const void* item, size_t num) const {
    splat_type(reinterpret_cast<TYPE*> (dest), reinterpret_cast<const TYPE*> (item), num);
}

template<class TYPE>
void SortedList<TYPE>::do_move_forward(void* dest, const void* from, size_t num) const {
    move_forward_type(reinterpret_cast<TYPE*> (dest), reinterpret_cast<const TYPE*> (from), num);
}

template<class TYPE>
void SortedList<TYPE>::do_move_backward(void* dest, const void* from, size_t num) const {
    move_backward_type(reinterpret_cast<TYPE*> (dest), reinterpret_cast<const TYPE*> (from), num);
}

template<class TYPE>
int SortedList<TYPE>::do_compare(const void* lhs, const void* rhs) const {
    return compare_type(*reinterpret_cast<const TYPE*> (lhs), *reinterpret_cast<const TYPE*> (rhs));
}

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SORTED_LIST_H
