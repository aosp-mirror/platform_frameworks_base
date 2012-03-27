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

#include "core/statistics.h"

#include <math.h>

namespace android {
namespace filterfw {

IncrementalGaussian::IncrementalGaussian()
    : n_(0),
      sum_x_(0.0f),
      sum_x2_(0.0f),
      mean_(0.0f),
      var_(0.0f),
      exp_denom_(0.0f),
      pdf_denom_(0.0f) {
}

void IncrementalGaussian::Add(float value) {
  ++n_;
  sum_x_ += value;
  sum_x2_ += value * value;

  mean_ = sum_x_ / n_;
  var_ = sum_x2_ / n_ - mean_ * mean_;

  exp_denom_ = 2.0f * var_;
  pdf_denom_ = sqrtf(M_PI * exp_denom_);
}

float IncrementalGaussian::Std() const {
  return sqrtf(var_);
}

float IncrementalGaussian::Pdf(float value) const {
  if (var_ == 0.0f) { return n_ > 0 ? value == mean_ : 0.0f; }
  const float diff = value - mean_;
  return expf(-diff * diff / exp_denom_) / pdf_denom_;
}

} // namespace filterfw
} // namespace android
