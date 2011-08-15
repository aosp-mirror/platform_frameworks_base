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

#define __STDC_LIMIT_MACROS

#define LOG_TAG "aah_timesrv"
#include <utils/Log.h>

#include <stdint.h>

#include <utils/Errors.h>
#include <utils/LinearTransform.h>

#include "common_clock.h"

namespace android {

CommonClock::CommonClock() {
    cur_slew_        = 0;
    cur_trans_valid_ = false;

    cur_trans_.a_zero = 0;
    cur_trans_.b_zero = 0;
    cur_trans_.a_to_b_numer = local_to_common_freq_numer_ = 1;
    cur_trans_.a_to_b_denom = local_to_common_freq_denom_ = 1;
}

bool CommonClock::init(uint64_t local_freq) {
    Mutex::Autolock lock(&lock_);

    if (!local_freq)
        return false;

    uint64_t numer = kCommonFreq;
    uint64_t denom = local_freq;

    LinearTransform::reduce(&numer, &denom);
    if ((numer > UINT32_MAX) || (denom > UINT32_MAX)) {
        LOGE("Overflow in CommonClock::init while trying to reduce %lld/%lld",
             kCommonFreq, local_freq);
        return false;
    }

    cur_trans_.a_to_b_numer = local_to_common_freq_numer_ =
        static_cast<uint32_t>(numer);
    cur_trans_.a_to_b_denom = local_to_common_freq_denom_ =
        static_cast<uint32_t>(denom);

    return true;
}

status_t CommonClock::localToCommon(int64_t local, int64_t *common_out) const {
    Mutex::Autolock lock(&lock_);

    if (!cur_trans_valid_)
        return INVALID_OPERATION;

    if (!cur_trans_.doForwardTransform(local, common_out))
        return INVALID_OPERATION;

    return OK;
}

status_t CommonClock::commonToLocal(int64_t common, int64_t *local_out) const {
    Mutex::Autolock lock(&lock_);

    if (!cur_trans_valid_)
        return INVALID_OPERATION;

    if (!cur_trans_.doReverseTransform(common, local_out))
        return INVALID_OPERATION;

    return OK;
}

void CommonClock::setBasis(int64_t local, int64_t common) {
    Mutex::Autolock lock(&lock_);

    cur_trans_.a_zero = local;
    cur_trans_.b_zero = common;
    cur_trans_valid_ = true;
}

void CommonClock::resetBasis() {
    Mutex::Autolock lock(&lock_);

    cur_trans_.a_zero = 0;
    cur_trans_.b_zero = 0;
    cur_trans_valid_ = false;
}

status_t CommonClock::setSlew(int64_t change_time, int32_t ppm) {
    Mutex::Autolock lock(&lock_);

    int64_t new_local_basis;
    int64_t new_common_basis;

    if (cur_trans_valid_) {
        new_local_basis = change_time;
        if (!cur_trans_.doForwardTransform(change_time, &new_common_basis)) {
            LOGE("Overflow when attempting to set slew rate to %d", ppm);
            return INVALID_OPERATION;
        }
    } else {
        new_local_basis = 0;
        new_common_basis = 0;
    }

    cur_slew_ = ppm;
    uint32_t n1 = local_to_common_freq_numer_;
    uint32_t n2 = 1000000 + cur_slew_;

    uint32_t d1 = local_to_common_freq_denom_;
    uint32_t d2 = 1000000;

    // n1/d1 has alredy been reduced, no need to do so here.
    LinearTransform::reduce(&n1, &d2);
    LinearTransform::reduce(&n2, &d1);
    LinearTransform::reduce(&n2, &d2);

    cur_trans_.a_zero = new_local_basis;
    cur_trans_.b_zero = new_common_basis;
    cur_trans_.a_to_b_numer = n1 * n2;
    cur_trans_.a_to_b_denom = d1 * d2;

    return OK;
}

}  // namespace android
