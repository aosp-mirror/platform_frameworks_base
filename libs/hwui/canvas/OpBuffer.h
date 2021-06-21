/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstddef>
#include <cstdlib>
#include <type_traits>
#include <utility>

namespace android::uirenderer {

template <typename T>
struct OpBufferItemHeader {
    T type : 8;
    uint32_t size : 24;
};

struct OpBufferAllocationHeader {
    // Used size, including header size
    size_t used = 0;
    // Capacity, including header size
    size_t capacity = 0;
    // Offset relative to `this` at which the first item is
    size_t startOffset = 0;
    // Offset relative to `this` at which the last item is
    size_t endOffset = 0;
};

#define BE_OPBUFFERS_FRIEND()                                      \
    template <typename ItemTypes, template <ItemTypes> typename, typename, typename> \
    friend class OpBuffer

template <typename ItemTypes, template <ItemTypes> typename ItemContainer,
          typename BufferHeader = OpBufferAllocationHeader,
          typename ItemTypesSequence = std::make_index_sequence<static_cast<int>(ItemTypes::COUNT)>>
class OpBuffer {
    // Instead of re-aligning individual inserts, just pad the size of everything
    // to a multiple of pointer alignment. This assumes we never work with doubles.
    // Which we don't.
    static constexpr size_t Alignment = alignof(void*);

    static constexpr size_t PadAlign(size_t size) {
        return (size + (Alignment - 1)) & -Alignment;
    }

public:
    static constexpr auto STARTING_SIZE = PadAlign(sizeof(BufferHeader));
    using ItemHeader = OpBufferItemHeader<ItemTypes>;

    explicit OpBuffer() = default;

    // Prevent copying by default
    OpBuffer(const OpBuffer&) = delete;
    void operator=(const OpBuffer&) = delete;

    OpBuffer(OpBuffer&& other) {
        mBuffer = other.mBuffer;
        other.mBuffer = nullptr;
    }

    void operator=(OpBuffer&& other) {
        destroy();
        mBuffer = other.mBuffer;
        other.mBuffer = nullptr;
    }

    ~OpBuffer() {
        destroy();
    }

    constexpr size_t capacity() const { return mBuffer ? mBuffer->capacity : 0; }

    constexpr size_t size() const { return mBuffer ? mBuffer->used : 0; }

    constexpr size_t remaining() const { return capacity() - size(); }

    // TODO: Add less-copy'ing variants of this. emplace_back? deferred initialization?
    template <ItemTypes T>
    void push_container(ItemContainer<T>&& op) {
        static_assert(alignof(ItemContainer<T>) <= Alignment);
        static_assert(offsetof(ItemContainer<T>, header) == 0);

        constexpr auto padded_size = PadAlign(sizeof(ItemContainer<T>));
        if (remaining() < padded_size) {
            resize(std::max(padded_size, capacity()) * 2);
        }
        mBuffer->endOffset = mBuffer->used;
        mBuffer->used += padded_size;

        void* allocateAt = reinterpret_cast<uint8_t*>(mBuffer) + mBuffer->endOffset;
        auto temp = new (allocateAt) ItemContainer<T>{std::move(op)};
        temp->header = {.type = T, .size = padded_size};
    }

    void resize(size_t newsize) {
        // Add the header size to newsize
        const size_t adjustedSize = newsize + STARTING_SIZE;

        if (adjustedSize < size()) {
            // todo: throw?
            return;
        }
        if (newsize == 0) {
            free(mBuffer);
            mBuffer = nullptr;
        } else {
            if (mBuffer) {
                mBuffer = reinterpret_cast<BufferHeader*>(realloc(mBuffer, adjustedSize));
                mBuffer->capacity = adjustedSize;
            } else {
                mBuffer = new (malloc(adjustedSize)) BufferHeader();
                mBuffer->capacity = adjustedSize;
                mBuffer->used = STARTING_SIZE;
                mBuffer->startOffset = STARTING_SIZE;
            }
        }
    }

    template <typename F>
    void for_each(F&& f) const {
        do_for_each(std::forward<F>(f), ItemTypesSequence{});
    }

    void clear();

    ItemHeader* first() const { return isEmpty() ? nullptr : itemAt(mBuffer->startOffset); }

    ItemHeader* last() const { return isEmpty() ? nullptr : itemAt(mBuffer->endOffset); }

