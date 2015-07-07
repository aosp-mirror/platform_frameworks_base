/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef ANDROIDFW_ATTRIBUTE_FINDER_H
#define ANDROIDFW_ATTRIBUTE_FINDER_H

#include <utils/KeyedVector.h>

#include <stdint.h>

namespace android {

static inline uint32_t get_package(uint32_t attr) { return attr >> 24; }

/**
 * A helper class to search linearly for the requested
 * attribute, maintaining it's position and optimizing for
 * the case that subsequent searches will involve an attribute with
 * a higher attribute ID.
 *
 * In the case that a subsequent attribute has a different package ID,
 * its resource ID may not be larger than the preceding search, so
 * back tracking is supported for this case. This
 * back tracking requirement is mainly for shared library
 * resources, whose package IDs get assigned at runtime
 * and thus attributes from a shared library may
 * be out of order.
 *
 * We make two assumptions about the order of attributes:
 * 1) The input has the same sorting rules applied to it as
 *    the attribute data contained by this class.
 * 2) Attributes are grouped by package ID.
 * 3) Among attributes with the same package ID, the attributes are
 *    sorted by increasing resource ID.
 *
 * Ex: 02010000, 02010001, 010100f4, 010100f5, 0x7f010001, 07f010003
 *
 * The total order of attributes (including package ID) can not be linear
 * as shared libraries get assigned dynamic package IDs at runtime, which
 * may break the sort order established at build time.
 */
template <typename Derived, typename Iterator>
class BackTrackingAttributeFinder {
 public:
  BackTrackingAttributeFinder(const Iterator& begin, const Iterator& end);

  Iterator Find(uint32_t attr);
  inline Iterator end();

 private:
  void JumpToClosestAttribute(uint32_t package_id);
  void MarkCurrentPackageId(uint32_t package_id);

  bool first_time_;
  Iterator begin_;
  Iterator end_;
  Iterator current_;
  Iterator largest_;
  uint32_t last_package_id_;
  uint32_t current_attr_;

  // Package offsets (best-case, fast look-up).
  Iterator framework_start_;
  Iterator lineage_framework_start_;
  Iterator app_start_;

  // Worst case, we have shared-library resources.
  KeyedVector<uint32_t, Iterator> package_offsets_;
};

template <typename Derived, typename Iterator>
inline BackTrackingAttributeFinder<Derived, Iterator>::BackTrackingAttributeFinder(
    const Iterator& begin, const Iterator& end)
    : first_time_(true),
      begin_(begin),
      end_(end),
      current_(begin),
      largest_(begin),
      last_package_id_(0),
      current_attr_(0),
      framework_start_(end),
      app_start_(end) {}

template <typename Derived, typename Iterator>
void BackTrackingAttributeFinder<Derived, Iterator>::JumpToClosestAttribute(
    const uint32_t package_id) {
  switch (package_id) {
    case 0x01u:
      current_ = framework_start_;
      break;
    case 0x3fu:
      current_ = lineage_framework_start_;
      break;
    case 0x7fu:
      current_ = app_start_;
      break;
    default: {
      ssize_t idx = package_offsets_.indexOfKey(package_id);
      if (idx >= 0) {
        // We have seen this package ID before, so jump to the first
        // attribute with this package ID.
        current_ = package_offsets_[idx];
      } else {
        current_ = end_;
      }
      break;
    }
  }

  // We have never seen this package ID yet, so jump to the
  // latest/largest index we have processed so far.
  if (current_ == end_) {
    current_ = largest_;
  }

  if (current_ != end_) {
    current_attr_ = static_cast<const Derived*>(this)->GetAttribute(current_);
  }
}

template <typename Derived, typename Iterator>
void BackTrackingAttributeFinder<Derived, Iterator>::MarkCurrentPackageId(
    const uint32_t package_id) {
  switch (package_id) {
    case 0x01u:
      framework_start_ = current_;
      break;
    case 0x3fu:
      lineage_framework_start_ = current_;
      break;
    case 0x7fu:
      app_start_ = current_;
      break;
    default:
      package_offsets_.add(package_id, current_);
      break;
  }
}

template <typename Derived, typename Iterator>
Iterator BackTrackingAttributeFinder<Derived, Iterator>::Find(uint32_t attr) {
  if (!(begin_ < end_)) {
    return end_;
  }

  if (first_time_) {
    // One-time initialization. We do this here instead of the constructor
    // because the derived class we access in getAttribute() may not be
    // fully constructed.
    first_time_ = false;
    current_attr_ = static_cast<const Derived*>(this)->GetAttribute(begin_);
    last_package_id_ = get_package(current_attr_);
    MarkCurrentPackageId(last_package_id_);
  }

  // Looking for the needle (attribute we're looking for)
  // in the haystack (the attributes we're searching through)
  const uint32_t needle_package_id = get_package(attr);
  if (last_package_id_ != needle_package_id) {
    JumpToClosestAttribute(needle_package_id);
    last_package_id_ = needle_package_id;
  }

  // Walk through the xml attributes looking for the requested attribute.
  while (current_ != end_) {
    const uint32_t haystack_package_id = get_package(current_attr_);
    if (needle_package_id == haystack_package_id && attr < current_attr_) {
      // The attribute we are looking was not found.
      break;
    }
    const uint32_t prev_attr = current_attr_;

    // Move to the next attribute in the XML.
    ++current_;
    if (current_ != end_) {
      current_attr_ = static_cast<const Derived*>(this)->GetAttribute(current_);
      const uint32_t new_haystack_package_id = get_package(current_attr_);
      if (haystack_package_id != new_haystack_package_id) {
        // We've moved to the next group of attributes
        // with a new package ID, so we should record
        // the offset of this new package ID.
        MarkCurrentPackageId(new_haystack_package_id);
      }
    }

    if (current_ > largest_) {
      // We've moved past the latest attribute we've seen.
      largest_ = current_;
    }

    if (attr == prev_attr) {
      // We found the attribute we were looking for.
      return current_ - 1;
    }
  }
  return end_;
}

template <typename Derived, typename Iterator>
Iterator BackTrackingAttributeFinder<Derived, Iterator>::end() {
  return end_;
}

}  // namespace android

#endif  // ANDROIDFW_ATTRIBUTE_FINDER_H
