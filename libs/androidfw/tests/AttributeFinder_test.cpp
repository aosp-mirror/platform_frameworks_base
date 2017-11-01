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

#include "androidfw/AttributeFinder.h"

#include "android-base/macros.h"
#include "gtest/gtest.h"

namespace android {

class MockAttributeFinder
    : public BackTrackingAttributeFinder<MockAttributeFinder, int> {
 public:
  MockAttributeFinder(const uint32_t* attrs, int len)
      : BackTrackingAttributeFinder(0, len) {
    attrs_ = new uint32_t[len];
    memcpy(attrs_, attrs, sizeof(*attrs) * len);
  }

  ~MockAttributeFinder() { delete attrs_; }

  inline uint32_t GetAttribute(const int index) const { return attrs_[index]; }

 private:
  uint32_t* attrs_;
};

static const uint32_t kSortedAttributes[] = {0x01010000, 0x01010001, 0x01010002,
                                             0x01010004, 0x02010001, 0x02010010,
                                             0x7f010001};

static const uint32_t kPackageUnsortedAttributes[] = {
    0x02010001, 0x02010010, 0x01010000, 0x01010001,
    0x01010002, 0x01010004, 0x7f010001};

static const uint32_t kSinglePackageAttributes[] = {0x7f010007, 0x7f01000a,
                                                    0x7f01000d, 0x00000000};

TEST(AttributeFinderTest, IteratesSequentially) {
  const int end = arraysize(kSortedAttributes);
  MockAttributeFinder finder(kSortedAttributes, end);

  EXPECT_EQ(0, finder.Find(0x01010000));
  EXPECT_EQ(1, finder.Find(0x01010001));
  EXPECT_EQ(2, finder.Find(0x01010002));
  EXPECT_EQ(3, finder.Find(0x01010004));
  EXPECT_EQ(4, finder.Find(0x02010001));
  EXPECT_EQ(5, finder.Find(0x02010010));
  EXPECT_EQ(6, finder.Find(0x7f010001));
  EXPECT_EQ(end, finder.Find(0x7f010002));
}

TEST(AttributeFinderTest, PackagesAreOutOfOrder) {
  const int end = arraysize(kSortedAttributes);
  MockAttributeFinder finder(kSortedAttributes, end);

  EXPECT_EQ(6, finder.Find(0x7f010001));
  EXPECT_EQ(end, finder.Find(0x7f010002));
  EXPECT_EQ(4, finder.Find(0x02010001));
  EXPECT_EQ(5, finder.Find(0x02010010));
  EXPECT_EQ(0, finder.Find(0x01010000));
  EXPECT_EQ(1, finder.Find(0x01010001));
  EXPECT_EQ(2, finder.Find(0x01010002));
  EXPECT_EQ(3, finder.Find(0x01010004));
}

TEST(AttributeFinderTest, SomeAttributesAreNotFound) {
  const int end = arraysize(kSortedAttributes);
  MockAttributeFinder finder(kSortedAttributes, end);

  EXPECT_EQ(0, finder.Find(0x01010000));
  EXPECT_EQ(1, finder.Find(0x01010001));
  EXPECT_EQ(2, finder.Find(0x01010002));
  EXPECT_EQ(end, finder.Find(0x01010003));
  EXPECT_EQ(3, finder.Find(0x01010004));
  EXPECT_EQ(end, finder.Find(0x01010005));
  EXPECT_EQ(end, finder.Find(0x01010006));
  EXPECT_EQ(4, finder.Find(0x02010001));
  EXPECT_EQ(end, finder.Find(0x02010002));
}

TEST(AttributeFinderTest, FindAttributesInPackageUnsortedAttributeList) {
  const int end = arraysize(kPackageUnsortedAttributes);
  MockAttributeFinder finder(kPackageUnsortedAttributes, end);

  EXPECT_EQ(2, finder.Find(0x01010000));
  EXPECT_EQ(3, finder.Find(0x01010001));
  EXPECT_EQ(4, finder.Find(0x01010002));
  EXPECT_EQ(end, finder.Find(0x01010003));
  EXPECT_EQ(5, finder.Find(0x01010004));
  EXPECT_EQ(end, finder.Find(0x01010005));
  EXPECT_EQ(end, finder.Find(0x01010006));
  EXPECT_EQ(0, finder.Find(0x02010001));
  EXPECT_EQ(end, finder.Find(0x02010002));
  EXPECT_EQ(1, finder.Find(0x02010010));
  EXPECT_EQ(6, finder.Find(0x7f010001));
}

TEST(AttributeFinderTest, FindAttributesInSinglePackageAttributeList) {
  const int end = arraysize(kSinglePackageAttributes);
  MockAttributeFinder finder(kSinglePackageAttributes, end);

  EXPECT_EQ(end, finder.Find(0x010100f4));
  EXPECT_EQ(end, finder.Find(0x010100f5));
  EXPECT_EQ(end, finder.Find(0x010100f6));
  EXPECT_EQ(end, finder.Find(0x010100f7));
  EXPECT_EQ(end, finder.Find(0x010100f8));
  EXPECT_EQ(end, finder.Find(0x010100fa));
  EXPECT_EQ(0, finder.Find(0x7f010007));
}

}  // namespace android
