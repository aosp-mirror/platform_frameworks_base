/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <utils/RefBase.h>
#include <unordered_map>
#include <vector>

using namespace android;

namespace android {
namespace os {
namespace statsd {

/** Defines a hash function for sp<const AA>, returning the hash of the underlying pointer. */
template <class AA>
struct SpHash {
    size_t operator()(const sp<const AA>& k) const {
        return std::hash<const AA*>()(k.get());
    }
};

/**
 * Min priority queue for generic type AA.
 * Unlike a regular priority queue, this class is also capable of removing interior elements.
 * @tparam Comparator must implement [bool operator()(sp<const AA> a, sp<const AA> b)], returning
 *    whether a should be closer to the top of the queue than b.
 */
template <class AA, class Comparator>
class indexed_priority_queue {
public:
    indexed_priority_queue();
    /** Adds a into the priority queue. If already present or a==nullptr, does nothing. */
    void push(sp<const AA> a);
    /*
     * Removes a from the priority queue. If not present or a==nullptr, does nothing.
     * Returns true if a had been present (and is now removed), else false.
     */
    bool remove(sp<const AA> a);
    /** Removes the top element, if there is one. */
    void pop();
    /** Removes all elements. */
    void clear();
    /** Returns whether priority queue contains a (not just a copy of a, but a itself). */
    bool contains(sp<const AA> a) const;
    /** Returns min element. Returns nullptr iff empty(). */
    sp<const AA> top() const;
    /** Returns number of elements in priority queue. */
    size_t size() const {
        return pq.size() - 1;
    }  // pq is 1-indexed
    /** Returns true iff priority queue is empty. */
    bool empty() const {
        return size() < 1;
    }

private:
    /** Vector representing a min-heap (1-indexed, with nullptr at 0). */
    std::vector<sp<const AA>> pq;
    /** Mapping of each element in pq to its index in pq (i.e. the inverse of a=pq[i]). */
    std::unordered_map<sp<const AA>, size_t, SpHash<AA>> indices;

    void init();
    void sift_up(size_t idx);
    void sift_down(size_t idx);
    /** Returns whether pq[idx1] is considered higher than pq[idx2], according to Comparator. */
    bool higher(size_t idx1, size_t idx2) const;
    void swap_indices(size_t i, size_t j);
};

// Implementation must be done in this file due to use of template.

template <class AA, class Comparator>
indexed_priority_queue<AA, Comparator>::indexed_priority_queue() {
    init();
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::push(sp<const AA> a) {
    if (a == nullptr) return;
    if (contains(a)) return;
    pq.push_back(a);
    size_t idx = size();  // index of last element since 1-indexed
    indices.insert({a, idx});
    sift_up(idx);  // get the pq back in order
}

template <class AA, class Comparator>
bool indexed_priority_queue<AA, Comparator>::remove(sp<const AA> a) {
    if (a == nullptr) return false;
    if (!contains(a)) return false;
    size_t idx = indices[a];
    if (idx >= pq.size()) {
        return false;
    }
    if (idx == size()) {  // if a is the last element, i.e. at index idx == size() == (pq.size()-1)
        pq.pop_back();
        indices.erase(a);
        return true;
    }
    // move last element (guaranteed not to be at idx) to idx, then delete a
    sp<const AA> last_a = pq.back();
    pq[idx] = last_a;
    pq.pop_back();
    indices[last_a] = idx;
    indices.erase(a);

    // get the heap back in order (since the element at idx is not in order)
    sift_up(idx);
    sift_down(idx);

    return true;
}

// The same as, but slightly more efficient than, remove(top()).
template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::pop() {
    sp<const AA> a = top();
    if (a == nullptr) return;
    const size_t idx = 1;
    if (idx == size()) {  // if a is the last element
        pq.pop_back();
        indices.erase(a);
        return;
    }
    // move last element (guaranteed not to be at idx) to idx, then delete a
    sp<const AA> last_a = pq.back();
    pq[idx] = last_a;
    pq.pop_back();
    indices[last_a] = idx;
    indices.erase(a);

    // get the heap back in order (since the element at idx is not in order)
    sift_down(idx);
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::clear() {
    pq.clear();
    indices.clear();
    init();
}

template <class AA, class Comparator>
sp<const AA> indexed_priority_queue<AA, Comparator>::top() const {
    if (empty()) return nullptr;
    return pq[1];
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::init() {
    pq.push_back(nullptr);         // so that pq is 1-indexed.
    indices.insert({nullptr, 0});  // just to be consistent with pq.
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::sift_up(size_t idx) {
    while (idx > 1) {
        size_t parent = idx / 2;
        if (higher(idx, parent))
            swap_indices(idx, parent);
        else
            break;
        idx = parent;
    }
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::sift_down(size_t idx) {
    while (2 * idx <= size()) {
        size_t child = 2 * idx;
        if (child < size() && higher(child + 1, child)) child++;
        if (higher(child, idx))
            swap_indices(child, idx);
        else
            break;
        idx = child;
    }
}

template <class AA, class Comparator>
bool indexed_priority_queue<AA, Comparator>::higher(size_t idx1, size_t idx2) const {
    if (!(0u < idx1 && idx1 < pq.size() && 0u < idx2 && idx2 < pq.size())) {
        return false;  // got to do something.
    }
    return Comparator()(pq[idx1], pq[idx2]);
}

template <class AA, class Comparator>
bool indexed_priority_queue<AA, Comparator>::contains(sp<const AA> a) const {
    if (a == nullptr) return false;  // publicly, we pretend that nullptr is not actually in pq.
    return indices.count(a) > 0;
}

template <class AA, class Comparator>
void indexed_priority_queue<AA, Comparator>::swap_indices(size_t i, size_t j) {
    if (!(0u < i && i < pq.size() && 0u < j && j < pq.size())) {
        return;
    }
    sp<const AA> val_i = pq[i];
    sp<const AA> val_j = pq[j];
    pq[i] = val_j;
    pq[j] = val_i;
    indices[val_i] = j;
    indices[val_j] = i;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
