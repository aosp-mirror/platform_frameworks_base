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

#define LOG_TAG "aah_timesrv"
#include <utils/Log.h>

#include <assert.h>
#include <stdint.h>

#include <aah_timesrv/local_clock.h>
#include <hardware/hardware.h>
#include <hardware/local_time_hal.h>
#include <utils/Errors.h>

namespace android {

LocalClock::LocalClock() {
    int res;
    const hw_module_t* mod;

    res = hw_get_module_by_class(LOCAL_TIME_HARDWARE_MODULE_ID, NULL, &mod);
    if (res) {
        LOGE("Failed to open local time HAL module (res = %d)", res);
    } else {
        res = local_time_hw_device_open(mod, &dev_);
        if (res) {
            LOGE("Failed to open local time HAL device (res = %d)", res);
            dev_ = NULL;
        }
    }
}

LocalClock::~LocalClock() {
    if (NULL != dev_)
        local_time_hw_device_close(dev_);
}

bool LocalClock::initCheck() {
    return (NULL != dev_);
}

int64_t LocalClock::getLocalTime() {
    assert(NULL != dev_);
    assert(NULL != dev_->get_local_time);

    return dev_->get_local_time(dev_);
}

uint64_t LocalClock::getLocalFreq() {
    assert(NULL != dev_);
    assert(NULL != dev_->get_local_freq);

    return dev_->get_local_freq(dev_);
}

status_t LocalClock::setLocalSlew(int16_t rate) {
    assert(NULL != dev_);

    if (!dev_->set_local_slew)
        return INVALID_OPERATION;

    return static_cast<status_t>(dev_->set_local_slew(dev_, rate));
}

int32_t LocalClock::getDebugLog(struct local_time_debug_event* records,
                                int max_records) {
    assert(NULL != dev_);

    if (!dev_->get_debug_log)
        return INVALID_OPERATION;

    return dev_->get_debug_log(dev_, records, max_records);
}

}  // namespace android
