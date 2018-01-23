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
    DimensionsValue dimensionsValue;
    dimensionsValue.set_field(tagId);
    dimensionsValue.mutable_value_tuple()->add_dimensions_value()->set_field(key);
    dimensionsValue.mutable_value_tuple()->mutable_dimensions_value(0)->set_value_str(value);
    return HashableDimensionKey(dimensionsValue);
}

MetricDimensionKey getMockedMetricDimensionKey(int tagId, int key, string value) {
    DimensionsValue dimensionsValue;
    dimensionsValue.set_field(tagId);
    dimensionsValue.mutable_value_tuple()->add_dimensions_value()->set_field(key);
    dimensionsValue.mutable_value_tuple()->mutable_dimensions_value(0)->set_value_str(value);
    return MetricDimensionKey(HashableDimensionKey(dimensionsValue), DEFAULT_DIMENSION_KEY);
}
}  // namespace statsd
}  // namespace os
}  // namespace android