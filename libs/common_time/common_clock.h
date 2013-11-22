/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef __COMMON_CLOCK_H__
#define __COMMON_CLOCK_H__

#include <stdint.h>

#include <utils/Errors.h>
#include <utils/LinearTransform.h>
#include <utils/threads.h>

namespace android {

class CommonClock {
  public:
    CommonClock();

    bool      init(uint64_t local_freq);

    status_t  localToCommon(int64_t local, int64_t *common_out) const;
    status_t  commonToLocal(int64_t common, int64_t *local_out) const;
    int64_t   localDurationToCommonDuration(int64_t localDur) const;
    uint64_t  getCommonFreq() const { return kCommonFreq; }
    bool      isValid() const { return cur_trans_valid_; }
    status_t  setSlew(int64_t change_time, int32_t ppm);
    void      setBasis(int64_t local, int64_t common);
    void      resetBasis();
  private:
    mutable Mutex lock_;

    int32_t  cur_slew_;
    uint32_t local_to_common_freq_numer_;
    uint32_t local_to_common_freq_denom_;

    LinearTransform duration_trans_;
    LinearTransform cur_trans_;
    bool cur_trans_valid_;

    static const uint64_t kCommonFreq = 1000000ull;
};

}  // namespace android
#endif  // __COMMON_CLOCK_H__