    class sentinal {
    public:
        explicit sentinal(const uint8_t* end) : end(end) {}
    private:
        const uint8_t* const end;
    };

    sentinal end() const {
        return sentinal{end_ptr()};
    }

    template <ItemTypes T>
    class filtered_iterator {
    public:
        explicit filtered_iterator(uint8_t* start, const uint8_t* end)
                : mCurrent(start), mEnd(end) {
            ItemHeader* header = reinterpret_cast<ItemHeader*>(mCurrent);
            if (header->type != T) {
                advance();
            }
        }

        filtered_iterator& operator++() {
            advance();
            return *this;
        }

        // Although this iterator self-terminates, we need a placeholder to compare against
        // to make for-each loops happy
        bool operator!=(const sentinal& other) const {
            return mCurrent != mEnd;
        }

        ItemContainer<T>& operator*() {
            return *reinterpret_cast<ItemContainer<T>*>(mCurrent);
        }
    private:
        void advance() {
            ItemHeader* header = reinterpret_cast<ItemHeader*>(mCurrent);
            do {
                mCurrent += header->size;
                header = reinterpret_cast<ItemHeader*>(mCurrent);
            } while (mCurrent != mEnd && header->type != T);
        }
        uint8_t* mCurrent;
        const uint8_t* const mEnd;
    };

    template <ItemTypes T>
    class filtered_view {
    public:
        explicit filtered_view(uint8_t* start, const uint8_t* end) : mStart(start), mEnd(end) {}

        filtered_iterator<T> begin() const {
            return filtered_iterator<T>{mStart, mEnd};
        }

        sentinal end() const {
            return sentinal{mEnd};
        }
    private:
        uint8_t* mStart;
        const uint8_t* const mEnd;
    };

    template <ItemTypes T>
    filtered_view<T> filter() const {
        return filtered_view<T>{start_ptr(), end_ptr()};
    }

private:

    uint8_t* start_ptr() const {
        return reinterpret_cast<uint8_t*>(mBuffer) + mBuffer->startOffset;
    }

    const uint8_t* end_ptr() const {
        return reinterpret_cast<uint8_t*>(mBuffer) + mBuffer->used;
    }

    template <typename F, std::size_t... I>
    void do_for_each(F&& f, std::index_sequence<I...>) const {
        // Validate we're not empty
        if (isEmpty()) return;

        // Setup the jump table, mapping from each type to a springboard that invokes the template
        // function with the appropriate concrete type
        using F_PTR = decltype(&f);
        using THUNK = void (*)(F_PTR, void*);
        static constexpr auto jump = std::array<THUNK, sizeof...(I)>{[](F_PTR fp, void* t) {
            (*fp)(reinterpret_cast<const ItemContainer<static_cast<ItemTypes>(I)>*>(t));
        }...};

        // Do the actual iteration of each item
        uint8_t* current = start_ptr();
        const uint8_t* end = end_ptr();
        while (current != end) {
            auto header = reinterpret_cast<ItemHeader*>(current);
            // `f` could be a destructor, so ensure all accesses to the OP happen prior to invoking
            // `f`
            auto it = (void*)current;
            current += header->size;
            jump[static_cast<int>(header->type)](&f, it);
        }
    }

    void destroy() {
        clear();
        resize(0);
    }

    bool offsetIsValid(size_t offset) const {
        return offset >= mBuffer->startOffset && offset < mBuffer->used;
    }

    ItemHeader* itemAt(size_t offset) const {
        if (!offsetIsValid(offset)) return nullptr;
        return reinterpret_cast<ItemHeader*>(reinterpret_cast<uint8_t*>(mBuffer) + offset);
    }

    bool isEmpty() const { return mBuffer == nullptr || mBuffer->used == STARTING_SIZE; }

    BufferHeader* mBuffer = nullptr;
};

template <typename ItemTypes, template <ItemTypes> typename ItemContainer, typename BufferHeader,
        typename ItemTypeSequence>
void OpBuffer<ItemTypes, ItemContainer, BufferHeader, ItemTypeSequence>::clear() {

    // Don't need to do anything if we don't have a buffer
    if (!mBuffer) return;

    for_each([](auto op) {
        using T = std::remove_reference_t<decltype(*op)>;
        op->~T();
    });
    mBuffer->used = STARTING_SIZE;
    mBuffer->startOffset = STARTING_SIZE;
    mBuffer->endOffset = 0;
}

}  // namespace android::uirenderer