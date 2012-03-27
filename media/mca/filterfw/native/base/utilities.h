/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_FILTERFW_BASE_UTILITIES_H
#define ANDROID_FILTERFW_BASE_UTILITIES_H

#include <set>
#include <utility>

namespace android {
namespace filterfw {

// Convenience Macro to make copy constructor and assignment operator private
// (thereby disallowing copying and assigning).
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
  TypeName(const TypeName&);               \
  void operator=(const TypeName&)

// A macro to disallow all the implicit constructors, namely the
// default constructor, copy constructor and operator= functions.
#define DISALLOW_IMPLICIT_CONSTRUCTORS(TypeName) \
  TypeName();                                    \
  DISALLOW_COPY_AND_ASSIGN(TypeName)

// STLDeleteContainerPointers()
//  For a range within a container of pointers, calls delete
//  (non-array version) on these pointers.
// NOTE: for these three functions, we could just implement a DeleteObject
// functor and then call for_each() on the range and functor, but this
// requires us to pull in all of algorithm.h, which seems expensive.
// For hash_[multi]set, it is important that this deletes behind the iterator
// because the hash_set may call the hash function on the iterator when it is
// advanced, which could result in the hash function trying to deference a
// stale pointer.
template <class ForwardIterator>
void STLDeleteContainerPointers(ForwardIterator begin,
                                ForwardIterator end) {
  while (begin != end) {
    ForwardIterator temp = begin;
    ++begin;
    delete *temp;
  }
}

// Given an STL container consisting of (key, value) pairs, STLDeleteValues
// deletes all the "value" components and clears the container.  Does nothing
// in the case it's given a NULL pointer.
template <class T>
void STLDeleteValues(T *v) {
  if (!v) return;
  for (typename T::iterator i = v->begin(); i != v->end(); ++i) {
    delete i->second;
  }
  v->clear();
}

// Perform a lookup in a map or hash_map.
// If the key is present a const pointer to the associated value is returned,
// otherwise a NULL pointer is returned.
template <class Collection>
const typename Collection::value_type::second_type*
FindOrNull(const Collection& collection,
           const typename Collection::value_type::first_type& key) {
  typename Collection::const_iterator it = collection.find(key);
  if (it == collection.end()) {
    return 0;
  }
  return &it->second;
}

// A simple class that gives checklist functionality: There are essemtially two
// operations defined on a CheckList:
//  - Adding a new (unchecked) item.
//  - Checking off an item.
// When checking off the last remaining item CheckItem() returns true.
template<typename T>
class CheckList {
  public:
    // Add a new unchecked item. Does nothing if item is already in checklist.
    void AddItem(const T& item);

    // Check off an item in the checklist. Returns true if all items have been
    // checked.
    bool CheckItem(const T& item);

    // Clear the checklist.
    void Clear() {
      items_.clear();
    }

  private:
    std::set<T> items_;
};

template<typename T>
void CheckList<T>::AddItem(const T& item) {
  if (!ContainsKey(items_, item))
    items_.insert(item);
}

template<typename T>
bool CheckList<T>::CheckItem(const T& item) {
  typename std::set<T>::iterator iter = items_.find(item);
  if (iter != items_.end())
    items_.erase(iter);
  return items_.empty();
}

// Perform a lookup in a map or hash_map whose values are pointers.
// If the key is present a const pointer to the associated value is returned,
// otherwise a NULL pointer is returned.
// This function does not distinguish between a missing key and a key mapped
// to a NULL value.
template <class Collection>
const typename Collection::value_type::second_type
FindPtrOrNull(const Collection& collection,
              const typename Collection::value_type::first_type& key) {
  typename Collection::const_iterator it = collection.find(key);
  if (it == collection.end()) {
    return 0;
  }
  return it->second;
}

// Test to see if a set, map, hash_set or hash_map contains a particular key.
// Returns true if the key is in the collection.
template <typename Collection, typename Key>
bool ContainsKey(const Collection& collection, const Key& key) {
  return collection.find(key) != collection.end();
}

// Insert a new key and value into a map or hash_map.
// If the key is not present in the map the key and value are
// inserted, otherwise nothing happens. True indicates that an insert
// took place, false indicates the key was already present.
template <class Collection, class Key, class Value>
bool InsertIfNotPresent(Collection * const collection,
                        const Key& key, const Value& value) {
  std::pair<typename Collection::iterator, bool> ret =
    collection->insert(typename Collection::value_type(key, value));
  return ret.second;
}

} // namespace filterfw
} // namespace android

#endif // ANDROID_FILTERFW_BASE_UTILITIES_H
