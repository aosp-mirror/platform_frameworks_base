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
#include <utils/LinearTransform.h>
#include <utils/threads.h>

#ifdef AAH_TSDEBUG
#include "diag_thread.h"
#endif

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

  private:
    typedef struct {
        // Limits for the correction factor supplied to set_counter_slew_rate.
        // The controller will always clamp its output to the range expressed by
        // correction_(min|max)
        int32_t correction_min;
        int32_t correction_max;

        // Limits for the internal integration accumulator in the PID
        // controller.  The value of the accumulator is scaled by gain_I to
        // produce the integral component of the PID controller output.
        // Platforms can use these limits to prevent windup in the system
        // if/when the correction factor needs to be driven to saturation for
        // extended periods of time.
        int32_t integrated_delta_min;
        int32_t integrated_delta_max;

        // Gain for the P, I and D components of the controller.
        LinearTransform gain_P;
        LinearTransform gain_I;
        LinearTransform gain_D;
    } PIDParams;

    typedef struct {
        int64_t local_time;
        int64_t observed_common_time;
        int64_t nominal_common_time;
        int64_t rtt;
        bool point_used;
    } DisciplineDataPoint;

    static uint32_t findMinRTTNdx(DisciplineDataPoint* data, uint32_t count);

    void computePIDParams();
    void reset_l(bool position, bool frequency);
    static int32_t doGainScale(const LinearTransform& gain, int32_t val);
    void applySlew();

    // The local clock HW abstraction we use as the basis for common time.
    LocalClock* local_clock_;
    bool local_clock_can_slew_;

    // The common clock we end up controlling along with the lock used to
    // serialize operations.
    CommonClock* common_clock_;
    Mutex lock_;

    // The parameters computed to be used for the PID Controller.
    PIDParams pid_params_;

    // The maximum allowed error (as indicated by a  pushDisciplineEvent) before
    // we panic.
    int32_t panic_thresh_;

    // parameters maintained while running and reset during a reset
    // of the frequency correction.
    bool    last_delta_valid_;
    int32_t last_delta_;
    int32_t integrated_error_;
    int32_t correction_cur_;

    // State kept for filtering the discipline data.
    static const uint32_t kFilterSize = 6;
    DisciplineDataPoint filter_data_[kFilterSize];
    uint32_t filter_wr_;
    bool filter_full_;

    static const uint32_t kStartupFilterSize = 4;
    DisciplineDataPoint startup_filter_data_[kStartupFilterSize];
    uint32_t startup_filter_wr_;

#ifdef AAH_TSDEBUG
    sp<DiagThread> diag_thread_;
#endif
};

}  // namespace android

#endif  // __CLOCK_RECOVERY_H__
