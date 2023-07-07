/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef ANDROIDFW_RESOURCETIMER_H_
#define ANDROIDFW_RESOURCETIMER_H_

#include <time.h>
#include <atomic>
#include <vector>

#include <utils/Mutex.h>
#include <android-base/macros.h>
#include <androidfw/Util.h>

namespace android {

// ResourceTimer captures the duration of short functions.  Durations are accumulated in registers
// and statistics are pulled back to the Java layer as needed.
// To monitor an API, first add it to the Counter enumeration.  Then, inside the API, create an
// instance of ResourceTimer with the appropriate enumeral.  The corresponding counter will be
// updated when the ResourceTimer destructor is called, normally at the end of the enclosing block.
class ResourceTimer {
 public:
  enum class Counter {
    GetResourceValue,
    RetrieveAttributes,

    LastCounter = RetrieveAttributes,
  };
  static const int counterSize = static_cast<int>(Counter::LastCounter) + 1;
  static char const *toString(Counter);

  // Start a timer for the specified counter.
  ResourceTimer(Counter);
  // The block is exiting.  If the timer is active, record it.
  ~ResourceTimer();
  // This records the elapsed time and disables further recording.  Use this if the containing
  // block includes extra processing that should not be included in the timer.  The method is
  // destructive in that the timer is no longer valid and further calls to record() will be
  // ignored.
  void record();
  // This cancels a timer.  Elapsed time will neither be computed nor recorded.
  void cancel();

  // A single timer contains the count of events and the cumulative time spent handling the
  // events.  It also includes the smallest value seen and 10 largest values seen.  Finally, it
  // includes a histogram of values that approximates a semi-log.

  // The timer can compute percentiles of recorded events.  For example, the p50 value is a time
  // such that 50% of the readings are below the value and 50% are above the value.  The
  // granularity in the readings means that a percentile cannot always be computed.  In this case,
  // the percentile is reported as zero.  (The simplest example is when there is a single
  // reading.)  Even if the value can be computed, it will not be exact.  Therefore, a percentile
  // is actually reported as two values: the lowest time at which it might be valid and the
  // highest time at which it might be valid.
  struct Timer {
    static const size_t MaxLargest = 5;

    // The construct zeros all the fields.  The destructor releases memory allocated to the
    // buckets.
    Timer();
    ~Timer();

    // The following summary values are set to zero on a reset.  All times are in ns.

    // The total number of events recorded.
    int count;
    // The total duration of events.
    int64_t total;
    // The smallest event duration seen.  This is guaranteed to be non-zero if count is greater
    // than 0.
    int mintime;
    // The largest event duration seen.
    int maxtime;

    // The largest values seen.  Element 0 is the largest value seen (and is the same as maxtime,
    // above).  Element 1 is the next largest, and so on.  If count is less than MaxLargest,
    // unused elements will be zero.
    int largest[MaxLargest];

    // The p50 value is a time such that 50% of the readings are below that time and 50% of the
    // readings.

    // A single percentile is defined by the lowest value supported by the readings and the
    // highest value supported by the readings.
    struct Percentile {
      // The nominal time (in ns) of the percentile.  The true percentile is guaranteed to be less
      // than or equal to this time.
      int nominal;
      // The actual percentile of the nominal time.
      int nominal_actual;
      // The time of the next lower bin.  The true percentile is guaranteed to be greater than
      // this time.
      int floor;
      // The actual percentile of the floor time.
      int floor_actual;

      // Fill in a percentile given the cumulative to the bin, the count in the current bin, the
      // total count, the width of the bin, and the time of the bin.
      void compute(int cumulative, int current, int count, int width, int time);
    };

    // The structure that holds the percentiles.
    struct {
      Percentile p50;
      Percentile p90;
      Percentile p95;
      Percentile p99;
    } pvalues;

    // Set all counters to zero.
    void reset();
    // Record an event.  The input time is in ns.
    void record(int);
    // Compute the percentiles.  Percentiles are computed on demand, as the computation is too
    // expensive to be done inline.
    void compute();

    // Copy one timer to another.  If reset is true then the src is reset immediately after the
    // copy.  The reset flag is exploited to make the copy faster.  Any data in dst is lost.
    static void copy(Timer &dst, Timer &src, bool reset);

   private:
    // Free any buckets.
    void freeBuckets();

    // Readings are placed in bins, which are orgzanized into decades.  The decade 0 covers
    // [0,100) in steps of 1us.  Decade 1 covers [0,1000) in steps of 10us.  Decade 2 covers
    // [0,10000) in steps of 100us.  And so on.

    // An event is placed in the first bin that can hold it.  This means that events in the range
    // of [0,100) are placed in the first decade, events in the range of [0,1000) are placed in
    // the second decade, and so on.  This also means that the first 10% of the bins are unused
    // in each decade after the first.

    // The design provides at least two significant digits across the range of [0,10000).

    static const size_t MaxDimension = 4;
    static const size_t MaxBuckets = 100;

    // The range of each dimension.  The lower value is always zero.
    static const int range[MaxDimension];
    // The width of each bin, by dimension
    static const int width[MaxDimension];

    // A histogram of the values seen. Centuries are allocated as needed, to minimize the memory
    // impact.
    int *buckets[MaxDimension];
  };

  // Fetch one Timer.  The function has a short-circuit behavior: if the count is zero then
  // destination count is set to zero and the function returns false.  Otherwise, the destination
  // is a copy of the source and the function returns true.  This behavior lowers the cost of
  // handling unused timers.
  static bool copy(int src, Timer &dst, bool reset);

  // Enable the timers.  Timers are initially disabled.  Enabling timers allocates memory for the
  // counters.  Timers cannot be disabled.
  static void enable();

 private:
  // An internal reset method.  This does not take a lock.
  static void reset();

  // Helper method to convert a counter into an enum.  Presumably, this will be inlined into zero
  // actual cpu instructions.
  static inline std::vector<unsigned int>::size_type toIndex(Counter c) {
    return static_cast<std::vector<unsigned int>::size_type>(c);
  }

  // Every counter has an associated lock.  The lock has been factored into a separate class to
  // keep the Timer class a POD.
  struct GuardedTimer {
    Mutex lock_;
    Timer timer_;
  };

  // Scoped timer
  struct ScopedTimer {
    AutoMutex _l;
    Timer &t;
    ScopedTimer(GuardedTimer &g) :
        _l(g.lock_), t(g.timer_) {
    }
    Timer *operator->() {
      return &t;
    }
    Timer& operator*() {
      return t;
    }
  };

  // An individual timer is active (or not), is tracking a specific API, and has a start time.
  // The api and the start time are undefined if the timer is not active.
  bool active_;
  Counter api_;
  struct timespec start_;

  // The global enable flag.  This is initially false and may be set true by the java runtime.
  static std::atomic<bool> enabled_;

  // The global timers.  The memory for the timers is not allocated until the timers are enabled.
  static std::atomic<GuardedTimer *> counter_;
};

}  // namespace android

#endif /* ANDROIDFW_RESOURCETIMER_H_ */
