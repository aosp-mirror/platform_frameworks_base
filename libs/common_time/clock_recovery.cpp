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
#define LOG_TAG "common_time"
#include <utils/Log.h>
#include <stdint.h>

#include <common_time/local_clock.h>
#include <assert.h>

#include "clock_recovery.h"
#include "common_clock.h"
#ifdef TIME_SERVICE_DEBUG
#include "diag_thread.h"
#endif

// Define log macro so we can make LOGV into LOGE when we are exclusively
// debugging this code.
#ifdef TIME_SERVICE_DEBUG
#define LOG_TS ALOGE
#else
#define LOG_TS ALOGV
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
    tgt_correction_ = 0;
    cur_correction_ = 0;

    // Precompute the max rate at which we are allowed to change the VCXO
    // control.
    uint64_t N = 0x10000ull * 1000ull;
    uint64_t D = local_clock_->getLocalFreq() * kMinFullRangeSlewChange_mSec;
    LinearTransform::reduce(&N, &D);
    while ((N > INT32_MAX) || (D > UINT32_MAX)) {
        N >>= 1;
        D >>= 1;
        LinearTransform::reduce(&N, &D);
    }
    time_to_cur_slew_.a_to_b_numer = static_cast<int32_t>(N);
    time_to_cur_slew_.a_to_b_denom = static_cast<uint32_t>(D);

    reset(true, true);

#ifdef TIME_SERVICE_DEBUG
    diag_thread_ = new DiagThread(common_clock_, local_clock_);
    if (diag_thread_ != NULL) {
        status_t res = diag_thread_->startWorkThread();
        if (res != OK)
            ALOGW("Failed to start A@H clock recovery diagnostic thread.");
    } else
        ALOGW("Failed to allocate diagnostic thread.");
#endif
}

ClockRecoveryLoop::~ClockRecoveryLoop() {
#ifdef TIME_SERVICE_DEBUG
    diag_thread_->stopWorkThread();
#endif
}

// Constants.
const float ClockRecoveryLoop::dT = 1.0;
const float ClockRecoveryLoop::Kc = 1.0f;
const float ClockRecoveryLoop::Ti = 15.0f;
const float ClockRecoveryLoop::Tf = 0.05;
const float ClockRecoveryLoop::bias_Fc = 0.01;
const float ClockRecoveryLoop::bias_RC = (dT / (2 * 3.14159f * bias_Fc));
const float ClockRecoveryLoop::bias_Alpha = (dT / (bias_RC + dT));
const int64_t ClockRecoveryLoop::panic_thresh_ = 50000;
const int64_t ClockRecoveryLoop::control_thresh_ = 10000;
const float ClockRecoveryLoop::COmin = -100.0f;
const float ClockRecoveryLoop::COmax = 100.0f;
const uint32_t ClockRecoveryLoop::kMinFullRangeSlewChange_mSec = 300;
const int ClockRecoveryLoop::kSlewChangeStepPeriod_mSec = 10;


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

    int64_t local_common_time = 0;
    common_clock_->localToCommon(local_time, &local_common_time);
    int64_t raw_delta = nominal_common_time - local_common_time;

#ifdef TIME_SERVICE_DEBUG
    ALOGE("local=%lld, common=%lld, delta=%lld, rtt=%lld\n",
         local_common_time, nominal_common_time,
         raw_delta, rtt);
