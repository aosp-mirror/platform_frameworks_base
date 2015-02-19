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

#ifndef H_ATTRIBUTE_FINDER
#define H_ATTRIBUTE_FINDER

#include <stdint.h>
#include <utils/KeyedVector.h>

namespace android {

static inline uint32_t getPackage(uint32_t attr) {
    return attr >> 24;
}

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

    Iterator find(uint32_t attr);

private:
    void jumpToClosestAttribute(uint32_t packageId);
    void markCurrentPackageId(uint32_t packageId);

    bool mFirstTime;
    Iterator mBegin;
    Iterator mEnd;
    Iterator mCurrent;
    Iterator mLargest;
    uint32_t mLastPackageId;
    uint32_t mCurrentAttr;

    // Package Offsets (best-case, fast look-up).
    Iterator mFrameworkStart;
    Iterator mAppStart;

    // Worst case, we have shared-library resources.
    KeyedVector<uint32_t, Iterator> mPackageOffsets;
};

template <typename Derived, typename Iterator> inline
BackTrackingAttributeFinder<Derived, Iterator>::BackTrackingAttributeFinder(const Iterator& begin, const Iterator& end)
    : mFirstTime(true)
    , mBegin(begin)
    , mEnd(end)
    , mCurrent(begin)
    , mLargest(begin)
    , mLastPackageId(0)
    , mCurrentAttr(0)
    , mFrameworkStart(end)
    , mAppStart(end) {
}

template <typename Derived, typename Iterator>
void BackTrackingAttributeFinder<Derived, Iterator>::jumpToClosestAttribute(const uint32_t packageId) {
    switch (packageId) {
        case 0x01:
            mCurrent = mFrameworkStart;
            break;
        case 0x7f:
            mCurrent = mAppStart;
            break;
        default: {
            ssize_t idx = mPackageOffsets.indexOfKey(packageId);
            if (idx >= 0) {
                // We have seen this package ID before, so jump to the first
                // attribute with this package ID.
                mCurrent = mPackageOffsets[idx];
            } else {
                mCurrent = mEnd;
            }
            break;
        }
    }

    // We have never seen this package ID yet, so jump to the
    // latest/largest index we have processed so far.
    if (mCurrent == mEnd) {
        mCurrent = mLargest;
    }

    if (mCurrent != mEnd) {
        mCurrentAttr = static_cast<const Derived*>(this)->getAttribute(mCurrent);
    }
}

template <typename Derived, typename Iterator>
void BackTrackingAttributeFinder<Derived, Iterator>::markCurrentPackageId(const uint32_t packageId) {
    switch (packageId) {
        case 0x01:
            mFrameworkStart = mCurrent;
            break;
        case 0x7f:
            mAppStart = mCurrent;
            break;
        default:
            mPackageOffsets.add(packageId, mCurrent);
            break;
    }
}

template <typename Derived, typename Iterator>
Iterator BackTrackingAttributeFinder<Derived, Iterator>::find(uint32_t attr) {
    if (!(mBegin < mEnd)) {
        return mEnd;
    }

    if (mFirstTime) {
        // One-time initialization. We do this here instead of the constructor
        // because the derived class we access in getAttribute() may not be
        // fully constructed.
        mFirstTime = false;
        mCurrentAttr = static_cast<const Derived*>(this)->getAttribute(mBegin);
        mLastPackageId = getPackage(mCurrentAttr);
        markCurrentPackageId(mLastPackageId);
    }

    // Looking for the needle (attribute we're looking for)
    // in the haystack (the attributes we're searching through)
    const uint32_t needlePackageId = getPackage(attr);
    if (mLastPackageId != needlePackageId) {
        jumpToClosestAttribute(needlePackageId);
        mLastPackageId = needlePackageId;
    }

    // Walk through the xml attributes looking for the requested attribute.
    while (mCurrent != mEnd) {
        const uint32_t haystackPackageId = getPackage(mCurrentAttr);
        if (needlePackageId == haystackPackageId && attr < mCurrentAttr) {
            // The attribute we are looking was not found.
            break;
        }
        const uint32_t prevAttr = mCurrentAttr;

        // Move to the next attribute in the XML.
        ++mCurrent;
        if (mCurrent != mEnd) {
            mCurrentAttr = static_cast<const Derived*>(this)->getAttribute(mCurrent);
            const uint32_t newHaystackPackageId = getPackage(mCurrentAttr);
            if (haystackPackageId != newHaystackPackageId) {
                // We've moved to the next group of attributes
                // with a new package ID, so we should record
                // the offset of this new package ID.
                markCurrentPackageId(newHaystackPackageId);
            }
        }

        if (mCurrent > mLargest) {
            // We've moved past the latest attribute we've
            // seen.
            mLargest = mCurrent;
        }

        if (attr == prevAttr) {
            // We found the attribute we were looking for.
            return mCurrent - 1;
        }
    }
    return mEnd;
}

} // namespace android

#endif // H_ATTRIBUTE_FINDER
