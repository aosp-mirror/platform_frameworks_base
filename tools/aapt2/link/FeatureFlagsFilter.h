/*
 * Copyright 2023 The Android Open Source Project
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

#include <optional>
#include <string>
#include <unordered_map>
#include <utility>

#include "android-base/macros.h"
#include "cmd/Util.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

struct FeatureFlagsFilterOptions {
  // If true, elements whose featureFlag values are false (i.e., disabled feature) will be removed.
  bool remove_disabled_elements = true;

  // If true, `Consume()` will return false (error) if a flag was found that is not in
  // `feature_flag_values`.
  bool fail_on_unrecognized_flags = true;

  // If true, `Consume()` will return false (error) if a flag was found whose value in
  // `feature_flag_values` is not defined (std::nullopt).
  bool flags_must_have_value = true;
};

// Looks for the `android:featureFlag` attribute in each XML element, validates the flag names and
// values, and removes elements according to the values in `feature_flag_values`. An element will be
// removed if the flag's given value is FALSE. A "!" before the flag name in the attribute indicates
// a boolean NOT operation, i.e., an element will be removed if the flag's given value is TRUE. For
// example, if the XML is the following:
//
//   <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="android">
//     <permission android:name="FOO" android:featureFlag="!flag"
//                 android:protectionLevel="normal" />
//     <permission android:name="FOO" android:featureFlag="flag"
//                 android:protectionLevel="dangerous" />
//   </manifest>
//
// If `feature_flag_values` contains {"flag", true}, then the <permission> element with
// protectionLevel="normal" will be removed, and the <permission> element with
// protectionLevel="normal" will be kept.
//
// The `Consume()` function will return false if there is an invalid flag found (see
// FeatureFlagsFilterOptions for customizing the filter's validation behavior). Do not use the XML
// further if there are errors as there may be elements removed already.
class FeatureFlagsFilter : public IXmlResourceConsumer {
 public:
  explicit FeatureFlagsFilter(FeatureFlagValues feature_flag_values,
                              FeatureFlagsFilterOptions options)
      : feature_flag_values_(std::move(feature_flag_values)), options_(options) {
  }

  bool Consume(IAaptContext* context, xml::XmlResource* doc) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(FeatureFlagsFilter);

  const FeatureFlagValues feature_flag_values_;
  const FeatureFlagsFilterOptions options_;
};

}  // namespace aapt
