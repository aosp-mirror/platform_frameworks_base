// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "dimension.h"

#include <gtest/gtest.h>

using namespace android::os::statsd;

#ifdef __ANDROID__

TEST(DimensionTest, subLeafNodes) {
    DimensionsValue dimension;
    int tagId = 100;
    dimension.set_field(tagId);
    auto child = dimension.mutable_value_tuple()->add_dimensions_value();
    child->set_field(1);
    child->set_value_int(2000);

    child = dimension.mutable_value_tuple()->add_dimensions_value();
    child->set_field(3);
    child->set_value_str("test");

    child = dimension.mutable_value_tuple()->add_dimensions_value();
    child->set_field(4);
    auto grandChild = child->mutable_value_tuple()->add_dimensions_value();
    grandChild->set_field(1);
    grandChild->set_value_float(1.3f);
    grandChild = child->mutable_value_tuple()->add_dimensions_value();
    grandChild->set_field(3);
    grandChild->set_value_str("tag");

    child = dimension.mutable_value_tuple()->add_dimensions_value();
    child->set_field(6);
    child->set_value_bool(false);

    DimensionsValue sub_dimension;
    FieldMatcher matcher;

    // Tag id not matched.
    matcher.set_field(tagId + 1);
    EXPECT_FALSE(getSubDimension(dimension, matcher, &sub_dimension));

    // Field not exist.
    matcher.Clear();
    matcher.set_field(tagId);
    matcher.add_child()->set_field(5);
    EXPECT_FALSE(getSubDimension(dimension, matcher, &sub_dimension));

    // Field exists.
    matcher.Clear();
    matcher.set_field(tagId);
    matcher.add_child()->set_field(3);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Field exists.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    matcher.add_child()->set_field(6);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Field exists.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    matcher.add_child()->set_field(1);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Not leaf field.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    matcher.add_child()->set_field(4);
    EXPECT_FALSE(getSubDimension(dimension, matcher, &sub_dimension));

    // Grand-child leaf field not exist.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    auto childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(2);
    EXPECT_FALSE(getSubDimension(dimension, matcher, &sub_dimension));

    // Grand-child leaf field.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(1);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(3);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Multiple grand-child fields.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(3);
    childMatcher->add_child()->set_field(1);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Multiple fields.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(3);
    childMatcher->add_child()->set_field(1);
    matcher.add_child()->set_field(3);
    EXPECT_TRUE(getSubDimension(dimension, matcher, &sub_dimension));

    // Subset of the fields not exist.
    matcher.Clear();
    sub_dimension.Clear();
    matcher.set_field(tagId);
    childMatcher = matcher.add_child();
    childMatcher->set_field(4);
    childMatcher->add_child()->set_field(3);
    childMatcher->add_child()->set_field(1);
    matcher.add_child()->set_field(2);
    EXPECT_FALSE(getSubDimension(dimension, matcher, &sub_dimension));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
