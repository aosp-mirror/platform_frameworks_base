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

#ifndef ANDROID_FILTERFW_CORE_STATISTICS_H
#define ANDROID_FILTERFW_CORE_STATISTICS_H

namespace android {
namespace filterfw {

// An incrementally-constructed Normal distribution.
class IncrementalGaussian {
 public:
  IncrementalGaussian();

  void Add(float value);

  float NumSamples() const { return n_; }
  float Mean() const { return mean_; }
  float Var() const { return var_; }
  float Std() const;
  float Pdf(float value) const;

 private:
  int n_;
  float sum_x_;
  float sum_x2_;
  float mean_;
  float var_;
  float exp_denom_;
  float pdf_denom_;
};

// Discrete-time implementation of a simple RC low-pass filter:
// exponentially-weighted moving average.
class RCFilter {
 public:
  explicit RCFilter(float gain)
      : gain_(gain), n_(0), value_(0.0f) {}

  void Add(float measurement) {
    value_ = n_++ ? gain_ * measurement + (1.0f - gain_) * value_ : measurement;
  }

  void Reset() { n_ = 0; }

  int NumMeasurements() const { return n_; }
  float Output() const { return value_; }

 private:
  float gain_;
  int n_;
  float value_;
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_CORE_STATISTICS_H
