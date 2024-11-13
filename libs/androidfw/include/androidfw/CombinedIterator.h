/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <compare>
#include <iterator>
#include <utility>

namespace android {

namespace detail {
// A few useful aliases to not repeat them everywhere
template <class It1, class It2>
using Value = std::pair<typename std::iterator_traits<It1>::value_type,
                        typename std::iterator_traits<It2>::value_type>;

template <class It1, class It2>
using BaseRefPair = std::pair<typename std::iterator_traits<It1>::reference,
                              typename std::iterator_traits<It2>::reference>;

template <class It1, class It2>
struct RefPair : BaseRefPair<It1, It2> {
  using Base = BaseRefPair<It1, It2>;
  using Value = detail::Value<It1, It2>;

  RefPair(It1 it1, It2 it2) : Base(*it1, *it2) {
  }

  RefPair& operator=(const Value& v) {
    this->first = v.first;
    this->second = v.second;
    return *this;
  }
  operator Value() const {
    return Value(this->first, this->second);
  }
  bool operator==(const RefPair& other) {
    return this->first == other.first;
  }
  bool operator==(const Value& other) {
    return this->first == other.first;
  }
  std::strong_ordering operator<=>(const RefPair& other) const {
    return this->first <=> other.first;
  }
  std::strong_ordering operator<=>(const Value& other) const {
    return this->first <=> other.first;
  }
  friend void swap(RefPair& l, RefPair& r) {
    using std::swap;
    swap(l.first, r.first);
    swap(l.second, r.second);
  }
};

template <class It1, class It2>
struct RefPairPtr {
  RefPair<It1, It2> value;

  RefPair<It1, It2>* operator->() const {
    return &value;
  }
};
}  // namespace detail

//
//   CombinedIterator - a class to combine two iterators to process them as a single iterator to a
// pair of values. Useful for processing a data structure of "struct of arrays", replacing
// array of structs for cache locality.
//
// The value type is a pair of copies of the values of each iterator, and the reference is a
// pair of references to the corresponding values. Comparison only compares the first element,
// making it most useful for using on data like (vector<Key>, vector<Value>) for binary searching,
// sorting both together and so on.
//
// The class is designed for handling arrays, so it requires random access iterators as an input.
//

template <class It1, class It2>
requires std::random_access_iterator<It1> && std::random_access_iterator<It2>
struct CombinedIterator {
  typedef detail::Value<It1, It2> value_type;
  typedef detail::RefPair<It1, It2> reference;
  typedef std::ptrdiff_t difference_type;
  typedef detail::RefPairPtr<It1, It2> pointer;
  typedef std::random_access_iterator_tag iterator_category;

  CombinedIterator(It1 it1 = {}, It2 it2 = {}) : it1(it1), it2(it2) {
  }

  bool operator<(const CombinedIterator& other) const {
    return it1 < other.it1;
  }
  bool operator<=(const CombinedIterator& other) const {
    return it1 <= other.it1;
  }
  bool operator>(const CombinedIterator& other) const {
    return it1 > other.it1;
  }
  bool operator>=(const CombinedIterator& other) const {
    return it1 >= other.it1;
  }
  bool operator==(const CombinedIterator& other) const {
    return it1 == other.it1;
  }
  pointer operator->() const {
    return pointer{{it1, it2}};
  }
  reference operator*() const {
    return {it1, it2};
  }
  reference operator[](difference_type n) const {
    return {it1 + n, it2 + n};
  }

  CombinedIterator& operator++() {
    ++it1;
    ++it2;
    return *this;
  }
  CombinedIterator operator++(int) {
    const auto res = *this;
    ++*this;
    return res;
  }
  CombinedIterator& operator--() {
    --it1;
    --it2;
    return *this;
  }
  CombinedIterator operator--(int) {
    const auto res = *this;
    --*this;
    return res;
  }
  CombinedIterator& operator+=(difference_type n) {
    it1 += n;
    it2 += n;
    return *this;
  }
  CombinedIterator operator+(difference_type n) const {
    CombinedIterator res = *this;
    return res += n;
  }

  CombinedIterator& operator-=(difference_type n) {
    it1 -= n;
    it2 -= n;
    return *this;
  }
  CombinedIterator operator-(difference_type n) const {
    CombinedIterator res = *this;
    return res -= n;
  }
  difference_type operator-(const CombinedIterator& other) {
    return it1 - other.it1;
  }

  It1 it1;
  It2 it2;
};

}  // namespace android
