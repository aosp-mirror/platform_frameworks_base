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

/*
 * A service that exchanges time synchronization information between
 * a master that defines a timeline and clients that follow the timeline.
 */

#define __STDC_LIMIT_MACROS
#define LOG_TAG "aah_timesrv"
#include <utils/Log.h>
#include <stdint.h>

#include <aah_timesrv/local_clock.h>
#include <assert.h>

#include "clock_recovery.h"
#include "common_clock.h"
#ifdef AAH_TSDEBUG
#include "diag_thread.h"
#endif

namespace android {

ClockRecoveryLoop::ClockRecoveryLoop(LocalClock* local_clock,
                                     CommonClock* common_clock) {
    assert(NULL != local_clock);
    assert(NULL != common_clock);

    local_clock_  = local_clock;
    common_clock_ = common_clock;

    local_clock_can_slew_ = local_clock_->initCheck() &&
                           (local_clock_->setLocalSlew(0) == OK);

    computePIDParams();
    reset(true, true);

#ifdef AAH_TSDEBUG
    diag_thread_ = new DiagThread(common_clock_, local_clock_);
    if (diag_thread_ != NULL) {
        status_t res = diag_thread_->startWorkThread();
        if (res != OK)
            LOGW("Failed to start A@H clock recovery diagnostic thread.");
    } else
        LOGW("Failed to allocate diagnostic thread.");
#endif
}

ClockRecoveryLoop::~ClockRecoveryLoop() {
#ifdef AAH_TSDEBUG
    diag_thread_->stopWorkThread();
#endif
}

void ClockRecoveryLoop::reset(bool position, bool frequency) {
    Mutex::Autolock lock(&lock_);
    reset_l(position, frequency);
}

uint32_t ClockRecoveryLoop::findMinRTTNdx(DisciplineDataPoint* data,
                                          uint32_t count) {
    uint32_t min_rtt = 0;
    for (uint32_t i = 1; i < count; ++i)
        if (data[min_rtt].rtt > data[i].rtt)
            min_rtt = i;

    return min_rtt;
}

bool ClockRecoveryLoop::pushDisciplineEvent(int64_t local_time,
                                            int64_t nominal_common_time,
                                            int64_t rtt) {
    Mutex::Autolock lock(&lock_);

    // If we have not defined a basis for common time, then we need to use these
    // initial points to do so.  In order to avoid significant initial error
    // from a particularly bad startup data point, we collect the first N data
    // points and choose the best of them before moving on.
    if (!common_clock_->isValid()) {
        if (startup_filter_wr_ < kStartupFilterSize) {
            DisciplineDataPoint& d =  startup_filter_data_[startup_filter_wr_];
            d.local_time = local_time;
            d.nominal_common_time = nominal_common_time;
            d.rtt = rtt;
            startup_filter_wr_++;
        }

        if (startup_filter_wr_ == kStartupFilterSize) {
            uint32_t min_rtt = findMinRTTNdx(startup_filter_data_,
                    kStartupFilterSize);

            common_clock_->setBasis(
                    startup_filter_data_[min_rtt].local_time,
                    startup_filter_data_[min_rtt].nominal_common_time);
        }

        return true;
    }

    int64_t observed_common;
    int64_t delta;
    int32_t delta32;
    int32_t correction_cur;
    int32_t correction_cur_P = 0;
    int32_t correction_cur_I = 0;
    int32_t correction_cur_D = 0;

    if (OK != common_clock_->localToCommon(local_time, &observed_common)) {
        // Since we just checked to make certain that this conversion was valid,
        // and no one else in the system should be messing with it, if this
        // conversion is suddenly invalid, it is a good reason to panic.
        LOGE("Failed to convert local time to common time in %s:%d",
                __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    // Implement a filter which should match NTP filtering behavior when a
    // client is associated with only one peer of lower stratum.  Basically,
    // always use the best of the N last data points, where best is defined as
    // lowest round trip time.  NTP uses an N of 8; we use a value of 6.
    //
    // TODO(johngro) : experiment with other filter strategies.  The goal here
    // is to mitigate the effects of high RTT data points which typically have
    // large asymmetries in the TX/RX legs.  Downside of the existing NTP
    // approach (particularly because of the PID controller we are using to
    // produce the control signal from the filtered data) are that the rate at
    // which discipline events are actually acted upon becomes irregular and can
    // become drawn out (the time between actionable event can go way up).  If
    // the system receives a strong high quality data point, the proportional
    // component of the controller can produce a strong correction which is left
    // in place for too long causing overshoot.  In addition, the integral
    // component of the system currently is an approximation based on the
    // assumption of a more or less homogeneous sampling of the error.  Its
    // unclear what the effect of undermining this assumption would be right
    // now.

    // Two ideas which come to mind immediately would be to...
    // 1) Keep a history of more data points (32 or so) and ignore data points
    //    whose RTT is more than a certain number of standard deviations outside
    //    of the norm.
    // 2) Eliminate the PID controller portion of this system entirely.
    //    Instead, move to a system which uses a very wide filter (128 data
    //    points or more) with a sum-of-least-squares line fitting approach to
    //    tracking the long term drift.  This would take the place of the I
    //    component in the current PID controller.  Also use a much more narrow
    //    outlier-rejector filter (as described in #1) to drive a short term
    //    correction factor similar to the P component of the PID controller.
    assert(filter_wr_ < kFilterSize);
    filter_data_[filter_wr_].local_time           = local_time;
    filter_data_[filter_wr_].observed_common_time = observed_common;
    filter_data_[filter_wr_].nominal_common_time  = nominal_common_time;
    filter_data_[filter_wr_].rtt                  = rtt;
    filter_data_[filter_wr_].point_used           = false;
    filter_wr_ = (filter_wr_ + 1) % kFilterSize;
    if (!filter_wr_)
        filter_full_ = true;

    // Scan the accumulated data for the point with the minimum RTT.  If that
    // point has never been used before, go ahead and use it now, otherwise just
    // do nothing.
    uint32_t scan_end = filter_full_ ? kFilterSize : filter_wr_;
    uint32_t min_rtt = findMinRTTNdx(filter_data_, scan_end);
    if (filter_data_[min_rtt].point_used)
        return true;

    local_time          = filter_data_[min_rtt].local_time;
    observed_common     = filter_data_[min_rtt].observed_common_time;
    nominal_common_time = filter_data_[min_rtt].nominal_common_time;
    filter_data_[min_rtt].point_used = true;

    // Compute the error then clamp to the panic threshold.  If we ever exceed
    // this amt of error, its time to panic and reset the system.
    delta = nominal_common_time - observed_common;
    if ((delta > panic_thresh_) || (delta < -panic_thresh_)) {
        // PANIC!!!
        //
        // TODO(johngro) : need to report this to the upper levels of
        // code.
        reset_l(false, true);
        return false;
    } else
        delta32 = delta;

    // Accumulate error into the integrated error, then clamp.
    integrated_error_ += delta32;
    if (integrated_error_ > pid_params_.integrated_delta_max)
        integrated_error_ = pid_params_.integrated_delta_max;
    else if (integrated_error_ < pid_params_.integrated_delta_min)
        integrated_error_ = pid_params_.integrated_delta_min;

    // Compute the difference in error between last time and this time, then
    // update last_delta_
    int32_t input_D = last_delta_valid_ ? delta32 - last_delta_ : 0;
    last_delta_valid_ = true;
    last_delta_ = delta32;

    // Compute the various components of the correction value.
    correction_cur_P = doGainScale(pid_params_.gain_P, delta32);
    correction_cur_I = doGainScale(pid_params_.gain_I, integrated_error_);

    // TODO(johngro) : the differential portion of this code used to rely
    // upon a completely homogeneous discipline frequency.  Now that the
    // discipline frequency may not be homogeneous, its probably important
    // to divide by the amt of time between discipline events during the
    // gain calculation.
    correction_cur_D = doGainScale(pid_params_.gain_D, input_D);

    // Compute the final correction value and clamp.
    correction_cur = correction_cur_P + correction_cur_I + correction_cur_D;
    if (correction_cur < pid_params_.correction_min)
        correction_cur = pid_params_.correction_min;
    else if (correction_cur > pid_params_.correction_max)
        correction_cur = pid_params_.correction_max;

    // If there was a change in the amt of correction to use, update the
    // system.
    if (correction_cur_ != correction_cur) {
        correction_cur_ = correction_cur;
        applySlew();
    }

    LOGV("observed %lld nominal %lld delta = %5lld "
          "int = %7d correction %3d (P %3d, I %3d, D %3d)\n",
          observed_common,
          nominal_common_time,
          nominal_common_time - observed_common,
          integrated_error_,
          correction_cur,
          correction_cur_P,
          correction_cur_I,
          correction_cur_D);

#ifdef AAH_TSDEBUG
    diag_thread_->pushDisciplineEvent(
            local_time,
            observed_common,
            nominal_common_time,
            correction_cur,
            correction_cur_P,
            correction_cur_I,
            correction_cur_D);
#endif

    return true;
}

void ClockRecoveryLoop::computePIDParams() {
    // TODO(johngro) : add the ability to fetch parameters from the driver/board
    // level in case they have a HW clock discipline solution with parameters
    // tuned specifically for it.

    // Correction factor is limited to +/-100 PPM.
    pid_params_.correction_min = -100;
    pid_params_.correction_max =  100;

    // Default proportional gain to 1:10.  (1 PPM of correction for
    // every 10 uSec of instantaneous error)
    memset(&pid_params_.gain_P, 0, sizeof(pid_params_.gain_P));
    pid_params_.gain_P.a_to_b_numer = 1;
    pid_params_.gain_P.a_to_b_denom = 10;

    // Set the integral gain to 1:50
    memset(&pid_params_.gain_I, 0, sizeof(pid_params_.gain_I));
    pid_params_.gain_I.a_to_b_numer = 1;
    pid_params_.gain_I.a_to_b_denom = 50;

    // Default controller is just a PI controller.  Right now, the network based
    // measurements of the error are way to noisy to feed into the differential
    // component of a PID controller.  Someday we might come back and add some
    // filtering of the error channel, but until then leave the controller as a
    // simple PI controller.
    memset(&pid_params_.gain_D, 0, sizeof(pid_params_.gain_D));

    // Don't let the integral component of the controller wind up to
    // the point where it would want to drive the correction factor
    // past saturation.
    int64_t tmp;
    pid_params_.gain_I.doReverseTransform(pid_params_.correction_min, &tmp);
    pid_params_.integrated_delta_min = static_cast<int32_t>(tmp);
    pid_params_.gain_I.doReverseTransform(pid_params_.correction_max, &tmp);
    pid_params_.integrated_delta_max = static_cast<int32_t>(tmp);

    // By default, panic when the sync error is > 50mSec;
    panic_thresh_ = 50000;
}

void ClockRecoveryLoop::reset_l(bool position, bool frequency) {
    assert(NULL != common_clock_);

    if (position) {
        common_clock_->resetBasis();
        startup_filter_wr_ = 0;
    }

    if (frequency) {
        last_delta_valid_ = false;
        last_delta_ = 0;
        integrated_error_ = 0;
        correction_cur_ = 0;
        applySlew();
    }

    filter_wr_   = 0;
    filter_full_ = false;
}

int32_t ClockRecoveryLoop::doGainScale(const LinearTransform& gain,
                                       int32_t val) {
    if (!gain.a_to_b_numer || !gain.a_to_b_denom || !val)
        return 0;

    int64_t tmp;
    int64_t val64 = static_cast<int64_t>(val);
    if (!gain.doForwardTransform(val64, &tmp)) {
        LOGW("Overflow/Underflow while scaling %d in %s",
             val, __PRETTY_FUNCTION__);
        return (val < 0) ? INT32_MIN : INT32_MAX;
    }

    if (tmp > INT32_MAX) {
        LOGW("Overflow while scaling %d in %s", val, __PRETTY_FUNCTION__);
        return INT32_MAX;
    }

    if (tmp < INT32_MIN) {
        LOGW("Underflow while scaling %d in %s", val, __PRETTY_FUNCTION__);
        return INT32_MIN;
    }

    return static_cast<int32_t>(tmp);
}

void ClockRecoveryLoop::applySlew() {
    if (local_clock_can_slew_)
        local_clock_->setLocalSlew(correction_cur_);
    else
        common_clock_->setSlew(local_clock_->getLocalTime(), correction_cur_);
}

}  // namespace android
