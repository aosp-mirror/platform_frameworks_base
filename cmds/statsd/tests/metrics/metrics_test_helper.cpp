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

#include "metrics_test_helper.h"

namespace android {
namespace os {
namespace statsd {

HashableDimensionKey getMockedDimensionKey(int tagId, int key, string value) {
    HashableDimensionKey dimension;
    int pos[] = {key, 0, 0};
    dimension.addValue(FieldValue(Field(tagId, pos, 0), Value(value)));

    return dimension;
}

MetricDimensionKey getMockedMetricDimensionKey(int tagId, int key, string value) {
    return MetricDimensionKey(getMockedDimensionKey(tagId, key, value), DEFAULT_DIMENSION_KEY);
}

void buildSimpleAtomFieldMatcher(const int tagId, FieldMatcher* matcher) {
    matcher->set_field(tagId);
}

void buildSimpleAtomFieldMatcher(const int tagId, const int fieldNum, FieldMatcher* matcher) {
    matcher->set_field(tagId);
    matcher->add_child()->set_field(fieldNum);
}

}  // namespace statsd
}  // namespace os
}  // namespace android