#endif

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
    float delta_f, dCO;
    int32_t tgt_correction;

    if (OK != common_clock_->localToCommon(local_time, &observed_common)) {
        // Since we just checked to make certain that this conversion was valid,
        // and no one else in the system should be messing with it, if this
        // conversion is suddenly invalid, it is a good reason to panic.
        ALOGE("Failed to convert local time to common time in %s:%d",
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
    uint32_t current_point = filter_wr_;
    filter_wr_ = (filter_wr_ + 1) % kFilterSize;
    if (!filter_wr_)
        filter_full_ = true;

    uint32_t scan_end = filter_full_ ? kFilterSize : filter_wr_;
    uint32_t min_rtt = findMinRTTNdx(filter_data_, scan_end);
    // We only use packets with low RTTs for control. If the packet RTT
    // is less than the panic threshold, we can probably eat the jitter with the
    // control loop. Otherwise, take the packet only if it better than all
    // of the packets we have in the history. That way we try to track
    // something, even if it is noisy.
    if (current_point == min_rtt || rtt < control_thresh_) {
        delta_f = delta = nominal_common_time - observed_common;

        last_error_est_valid_ = true;
        last_error_est_usec_ = delta;

        // Compute the error then clamp to the panic threshold.  If we ever
        // exceed this amt of error, its time to panic and reset the system.
        // Given that the error in the measurement of the error could be as
        // high as the RTT of the data point, we don't actually panic until
        // the implied error (delta) is greater than the absolute panic
        // threashold plus the RTT.  IOW - we don't panic until we are
        // absoluely sure that our best case sync is worse than the absolute
        // panic threshold.
        int64_t effective_panic_thresh = panic_thresh_ + rtt;
        if ((delta > effective_panic_thresh) ||
            (delta < -effective_panic_thresh)) {
            // PANIC!!!
            reset_l(false, true);
            return false;
        }

    } else {
        // We do not have a good packet to look at, but we also do not want to
        // free-run the clock at some crazy slew rate. So we guess the
        // trajectory of the clock based on the last controller output and the
        // estimated bias of our clock against the master.
        // The net effect of this is that CO == CObias after some extended
        // period of no feedback.
        delta_f = last_delta_f_ - dT*(CO - CObias);
        delta = delta_f;
    }

    // Velocity form PI control equation.
    dCO = Kc * (1.0f + dT/Ti) * delta_f - Kc * last_delta_f_;
    CO += dCO * Tf; // Filter CO by applying gain <1 here.

    // Save error terms for later.
    last_delta_f_ = delta_f;

    // Clamp CO to +/- 100ppm.
    if (CO < COmin)
        CO = COmin;
    else if (CO > COmax)
        CO = COmax;

    // Update the controller bias.
    CObias = bias_Alpha * CO + (1.0f - bias_Alpha) * lastCObias;
    lastCObias = CObias;

    // Convert PPM to 16-bit int range. Add some guard band (-0.01) so we
    // don't get fp weirdness.
    tgt_correction = CO * 327.66;

    // If there was a change in the amt of correction to use, update the
    // system.
    setTargetCorrection_l(tgt_correction);

    LOG_TS("clock_loop %lld %f %f %f %d\n", raw_delta, delta_f, CO, CObias, tgt_correction);

#ifdef TIME_SERVICE_DEBUG
    diag_thread_->pushDisciplineEvent(
            local_time,
            observed_common,
            nominal_common_time,
            tgt_correction,
            rtt);
#endif

    return true;
}

int32_t ClockRecoveryLoop::getLastErrorEstimate() {
    Mutex::Autolock lock(&lock_);

    if (last_error_est_valid_)
        return last_error_est_usec_;
    else
        return ICommonClock::kErrorEstimateUnknown;
}

void ClockRecoveryLoop::reset_l(bool position, bool frequency) {
    assert(NULL != common_clock_);

    if (position) {
        common_clock_->resetBasis();
        startup_filter_wr_ = 0;
    }

    if (frequency) {
        last_error_est_valid_ = false;
        last_error_est_usec_ = 0;
        last_delta_f_ = 0.0;
        CO = 0.0f;
        lastCObias = CObias = 0.0f;
        setTargetCorrection_l(0);
        applySlew_l();
    }

    filter_wr_   = 0;
    filter_full_ = false;
}

void ClockRecoveryLoop::setTargetCorrection_l(int32_t tgt) {
    // When we make a change to the slew rate, we need to be careful to not
    // change it too quickly as it can anger some HDMI sinks out there, notably
    // some Sony panels from the 2010-2011 timeframe.  From experimenting with
    // some of these sinks, it seems like swinging from one end of the range to
    // another in less that 190mSec or so can start to cause trouble.  Adding in
    // a hefty margin, we limit the system to a full range sweep in no less than
    // 300mSec.
    if (tgt_correction_ != tgt) {
        int64_t now = local_clock_->getLocalTime();
        status_t res;

        tgt_correction_ = tgt;

        // Set up the transformation to figure out what the slew should be at
        // any given point in time in the future.
        time_to_cur_slew_.a_zero = now;
        time_to_cur_slew_.b_zero = cur_correction_;

        // Make sure the sign of the slope is headed in the proper direction.
        bool needs_increase = (cur_correction_ < tgt_correction_);
        bool is_increasing  = (time_to_cur_slew_.a_to_b_numer > 0);
        if (( needs_increase && !is_increasing) ||
            (!needs_increase &&  is_increasing)) {
            time_to_cur_slew_.a_to_b_numer = -time_to_cur_slew_.a_to_b_numer;
        }

        // Finally, figure out when the change will be finished and start the
        // slew operation.
        time_to_cur_slew_.doReverseTransform(tgt_correction_,
                                             &slew_change_end_time_);

        applySlew_l();
    }
}

bool ClockRecoveryLoop::applySlew_l() {
    bool ret = true;

    // If cur == tgt, there is no ongoing sleq rate change and we are already
    // finished.
    if (cur_correction_ == tgt_correction_)
        goto bailout;

    if (local_clock_can_slew_) {
        int64_t now = local_clock_->getLocalTime();
        int64_t tmp;

        if (now >= slew_change_end_time_) {
            cur_correction_ = tgt_correction_;
            next_slew_change_timeout_.setTimeout(-1);
        } else {
            time_to_cur_slew_.doForwardTransform(now, &tmp);

            if (tmp > INT16_MAX)
                cur_correction_ = INT16_MAX;
            else if (tmp < INT16_MIN)
                cur_correction_ = INT16_MIN;
            else
                cur_correction_ = static_cast<int16_t>(tmp);

            next_slew_change_timeout_.setTimeout(kSlewChangeStepPeriod_mSec);
            ret = false;
        }

        local_clock_->setLocalSlew(cur_correction_);
    } else {
        // Since we are not actually changing the rate of a HW clock, we don't
        // need to worry to much about changing the slew rate so fast that we
        // anger any downstream HDMI devices.
        cur_correction_ = tgt_correction_;
        next_slew_change_timeout_.setTimeout(-1);

        // The SW clock recovery implemented by the common clock class expects
        // values expressed in PPM. CO is in ppm.
        common_clock_->setSlew(local_clock_->getLocalTime(), CO);
    }

bailout:
    return ret;
}

int ClockRecoveryLoop::applyRateLimitedSlew() {
    Mutex::Autolock lock(&lock_);

    int ret = next_slew_change_timeout_.msecTillTimeout();
    if (!ret) {
        if (applySlew_l())
            next_slew_change_timeout_.setTimeout(-1);
        ret = next_slew_change_timeout_.msecTillTimeout();
    }

    return ret;
}

}  // namespace android
