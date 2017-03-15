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

#ifndef __CLOCK_RECOVERY_H__
#define __CLOCK_RECOVERY_H__

#include <stdint.h>
#include <common_time/ICommonClock.h>
#include <utils/threads.h>

#include "LinearTransform.h"

#ifdef TIME_SERVICE_DEBUG
#include "diag_thread.h"
#endif

#include "utils.h"

namespace android {

class CommonClock;
class LocalClock;

class ClockRecoveryLoop {
  public:
     ClockRecoveryLoop(LocalClock* local_clock, CommonClock* common_clock);
    ~ClockRecoveryLoop();

    void reset(bool position, bool frequency);
    bool pushDisciplineEvent(int64_t local_time,
                             int64_t nominal_common_time,
                             int64_t data_point_rtt);
    int32_t getLastErrorEstimate();

    // Applies the next step in any ongoing slew change operation.  Returns a
    // timeout suitable for use with poll/select indicating the number of mSec
    // until the next change should be applied.
    int applyRateLimitedSlew();

  private:

    // Tuned using the "Good Gain" method.
    // See:
    // http://techteach.no/publications/books/dynamics_and_control/tuning_pid_controller.pdf

    // Controller period (1Hz for now).
    static const float dT;

    // Controller gain, positive and unitless. Larger values converge faster,
    // but can cause instability.
    static const float Kc;

    // Integral reset time. Smaller values cause loop to track faster, but can
    // also cause instability.
    static const float Ti;

    // Controller output filter time constant. Range (0-1). Smaller values make
    // output smoother, but slow convergence.
    static const float Tf;

    // Low-pass filter for bias tracker.
    static const float bias_Fc; // HZ
    static const float bias_RC; // Computed in constructor.
    static const float bias_Alpha; // Computed inconstructor.

    // The maximum allowed error (as indicated by a  pushDisciplineEvent) before
    // we panic.
    static const int64_t panic_thresh_;

    // The maximum allowed error rtt time for packets to be used for control
    // feedback, unless the packet is the best in recent memory.
    static const int64_t control_thresh_;

    typedef struct {
        int64_t local_time;
        int64_t observed_common_time;
        int64_t nominal_common_time;
        int64_t rtt;
        bool point_used;
    } DisciplineDataPoint;

    static uint32_t findMinRTTNdx(DisciplineDataPoint* data, uint32_t count);

    void reset_l(bool position, bool frequency);
    void setTargetCorrection_l(int32_t tgt);
    bool applySlew_l();

    // The local clock HW abstraction we use as the basis for common time.
    LocalClock* local_clock_;
    bool local_clock_can_slew_;

    // The common clock we end up controlling along with the lock used to
    // serialize operations.
    CommonClock* common_clock_;
    Mutex lock_;

    // parameters maintained while running and reset during a reset
    // of the frequency correction.
    bool    last_error_est_valid_;
    int32_t last_error_est_usec_;
    float last_delta_f_;
    int32_t tgt_correction_;
    int32_t cur_correction_;
    LinearTransform time_to_cur_slew_;
    int64_t slew_change_end_time_;
    Timeout next_slew_change_timeout_;

    // Contoller Output.
    float CO;

    // Bias tracking for trajectory estimation.
    float CObias;
    float lastCObias;

    // Controller output bounds. The controller will not try to
    // slew faster that +/-100ppm offset from center per interation.
    static const float COmin;
    static const float COmax;

    // State kept for filtering the discipline data.
    static const uint32_t kFilterSize = 16;
    DisciplineDataPoint filter_data_[kFilterSize];
    uint32_t filter_wr_;
    bool filter_full_;

    static const uint32_t kStartupFilterSize = 4;
    DisciplineDataPoint startup_filter_data_[kStartupFilterSize];
    uint32_t startup_filter_wr_;

    // Minimum number of milliseconds over which we allow a full range change
    // (from rail to rail) of the VCXO control signal.  This is the rate
    // limiting factor which keeps us from changing the clock rate so fast that
    // we get in trouble with certain HDMI sinks.
    static const uint32_t kMinFullRangeSlewChange_mSec;

    // How much time (in msec) to wait 
    static const int kSlewChangeStepPeriod_mSec;

#ifdef TIME_SERVICE_DEBUG
    sp<DiagThread> diag_thread_;
#endif
};

}  // namespace android

#endif  // __CLOCK_RECOVERY_H__
